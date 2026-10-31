package com.example.data.repository

import com.example.data.local.dao.*
import com.example.data.local.entities.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class ProductRepository(
    private val productDao: ProductDao,
    private val stockDao: StockDao,
    private val variantDao: ProductVariantDao,
    private val priceHistoryDao: ProductPriceHistoryDao,
    private val auditDao: SettingsAndAuditDao
) {
    constructor(database: com.example.data.local.database.AppDatabase) : this(
        database.productDao(),
        database.stockDao(),
        database.productVariantDao(),
        database.productPriceHistoryDao(),
        database.settingsAndAuditDao()
    )

    val allProducts: Flow<List<ProductEntity>> = productDao.getAllActiveProducts()
    val allActiveProducts: Flow<List<ProductEntity>> = allProducts
    val activeProducts: Flow<List<ProductEntity>> = allProducts
    val lowStockProducts: Flow<List<ProductEntity>> = productDao.getLowStockProducts()
    val outOfStockProducts: Flow<List<ProductEntity>> = productDao.getOutOfStockProducts()
    val totalStockValue: Flow<Double?> = productDao.getTotalStockValue()
    val totalProductCount: Flow<Int> = productDao.getTotalProductCount()

    suspend fun getAvailableSerialsForProductSync(productId: Long) = productDao.getAvailableSerialsForProductSync(productId)

    suspend fun getProductById(id: Long) = productDao.getProductById(id)
    fun observeProductById(id: Long) = productDao.observeProductById(id)
    suspend fun getProductByBarcode(barcode: String) = productDao.getProductByBarcode(barcode)
    suspend fun getProductBySku(sku: String) = productDao.getProductBySku(sku)

    /**
     * Resolves a scanned or typed code: product barcode, product SKU, variant SKU, or the IMEI / serial
     * number of one physical unit (then [ProductCodeMatch.unit] is that unit).
     */
    suspend fun findByCode(rawCode: String): ProductCodeMatch? {
        val code = rawCode.trim()
        if (code.isEmpty()) return null
        productDao.getProductByBarcode(code)?.let { return ProductCodeMatch(it) }
        productDao.getProductBySku(code)?.let { return ProductCodeMatch(it) }
        (variantDao.getVariantByBarcode(code) ?: variantDao.getVariantBySku(code))?.takeIf { it.isActive }?.let { variant ->
            productDao.getProductById(variant.productId)?.takeIf { it.isActive }?.let { return ProductCodeMatch(it, variant = variant) }
        }
        val unit = productDao.getSerialByImei(code) ?: productDao.getBySerialNumber(code)
        if (unit != null) {
            productDao.getProductById(unit.productId)?.let { product ->
                val variant = if (unit.variantId > 0) variantDao.getVariantById(unit.variantId) else null
                return ProductCodeMatch(product, variant = variant, unit = unit)
            }
        }
        return null
    }
    fun searchProducts(query: String) = productDao.searchProducts(query)

    suspend fun checkSkuExists(sku: String, excludeId: Long = 0L): Boolean {
        if (sku.isBlank()) return false
        val inProducts = productDao.countSkuOccurrences(sku.trim(), excludeId)
        val inVariants = variantDao.checkVariantSkuExists(sku.trim(), 0L) // excludeId is a product id, not a variant id
        return inProducts > 0 || inVariants > 0
    }

    suspend fun checkBarcodeExists(barcode: String, excludeId: Long = 0L): Boolean {
        if (barcode.isBlank()) return false
        return productDao.countBarcodeOccurrences(barcode.trim(), excludeId) > 0 ||
            variantDao.countVariantBarcode(barcode.trim(), 0L) > 0
    }

    suspend fun saveProduct(product: ProductEntity, operatorName: String = "Admin"): Result<Long> = runCatching {
        if (product.name.isBlank()) {
            throw IllegalArgumentException("Product name cannot be empty.")
        }
        if (product.retailPrice < 0 || product.purchasePrice < 0) {
            throw IllegalArgumentException("Prices cannot be negative.")
        }
        if (product.minStock < 0 || product.currentStock < 0) {
            throw IllegalArgumentException("Stock cannot be negative.")
        }
        if (product.maxStock < product.minStock) {
            throw IllegalArgumentException("Maximum stock cannot be less than minimum stock.")
        }
        if (product.sku.isNotBlank() && checkSkuExists(product.sku, product.id)) {
            throw IllegalArgumentException("SKU '${product.sku}' already exists.")
        }
        if (product.barcode.isNotBlank() && checkBarcodeExists(product.barcode, product.id)) {
            throw IllegalArgumentException("Barcode '${product.barcode}' already exists.")
        }

        val serialized = TrackingType.requiresSerial(product.trackingType) || TrackingType.requiresImei(product.trackingType)
        if (product.id == 0L) {
            if (serialized && product.currentStock > 0) {
                throw IllegalArgumentException("For serial/IMEI products leave opening stock at 0 and add the units through a purchase, so each unit's IMEI/serial is recorded.")
            }
            val generatedId = productDao.insertProduct(product.copy(openingStock = product.currentStock))

            // Record initial price history
            priceHistoryDao.insertPriceHistory(
                ProductPriceHistoryEntity(
                    productId = generatedId,
                    purchasePrice = product.purchasePrice,
                    wholesalePrice = product.wholesalePrice,
                    retailPrice = product.retailPrice,
                    minSellingPrice = product.minSellingPrice,
                    changedBy = operatorName,
                    note = "Initial product price"
                )
            )

            // Record opening stock transaction if stock > 0
            if (product.currentStock > 0) {
                stockDao.insertStockTransaction(
                    StockTransactionEntity(
                        productId = generatedId,
                        productName = product.name,
                        transactionType = "OPENING_STOCK",
                        quantityChange = product.currentStock,
                        previousStock = 0,
                        newStock = product.currentStock,
                        unitCost = product.purchasePrice,
                        referenceId = generatedId,
                        referenceType = "PRODUCT",
                        notes = "Initial opening stock"
                    )
                )
            }

            auditDao.insertAuditLog(
                AuditLogEntity(
                    userRole = "ADMIN",
                    action = "PRODUCT_CREATED",
                    module = "PRODUCTS",
                    recordId = generatedId.toString(),
                    description = "Created product '${product.name}' (SKU: ${product.sku}) by $operatorName"
                )
            )

            generatedId
        } else {
            val existing = productDao.getProductById(product.id)
            if (existing != null && (
                existing.purchasePrice != product.purchasePrice ||
                existing.retailPrice != product.retailPrice ||
                existing.wholesalePrice != product.wholesalePrice ||
                existing.minSellingPrice != product.minSellingPrice
            )) {
                // Record price history entry
                priceHistoryDao.insertPriceHistory(
                    ProductPriceHistoryEntity(
                        productId = product.id,
                        purchasePrice = product.purchasePrice,
                        wholesalePrice = product.wholesalePrice,
                        retailPrice = product.retailPrice,
                        minSellingPrice = product.minSellingPrice,
                        changedBy = operatorName,
                        note = "Updated prices"
                    )
                )
            }

            if (existing != null && existing.trackingType != product.trackingType &&
                (existing.currentStock > 0 || productDao.countSerialsForProduct(existing.id) > 0)
            ) {
                throw IllegalArgumentException("Tracking type can only be changed while the product has no stock and no serial/IMEI units.")
            }

            // Stock is changed only by purchases, sales, returns and adjustments (each leaves a stock record)
            productDao.updateProduct(
                product.copy(
                    currentStock = existing?.currentStock ?: product.currentStock,
                    openingStock = existing?.openingStock ?: product.openingStock,
                    createdAt = existing?.createdAt ?: product.createdAt,
                    updatedAt = System.currentTimeMillis()
                )
            )

            auditDao.insertAuditLog(
                AuditLogEntity(
                    userRole = "ADMIN",
                    action = "PRODUCT_UPDATED",
                    module = "PRODUCTS",
                    recordId = product.id.toString(),
                    description = "Updated product '${product.name}' by $operatorName"
                )
            )

            product.id
        }
    }

    suspend fun deleteProductIfUnused(product: ProductEntity, operatorName: String = "Admin"): Result<Unit> = runCatching {
        val stockTxCount = productDao.countStockTransactions(product.id)
        val saleCount = productDao.countSaleItems(product.id)
        val purchaseCount = productDao.countPurchaseItems(product.id)
        val totalTransactions = stockTxCount + saleCount + purchaseCount

        if (totalTransactions > 0) {
            productDao.deactivateProduct(product.id)
            auditDao.insertAuditLog(
                AuditLogEntity(
                    userRole = "ADMIN",
                    action = "PRODUCT_DEACTIVATED",
                    module = "PRODUCTS",
                    recordId = product.id.toString(),
                    description = "Product '${product.name}' deactivated (has $totalTransactions transactions) by $operatorName"
                )
            )
            throw IllegalStateException("This product has $totalTransactions historical transactions and cannot be deleted. It has been deactivated instead.")
        } else {
            productDao.deleteProduct(product)
            auditDao.insertAuditLog(
                AuditLogEntity(
                    userRole = "ADMIN",
                    action = "PRODUCT_DELETED",
                    module = "PRODUCTS",
                    recordId = product.id.toString(),
                    description = "Permanently deleted unused product '${product.name}' by $operatorName"
                )
            )
        }
    }

    suspend fun deactivateProduct(productId: Long) = productDao.deactivateProduct(productId)

    // Variants
    fun getVariantsForProduct(productId: Long): Flow<List<ProductVariantEntity>> =
        variantDao.getVariantsForProduct(productId)

    suspend fun getVariantsForProductSync(productId: Long): List<ProductVariantEntity> =
        variantDao.getVariantsForProductSync(productId)

    /** All active variants (POS and purchase entry pick a variant per product from this). */
    val allActiveVariants: Flow<List<ProductVariantEntity>> = variantDao.getAllActiveVariants()

    suspend fun saveVariant(input: ProductVariantEntity, operatorName: String = "Admin"): Result<Long> = runCatching {
        val variant = input.copy(
            variantName = input.variantName.trim(),
            sku = input.sku.trim(),
            barcode = input.barcode.trim()
        )
        if (variant.variantName.isBlank()) throw IllegalArgumentException("Variant name cannot be empty.")
        if (variant.retailPrice < 0 || variant.purchasePrice < 0 || variant.minSellingPrice < 0) {
            throw IllegalArgumentException("Prices cannot be negative.")
        }
        if (variant.sku.isNotBlank() && (variantDao.checkVariantSkuExists(variant.sku, variant.id) > 0 ||
                productDao.countSkuOccurrences(variant.sku, 0L) > 0)
        ) {
            throw IllegalArgumentException("SKU '${variant.sku}' is already used.")
        }
        if (variant.barcode.isNotBlank() && (variantDao.countVariantBarcode(variant.barcode, variant.id) > 0 ||
                productDao.countBarcodeOccurrences(variant.barcode, 0L) > 0)
        ) {
            throw IllegalArgumentException("Barcode '${variant.barcode}' is already used.")
        }
        val sameName = variantDao.getVariantsForProductSync(variant.productId)
            .firstOrNull { it.id != variant.id && it.variantName.equals(variant.variantName, ignoreCase = true) }
        if (sameName != null) throw IllegalArgumentException("This product already has a variant called '${sameName.variantName}'.")

        val id = if (variant.id == 0L) {
            // New variants start empty: stock arrives through purchases so it always has a stock record
            val genId = variantDao.insertVariant(variant.copy(currentStock = 0))
            productDao.getProductById(variant.productId)?.takeIf { !it.hasVariants }?.let {
                productDao.updateProduct(it.copy(hasVariants = true, updatedAt = System.currentTimeMillis()))
            }
            auditDao.insertAuditLog(
                AuditLogEntity(
                    userRole = "ADMIN",
                    action = "VARIANT_CREATED",
                    module = "PRODUCTS",
                    recordId = genId.toString(),
                    description = "Created variant '${variant.variantName}' by $operatorName"
                )
            )
            genId
        } else {
            val existing = variantDao.getVariantById(variant.id) ?: throw IllegalArgumentException("Variant not found.")
            // Stock and the weighted cost only change through purchases, sales and returns
            variantDao.updateVariant(
                variant.copy(
                    productId = existing.productId,
                    currentStock = existing.currentStock,
                    createdAt = existing.createdAt,
                    updatedAt = System.currentTimeMillis()
                )
            )
            variant.id
        }
        id
    }

    suspend fun deactivateVariant(variantId: Long): Result<Unit> = runCatching {
        val variant = variantDao.getVariantById(variantId) ?: throw IllegalArgumentException("Variant not found.")
        require(variant.currentStock <= 0) {
            "'${variant.variantName}' still has ${variant.currentStock} in stock. Sell or return them first."
        }
        variantDao.deactivateVariant(variantId)
    }

    // Price History
    fun getPriceHistory(productId: Long): Flow<List<ProductPriceHistoryEntity>> =
        priceHistoryDao.getPriceHistory(productId)

    // Serial & IMEI
    fun getAvailableSerials(productId: Long) = productDao.getAvailableSerialsForProduct(productId)
    suspend fun getAvailableSerialsSync(productId: Long) = productDao.getAvailableSerialsForProductSync(productId)
    fun getAllSerials(productId: Long) = productDao.getAllSerialsForProduct(productId)
    suspend fun getByImei(imei: String) = productDao.getSerialByImei(imei)
    suspend fun getSerialById(id: Long) = productDao.getSerialById(id)
    suspend fun getBySerialNumber(serial: String) = productDao.getBySerialNumber(serial)
    fun searchSerials(query: String) = productDao.searchSerialImeis(query)

    suspend fun saveSerialImei(item: ProductSerialImeiEntity): Long {
        val cleanSerial = item.serialNumber?.trim()?.ifEmpty { null }
        val cleanImei1 = item.imei1?.trim()?.ifEmpty { null }
        val cleanImei2 = item.imei2?.trim()?.ifEmpty { null }

        if (cleanImei1 != null && cleanImei2 != null) {
            require(cleanImei1 != cleanImei2) { "IMEI 1 and IMEI 2 cannot be identical ($cleanImei1)" }
        }

        if (cleanImei1 != null) {
            val count1 = productDao.countImeiOccurrences(cleanImei1, item.id)
            if (count1 > 0) throw IllegalArgumentException("IMEI '$cleanImei1' already exists in inventory.")
        }
        if (cleanImei2 != null) {
            val count2 = productDao.countImeiOccurrences(cleanImei2, item.id)
            if (count2 > 0) throw IllegalArgumentException("IMEI '$cleanImei2' already exists in inventory.")
        }
        if (cleanSerial != null) {
            val countS = productDao.countSerialOccurrences(cleanSerial, item.id)
            if (countS > 0) throw IllegalArgumentException("Serial number '$cleanSerial' already exists in inventory.")
        }

        val cleaned = item.copy(
            serialNumber = cleanSerial,
            imei1 = cleanImei1,
            imei2 = cleanImei2,
            updatedAt = System.currentTimeMillis()
        )

        return if (item.id == 0L) {
            productDao.insertSerialImei(cleaned)
        } else {
            productDao.updateSerialImei(cleaned)
            item.id
        }
    }

    suspend fun getSerialsForSaleSync(saleId: Long): List<ProductSerialImeiEntity> =
        productDao.getSerialsForSale(saleId)
}

data class ProductCodeMatch(
    val product: ProductEntity,
    val variant: ProductVariantEntity? = null,
    val unit: ProductSerialImeiEntity? = null
)
