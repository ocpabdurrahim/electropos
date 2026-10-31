package com.example.sync

import com.example.data.local.entities.*

/** How a column's value travels (docs/data-model.md, "Documents"). */
enum class ColType { LONG, DOUBLE, STRING, BOOLEAN }

data class SyncColumn(val name: String, val type: ColType, val nullable: Boolean)

/**
 * One synced Room table. [spec] lists every column as `name:T` (T = L integer, D number,
 * S string, B boolean; `?` = may be null), exactly as the entity declares them; SyncTablesTest
 * checks it against the entity class, so a new column can't be forgotten.
 *
 * @param template an entity made with its defaults; a document without a field takes it.
 * @param localOnly never sent, ignored when received.
 * @param cache sent, but every device recomputes them (CacheRecompute); changes to them alone
 *   don't make the row go out. Neither does `updatedAt` on its own.
 * @param parents foreign keys: column -> parent table (a row whose parent is missing waits).
 * @param costWhenNoPurchases the cache column `purchasePrice` is the entered cost until the
 *   first purchase: until then a change to it alone does go out ([costOwnerColumn] says which
 *   stock movements belong to the row).
 */
class SyncTable(
    val name: String,
    template: Any,
    spec: String,
    val localOnly: Set<String> = emptySet(),
    val cache: Set<String> = emptySet(),
    val parents: Map<String, String> = emptyMap(),
    val costOwnerColumn: String? = null
) {
    val entityClass: Class<*> = template.javaClass

    val columns: List<SyncColumn> = spec.trim().split(Regex("\\s+")).map { part ->
        val (col, t) = part.split(':')
        val type = when (t.removeSuffix("?")) {
            "L" -> ColType.LONG
            "D" -> ColType.DOUBLE
            "S" -> ColType.STRING
            "B" -> ColType.BOOLEAN
            else -> error("Unknown column type in $name.$col: $t")
        }
        SyncColumn(col, type, t.endsWith("?"))
    }

    val columnNames: List<String> = columns.map { it.name }

    /** Columns that go into the document. */
    val synced: List<SyncColumn> = columns.filter { it.name !in localOnly }

    /** Columns whose change makes the row go out. */
    val watched: List<SyncColumn> = columns.filter {
        it.name != "id" && it.name !in localOnly && it.name !in cache && it.name != "updatedAt"
    }

    /** Value of each column in the template entity, as SQLite stores it (booleans 0/1). */
    val defaults: Map<String, Any?> = columns.associate { c ->
        val field = entityClass.getDeclaredField(c.name).apply { isAccessible = true }
        c.name to toSqlValue(field.get(template))
    }

    val hasUpdatedAt: Boolean get() = "updatedAt" in columnNames

    init {
        require(columns.first().name == "id") { "$name: id must come first" }
        require((localOnly + cache + parents.keys).all { it in columnNames }) { "$name: unknown column in config" }
    }

    companion object {
        fun toSqlValue(value: Any?): Any? = when (value) {
            is Boolean -> if (value) 1L else 0L
            is Int -> value.toLong()
            else -> value
        }
    }
}

/** Every synced table, parents before children (the order rows are applied and downloaded in). */
object SyncTables {
    private val shopProfileCounters = setOf(
        "invoiceNextNumber", "purchaseNextNumber", "purchaseReturnNextNumber", "salesReturnNextNumber",
        "customerPaymentNextNumber", "customerNextNumber"
    )

