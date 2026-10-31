package com.example.data.local.dao

import androidx.room.*
import com.example.sync.SyncIds
import com.example.data.local.entities.ProductEntity
import com.example.data.local.entities.ProductSerialImeiEntity
import kotlinx.coroutines.flow.Flow

@Dao
abstract class ProductDao(private val db: RoomDatabase) {
    @Query("SELECT * FROM products WHERE isActive = 1 ORDER BY name ASC")
    abstract fun getAllActiveProducts(): Flow<List<ProductEntity>>

    @Query("SELECT * FROM products ORDER BY name ASC")
    abstract suspend fun getAllProductsSync(): List<ProductEntity>

    @Query("SELECT * FROM products WHERE id = :id LIMIT 1")
    abstract suspend fun getProductById(id: Long): ProductEntity?

    @Query("SELECT * FROM products WHERE id = :id LIMIT 1")
    abstract fun observeProductById(id: Long): Flow<ProductEntity?>

    @Query("SELECT * FROM products WHERE barcode = :barcode AND isActive = 1 LIMIT 1")
    abstract suspend fun getProductByBarcode(barcode: String): ProductEntity?

    @Query("SELECT * FROM products WHERE sku = :sku AND isActive = 1 LIMIT 1")
    abstract suspend fun getProductBySku(sku: String): ProductEntity?

    @Query("""
        SELECT * FROM products 
        WHERE isActive = 1 AND (
            name LIKE '%' || :query || '%' OR
            sku LIKE '%' || :query || '%' OR
            barcode LIKE '%' || :query || '%' OR
            model LIKE '%' || :query || '%' OR
            brandName LIKE '%' || :query || '%' OR
            categoryName LIKE '%' || :query || '%'
        )
        ORDER BY name ASC
    """)
    abstract fun searchProducts(query: String): Flow<List<ProductEntity>>

    @Query("SELECT * FROM products WHERE isActive = 1 AND currentStock <= minStock AND currentStock > 0 ORDER BY currentStock ASC")
    abstract fun getLowStockProducts(): Flow<List<ProductEntity>>

    @Query("SELECT * FROM products WHERE isActive = 1 AND currentStock <= 0 ORDER BY name ASC")
    abstract fun getOutOfStockProducts(): Flow<List<ProductEntity>>

    @Query("SELECT * FROM products WHERE categoryId = :categoryId AND isActive = 1")
    abstract fun getProductsByCategory(categoryId: Long): Flow<List<ProductEntity>>

    @Query("SELECT * FROM products WHERE brandId = :brandId AND isActive = 1")
    abstract fun getProductsByBrand(brandId: Long): Flow<List<ProductEntity>>

    @Query("SELECT SUM(currentStock * purchasePrice) FROM products WHERE isActive = 1")
    abstract fun getTotalStockValue(): Flow<Double?>

    @Query("SELECT COUNT(*) FROM products WHERE isActive = 1")
    abstract fun getTotalProductCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM products WHERE isActive = 1 AND currentStock <= minStock AND currentStock > 0")
    abstract fun getLowStockCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM products WHERE isActive = 1 AND currentStock <= 0")
    abstract fun getOutOfStockCount(): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertProductRaw(product: ProductEntity): Long

    suspend fun insertProduct(product: ProductEntity): Long =
        insertProductRaw(if (product.id == 0L) product.copy(id = SyncIds.next(db, "products")) else product)

    @Update
    abstract suspend fun updateProduct(product: ProductEntity)

