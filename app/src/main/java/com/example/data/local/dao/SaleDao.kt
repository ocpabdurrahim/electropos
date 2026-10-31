package com.example.data.local.dao

import androidx.room.*
import com.example.sync.SyncIds
import com.example.data.local.entities.SaleEntity
import com.example.data.local.entities.SaleItemEntity
import kotlinx.coroutines.flow.Flow

@Dao
abstract class SaleDao(private val db: RoomDatabase) {
    @Query("SELECT * FROM sales ORDER BY saleDate DESC")
    abstract fun getAllSales(): Flow<List<SaleEntity>>

    @Query("SELECT * FROM sales ORDER BY saleDate DESC")
    abstract suspend fun getAllSalesSync(): List<SaleEntity>

    @Query("SELECT * FROM sales WHERE id = :id LIMIT 1")
    abstract suspend fun getSaleById(id: Long): SaleEntity?

    @Query("SELECT * FROM sales WHERE invoiceNumber = :invoiceNumber LIMIT 1")
    abstract suspend fun getSaleByInvoiceNumber(invoiceNumber: String): SaleEntity?

    @Query("SELECT * FROM sales WHERE customerId = :customerId ORDER BY saleDate DESC")
    abstract fun getSalesByCustomer(customerId: Long): Flow<List<SaleEntity>>

    @Query("SELECT * FROM sales WHERE customerId = :customerId AND status NOT IN ('CANCELLED', 'VOID') ORDER BY saleDate ASC, id ASC")
    abstract suspend fun getActiveSalesForCustomerSync(customerId: Long): List<SaleEntity>

    @Query("UPDATE sales SET paidAmount = :paid, dueAmount = :due, paymentStatus = :status, updatedAt = :timestamp WHERE id = :id")
    abstract suspend fun setSalePaymentState(id: Long, paid: Double, due: Double, status: String, timestamp: Long = System.currentTimeMillis())

    @Query("SELECT * FROM sale_items WHERE saleId = :saleId")
    abstract suspend fun getSaleItems(saleId: Long): List<SaleItemEntity>

    @Query("SELECT * FROM sale_items WHERE saleId = :saleId")
    abstract fun observeSaleItems(saleId: Long): Flow<List<SaleItemEntity>>

    @Query("SELECT * FROM sales WHERE saleDate BETWEEN :startDate AND :endDate AND status NOT IN ('CANCELLED', 'VOID') ORDER BY saleDate DESC")
    abstract fun getSalesBetween(startDate: Long, endDate: Long): Flow<List<SaleEntity>>

    @Query("SELECT SUM(totalAmount) FROM sales WHERE saleDate BETWEEN :startDate AND :endDate AND status NOT IN ('CANCELLED', 'VOID')")
    abstract fun getTotalSalesAmountBetween(startDate: Long, endDate: Long): Flow<Double?>

    @Query("SELECT SUM(totalProfit) FROM sales WHERE saleDate BETWEEN :startDate AND :endDate AND status NOT IN ('CANCELLED', 'VOID')")
    abstract fun getTotalProfitBetween(startDate: Long, endDate: Long): Flow<Double?>

    @Query("SELECT SUM(dueAmount) FROM sales WHERE status NOT IN ('CANCELLED', 'VOID')")
    abstract fun getTotalSalesDue(): Flow<Double?>

    @Query("SELECT COUNT(*) FROM sales WHERE saleDate BETWEEN :startDate AND :endDate AND status NOT IN ('CANCELLED', 'VOID')")
    abstract fun getSalesCountBetween(startDate: Long, endDate: Long): Flow<Int>

    @Query("""
        SELECT * FROM sales 
        WHERE invoiceNumber LIKE '%' || :query || '%' 
           OR customerName LIKE '%' || :query || '%' 
           OR customerPhone LIKE '%' || :query || '%'
        ORDER BY saleDate DESC
    """)
    abstract fun searchSales(query: String): Flow<List<SaleEntity>>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertSaleRaw(sale: SaleEntity): Long

    suspend fun insertSale(sale: SaleEntity): Long =
        insertSaleRaw(if (sale.id == 0L) sale.copy(id = SyncIds.next(db, "sales")) else sale)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertSaleItemsRaw(items: List<SaleItemEntity>): List<Long>

    suspend fun insertSaleItems(items: List<SaleItemEntity>): List<Long> =
        insertSaleItemsRaw(SyncIds.assign(db, "sale_items", items, { it.id }) { row, id -> row.copy(id = id) })

    @Update
    abstract suspend fun updateSale(sale: SaleEntity)

    @Query("UPDATE sales SET status = :status, updatedAt = :timestamp WHERE id = :id")
    abstract suspend fun updateSaleStatus(id: Long, status: String, timestamp: Long = System.currentTimeMillis())

    @Query("SELECT * FROM sale_items WHERE id = :id LIMIT 1")
    abstract suspend fun getSaleItemById(id: Long): SaleItemEntity?

    @Query("SELECT COUNT(*) FROM sales WHERE invoiceNumber = :invoiceNumber")
    abstract suspend fun countInvoicesMatching(invoiceNumber: String): Int

    @Query("SELECT COUNT(*) FROM sales WHERE customerId = :customerId AND status NOT IN ('CANCELLED', 'VOID')")
    abstract suspend fun countSalesForCustomer(customerId: Long): Int

    @Query("""
        SELECT si.productId AS productId, si.productName AS productName, SUM(si.quantity) as totalQty, SUM(si.totalPrice) as totalSales, SUM(si.profit) as totalProf
        FROM sale_items si
        INNER JOIN sales s ON si.saleId = s.id
        WHERE s.status NOT IN ('CANCELLED', 'VOID')
        GROUP BY si.productId
        ORDER BY totalQty DESC
        LIMIT :limit
    """)
    abstract fun getTopSellingProducts(limit: Int = 5): Flow<List<TopProductSummary>>
}

data class TopProductSummary(
    val productId: Long,
    val productName: String,
    val totalQty: Int,
    val totalSales: Double,
    val totalProf: Double
)
