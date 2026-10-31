package com.example.data.local.dao

import androidx.room.*
import com.example.sync.SyncIds
import com.example.data.local.entities.SupplierLedgerEntryEntity
import kotlinx.coroutines.flow.Flow

@Dao
abstract class SupplierLedgerDao(private val db: RoomDatabase) {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertLedgerEntryRaw(entry: SupplierLedgerEntryEntity): Long

    suspend fun insertLedgerEntry(entry: SupplierLedgerEntryEntity): Long =
        insertLedgerEntryRaw(if (entry.id == 0L) entry.copy(id = SyncIds.next(db, "supplier_ledger_entries")) else entry)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertLedgerEntriesRaw(entries: List<SupplierLedgerEntryEntity>): List<Long>

    suspend fun insertLedgerEntries(entries: List<SupplierLedgerEntryEntity>): List<Long> =
        insertLedgerEntriesRaw(SyncIds.assign(db, "supplier_ledger_entries", entries, { it.id }) { row, id -> row.copy(id = id) })

    @Query("SELECT * FROM supplier_ledger_entries WHERE supplierId = :supplierId ORDER BY createdAt ASC, id ASC")
    abstract fun getLedgerEntriesForSupplier(supplierId: Long): Flow<List<SupplierLedgerEntryEntity>>

    @Query("SELECT * FROM supplier_ledger_entries WHERE supplierId = :supplierId ORDER BY createdAt ASC, id ASC")
    abstract suspend fun getLedgerEntriesForSupplierSync(supplierId: Long): List<SupplierLedgerEntryEntity>

    @Query("SELECT * FROM supplier_ledger_entries WHERE supplierId = :supplierId ORDER BY createdAt DESC, id DESC LIMIT 1")
    abstract suspend fun getLatestLedgerEntryForSupplier(supplierId: Long): SupplierLedgerEntryEntity?

    @Query("SELECT COALESCE(SUM(signedAmount), 0.0) FROM supplier_ledger_entries WHERE supplierId = :supplierId")
    abstract suspend fun calculateAuthoritativeBalance(supplierId: Long): Double

    @Query("SELECT COALESCE(SUM(signedAmount), 0.0) FROM supplier_ledger_entries WHERE supplierId = :supplierId")
    abstract fun observeAuthoritativeBalance(supplierId: Long): Flow<Double>

    @Query("DELETE FROM supplier_ledger_entries WHERE referenceId = :referenceId AND transactionType = :type")
    abstract suspend fun deleteByReference(referenceId: Long, type: String)

    @Query("DELETE FROM supplier_ledger_entries WHERE supplierId = :supplierId")
    abstract suspend fun deleteBySupplier(supplierId: Long)
}