    @Query("UPDATE products SET currentStock = currentStock + :quantityChange, updatedAt = :timestamp WHERE id = :productId")
    abstract suspend fun updateStockQuantity(productId: Long, quantityChange: Int, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE products SET currentStock = :exactStock, updatedAt = :timestamp WHERE id = :productId")
    abstract suspend fun setStockQuantity(productId: Long, exactStock: Int, timestamp: Long = System.currentTimeMillis())

    @Query("SELECT COUNT(*) FROM product_serial_imei WHERE productId = :productId AND status = 'IN_STOCK'")
    abstract suspend fun countAvailableSerialsForProductSync(productId: Long): Int

    @Query("SELECT COUNT(*) FROM product_serial_imei WHERE productId = :productId AND variantId = :variantId AND status = 'IN_STOCK'")
    abstract suspend fun countAvailableSerialsForVariantSync(productId: Long, variantId: Long): Int

    @Query("SELECT * FROM products WHERE currentStock < 0")
    abstract suspend fun getNegativeStockProducts(): List<ProductEntity>

    @Query("UPDATE products SET purchasePrice = :newWeightedCost, updatedAt = :timestamp WHERE id = :productId")
    abstract suspend fun updatePurchaseCost(productId: Long, newWeightedCost: Double, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE products SET isActive = 0 WHERE id = :productId")
    abstract suspend fun deactivateProduct(productId: Long)

    @Query("SELECT COUNT(*) FROM products WHERE sku = :sku AND id != :excludeId AND isActive = 1")
    abstract suspend fun countSkuOccurrences(sku: String, excludeId: Long): Int

    @Query("SELECT COUNT(*) FROM products WHERE barcode = :barcode AND id != :excludeId AND isActive = 1")
    abstract suspend fun countBarcodeOccurrences(barcode: String, excludeId: Long): Int

    @Query("SELECT COUNT(*) FROM stock_transactions WHERE productId = :productId")
    abstract suspend fun countStockTransactions(productId: Long): Int

    @Query("SELECT COUNT(*) FROM sale_items WHERE productId = :productId")
    abstract suspend fun countSaleItems(productId: Long): Int

    @Query("SELECT COUNT(*) FROM purchase_items WHERE productId = :productId")
    abstract suspend fun countPurchaseItems(productId: Long): Int

    @Delete
    abstract suspend fun deleteProduct(product: ProductEntity)

    // Serial & IMEI Tracking
    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertSerialImeiRaw(item: ProductSerialImeiEntity): Long

    suspend fun insertSerialImei(item: ProductSerialImeiEntity): Long =
        insertSerialImeiRaw(if (item.id == 0L) item.copy(id = SyncIds.next(db, "product_serial_imei")) else item)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertSerialImeisRaw(items: List<ProductSerialImeiEntity>): List<Long>

    suspend fun insertSerialImeis(items: List<ProductSerialImeiEntity>): List<Long> =
        insertSerialImeisRaw(SyncIds.assign(db, "product_serial_imei", items, { it.id }) { row, id -> row.copy(id = id) })

    @Update
    abstract suspend fun updateSerialImei(item: ProductSerialImeiEntity)

    @Query("SELECT * FROM product_serial_imei WHERE productId = :productId AND status = 'IN_STOCK' ORDER BY id ASC")
    abstract fun getAvailableSerialsForProduct(productId: Long): Flow<List<ProductSerialImeiEntity>>

    @Query("SELECT * FROM product_serial_imei WHERE productId = :productId AND status = 'IN_STOCK' ORDER BY id ASC")
    abstract suspend fun getAvailableSerialsForProductSync(productId: Long): List<ProductSerialImeiEntity>

    @Query("SELECT * FROM product_serial_imei WHERE productId = :productId ORDER BY id DESC")
    abstract fun getAllSerialsForProduct(productId: Long): Flow<List<ProductSerialImeiEntity>>

    @Query("SELECT COUNT(*) FROM product_serial_imei WHERE productId = :productId")
    abstract suspend fun countSerialsForProduct(productId: Long): Int

    @Query("SELECT * FROM product_serial_imei WHERE id = :id LIMIT 1")
    abstract suspend fun getSerialById(id: Long): ProductSerialImeiEntity?

    @Query("SELECT * FROM product_serial_imei WHERE (imei1 = :imei OR imei2 = :imei) AND :imei IS NOT NULL AND :imei != '' LIMIT 1")
    abstract suspend fun getSerialByImei(imei: String): ProductSerialImeiEntity?

    @Query("SELECT * FROM product_serial_imei WHERE serialNumber = :serial AND :serial IS NOT NULL AND :serial != '' LIMIT 1")
    abstract suspend fun getBySerialNumber(serial: String): ProductSerialImeiEntity?

    @Query("SELECT * FROM product_serial_imei WHERE purchaseId = :purchaseId ORDER BY id ASC")
    abstract suspend fun getSerialsForPurchase(purchaseId: Long): List<ProductSerialImeiEntity>

    @Query("SELECT * FROM product_serial_imei WHERE purchaseItemId = :purchaseItemId ORDER BY id ASC")
    abstract suspend fun getSerialsForPurchaseItem(purchaseItemId: Long): List<ProductSerialImeiEntity>

    @Query("SELECT COUNT(*) FROM product_serial_imei WHERE (imei1 = :imei OR imei2 = :imei) AND :imei IS NOT NULL AND :imei != '' AND (:excludeId = 0 OR id != :excludeId)")
    abstract suspend fun countImeiOccurrences(imei: String, excludeId: Long = 0L): Int

    @Query("SELECT COUNT(*) FROM product_serial_imei WHERE serialNumber = :serial AND :serial IS NOT NULL AND :serial != '' AND (:excludeId = 0 OR id != :excludeId)")
    abstract suspend fun countSerialOccurrences(serial: String, excludeId: Long = 0L): Int

    @Query("""
        SELECT * FROM product_serial_imei 
        WHERE (imei1 LIKE '%' || :query || '%' 
           OR imei2 LIKE '%' || :query || '%' 
           OR serialNumber LIKE '%' || :query || '%')
        ORDER BY id DESC
    """)
    abstract fun searchSerialImeis(query: String): Flow<List<ProductSerialImeiEntity>>

    @Query("UPDATE product_serial_imei SET status = :status, saleId = :saleId, invoiceNumber = :invoiceNumber, updatedAt = :timestamp WHERE id = :id AND status = 'IN_STOCK'")
    abstract suspend fun markSerialSold(id: Long, status: String = "SOLD", saleId: Long, invoiceNumber: String, timestamp: Long = System.currentTimeMillis()): Int

    @Query("SELECT * FROM product_serial_imei WHERE saleId = :saleId ORDER BY id ASC")
    abstract suspend fun getSerialsForSale(saleId: Long): List<ProductSerialImeiEntity>

    @Query("UPDATE product_serial_imei SET status = :status, saleId = 0, invoiceNumber = '', updatedAt = :timestamp WHERE id = :id AND saleId = :saleId")
    abstract suspend fun restoreSerialFromSale(id: Long, saleId: Long, status: String = "IN_STOCK", timestamp: Long = System.currentTimeMillis()): Int

    @Query("UPDATE product_serial_imei SET status = :status, notes = :notes, updatedAt = :timestamp WHERE id = :id AND saleId = :saleId")
    abstract suspend fun markSerialReturnedFromSale(id: Long, saleId: Long, status: String, notes: String = "", timestamp: Long = System.currentTimeMillis()): Int
}
