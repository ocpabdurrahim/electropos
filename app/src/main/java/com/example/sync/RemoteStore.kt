package com.example.sync

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** A cloud document. [ts] is its `_ts` in ms (null while this phone's own write is still on its way). */
data class RemoteDoc(
    val id: String,
    val data: Map<String, Any?>,
    val ts: Long?,
    val pendingWrite: Boolean = false
)

/** What a listener delivers: the documents that changed, and whether this came from the server. */
data class RemoteSnapshot(val changed: List<RemoteDoc>, val fromServer: Boolean)

/** Written as the server's own time (Firestore `serverTimestamp()`); the rules want `_ts == request.time`. */
object ServerTime

sealed class RemoteWrite {
    abstract val path: String
    abstract val data: Map<String, Any?>

    /** Replaces the whole document. */
    data class Set(override val path: String, override val data: Map<String, Any?>) : RemoteWrite()

    /** Changes only these fields (creates the document if needed). */
    data class Merge(override val path: String, override val data: Map<String, Any?>) : RemoteWrite()
}

fun interface RemoteListener {
    fun remove()
}

/** Reads and writes of one server transaction (all reads first, as Firestore requires). */
interface RemoteTransaction {
    fun get(path: String): Map<String, Any?>?
    fun set(path: String, data: Map<String, Any?>)
    fun merge(path: String, data: Map<String, Any?>)
}

class RemoteException(val code: Code, message: String, cause: Throwable? = null) : Exception(message, cause) {
    enum class Code { PERMISSION_DENIED, UNAVAILABLE, NOT_FOUND, NOT_SET_UP, OTHER }
}

/**
 * The cloud as the sync engine needs it: a thin layer over Firestore ([FirestoreRemoteStore]),
 * with an in-memory twin for the JVM tests. Paths are Firestore paths ("shops/abc/sales/42").
 */
interface RemoteStore {
    /** False when this build has no Firebase project (google-services.json missing). */
    val available: Boolean

    fun newId(): String

    /** One atomic batch (at most 500 writes). Suspends while offline, like Firestore. */
    suspend fun commit(writes: List<RemoteWrite>)

    /** One document from the server. */
    suspend fun get(path: String): RemoteDoc?

    /** A collection's documents with `_ts >= sinceTs`, from the server. */
    suspend fun getAll(collectionPath: String, sinceTs: Long = 0L): List<RemoteDoc>

    /** Every `members` document (in any shop) with this email: shop id to the member document. */
    suspend fun membersByEmail(email: String): List<Pair<String, RemoteDoc>>

    /** Live changes of a collection's documents with `_ts >= sinceTs` (the first delivery holds them all). */
    fun listen(
        collectionPath: String,
        sinceTs: Long,
        onSnapshot: (RemoteSnapshot) -> Unit,
        onError: (RemoteException) -> Unit
    ): RemoteListener

    suspend fun <T> transaction(block: (RemoteTransaction) -> T): T
}

/** The Google account the phone is signed in with (for the cloud only; the counter uses PINs). */
data class SyncUser(val uid: String, val email: String, val name: String, val emailVerified: Boolean = true)

interface SyncAuth {
    val user: StateFlow<SyncUser?>
    suspend fun signInWithGoogle(idToken: String)

    /** Firebase emulator builds only: any email, no Google account needed. */
    suspend fun signInForTests(email: String)
    fun signOut()
}

/** Stand-in when the build has no Firebase project: everything works offline, the Cloud screen says why. */
object NoRemote : RemoteStore {
    private fun notSetUp(): Nothing = throw RemoteException(RemoteException.Code.NOT_SET_UP, "Cloud sync isn't set up in this build.")
    override val available = false
    override fun newId(): String = notSetUp()
    override suspend fun commit(writes: List<RemoteWrite>) = notSetUp()
    override suspend fun get(path: String): RemoteDoc? = notSetUp()
    override suspend fun getAll(collectionPath: String, sinceTs: Long): List<RemoteDoc> = notSetUp()
    override suspend fun membersByEmail(email: String): List<Pair<String, RemoteDoc>> = notSetUp()
    override fun listen(
        collectionPath: String,
        sinceTs: Long,
        onSnapshot: (RemoteSnapshot) -> Unit,
        onError: (RemoteException) -> Unit
    ): RemoteListener = notSetUp()
    override suspend fun <T> transaction(block: (RemoteTransaction) -> T): T = notSetUp()
}

object NoAuth : SyncAuth {
    override val user: StateFlow<SyncUser?> = MutableStateFlow(null)
    override suspend fun signInWithGoogle(idToken: String) = throw IllegalStateException("Cloud sync isn't set up in this build.")
    override suspend fun signInForTests(email: String) = throw IllegalStateException("Cloud sync isn't set up in this build.")
    override fun signOut() {}
}
