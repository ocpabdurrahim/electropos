package com.example.sync

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.sqlite.db.SupportSQLiteQuery
import kotlinx.coroutines.flow.Flow

/** One changed row waiting for upload: [lastSeq] is the newest outbox entry seen for it. */
data class PendingRow(val tbl: String, val rowId: Long, val firstSeq: Long, val lastSeq: Long)

@Dao
interface SyncDao {
    // ---- flags
    @Query("SELECT * FROM sync_flags WHERE id = 1")
    suspend fun flags(): SyncFlagsEntity?

    @Query("SELECT * FROM sync_flags WHERE id = 1")
    fun observeFlags(): Flow<SyncFlagsEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveFlags(flags: SyncFlagsEntity)

    @Query("UPDATE sync_flags SET applying = :on WHERE id = 1")
    suspend fun setApplying(on: Boolean)

    @Query("UPDATE sync_flags SET lastSyncAt = :at WHERE id = 1")
    suspend fun setLastSyncAt(at: Long)

    @Query("UPDATE sync_flags SET role = :role WHERE id = 1")
    suspend fun setRole(role: String?)

    @Query("SELECT block FROM sync_flags WHERE id = 1")
    suspend fun block(): Long?

    // ---- ids
    @RawQuery
    suspend fun rawLong(query: SupportSQLiteQuery): Long?

    @Query("SELECT next FROM sync_idgen WHERE tbl = :tbl")
    suspend fun idgenNext(tbl: String): Long?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun setIdgen(entry: SyncIdGenEntity)

    @Query("DELETE FROM sync_idgen")
    suspend fun clearIdgen()

    // ---- outbox
    @Query("""
        SELECT tbl, rowId, MIN(id) AS firstSeq, MAX(id) AS lastSeq FROM sync_outbox
        GROUP BY tbl, rowId ORDER BY firstSeq LIMIT :limit
    """)
    suspend fun pendingBatch(limit: Int): List<PendingRow>

    /** Removes what was sent; an entry written after the row was read (id > [upTo]) stays for the next round. */
    @Query("DELETE FROM sync_outbox WHERE tbl = :tbl AND rowId = :rowId AND id <= :upTo")
    suspend fun clearSent(tbl: String, rowId: Long, upTo: Long)

    @Query("SELECT COUNT(*) FROM (SELECT DISTINCT tbl, rowId FROM sync_outbox)")
    fun observePendingCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM (SELECT DISTINCT tbl, rowId FROM sync_outbox)")
    suspend fun pendingCount(): Int

    @Query("SELECT EXISTS(SELECT 1 FROM sync_outbox WHERE tbl = :tbl AND rowId = :rowId)")
    suspend fun hasPending(tbl: String, rowId: Long): Boolean

    @Query("DELETE FROM sync_outbox")
    suspend fun clearOutbox()

    // ---- sync issues
    @Query("SELECT * FROM sync_conflicts ORDER BY createdAt DESC, id DESC")
    fun observeConflicts(): Flow<List<SyncConflictEntity>>

    @Query("SELECT * FROM sync_conflicts ORDER BY createdAt DESC, id DESC")
    suspend fun conflicts(): List<SyncConflictEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addConflict(conflict: SyncConflictEntity)

    @Query("DELETE FROM sync_conflicts WHERE id = :id")
    suspend fun dismissConflict(id: Long)

    @Query("DELETE FROM sync_conflicts WHERE tbl = :tbl AND rowId = :rowId AND kind = :kind")
    suspend fun clearConflict(tbl: String, rowId: Long, kind: String)

    @Query("DELETE FROM sync_conflicts")
    suspend fun clearConflicts()

    // ---- records waiting for their parent
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun park(entry: SyncPendingInboundEntity)

    @Query("SELECT * FROM sync_pending_inbound")
    suspend fun parked(): List<SyncPendingInboundEntity>

    @Query("SELECT COUNT(*) FROM sync_pending_inbound")
    fun observeParkedCount(): Flow<Int>

    @Query("DELETE FROM sync_pending_inbound WHERE tbl = :tbl AND rowId = :rowId")
    suspend fun unpark(tbl: String, rowId: Long)

    @Query("DELETE FROM sync_pending_inbound")
    suspend fun clearParked()

    // ---- this phone's number counters
    @Query("SELECT next FROM sync_counters WHERE series = :series")
    suspend fun counter(series: String): Long?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun setCounter(counter: SyncCounterEntity)

    @Query("DELETE FROM sync_counters")
    suspend fun clearCounters()

    // ---- download positions
    @Query("SELECT ts FROM sync_cursors WHERE tbl = :tbl")
    suspend fun cursor(tbl: String): Long?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun setCursor(cursor: SyncCursorEntity)

    @Query("DELETE FROM sync_cursors")
    suspend fun clearCursors()
}
