package com.example.data.local.dao

import androidx.room.*
import com.example.sync.SyncIds
import com.example.data.local.entities.ProductPriceHistoryEntity
import com.example.data.local.entities.ProductVariantEntity
import kotlinx.coroutines.flow.Flow

@Dao
abstract class ProductVariantDao(private val db: RoomDatabase) {
    @Query("SELECT * FROM product_variants WHERE productId = :productId AND isActive = 1 ORDER BY id ASC")
    abstract fun getVariantsForProduct(productId: Long): Flow<List<ProductVariantEntity>>

    @Query("SELECT * FROM product_variants WHERE productId = :productId AND isActive = 1 ORDER BY id ASC")
    abstract suspend fun getVariantsForProductSync(productId: Long): List<ProductVariantEntity>

    @Query("SELECT * FROM product_variants WHERE id = :id LIMIT 1")
    abstract suspend fun getVariantById(id: Long): ProductVariantEntity?

    @Query("SELECT * FROM product_variants WHERE sku = :sku LIMIT 1")
    abstract suspend fun getVariantBySku(sku: String): ProductVariantEntity?

    @Query("SELECT * FROM product_variants WHERE barcode = :barcode AND barcode != '' LIMIT 1")
    abstract suspend fun getVariantByBarcode(barcode: String): ProductVariantEntity?

    @Query("SELECT COUNT(*) FROM product_variants WHERE barcode = :barcode AND barcode != '' AND id != :excludeId")
    abstract suspend fun countVariantBarcode(barcode: String, excludeId: Long): Int

    /** Every active variant, for screens that pick a variant per product (POS, purchase entry). */
    @Query("SELECT * FROM product_variants WHERE isActive = 1 ORDER BY productId, id")
    abstract fun getAllActiveVariants(): Flow<List<ProductVariantEntity>>

    @Query("SELECT COUNT(*) FROM product_variants WHERE sku = :sku AND id != :excludeId")
    abstract suspend fun checkVariantSkuExists(sku: String, excludeId: Long): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertVariantRaw(variant: ProductVariantEntity): Long

    suspend fun insertVariant(variant: ProductVariantEntity): Long =
        insertVariantRaw(if (variant.id == 0L) variant.copy(id = SyncIds.next(db, "product_variants")) else variant)

    @Update
    abstract suspend fun updateVariant(variant: ProductVariantEntity)

    @Query("UPDATE product_variants SET currentStock = currentStock + :delta, updatedAt = :timestamp WHERE id = :id")
    abstract suspend fun updateVariantStock(id: Long, delta: Int, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE product_variants SET currentStock = :exactStock, updatedAt = :timestamp WHERE id = :id")
    abstract suspend fun setVariantStock(id: Long, exactStock: Int, timestamp: Long = System.currentTimeMillis())

    @Query("SELECT * FROM product_variants WHERE currentStock < 0")
    abstract suspend fun getNegativeStockVariants(): List<ProductVariantEntity>

    @Query("UPDATE product_variants SET isActive = 0, updatedAt = :timestamp WHERE id = :id")
    abstract suspend fun deactivateVariant(id: Long, timestamp: Long = System.currentTimeMillis())

    @Delete
    abstract suspend fun deleteVariant(variant: ProductVariantEntity)
}

@Dao
abstract class ProductPriceHistoryDao(private val db: RoomDatabase) {
    @Query("SELECT * FROM product_price_history WHERE productId = :productId ORDER BY changedAt DESC")
    abstract fun getPriceHistory(productId: Long): Flow<List<ProductPriceHistoryEntity>>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertPriceHistoryRaw(entry: ProductPriceHistoryEntity): Long

    suspend fun insertPriceHistory(entry: ProductPriceHistoryEntity): Long =
        insertPriceHistoryRaw(if (entry.id == 0L) entry.copy(id = SyncIds.next(db, "product_price_history")) else entry)
}
