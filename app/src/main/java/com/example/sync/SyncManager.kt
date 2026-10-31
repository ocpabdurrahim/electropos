package com.example.sync

import android.util.Log
import androidx.room.withTransaction
import com.example.data.local.database.AppDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class SyncStatus(
    val state: State = State.NOT_LINKED,
    val signedInEmail: String? = null,
    val shopId: String? = null,
    val shopName: String? = null,
    /** The signed-in account's role in the shop (OWNER, ADMIN, MANAGER, SALESPERSON). */
    val role: String? = null,
    val block: Long? = null,
    val tillCode: String? = null,
    /** Records changed on this phone and not uploaded yet. */
    val pending: Int = 0,
    /** Cloud records waiting for a related record. */
    val waiting: Int = 0,
    val conflicts: Int = 0,
    val lastSyncAt: Long = 0,
    val error: String? = null,
    val downloading: Boolean = false,
    val downloadProgress: String? = null
) {
    enum class State { NOT_SET_UP, NOT_LINKED, SIGNED_OUT, DOWNLOADING, CONNECTING, SYNCING, UP_TO_DATE, ERROR }

    val isLinked: Boolean get() = shopId != null
}

/** A shop the signed-in account is a member of. */
data class ShopRef(val id: String, val name: String, val role: String)

data class ShopMember(val email: String, val name: String, val role: String, val active: Boolean, val addedBy: String, val createdAt: Long)

data class ShopDevice(val block: Long, val kind: String, val tillCode: String, val email: String, val label: String, val createdAt: Long)

/**
 * Two-way sync between this phone's Room database and the shop in Firestore
 * (docs/data-model.md). Room stays the truth for every screen; the cloud only feeds it.
 *
 *  - Upload: the triggers record every change in sync_outbox; it goes up in batches of 400 and
 *    what was sent is cleared (guarded by the outbox id, so a newer change stays).
 *  - Download: one listener per collection on `_ts >= newest applied`, applied by [SyncApplier].
 *  - Put online / join / unlink set up or end the link; reset and restore unlink first.
 */
