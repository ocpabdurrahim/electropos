package com.example.data.local.dao

import androidx.room.*
import com.example.sync.SyncIds
import com.example.data.local.entities.SalesReturnEntity
import com.example.data.local.entities.SalesReturnItemEntity
import kotlinx.coroutines.flow.Flow

@Dao
abstract class SalesReturnDao(private val db: RoomDatabase) {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertReturnRaw(returnEntity: SalesReturnEntity): Long

    suspend fun insertReturn(returnEntity: SalesReturnEntity): Long =
        insertReturnRaw(if (returnEntity.id == 0L) returnEntity.copy(id = SyncIds.next(db, "sales_returns")) else returnEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertReturnItemsRaw(items: List<SalesReturnItemEntity>): List<Long>

    suspend fun insertReturnItems(items: List<SalesReturnItemEntity>): List<Long> =
        insertReturnItemsRaw(SyncIds.assign(db, "sales_return_items", items, { it.id }) { row, id -> row.copy(id = id) })

    @Query("SELECT * FROM sales_returns ORDER BY returnDate DESC")
    abstract fun getAllReturns(): Flow<List<SalesReturnEntity>>

    @Query("SELECT * FROM sales_returns WHERE id = :id LIMIT 1")
    abstract suspend fun getReturnById(id: Long): SalesReturnEntity?

    @Query("SELECT * FROM sales_returns WHERE returnNumber = :returnNumber LIMIT 1")
    abstract suspend fun getReturnByNumber(returnNumber: String): SalesReturnEntity?

    @Query("SELECT * FROM sales_returns WHERE saleId = :saleId ORDER BY returnDate DESC")
    abstract fun getReturnsForSale(saleId: Long): Flow<List<SalesReturnEntity>>

    @Query("SELECT * FROM sales_returns WHERE saleId = :saleId AND status = 'COMPLETED' ORDER BY returnDate DESC")
    abstract suspend fun getReturnsForSaleSync(saleId: Long): List<SalesReturnEntity>

    @Query("SELECT * FROM sales_return_items WHERE salesReturnId = :salesReturnId")
    abstract fun getReturnItems(salesReturnId: Long): Flow<List<SalesReturnItemEntity>>

    @Query("SELECT * FROM sales_return_items WHERE salesReturnId = :salesReturnId")
    abstract suspend fun getReturnItemsSync(salesReturnId: Long): List<SalesReturnItemEntity>

    @Query("""
        SELECT COALESCE(SUM(sri.quantity), 0) 
        FROM sales_return_items sri 
        INNER JOIN sales_returns sr ON sri.salesReturnId = sr.id 
        WHERE sri.saleItemId = :saleItemId AND sr.status = 'COMPLETED'
    """)
    abstract suspend fun getReturnedQuantityForSaleItem(saleItemId: Long): Int

    @Query("""
        SELECT COUNT(*) 
        FROM sales_return_items sri 
        INNER JOIN sales_returns sr ON sri.salesReturnId = sr.id 
        WHERE sri.serialUnitId = :serialUnitId AND sr.status = 'COMPLETED' AND sri.serialUnitId > 0
    """)
    abstract suspend fun countSerialReturned(serialUnitId: Long): Int

    /** Returns of this unit from ONE sale: a phone resold to someone else can be returned again by the new buyer. */
    @Query("""
        SELECT COUNT(*)
        FROM sales_return_items sri
        INNER JOIN sales_returns sr ON sri.salesReturnId = sr.id
        WHERE sri.serialUnitId = :serialUnitId AND sr.saleId = :saleId AND sr.status = 'COMPLETED' AND sri.serialUnitId > 0
    """)
    abstract suspend fun countSerialReturnedForSale(serialUnitId: Long, saleId: Long): Int

    @Query("SELECT * FROM sales_returns WHERE customerId = :customerId AND status = 'COMPLETED'")
    abstract suspend fun getCompletedReturnsForCustomerSync(customerId: Long): List<SalesReturnEntity>

    @Query("SELECT COALESCE(SUM(totalAmount), 0.0) FROM sales_returns WHERE returnDate BETWEEN :startDate AND :endDate AND status = 'COMPLETED'")
    abstract fun getTotalReturnsBetween(startDate: Long, endDate: Long): Flow<Double>

    /** Profit given back by returns: returned value excl. tax, minus the cost of units that went back on the shelf. */
    @Query("""
        SELECT
            (SELECT COALESCE(SUM(sri.totalPrice - CASE WHEN sri.restockOption = 'RESTOCK_SELLABLE' THEN sri.unitCostAtSale * sri.quantity ELSE 0 END), 0.0)
             FROM sales_return_items sri
             INNER JOIN sales_returns sr ON sri.salesReturnId = sr.id
             WHERE sr.returnDate BETWEEN :startDate AND :endDate AND sr.status = 'COMPLETED')
          - (SELECT COALESCE(SUM(taxAmount), 0.0) FROM sales_returns
             WHERE returnDate BETWEEN :startDate AND :endDate AND status = 'COMPLETED')
    """)
    abstract fun getReturnedProfitBetween(startDate: Long, endDate: Long): Flow<Double>

    @Query("SELECT COUNT(*) FROM sales_returns WHERE returnNumber = :returnNumber")
    abstract suspend fun countReturnNumberMatches(returnNumber: String): Int
}
