package com.example.sync

import android.content.Context
import androidx.room.Room
import com.example.data.local.database.AppDatabase
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.util.UUID

/**
 * Firestore as the sync engine sees it, in memory: server timestamps in arrival order (one per
 * batch), merge writes, listeners on `_ts >= x` that deliver changed documents, and devices that
 * go offline (their writes wait, their listeners hear nothing) and come back.
 */
class FakeCloud {
    private val lock = Any()
    private val pumpLock = Any()
    private var clock = 1_000L
    /** path -> document fields, `_ts` as server ms. */
    val docs = LinkedHashMap<String, MutableMap<String, Any?>>()
    private val listeners = ArrayList<Listener>()
    val commits = ArrayList<List<RemoteWrite>>()

    /** Lets a test play the rules: return true to refuse a write. */
    @Volatile var refuse: (path: String, email: String?) -> Boolean = { _, _ -> false }

    private class Listener(
        val store: FakeRemoteStore,
        val collection: String,
        val since: Long,
        val onSnapshot: (RemoteSnapshot) -> Unit,
        val delivered: HashMap<String, Long> = HashMap(),
        @Volatile var removed: Boolean = false
    )

    fun doc(path: String): Map<String, Any?>? = synchronized(lock) { docs[path]?.toMap() }

    fun collection(path: String): Map<String, Map<String, Any?>> = synchronized(lock) {
        docs.filterKeys { it.substringBeforeLast('/') == path }.mapKeys { it.key.substringAfterLast('/') }.mapValues { it.value.toMap() }
    }

    fun put(path: String, data: Map<String, Any?>) {
        synchronized(lock) { write(listOf(RemoteWrite.Set(path, data))) }
        pump()
    }

    internal fun commit(writes: List<RemoteWrite>, email: String?) {
        synchronized(lock) {
            if (writes.any { refuse(it.path, email) }) throw RemoteException(RemoteException.Code.PERMISSION_DENIED, "Missing or insufficient permissions.")
            commits.add(writes)
            write(writes)
        }
        pump()
    }

    private fun write(writes: List<RemoteWrite>) {
        val ts = ++clock
        for (w in writes) {
            val data = w.data.mapValues { (_, v) -> if (v === ServerTime) ts else v }
            when (w) {
                is RemoteWrite.Set -> docs[w.path] = LinkedHashMap(data)
                is RemoteWrite.Merge -> docs.getOrPut(w.path) { LinkedHashMap() }.putAll(data)
            }
        }
    }

    internal fun <T> transaction(block: (RemoteTransaction) -> T): T {
        val result = synchronized(lock) {
            val pending = ArrayList<RemoteWrite>()
            val r = block(object : RemoteTransaction {
                override fun get(path: String): Map<String, Any?>? = docs[path]?.filterKeys { it != "_ts" }
                override fun set(path: String, data: Map<String, Any?>) { pending.add(RemoteWrite.Set(path, data)) }
                override fun merge(path: String, data: Map<String, Any?>) { pending.add(RemoteWrite.Merge(path, data)) }
            })
            commits.add(pending)
            write(pending)
            r
        }
        pump()
        return result
    }

    internal fun get(path: String): RemoteDoc? = synchronized(lock) { docs[path]?.let { toDoc(path, it) } }

    internal fun getAll(collection: String, since: Long): List<RemoteDoc> = synchronized(lock) {
        docs.filter { (p, d) -> p.substringBeforeLast('/') == collection && ((d["_ts"] as? Long) ?: 0L) >= since }.map { (p, d) -> toDoc(p, d) }
    }

    internal fun membersByEmail(email: String): List<Pair<String, RemoteDoc>> = synchronized(lock) {
        docs.filter { (p, d) -> p.matches(Regex("shops/[^/]+/members/[^/]+")) && d["email"] == email }
            .map { (p, d) -> p.split('/')[1] to toDoc(p, d) }
    }

    internal fun listen(store: FakeRemoteStore, collection: String, since: Long, onSnapshot: (RemoteSnapshot) -> Unit): RemoteListener {
        val l = Listener(store, collection, since, onSnapshot)
        synchronized(lock) { listeners.add(l) }
        pump()
        return RemoteListener {
            l.removed = true
            synchronized(lock) { listeners.remove(l) }
        }
    }

