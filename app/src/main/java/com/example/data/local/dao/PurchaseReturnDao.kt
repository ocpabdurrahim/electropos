package com.example.data.local.dao

import androidx.room.*
import com.example.sync.SyncIds
import com.example.data.local.entities.PurchaseReturnEntity
import com.example.data.local.entities.PurchaseReturnItemEntity
import kotlinx.coroutines.flow.Flow

@Dao
abstract class PurchaseReturnDao(private val db: RoomDatabase) {
    @Query("SELECT * FROM purchase_returns ORDER BY returnDate DESC")
    abstract fun getAllReturns(): Flow<List<PurchaseReturnEntity>>

    @Query("SELECT * FROM purchase_returns WHERE id = :id LIMIT 1")
    abstract suspend fun getReturnById(id: Long): PurchaseReturnEntity?

    @Query("SELECT * FROM purchase_returns WHERE returnNumber = :returnNumber LIMIT 1")
    abstract suspend fun getReturnByNumber(returnNumber: String): PurchaseReturnEntity?

    @Query("SELECT * FROM purchase_returns WHERE purchaseId = :purchaseId ORDER BY returnDate DESC")
    abstract fun getReturnsForPurchase(purchaseId: Long): Flow<List<PurchaseReturnEntity>>

    @Query("SELECT * FROM purchase_returns WHERE purchaseId = :purchaseId AND status != 'CANCELLED'")
    abstract suspend fun getReturnsForPurchaseSync(purchaseId: Long): List<PurchaseReturnEntity>

    @Query("SELECT * FROM purchase_returns WHERE supplierId = :supplierId ORDER BY returnDate DESC")
    abstract fun getReturnsBySupplier(supplierId: Long): Flow<List<PurchaseReturnEntity>>

    @Query("SELECT * FROM purchase_returns WHERE supplierId = :supplierId AND status != 'CANCELLED' ORDER BY returnDate DESC")
    abstract suspend fun getReturnsBySupplierSync(supplierId: Long): List<PurchaseReturnEntity>

    @Query("SELECT * FROM purchase_return_items WHERE returnId = :returnId")
    abstract suspend fun getReturnItems(returnId: Long): List<PurchaseReturnItemEntity>

    @Query("SELECT * FROM purchase_return_items WHERE returnId = :returnId")
    abstract fun observeReturnItems(returnId: Long): Flow<List<PurchaseReturnItemEntity>>

    @Query("SELECT SUM(quantity) FROM purchase_return_items pri INNER JOIN purchase_returns pr ON pri.returnId = pr.id WHERE pri.purchaseItemId = :purchaseItemId AND pr.status != 'CANCELLED'")
    abstract suspend fun getTotalReturnedQuantityForPurchaseItem(purchaseItemId: Long): Int?

    @Query("SELECT COUNT(*) FROM purchase_returns")
    abstract suspend fun getReturnCount(): Int

    @Query("SELECT MAX(id) FROM purchase_returns")
    abstract suspend fun getMaxReturnId(): Long?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertReturnRaw(purchaseReturn: PurchaseReturnEntity): Long

    suspend fun insertReturn(purchaseReturn: PurchaseReturnEntity): Long =
        insertReturnRaw(if (purchaseReturn.id == 0L) purchaseReturn.copy(id = SyncIds.next(db, "purchase_returns")) else purchaseReturn)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertReturnItemsRaw(items: List<PurchaseReturnItemEntity>)

    suspend fun insertReturnItems(items: List<PurchaseReturnItemEntity>) =
        insertReturnItemsRaw(SyncIds.assign(db, "purchase_return_items", items, { it.id }) { row, id -> row.copy(id = id) })

    @Update
    abstract suspend fun updateReturn(purchaseReturn: PurchaseReturnEntity)

    @Query("UPDATE purchase_returns SET status = :status WHERE id = :id")
    abstract suspend fun updateReturnStatus(id: Long, status: String)
}
