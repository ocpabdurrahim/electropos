package com.example.sync

import androidx.room.RoomDatabase
import androidx.room.withTransaction
import androidx.sqlite.db.SimpleSQLiteQuery
import com.example.data.local.database.AppDatabase

/**
 * Row ids that stay unique across devices (docs/data-model.md, "Ids"): every device owns an
 * id block of 2^32 ids and new rows take the next free id inside it. SQLite's own sequence is
 * never used for synced tables; the guard trigger (SyncSchema) refuses an insert outside the block.
 */
object SyncIds {
    const val BLOCK_SIZE = 4_294_967_296L // 2^32
    /** Blocks stay below 2^21, so every id is below 2^53 (exact in JavaScript). */
    const val MAX_BLOCKS = 2_097_152L

    fun firstId(block: Long): Long = block * BLOCK_SIZE + 1
    fun lastId(block: Long): Long = block * BLOCK_SIZE + BLOCK_SIZE - 1
    fun blockOf(id: Long): Long = id / BLOCK_SIZE

    /** The next id of [table] in this device's block. */
    suspend fun next(db: RoomDatabase, table: String): Long = reserve(db, table, 1)

    /** Gives every row of [rows] without an id (0) the next id of this device's block. */
    suspend fun <T> assign(db: RoomDatabase, table: String, rows: List<T>, idOf: (T) -> Long, withId: (T, Long) -> T): List<T> {
        val missing = rows.count { idOf(it) == 0L }
        if (missing == 0) return rows
        var nextId = reserve(db, table, missing)
        return rows.map { if (idOf(it) == 0L) withId(it, nextId++) else it }
    }

    /** Reserves [count] consecutive ids and returns the first. */
    private suspend fun reserve(db: RoomDatabase, table: String, count: Int): Long {
        val dao = (db as AppDatabase).syncDao()
        return db.withTransaction {
            val block = dao.block() ?: 0L
            val first = firstId(block)
            val last = lastId(block)
            // Rows can also arrive with an id of this block (a restore, a test, a row this phone sent and got back)
            val highest = dao.rawLong(SimpleSQLiteQuery("SELECT MAX(id) FROM $table WHERE id BETWEEN ? AND ?", arrayOf<Any>(first, last)))
            val stored = dao.idgenNext(table)?.takeIf { it in first..last } ?: first
            val id = maxOf(stored, (highest ?: 0L) + 1, first)
            check(id + count - 1 <= last) { "This device has used every id of its block for $table." }
            dao.setIdgen(SyncIdGenEntity(table, id + count))
            id
        }
    }
}