class SyncManager(
    private val db: AppDatabase,
    private val remote: RemoteStore,
    private val auth: SyncAuth,
    private val scope: CoroutineScope,
    private val deviceLabel: String = "Phone",
    /** Saves a copy of this phone's data before joining wipes it. */
    private val safetyCopy: suspend (reason: String) -> Result<Unit> = { Result.success(Unit) },
    /** After a join finished downloading: sign the PIN user out, so the shop's own users apply. */
    private val onShopDownloaded: suspend () -> Unit = {},
    private val retryDelayMs: Long = 5_000L
) {
    private val syncDao = db.syncDao()
    private val applier = SyncApplier(db)
    /** One delivery is applied at a time. */
    private val applyLock = Mutex()
    private val pushKicks = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val generation = MutableStateFlow(0)
    private var started = false

    private data class Runtime(
        val error: String? = null,
        val progress: String? = null,
        val loaded: Set<String> = emptySet(),
        val running: Boolean = false
    )

    private val runtime = MutableStateFlow(Runtime())

    val user: StateFlow<SyncUser?> get() = auth.user
    val available: Boolean get() = remote.available
    val issues: Flow<List<SyncConflictEntity>> = syncDao.observeConflicts()

    val status: StateFlow<SyncStatus> = combine(
        combine(auth.user, syncDao.observeFlags(), syncDao.observePendingCount(), syncDao.observeParkedCount(), syncDao.observeConflicts()) { u, f, p, w, c ->
            Snapshot(u, f, p, w, c.size)
        },
        runtime
    ) { s, r -> buildStatus(s, r) }
        .stateIn(scope, SharingStarted.Eagerly, SyncStatus(state = if (remote.available) SyncStatus.State.NOT_LINKED else SyncStatus.State.NOT_SET_UP))

    private data class Snapshot(val user: SyncUser?, val flags: SyncFlagsEntity?, val pending: Int, val waiting: Int, val conflicts: Int)

    private fun buildStatus(s: Snapshot, r: Runtime): SyncStatus {
        val f = s.flags
        val linked = f?.isLinked == true
        val state = when {
            !remote.available -> SyncStatus.State.NOT_SET_UP
            !linked -> SyncStatus.State.NOT_LINKED
            f?.downloading == true -> SyncStatus.State.DOWNLOADING
            s.user == null -> SyncStatus.State.SIGNED_OUT
            r.error != null -> SyncStatus.State.ERROR
            !r.running -> SyncStatus.State.CONNECTING
            s.pending > 0 || r.loaded.size < SyncTables.ALL.size -> SyncStatus.State.SYNCING
            else -> SyncStatus.State.UP_TO_DATE
        }
        return SyncStatus(
            state = state,
            signedInEmail = s.user?.email,
            shopId = f?.shopId,
            shopName = f?.shopName,
            role = f?.role,
            block = f?.takeIf { linked }?.block,
            tillCode = f?.takeIf { linked }?.tillCode,
            pending = s.pending,
            waiting = s.waiting,
            conflicts = s.conflicts,
            lastSyncAt = f?.lastSyncAt ?: 0L,
            error = r.error,
            downloading = f?.downloading == true,
            downloadProgress = r.progress
        )
    }

    // ------------------------------------------------------------------ session

    private data class SessionKey(val uid: String?, val email: String?, val shopId: String?, val tracking: Boolean, val downloading: Boolean, val generation: Int)

    /** Starts following the account and the link; call once (AppContainer). */
    fun start() {
        if (started || !remote.available) return
        started = true
        scope.launch {
            combine(auth.user, syncDao.observeFlags(), generation) { u, f, g ->
                SessionKey(u?.uid, u?.email, f?.shopId, f?.tracking == true, f?.downloading == true, g)
            }
                .distinctUntilChanged()
                .collectLatest { key -> runFor(key) }
        }
    }

    private suspend fun runFor(key: SessionKey) {
        runtime.value = Runtime()
        val shopId = key.shopId ?: return
        if (key.uid == null || key.email == null) return
        when {
            key.downloading -> runWithRetry { download(shopId) }
            key.tracking -> runWithRetry { session(shopId, key.email) }
        }
    }

    private suspend fun runWithRetry(block: suspend () -> Unit) {
        var backoff = retryDelayMs
        while (true) {
            try {
                block()
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Sync stopped; trying again in ${backoff / 1000}s", e)
                runtime.update { it.copy(error = friendly(e), running = false) }
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(5 * 60_000L)
                runtime.update { it.copy(error = null) }
            }
        }
    }

    /** Runs until cancelled (sign-out, unlink) or until something fails, which restarts it. */
    private suspend fun session(shopId: String, email: String) = coroutineScope {
        runtime.update { it.copy(running = true, loaded = emptySet(), error = null) }
        launch { refreshRole(shopId, email) }
        launch { pushLoop(shopId) }
        for (table in SyncTables.ALL) launch { pullLoop(shopId, table) }
    }

    private suspend fun refreshRole(shopId: String, email: String) {
        val doc = try {
            remote.get("shops/$shopId/members/$email")
        } catch (e: RemoteException) {
            return // offline: the last known role stays
        }
        val active = doc != null && doc.data["_del"] != true && doc.data["active"] != false
        syncDao.setRole(if (active) doc?.data?.get("role") as? String else null)
        if (!active) {
            runtime.update { it.copy(error = "$email is not an active member of this shop any more. Changes stay on this phone until an owner adds it again.") }
        }
    }

    // ------------------------------------------------------------------ upload

    private suspend fun pushLoop(shopId: String) {
        merge(syncDao.observePendingCount().map { }, pushKicks).conflate().collect { pushAll(shopId) }
    }

    /** Sends everything in the outbox, 400 writes per batch. */
    private suspend fun pushAll(shopId: String) {
        while (true) {
            val batch = syncDao.pendingBatch(BATCH_SIZE)
            if (batch.isEmpty()) return
            // Rows are read in one transaction, so the batch is one consistent moment of this phone
            val writes = db.withTransaction {
                if (syncDao.flags()?.shopId != shopId) null else batch.map { it to writeFor(shopId, it) }
            } ?: return
            val toSend = writes.mapNotNull { it.second }
            if (toSend.isNotEmpty()) {
                try {
                    remote.commit(toSend)
                } catch (e: RemoteException) {
                    if (e.code != RemoteException.Code.PERMISSION_DENIED) throw e
                    pushOneByOne(shopId, writes)
                }
            }
            db.withTransaction { batch.forEach { syncDao.clearSent(it.tbl, it.rowId, it.lastSeq) } }
            markSynced()
        }
    }

    /** The whole row (set) or, for a deleted row, a tombstone merged into the last version. */
    private fun writeFor(shopId: String, row: PendingRow): RemoteWrite? {
        val table = SyncTables.find(row.tbl) ?: return null
        val path = "shops/$shopId/${table.name}/${row.rowId}"
        val doc = SyncMapper.readRow(db.openHelper.writableDatabase, table, row.rowId)
        return if (doc != null) RemoteWrite.Set(path, doc + mapOf("_ts" to ServerTime, "_del" to false))
        else RemoteWrite.Merge(path, mapOf("id" to row.rowId, "_del" to true, "_ts" to ServerTime))
    }

    /**
     * The server refused the batch: find the refused rows, list them as sync issues, send the rest.
     * If it refuses everything and the account is no longer an active member, the changes stay queued.
     */
    private suspend fun pushOneByOne(shopId: String, writes: List<Pair<PendingRow, RemoteWrite?>>) {
        val refused = ArrayList<PendingRow>()
        for ((row, write) in writes) {
            write ?: continue
            try {
                remote.commit(listOf(write))
            } catch (e: RemoteException) {
                if (e.code != RemoteException.Code.PERMISSION_DENIED) throw e
                refused.add(row)
            }
        }
        if (refused.isEmpty()) return
        if (refused.size == writes.count { it.second != null }) {
            val email = auth.user.value?.email
            val me = email?.let { runCatching { remote.get("shops/$shopId/members/$it") }.getOrNull() }
            if (me == null || me.data["_del"] == true || me.data["active"] == false) {
                throw RemoteException(
                    RemoteException.Code.PERMISSION_DENIED,
                    "${email ?: "This account"} can't change the shop (not an active member). Changes wait on this phone."
                )
            }
        }
        val role = syncDao.flags()?.role
        for (row in refused) {
            syncDao.addConflict(
                SyncConflictEntity(
                    tbl = row.tbl, rowId = row.rowId, kind = SyncApplier.KIND_REJECTED,
                    message = "The cloud refused a change to ${SyncApplier.label(row.tbl)} #${row.rowId}: " +
                        "your role in the shop (${ShopRoles.label(role)}) can't change it. It stays on this phone only."
                )
            )
        }
    }

    // ------------------------------------------------------------------ download

    private suspend fun pullLoop(shopId: String, table: SyncTable) {
        val since = syncDao.cursor(table.name) ?: 0L
        try {
            snapshots("shops/$shopId/${table.name}", since).collect { snap ->
                if (snap.changed.isNotEmpty()) {
                    val result = applyLock.withLock { applier.apply(shopId, table, snap.changed) } ?: return@collect
                    if (result.applied > 0) markSynced()
                }
                if (snap.fromServer) runtime.update { it.copy(loaded = it.loaded + table.name) }
            }
        } catch (e: RemoteException) {
            if (e.code != RemoteException.Code.PERMISSION_DENIED) throw e
            // e.g. `users` for a role that may not read it: the other collections carry on
            Log.w(TAG, "No access to ${table.name}", e)
            runtime.update { it.copy(loaded = it.loaded + table.name) }
            awaitCancellation()
        }
    }

    private fun snapshots(path: String, since: Long): Flow<RemoteSnapshot> = callbackFlow {
        val registration = remote.listen(path, since, { trySend(it) }, { close(it) })
        awaitClose { registration.remove() }
    }.buffer(Channel.UNLIMITED)

    /** Joining: the whole shop, collection by collection (parents first); the app waits for it. */
    private suspend fun download(shopId: String) {
        val tables = SyncTables.ALL
        for ((i, table) in tables.withIndex()) {
            runtime.update { it.copy(progress = "Downloading ${SyncApplier.label(table.name)} records (${i + 1} of ${tables.size})") }
            val docs = remote.getAll("shops/$shopId/${table.name}", syncDao.cursor(table.name) ?: 0L)
            applyLock.withLock { applier.apply(shopId, table, docs, recompute = false) } ?: return
        }
        runtime.update { it.copy(progress = "Working out stock and balances") }
        applyLock.withLock {
            applier.retryParked(shopId) ?: return
            applier.recomputeAll(shopId) ?: return
        }
        onShopDownloaded()
        db.withTransaction {
            val f = syncDao.flags()
            if (f?.shopId == shopId) syncDao.saveFlags(f.copy(downloading = false, tracking = true, lastSyncAt = System.currentTimeMillis()))
        }
    }

    private suspend fun markSynced() = syncDao.setLastSyncAt(System.currentTimeMillis())

    // ------------------------------------------------------------------ account

    suspend fun signInWithGoogle(idToken: String) = auth.signInWithGoogle(idToken)

    suspend fun signInForTests(email: String) = auth.signInForTests(email)

    /** Stops syncing; a linked phone keeps recording changes and sends them after the next sign-in. */
    fun signOut() = auth.signOut()

    /** "Sync now": sends what is waiting, retries waiting records and, after an error, reconnects. */
    fun syncNow() {
        if (runtime.value.error != null || !runtime.value.running) generation.update { it + 1 } else pushKicks.tryEmit(Unit)
        val shopId = status.value.shopId ?: return
        scope.launch {
            try {
                applyLock.withLock { applier.retryParked(shopId) }
            } catch (e: Exception) {
                Log.w(TAG, "Retrying waiting records failed", e)
            }
        }
    }

    suspend fun dismissIssue(id: Long) = syncDao.dismissConflict(id)

    // ------------------------------------------------------------------ shops

    /** Shops the signed-in account belongs to (collection-group query on members). */
    suspend fun findMyShops(): List<ShopRef> {
        val user = requireUser()
        return remote.membersByEmail(user.email)
            .filter { (_, doc) -> doc.data["_del"] != true && doc.data["active"] != false }
            .map { (shopId, doc) ->
                val name = runCatching { remote.get("shops/$shopId")?.data?.get("name") as? String }.getOrNull()
                ShopRef(shopId, name?.ifBlank { null } ?: "Unnamed shop", doc.data["role"] as? String ?: "")
            }
            .sortedBy { it.name.lowercase() }
    }

    /** Whether the signed-in account may put a shop online (app owner, or approved under creators/). */
    suspend fun mayCreateShops(): Boolean {
        val user = requireUser()
        // Always a server read: it also fails at once when the phone is offline
        val approved = remote.get("creators/${user.email}") != null
        return approved || AppOwner.isAppOwner(user.email)
    }

    /**
     * Puts this phone's shop online: creates the shop with this account as its owner, takes id block 0
     * and till A, queues every existing row for upload and turns change tracking on.
     */
    suspend fun putShopOnline() {
        val user = requireUser()
        check(syncDao.flags()?.isLinked != true) { "This phone is already linked to a shop." }
        if (!mayCreateShops()) {
            throw IllegalStateException("Only the app owner, or someone the owner approved, can put a shop online. Ask ${AppOwner.EMAILS.first()}.")
        }
        check(rowsOutsideBlockZero() == 0L) {
            "This phone's records came from a cloud shop (they carry other devices' ids). Join that shop instead, or reset the app first."
        }
        val shopName = db.settingsAndAuditDao().getShopProfileSync()?.shopName?.trim()?.ifBlank { null } ?: "My shop"
        val shopId = remote.newId()
        val now = System.currentTimeMillis()
        val base = "shops/$shopId"
        // One batch, exactly as firestore.rules expects a new shop
        remote.commit(
            listOf(
                RemoteWrite.Set(base, mapOf("ownerUid" to user.uid, "ownerEmail" to user.email, "name" to shopName, "createdAt" to now, "_ts" to ServerTime)),
                RemoteWrite.Set(
                    "$base/members/${user.email}",
                    mapOf(
                        "email" to user.email, "name" to user.name, "role" to ShopRoles.OWNER, "active" to true, "addedBy" to user.email,
                        "createdAt" to now, "_ts" to ServerTime, "_del" to false
                    )
                ),
                RemoteWrite.Set("$base/meta/counters", mapOf("nextBlock" to 1L, "nextPhone" to 1L, "_ts" to ServerTime)),
                RemoteWrite.Set(
                    "$base/devices/0",
                    mapOf(
                        "block" to 0L, "kind" to "PHONE", "tillCode" to TillCodes.forIndex(0), "email" to user.email,
                        "label" to deviceLabel, "createdAt" to now, "_ts" to ServerTime
                    )
                )
            )
        )
        db.withTransaction {
            val sql = db.openHelper.writableDatabase
            syncDao.clearOutbox()
            syncDao.clearCursors()
            syncDao.clearCounters()
            syncDao.clearParked()
            syncDao.clearConflicts()
            // Every row goes up once; from now on the triggers record each change
            for (t in SyncTables.ALL) sql.execSQL("INSERT INTO sync_outbox (tbl, rowId) SELECT '${t.name}', id FROM ${t.name} ORDER BY id")
            syncDao.saveFlags(
                SyncFlagsEntity(
                    tracking = true, block = 0, tillCode = TillCodes.forIndex(0), shopId = shopId, shopName = shopName,
                    email = user.email, role = ShopRoles.OWNER, linkedAt = now
                )
            )
        }
    }

    /**
     * Links this phone to [shop]: a safety copy first, then a fresh id block and till code, then every
     * synced table is emptied (tracking off, nothing is sent) and the whole shop downloads. The app
     * shows "Downloading shop…" until that is done, then asks for a PIN again.
     */
    suspend fun joinShop(shop: ShopRef) {
        val user = requireUser()
        check(syncDao.flags()?.isLinked != true) { "Unlink this phone from its shop first." }
        check(shop.role in ShopRoles.PHONE) {
            "Only an owner, admin or manager can link a phone to the shop. As ${ShopRoles.label(shop.role)} use the web app."
        }
        safetyCopy("Before joining ${shop.name}").getOrElse { throw IllegalStateException("Joining cancelled: the safety copy could not be saved (${it.message}).", it) }
        val (block, till) = claimDevice(shop.id, user)
        db.withTransaction {
            syncDao.saveFlags(
                SyncFlagsEntity(
                    tracking = false, applying = true, block = block, tillCode = till, shopId = shop.id, shopName = shop.name,
                    email = user.email, role = shop.role, downloading = true, linkedAt = System.currentTimeMillis()
                )
            )
            val sql = db.openHelper.writableDatabase
            // Children first: several foreign keys refuse to lose their parent (RESTRICT)
            for (t in SyncTables.ALL.asReversed()) sql.execSQL("DELETE FROM ${t.name}")
            syncDao.clearOutbox()
            syncDao.clearIdgen()
            syncDao.clearConflicts()
            syncDao.clearParked()
            syncDao.clearCursors()
            syncDao.clearCounters()
            syncDao.setApplying(false)
        }
        // start() sees downloading = 1 and downloads the shop
    }

    /** Takes the next id block and till code, together with the counter that hands them out. */
    private suspend fun claimDevice(shopId: String, user: SyncUser): Pair<Long, String> {
        val now = System.currentTimeMillis()
        return remote.transaction { tx ->
            val counters = tx.get("shops/$shopId/meta/counters") ?: throw IllegalStateException("This shop has no counters yet.")
            val block = (counters["nextBlock"] as? Number)?.toLong() ?: throw IllegalStateException("This shop's counters are damaged.")
            val phone = (counters["nextPhone"] as? Number)?.toLong() ?: 0L
            check(block < SyncIds.MAX_BLOCKS) { "This shop has no device numbers left." }
            val till = TillCodes.forIndex(phone)
            tx.merge("shops/$shopId/meta/counters", mapOf("nextBlock" to block + 1, "nextPhone" to phone + 1, "_ts" to ServerTime))
            tx.set(
                "shops/$shopId/devices/$block",
                mapOf("block" to block, "kind" to "PHONE", "tillCode" to till, "email" to user.email, "label" to deviceLabel, "createdAt" to now, "_ts" to ServerTime)
            )
            block to till
        }
    }

    /** Ends the link. The shop's records stay on this phone (and in the cloud); nothing is sent. */
    suspend fun unlink() = SyncLocal.unlink(db)

    private suspend fun rowsOutsideBlockZero(): Long {
        var n = 0L
        for (t in SyncTables.ALL) {
            n += syncDao.rawLong(androidx.sqlite.db.SimpleSQLiteQuery("SELECT COUNT(*) FROM ${t.name} WHERE id > ?", arrayOf<Any>(SyncIds.lastId(0)))) ?: 0L
        }
        return n
    }

    // ------------------------------------------------------------------ members & devices

    suspend fun members(): List<ShopMember> {
        val shopId = requireShop()
        return remote.getAll("shops/$shopId/members")
            .filter { it.data["_del"] != true }
            .map { d ->
                ShopMember(
                    email = d.data["email"] as? String ?: d.id,
                    name = d.data["name"] as? String ?: "",
                    role = d.data["role"] as? String ?: "",
                    active = d.data["active"] != false,
                    addedBy = d.data["addedBy"] as? String ?: "",
                    createdAt = (d.data["createdAt"] as? Number)?.toLong() ?: 0L
                )
            }
            .sortedWith(compareBy({ it.role != ShopRoles.OWNER }, { it.email }))
    }

    /** Adds (or re-adds) a member; owners and admins only, and never as owner. */
    suspend fun addMember(email: String, role: String) {
        val shopId = requireShop()
        val me = requireUser()
        val clean = email.trim().lowercase()
        require(clean.matches(Regex("[^@\\s]+@[^@\\s]+\\.[^@\\s]+"))) { "Enter a valid email address." }
        require(role in ShopRoles.ASSIGNABLE) { "Choose admin, manager or salesperson." }
        check(ShopRoles.canManageMembers(syncDao.flags()?.role)) { "Only the owner or an admin can add members." }
        remote.commit(
            listOf(
                RemoteWrite.Set(
                    "shops/$shopId/members/$clean",
                    mapOf(
                        "email" to clean, "name" to "", "role" to role, "active" to true, "addedBy" to me.email,
                        "createdAt" to System.currentTimeMillis(), "_ts" to ServerTime, "_del" to false
                    )
                )
            )
        )
    }

    suspend fun changeMemberRole(email: String, role: String) {
        val shopId = requireShop()
        require(role in ShopRoles.ASSIGNABLE) { "Choose admin, manager or salesperson." }
        check(ShopRoles.canManageMembers(syncDao.flags()?.role)) { "Only the owner or an admin can change roles." }
        remote.commit(listOf(RemoteWrite.Merge("shops/$shopId/members/$email", mapOf("email" to email, "role" to role, "_ts" to ServerTime))))
    }

    suspend fun setMemberActive(email: String, active: Boolean) {
        val shopId = requireShop()
        check(ShopRoles.canManageMembers(syncDao.flags()?.role)) { "Only the owner or an admin can do this." }
        remote.commit(listOf(RemoteWrite.Merge("shops/$shopId/members/$email", mapOf("email" to email, "active" to active, "_ts" to ServerTime))))
    }

    suspend fun devices(): List<ShopDevice> {
        val shopId = requireShop()
        return remote.getAll("shops/$shopId/devices").map { d ->
            ShopDevice(
                block = (d.data["block"] as? Number)?.toLong() ?: d.id.toLongOrNull() ?: 0L,
                kind = d.data["kind"] as? String ?: "",
                tillCode = d.data["tillCode"] as? String ?: "",
                email = d.data["email"] as? String ?: "",
                label = d.data["label"] as? String ?: "",
                createdAt = (d.data["createdAt"] as? Number)?.toLong() ?: 0L
            )
        }.sortedBy { it.block }
    }

    private fun requireUser(): SyncUser {
        check(remote.available) { "Cloud sync isn't set up in this build." }
        val user = auth.user.value ?: throw IllegalStateException("Sign in with Google first.")
        check(user.email.isNotBlank()) { "This Google account has no email address." }
        check(user.emailVerified) { "This account's email address isn't verified." }
        return user
    }

    private suspend fun requireShop(): String = syncDao.flags()?.shopId ?: throw IllegalStateException("This phone isn't linked to a shop.")

    companion object {
        private const val TAG = "ElectroPosSync"
        /** Firestore allows 500 writes per batch. */
        const val BATCH_SIZE = 400

        fun friendly(e: Throwable): String = when {
            e is RemoteException && e.code == RemoteException.Code.UNAVAILABLE -> "No connection to the cloud. Check the internet and try again."
            e is RemoteException && e.code == RemoteException.Code.PERMISSION_DENIED -> "The cloud refused this: the account has no permission for it."
            else -> e.message ?: e.javaClass.simpleName
        }
    }
}
