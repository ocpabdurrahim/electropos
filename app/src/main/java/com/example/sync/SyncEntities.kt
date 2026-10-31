package com.example.sync

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * This phone's link to a cloud shop, one row (id 1). The change triggers read [tracking] and
 * [applying]; the id guard reads [block]. A missing row means "never linked" (see SyncSchema).
 */
@Entity(tableName = "sync_flags")
data class SyncFlagsEntity(
    @PrimaryKey val id: Long = 1,
    /** Local changes are recorded in sync_outbox. */
    val tracking: Boolean = false,
    /** Rows written now come from the cloud: not recorded, and allowed outside the block. */
    val applying: Boolean = false,
    /** This phone's id block (ids block·2^32+1 … (block+1)·2^32−1). */
    val block: Long = 0,
    val tillCode: String? = null,
    val shopId: String? = null,
    val shopName: String? = null,
    /** Google account that linked the phone. */
    val email: String? = null,
    /** That account's role in the shop when last checked. */
    val role: String? = null,
    /** Joining: the shop is still downloading, the app can't be used yet. */
    val downloading: Boolean = false,
    val linkedAt: Long = 0,
    val lastSyncAt: Long = 0
) {
    val isLinked: Boolean get() = shopId != null
}

/** Append-only change log written by the triggers; read grouped by row, cleaned up to the id that was sent. */
@Entity(tableName = "sync_outbox", indices = [Index(value = ["tbl", "rowId"])])
data class SyncOutboxEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tbl: String,
    val rowId: Long
)

/** Next id to hand out per table (never goes back, so deleted ids are not reused). */
@Entity(tableName = "sync_idgen")
data class SyncIdGenEntity(
    @PrimaryKey val tbl: String,
    val next: Long
)

/** A cloud record that could not be applied (or a local one the server refused), listed under Sync issues. */
@Entity(tableName = "sync_conflicts", indices = [Index(value = ["tbl", "rowId", "kind"], unique = true)])
data class SyncConflictEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tbl: String,
    val rowId: Long,
    /** DUPLICATE (a unique value clash), SAME_NAME, REJECTED (refused by the server). */
    val kind: String,
    val message: String,
    val createdAt: Long = System.currentTimeMillis()
)

/** A cloud record whose parent row has not arrived yet; retried after every download. */
@Entity(tableName = "sync_pending_inbound", primaryKeys = ["tbl", "rowId"])
data class SyncPendingInboundEntity(
    val tbl: String,
    val rowId: Long,
    /** The document as JSON. */
    val data: String,
    val ts: Long,
    val reason: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

/** This phone's own number counters while online (invoice, purchase, ... ; see DocNumbers). */
@Entity(tableName = "sync_counters")
data class SyncCounterEntity(
    @PrimaryKey val series: String,
    val next: Long
)

/** Newest server time (`_ts`, ms) already applied per collection; the listeners start from it. */
@Entity(tableName = "sync_cursors")
data class SyncCursorEntity(
    @PrimaryKey val tbl: String,
    val ts: Long
)
