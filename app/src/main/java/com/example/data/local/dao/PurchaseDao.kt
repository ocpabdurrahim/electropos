package com.example.data.local.dao

import androidx.room.*
import com.example.sync.SyncIds
import com.example.data.local.entities.PurchaseEntity
import com.example.data.local.entities.PurchaseItemEntity
import kotlinx.coroutines.flow.Flow

@Dao
abstract class PurchaseDao(private val db: RoomDatabase) {
    @Query("SELECT * FROM purchases ORDER BY purchaseDate DESC")
    abstract fun getAllPurchases(): Flow<List<PurchaseEntity>>

    @Query("SELECT * FROM purchases ORDER BY purchaseDate DESC")
    abstract suspend fun getAllPurchasesSync(): List<PurchaseEntity>

    @Query("SELECT * FROM purchases WHERE id = :id LIMIT 1")
    abstract suspend fun getPurchaseById(id: Long): PurchaseEntity?

    @Query("SELECT * FROM purchases WHERE id = :id LIMIT 1")
    abstract fun observePurchaseById(id: Long): Flow<PurchaseEntity?>

    @Query("SELECT * FROM purchases WHERE purchaseNumber = :purchaseNumber LIMIT 1")
    abstract suspend fun getPurchaseByPurchaseNumber(purchaseNumber: String): PurchaseEntity?

    @Query("SELECT * FROM purchases WHERE invoiceNumber = :invoiceNumber LIMIT 1")
    abstract suspend fun getPurchaseByInvoiceNumber(invoiceNumber: String): PurchaseEntity?

    @Query("SELECT * FROM purchases WHERE supplierId = :supplierId ORDER BY purchaseDate DESC")
    abstract fun getPurchasesBySupplier(supplierId: Long): Flow<List<PurchaseEntity>>

    @Query("SELECT * FROM purchases WHERE supplierId = :supplierId AND status != 'CANCELLED' ORDER BY purchaseDate DESC")
    abstract suspend fun getPurchasesBySupplierSync(supplierId: Long): List<PurchaseEntity>

    @Query("""
        SELECT * FROM purchases 
        WHERE (purchaseNumber LIKE '%' || :query || '%'
           OR invoiceNumber LIKE '%' || :query || '%'
           OR supplierName LIKE '%' || :query || '%'
           OR supplierInvoiceNumber LIKE '%' || :query || '%'
           OR referenceNumber LIKE '%' || :query || '%')
        ORDER BY purchaseDate DESC
    """)
    abstract fun searchPurchases(query: String): Flow<List<PurchaseEntity>>

    @Query("SELECT * FROM purchase_items WHERE purchaseId = :purchaseId")
    abstract suspend fun getPurchaseItems(purchaseId: Long): List<PurchaseItemEntity>

    @Query("SELECT * FROM purchase_items WHERE purchaseId = :purchaseId")
    abstract fun observePurchaseItems(purchaseId: Long): Flow<List<PurchaseItemEntity>>

    @Query("SELECT * FROM purchases WHERE purchaseDate BETWEEN :startDate AND :endDate ORDER BY purchaseDate DESC")
    abstract fun getPurchasesBetween(startDate: Long, endDate: Long): Flow<List<PurchaseEntity>>

    @Query("SELECT SUM(totalAmount) FROM purchases WHERE purchaseDate BETWEEN :startDate AND :endDate AND status != 'CANCELLED'")
    abstract fun getTotalPurchaseAmountBetween(startDate: Long, endDate: Long): Flow<Double?>

    @Query("SELECT COUNT(*) FROM purchases")
    abstract suspend fun getPurchaseCount(): Int

    @Query("SELECT MAX(id) FROM purchases")
    abstract suspend fun getMaxPurchaseId(): Long?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertPurchaseRaw(purchase: PurchaseEntity): Long

    suspend fun insertPurchase(purchase: PurchaseEntity): Long =
        insertPurchaseRaw(if (purchase.id == 0L) purchase.copy(id = SyncIds.next(db, "purchases")) else purchase)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertPurchaseItemsRaw(items: List<PurchaseItemEntity>): List<Long>

    suspend fun insertPurchaseItems(items: List<PurchaseItemEntity>): List<Long> =
        insertPurchaseItemsRaw(SyncIds.assign(db, "purchase_items", items, { it.id }) { row, id -> row.copy(id = id) })

    @Update
    abstract suspend fun updatePurchase(purchase: PurchaseEntity)

    @Query("UPDATE purchases SET status = :status, updatedAt = :timestamp WHERE id = :id")
    abstract suspend fun updatePurchaseStatus(id: Long, status: String, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE purchases SET paidAmount = MIN(totalAmount, paidAmount + :amountPaid), dueAmount = MAX(0, dueAmount - :amountPaid), paymentStatus = :newStatus, updatedAt = :timestamp WHERE id = :id")
    abstract suspend fun applyPaymentToPurchase(id: Long, amountPaid: Double, newStatus: String, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE purchases SET paidAmount = :paid, dueAmount = :due, paymentStatus = :status, updatedAt = :timestamp WHERE id = :id")
    abstract suspend fun setPurchasePaymentState(id: Long, paid: Double, due: Double, status: String, timestamp: Long = System.currentTimeMillis())
}
