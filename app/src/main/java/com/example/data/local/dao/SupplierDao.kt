package com.example.data.local.dao

import androidx.room.*
import com.example.sync.SyncIds
import com.example.data.local.entities.SupplierEntity
import kotlinx.coroutines.flow.Flow

@Dao
abstract class SupplierDao(private val db: RoomDatabase) {
    @Query("SELECT * FROM suppliers WHERE isActive = 1 ORDER BY name ASC")
    abstract fun getAllSuppliers(): Flow<List<SupplierEntity>>

    @Query("SELECT * FROM suppliers WHERE isActive = 1 ORDER BY name ASC")
    abstract suspend fun getAllSuppliersSync(): List<SupplierEntity>

    @Query("SELECT * FROM suppliers WHERE id = :id LIMIT 1")
    abstract suspend fun getSupplierById(id: Long): SupplierEntity?

    @Query("SELECT * FROM suppliers WHERE id = :id LIMIT 1")
    abstract fun observeSupplierById(id: Long): Flow<SupplierEntity?>

    @Query("SELECT * FROM suppliers WHERE supplierCode = :code LIMIT 1")
    abstract suspend fun getSupplierByCode(code: String): SupplierEntity?

    @Query("""
        SELECT * FROM suppliers 
        WHERE (name LIKE '%' || :query || '%' 
           OR company LIKE '%' || :query || '%' 
           OR phone LIKE '%' || :query || '%' 
           OR supplierCode LIKE '%' || :query || '%')
          AND isActive = 1
        ORDER BY name ASC
    """)
    abstract fun searchSuppliers(query: String): Flow<List<SupplierEntity>>

    @Query("SELECT COUNT(*) FROM suppliers")
    abstract suspend fun getSupplierCount(): Int

    @Query("SELECT MAX(id) FROM suppliers")
    abstract suspend fun getMaxSupplierId(): Long?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertSupplierRaw(supplier: SupplierEntity): Long

    suspend fun insertSupplier(supplier: SupplierEntity): Long =
        insertSupplierRaw(if (supplier.id == 0L) supplier.copy(id = SyncIds.next(db, "suppliers")) else supplier)

    @Update
    abstract suspend fun updateSupplier(supplier: SupplierEntity)

    @Query("UPDATE suppliers SET currentDue = currentDue + :delta, updatedAt = :timestamp WHERE id = :id")
    abstract suspend fun adjustSupplierDue(id: Long, delta: Double, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE suppliers SET currentDue = :balance, updatedAt = :timestamp WHERE id = :id")
    abstract suspend fun setSupplierDue(id: Long, balance: Double, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE suppliers SET isActive = 0, updatedAt = :timestamp WHERE id = :id")
    abstract suspend fun deactivateSupplier(id: Long, timestamp: Long = System.currentTimeMillis())

    @Query("SELECT SUM(currentDue) FROM suppliers WHERE isActive = 1")
    abstract fun getTotalSupplierDue(): Flow<Double?>
}