    val ALL: List<SyncTable> = listOf(
        SyncTable(
            "shop_profile", ShopProfileEntity(),
            """id:L shopName:S slogan:S address:S phone:S alternativePhone:S email:S website:S logoUri:S
               currencySymbol:S currencyCode:S invoicePrefix:S invoiceNextNumber:L purchasePrefix:S purchaseNextNumber:L
               purchaseReturnPrefix:S purchaseReturnNextNumber:L salesReturnPrefix:S salesReturnNextNumber:L
               customerPaymentPrefix:S customerPaymentNextNumber:L customerCodePrefix:S customerNextNumber:L
               defaultTaxRate:D defaultDiscountRate:D lowStockAlertThreshold:L adminPin:S isPinRequired:B
               isSampleDataLoaded:B setupCompleted:B themeMode:S rememberLogin:B lastActiveUserId:L updatedAt:L""",
            localOnly = setOf("themeMode", "rememberLogin", "lastActiveUserId", "logoUri") + shopProfileCounters
        ),
        SyncTable(
            "users", UserEntity(name = "", username = "", pinHash = "", salt = ""),
            "id:L name:S username:S pinHash:S salt:S role:S isActive:B createdAt:L updatedAt:L lastLoginAt:L",
            localOnly = setOf("lastLoginAt")
        ),
        SyncTable(
            "categories", CategoryEntity(name = ""),
            "id:L name:S description:S iconName:S isActive:B createdAt:L updatedAt:L"
        ),
        SyncTable(
            "subcategories", SubcategoryEntity(categoryId = 0, name = ""),
            "id:L categoryId:L name:S description:S isActive:B createdAt:L updatedAt:L",
            parents = mapOf("categoryId" to "categories")
        ),
        SyncTable(
            "brands", BrandEntity(name = ""),
            "id:L name:S description:S originCountry:S logoUri:S isActive:B createdAt:L updatedAt:L",
            localOnly = setOf("logoUri")
        ),
        SyncTable(
            "units", UnitEntity(name = ""),
            "id:L name:S code:S isActive:B createdAt:L"
        ),
        SyncTable(
            "suppliers", SupplierEntity(name = "", phone = ""),
            """id:L supplierCode:S name:S company:S phone:S alternativePhone:S whatsApp:S email:S address:S city:S
               openingDue:D currentDue:D creditLimit:D notes:S isActive:B createdAt:L updatedAt:L""",
            cache = setOf("currentDue")
        ),
        SyncTable(
            "customers", CustomerEntity(name = "", phone = ""),
            """id:L customerCode:S name:S phone:S alternativePhone:S whatsApp:S email:S address:S city:S customerType:S
               openingDue:D currentDue:D creditLimit:D notes:S isActive:B createdAt:L updatedAt:L""",
            cache = setOf("currentDue")
        ),
        SyncTable(
            "products", ProductEntity(name = ""),
            """id:L sku:S barcode:S name:S categoryId:L categoryName:S subcategoryId:L subcategoryName:S brandId:L
               brandName:S model:S description:S unitId:L unit:S color:S variant:S productType:S trackingType:S
               purchasePrice:D wholesalePrice:D retailPrice:D minSellingPrice:D taxPercent:D discountPercent:D
               currentStock:L minStock:L maxStock:L openingStock:L supplierId:L supplierName:S hasVariants:B
               hasWarranty:B warrantyPeriod:L warrantyUnit:S warrantyPeriodMonths:L warrantyType:S isSerialRequired:B
               isImeiRequired:B isBarcodeRequired:B imageUri:S isActive:B createdAt:L updatedAt:L notes:S""",
            localOnly = setOf("imageUri"),
            cache = setOf("currentStock", "purchasePrice"),
            costOwnerColumn = "productId"
        ),
        SyncTable(
            "product_variants", ProductVariantEntity(productId = 0, variantName = ""),
            """id:L productId:L variantName:S sku:S barcode:S color:S ram:S storage:S otherAttributes:S
               purchasePrice:D wholesalePrice:D retailPrice:D minSellingPrice:D minStock:L currentStock:L isActive:B
               createdAt:L updatedAt:L""",
            cache = setOf("currentStock", "purchasePrice"),
            parents = mapOf("productId" to "products"),
            costOwnerColumn = "variantId"
        ),
        SyncTable(
            "product_serial_imei", ProductSerialImeiEntity(productId = 0),
            """id:L productId:L variantId:L purchaseItemId:L serialNumber:S? imei1:S? imei2:S? ram:S storage:S color:S
               purchasePrice:D sellingPrice:D supplierId:L purchaseId:L saleId:L invoiceNumber:S status:S
               warrantyPeriodMonths:L warrantyStartDate:L warrantyEndDate:L notes:S createdAt:L updatedAt:L""",
            parents = mapOf("productId" to "products")
        ),
        SyncTable(
            "product_price_history",
            ProductPriceHistoryEntity(productId = 0, purchasePrice = 0.0, wholesalePrice = 0.0, retailPrice = 0.0, minSellingPrice = 0.0),
            "id:L productId:L purchasePrice:D wholesalePrice:D retailPrice:D minSellingPrice:D changedAt:L changedBy:S note:S",
            parents = mapOf("productId" to "products")
        ),
        SyncTable(
            "purchases", PurchaseEntity(supplierId = 0),
            """id:L purchaseNumber:S invoiceNumber:S supplierInvoiceNumber:S referenceNumber:S supplierId:L supplierName:S
               purchaseDate:L paymentMethod:S subTotal:D itemDiscount:D invoiceDiscount:D discountAmount:D taxAmount:D
               transportCost:D otherCost:D totalAmount:D paidAmount:D dueAmount:D paymentStatus:S notes:S status:S
               createdBy:S createdAt:L updatedAt:L""",
            cache = setOf("paidAmount", "dueAmount", "paymentStatus"),
            parents = mapOf("supplierId" to "suppliers")
        ),
        SyncTable(
            "purchase_items", PurchaseItemEntity(purchaseId = 0, productId = 0, quantity = 0, unitCost = 0.0),
            """id:L purchaseId:L productId:L productName:S variantId:L quantity:L unitCost:D discount:D tax:D lineTotal:D
               totalCost:D trackingType:S allocatedLandedCost:D serialImeisJson:S""",
            parents = mapOf("purchaseId" to "purchases", "productId" to "products")
        ),
        SyncTable(
            "purchase_returns", PurchaseReturnEntity(purchaseId = 0, supplierId = 0),
            """id:L returnNumber:S purchaseId:L supplierId:L supplierName:S returnDate:L subtotal:D discount:D tax:D
               totalAmount:D refundAmount:D supplierCredit:D reason:S status:S createdBy:S createdAt:L""",
            parents = mapOf("purchaseId" to "purchases", "supplierId" to "suppliers")
        ),
        SyncTable(
            "purchase_return_items",
            PurchaseReturnItemEntity(returnId = 0, productId = 0, quantity = 0, unitCost = 0.0, totalAmount = 0.0),
            """id:L returnId:L purchaseItemId:L productId:L productName:S variantId:L quantity:L unitCost:D totalAmount:D
               serialUnitId:L serialOrImei:S""",
            parents = mapOf("returnId" to "purchase_returns")
        ),
        SyncTable(
            "supplier_payments", SupplierPaymentEntity(supplierId = 0, amount = 0.0),
            """id:L paymentNumber:S supplierId:L supplierName:S purchaseId:L paymentDate:L amount:D paymentMethod:S
               referenceNumber:S notes:S createdBy:S createdAt:L""",
            parents = mapOf("supplierId" to "suppliers")
        ),
        SyncTable(
            "supplier_ledger_entries",
            SupplierLedgerEntryEntity(supplierId = 0, transactionType = "", amount = 0.0, signedAmount = 0.0),
            """id:L supplierId:L transactionType:S referenceId:L referenceNumber:S amount:D signedAmount:D
               runningBalance:D description:S createdBy:S createdAt:L""",
            cache = setOf("runningBalance"),
            parents = mapOf("supplierId" to "suppliers")
        ),
        SyncTable(
            "sales", SaleEntity(invoiceNumber = ""),
            """id:L invoiceNumber:S customerId:L? customerName:S customerPhone:S saleDate:L subTotal:D itemDiscount:D
               invoiceDiscount:D discountAmount:D taxAmount:D totalAmount:D paidAmount:D dueAmount:D paymentStatus:S
               paymentMethod:S notes:S status:S salespersonName:S totalCostOfGoodsSold:D totalProfit:D createdAt:L
               updatedAt:L""",
            cache = setOf("paidAmount", "dueAmount", "paymentStatus"),
            parents = mapOf("customerId" to "customers")
        ),
        SyncTable(
            "sale_items",
            SaleItemEntity(saleId = 0, productId = 0, productName = "", quantity = 0, unitPrice = 0.0, unitCostAtSale = 0.0, totalPrice = 0.0),
            """id:L saleId:L productId:L productName:S variantId:L quantity:L unitPrice:D unitCostAtSale:D discount:D
               tax:D totalPrice:D profit:D trackingType:S serialImeisJson:S""",
            parents = mapOf("saleId" to "sales", "productId" to "products")
        ),
        SyncTable(
            "sales_returns", SalesReturnEntity(returnNumber = "", saleId = 0),
            """id:L returnNumber:S saleId:L invoiceNumber:S customerId:L customerName:S customerPhone:S returnDate:L
               subtotal:D taxAmount:D discountAmount:D totalAmount:D refundAmount:D customerCredit:D
               refundPaymentMethod:S reason:S status:S createdBy:S createdAt:L""",
            parents = mapOf("saleId" to "sales")
        ),
        SyncTable(
            "sales_return_items",
            SalesReturnItemEntity(salesReturnId = 0, productId = 0, quantity = 0, unitPrice = 0.0, totalPrice = 0.0),
            """id:L salesReturnId:L saleItemId:L productId:L productName:S variantId:L quantity:L unitPrice:D
               unitCostAtSale:D totalPrice:D serialUnitId:L serialOrImei:S restockOption:S""",
            parents = mapOf("salesReturnId" to "sales_returns")
        ),
        SyncTable(
            "customer_payments", CustomerPaymentEntity(paymentNumber = "", customerId = 0, amount = 0.0),
            """id:L paymentNumber:S customerId:L customerName:S saleId:L paymentDate:L amount:D paymentMethod:S
               referenceNumber:S notes:S createdBy:S createdAt:L""",
            parents = mapOf("customerId" to "customers")
        ),
        SyncTable(
            "customer_ledger_entries",
            CustomerLedgerEntryEntity(customerId = 0, transactionType = "", amount = 0.0, signedAmount = 0.0),
            """id:L customerId:L transactionType:S referenceId:L referenceNumber:S amount:D signedAmount:D
               runningBalance:D description:S createdBy:S createdAt:L""",
            cache = setOf("runningBalance"),
            parents = mapOf("customerId" to "customers")
        ),
        SyncTable(
            "stock_transactions",
            StockTransactionEntity(productId = 0, productName = "", transactionType = "", quantityChange = 0, previousStock = 0, newStock = 0, unitCost = 0.0),
            """id:L productId:L productName:S variantId:L transactionType:S quantityChange:L previousStock:L newStock:L
               unitCost:D referenceId:L referenceType:S serialUnitId:L balanceAfter:L createdBy:S notes:S createdAt:L""",
            parents = mapOf("productId" to "products")
        ),
        SyncTable(
            "payment_transactions", PaymentTransactionEntity(transactionType = "", amount = 0.0),
            """id:L transactionType:S customerId:L supplierId:L saleId:L purchaseId:L amount:D paymentMethod:S
               referenceNo:S notes:S transactionDate:L createdAt:L"""
        ),
        SyncTable(
            "expenses", ExpenseEntity(categoryName = "", title = "", amount = 0.0),
            "id:L categoryName:S title:S description:S amount:D paymentMethod:S referenceNo:S expenseDate:L notes:S createdAt:L"
        ),
        SyncTable(
            "expense_categories", ExpenseCategoryEntity(name = ""),
            "id:L name:S isDefault:B"
        ),
        SyncTable(
            "cash_register_transactions", CashRegisterTransactionEntity(type = "", amount = 0.0, flowDirection = ""),
            "id:L type:S amount:D flowDirection:S paymentMethod:S referenceId:L referenceText:S notes:S date:L createdAt:L"
        ),
        SyncTable(
            "warranty_records",
            WarrantyRecordEntity(
                saleId = 0, invoiceNumber = "", customerId = 0, customerName = "", customerPhone = "", productId = 0,
                productName = "", warrantyPeriodMonths = 0, warrantyEndDate = 0
            ),
            """id:L saleId:L invoiceNumber:S customerId:L customerName:S customerPhone:S productId:L productName:S
               serialImeiId:L serialOrImei:S saleDate:L warrantyPeriodMonths:L warrantyStartDate:L warrantyEndDate:L
               status:S claimNotes:S createdAt:L"""
        ),
        SyncTable(
            "audit_logs", AuditLogEntity(action = "", module = "", description = ""),
            "id:L userRole:S action:S module:S recordId:S description:S timestamp:L"
        )
    )

    private val byName: Map<String, SyncTable> = ALL.associateBy { it.name }

    fun get(name: String): SyncTable = byName[name] ?: error("Not a synced table: $name")
    fun find(name: String): SyncTable? = byName[name]

    /** Position in [ALL]: parents come first. */
    fun order(name: String): Int = ALL.indexOfFirst { it.name == name }

    /** The singleton row that every device shares (id 1), outside the id blocks. */
    const val SHOP_PROFILE = "shop_profile"

    /** Tables whose name is no longer unique: a second row with the same name is a sync issue. */
    val NAMED = setOf("categories", "brands", "units", "expense_categories")
}