    /** Delivers every change a listening, online device hasn't heard about yet. */
    fun pump() = synchronized(pumpLock) {
        // One pump at a time, so a listener never hears an older version after a newer one
        val deliveries = ArrayList<Pair<Listener, RemoteSnapshot>>()
        synchronized(lock) {
            for (l in listeners) {
                if (l.removed || !l.store.online.value) continue
                val changed = ArrayList<RemoteDoc>()
                for ((p, d) in docs) {
                    if (p.substringBeforeLast('/') != l.collection) continue
                    val ts = (d["_ts"] as? Long) ?: continue
                    if (ts < l.since || l.delivered[p] == ts) continue
                    l.delivered[p] = ts
                    changed.add(toDoc(p, d))
                }
                // The first delivery always comes, even when empty: it says "loaded from the server"
                if (changed.isNotEmpty() || !l.delivered.containsKey(FIRST)) {
                    l.delivered[FIRST] = 0L
                    deliveries.add(l to RemoteSnapshot(changed, fromServer = true))
                }
            }
        }
        for ((l, snap) in deliveries) if (!l.removed) l.onSnapshot(snap)
    }

    private fun toDoc(path: String, d: Map<String, Any?>) =
        RemoteDoc(path.substringAfterLast('/'), d.filterKeys { it != "_ts" }, d["_ts"] as? Long)

    companion object {
        private const val FIRST = "\u0000first"
    }
}

/** One device's view of [FakeCloud]. */
class FakeRemoteStore(private val cloud: FakeCloud, private val auth: FakeAuth? = null) : RemoteStore {
    val online = MutableStateFlow(true)
    override val available = true

    fun goOffline() {
        online.value = false
    }

    fun goOnline() {
        online.value = true
        cloud.pump()
    }

    override fun newId(): String = UUID.randomUUID().toString().replace("-", "").take(20)

    override suspend fun commit(writes: List<RemoteWrite>) {
        online.first { it } // like Firestore: the write waits for the connection
        cloud.commit(writes, auth?.user?.value?.email)
    }

    private fun requireOnline() {
        if (!online.value) throw RemoteException(RemoteException.Code.UNAVAILABLE, "offline")
    }

    override suspend fun get(path: String): RemoteDoc? {
        requireOnline()
        return cloud.get(path)
    }

    override suspend fun getAll(collectionPath: String, sinceTs: Long): List<RemoteDoc> {
        requireOnline()
        return cloud.getAll(collectionPath, sinceTs)
    }

    override suspend fun membersByEmail(email: String): List<Pair<String, RemoteDoc>> {
        requireOnline()
        return cloud.membersByEmail(email)
    }

    override fun listen(collectionPath: String, sinceTs: Long, onSnapshot: (RemoteSnapshot) -> Unit, onError: (RemoteException) -> Unit): RemoteListener =
        cloud.listen(this, collectionPath, sinceTs, onSnapshot)

    override suspend fun <T> transaction(block: (RemoteTransaction) -> T): T {
        requireOnline()
        return cloud.transaction(block)
    }
}

class FakeAuth(email: String? = null) : SyncAuth {
    private val _user = MutableStateFlow(email?.let(::userFor))
    override val user: StateFlow<SyncUser?> = _user

    fun signIn(email: String) {
        _user.value = userFor(email)
    }

    override suspend fun signInWithGoogle(idToken: String) = signIn(idToken)
    override suspend fun signInForTests(email: String) = signIn(email)
    override fun signOut() {
        _user.value = null
    }

    private fun userFor(email: String) = SyncUser(uid = "uid-$email", email = email, name = email.substringBefore('@'))
}

/** An in-memory database with the sync triggers, as the app opens it. */
fun syncDatabase(context: Context): AppDatabase =
    Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
        .addCallback(SyncSchema.Callback())
        .allowMainThreadQueries()
        .build()

/** Waits (up to [timeoutMs]) until [condition] holds; fails with [message] otherwise. */
fun eventually(message: String = "condition", timeoutMs: Long = 20_000, condition: suspend () -> Boolean) = runBlocking {
    val end = System.currentTimeMillis() + timeoutMs
    while (true) {
        if (condition()) return@runBlocking
        if (System.currentTimeMillis() > end) throw AssertionError("Timed out waiting for: $message")
        delay(25)
    }
}
