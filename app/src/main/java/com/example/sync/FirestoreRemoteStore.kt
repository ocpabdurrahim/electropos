package com.example.sync

import com.google.firebase.Firebase
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.auth
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.MetadataChanges
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.Source
import com.google.firebase.firestore.Transaction
import com.google.firebase.firestore.firestore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.tasks.await
import org.json.JSONObject

/** [RemoteStore] on Cloud Firestore. Kept to plain calls so the emulator test can cover it later. */
class FirestoreRemoteStore(private val db: FirebaseFirestore = Firebase.firestore) : RemoteStore {
    override val available = true

    override fun newId(): String = db.collection("shops").document().id

    override suspend fun commit(writes: List<RemoteWrite>) = remote {
        val batch = db.batch()
        for (w in writes) when (w) {
            is RemoteWrite.Set -> batch.set(db.document(w.path), encode(w.data))
            is RemoteWrite.Merge -> batch.set(db.document(w.path), encode(w.data), SetOptions.merge())
        }
        batch.commit().await()
        Unit
    }

    override suspend fun get(path: String): RemoteDoc? = remote {
        db.document(path).get(Source.SERVER).await().takeIf { it.exists() }?.let(::toDoc)
    }

    override suspend fun getAll(collectionPath: String, sinceTs: Long): List<RemoteDoc> = remote {
        changedSince(collectionPath, sinceTs).get(Source.SERVER).await().documents.map(::toDoc)
    }

    override suspend fun membersByEmail(email: String): List<Pair<String, RemoteDoc>> = remote {
        db.collectionGroup("members").whereEqualTo("email", email).get(Source.SERVER).await().documents
            .mapNotNull { d -> d.reference.parent.parent?.id?.let { shopId -> shopId to toDoc(d) } }
    }

    override fun listen(
        collectionPath: String,
        sinceTs: Long,
        onSnapshot: (RemoteSnapshot) -> Unit,
        onError: (RemoteException) -> Unit
    ): RemoteListener {
        val registration = changedSince(collectionPath, sinceTs).addSnapshotListener(MetadataChanges.INCLUDE) { snap, err ->
            when {
                err != null -> onError(mapError(err))
                snap != null -> onSnapshot(
                    RemoteSnapshot(
                        changed = snap.documentChanges.filter { it.type != DocumentChange.Type.REMOVED }.map { toDoc(it.document) },
                        fromServer = !snap.metadata.isFromCache
                    )
                )
            }
        }
        return RemoteListener { registration.remove() }
    }

    override suspend fun <T> transaction(block: (RemoteTransaction) -> T): T = remote {
        db.runTransaction { t -> block(Tx(t)) }.await()
    }

    private inner class Tx(private val t: Transaction) : RemoteTransaction {
        override fun get(path: String): Map<String, Any?>? = t.get(db.document(path)).takeIf { it.exists() }?.let { toDoc(it).data }
        override fun set(path: String, data: Map<String, Any?>) {
            t.set(db.document(path), encode(data))
        }
        override fun merge(path: String, data: Map<String, Any?>) {
            t.set(db.document(path), encode(data), SetOptions.merge())
        }
    }

    private fun changedSince(path: String, sinceTs: Long): Query =
        db.collection(path).whereGreaterThanOrEqualTo("_ts", Timestamp(sinceTs / 1000, ((sinceTs % 1000) * 1_000_000).toInt()))

    private fun encode(data: Map<String, Any?>): Map<String, Any?> =
        data.mapValues { (_, v) -> if (v === ServerTime) FieldValue.serverTimestamp() else v }

    private fun toDoc(d: DocumentSnapshot): RemoteDoc {
        val raw = d.getData(DocumentSnapshot.ServerTimestampBehavior.ESTIMATE).orEmpty()
        val data = LinkedHashMap<String, Any?>()
        for ((k, v) in raw) if (k != "_ts") data[k] = if (v is Timestamp) millis(v) else v
        return RemoteDoc(d.id, data, (raw["_ts"] as? Timestamp)?.let(::millis), d.metadata.hasPendingWrites())
    }

    private fun millis(t: Timestamp): Long = t.seconds * 1000 + t.nanoseconds / 1_000_000

    private suspend fun <T> remote(block: suspend () -> T): T = try {
        block()
    } catch (e: FirebaseFirestoreException) {
        throw mapError(e)
    }

    private fun mapError(e: FirebaseFirestoreException): RemoteException {
        val code = when (e.code) {
            FirebaseFirestoreException.Code.PERMISSION_DENIED, FirebaseFirestoreException.Code.UNAUTHENTICATED ->
                RemoteException.Code.PERMISSION_DENIED
            FirebaseFirestoreException.Code.UNAVAILABLE, FirebaseFirestoreException.Code.DEADLINE_EXCEEDED ->
                RemoteException.Code.UNAVAILABLE
            FirebaseFirestoreException.Code.NOT_FOUND -> RemoteException.Code.NOT_FOUND
            else -> RemoteException.Code.OTHER
        }
        return RemoteException(code, e.message ?: e.code.name, e)
    }
}

/** [SyncAuth] on Firebase Authentication (Google accounts). */
class FirebaseSyncAuth : SyncAuth {
    private val auth get() = Firebase.auth
    private val _user = MutableStateFlow(auth.currentUser?.toSyncUser())
    override val user: StateFlow<SyncUser?> = _user

    init {
        auth.addAuthStateListener { _user.value = it.currentUser?.toSyncUser() }
    }

    override suspend fun signInWithGoogle(idToken: String) {
        auth.signInWithCredential(GoogleAuthProvider.getCredential(idToken, null)).await()
    }

    /** The Auth emulator accepts an unsigned Google token, so any test email works there. */
    override suspend fun signInForTests(email: String) {
        val clean = email.trim().lowercase()
        val token = JSONObject()
            .put("sub", "test-" + clean.hashCode().toUInt().toString(16))
            .put("email", clean)
            .put("email_verified", true)
            .put("name", clean.substringBefore('@').replaceFirstChar { it.uppercase() })
            .toString()
        auth.signInWithCredential(GoogleAuthProvider.getCredential(token, null)).await()
    }

    override fun signOut() = auth.signOut()

    private fun FirebaseUser.toSyncUser() =
        SyncUser(uid = uid, email = email?.lowercase().orEmpty(), name = displayName.orEmpty(), emailVerified = isEmailVerified)
}
