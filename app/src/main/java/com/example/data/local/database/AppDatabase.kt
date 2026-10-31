package com.example.data.local.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.data.local.dao.*
import com.example.data.local.entities.*
import com.example.data.repository.BackupRepository
import com.example.sync.*

@Database(
    entities = [
        CategoryEntity::class,
        SubcategoryEntity::class,
        BrandEntity::class,
        SupplierEntity::class,
        CustomerEntity::class,
        UnitEntity::class,
        ProductEntity::class,
        ProductVariantEntity::class,
        ProductSerialImeiEntity::class,
        ProductPriceHistoryEntity::class,
        PurchaseEntity::class,
        PurchaseItemEntity::class,
        PurchaseReturnEntity::class,
        PurchaseReturnItemEntity::class,
        SupplierPaymentEntity::class,
        SaleEntity::class,
        SaleItemEntity::class,
        StockTransactionEntity::class,
        PaymentTransactionEntity::class,
        ExpenseEntity::class,
        ExpenseCategoryEntity::class,
        CashRegisterTransactionEntity::class,
        WarrantyRecordEntity::class,
        ShopProfileEntity::class,
        AuditLogEntity::class,
        UserEntity::class,
        SupplierLedgerEntryEntity::class,
        CustomerLedgerEntryEntity::class,
        SalesReturnEntity::class,
        SalesReturnItemEntity::class,
        CustomerPaymentEntity::class,
        // Cloud sync bookkeeping (com.example.sync)
        SyncFlagsEntity::class,
        SyncOutboxEntity::class,
        SyncIdGenEntity::class,
        SyncConflictEntity::class,
        SyncPendingInboundEntity::class,
        SyncCounterEntity::class,
        SyncCursorEntity::class
    ],
    version = 9,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun masterDao(): MasterDao
    abstract fun supplierDao(): SupplierDao
    abstract fun supplierLedgerDao(): SupplierLedgerDao
    abstract fun customerDao(): CustomerDao
    abstract fun customerLedgerDao(): CustomerLedgerDao
    abstract fun customerPaymentDao(): CustomerPaymentDao
    abstract fun subcategoryDao(): SubcategoryDao
    abstract fun unitDao(): UnitDao
    abstract fun productDao(): ProductDao
    abstract fun productVariantDao(): ProductVariantDao
    abstract fun productPriceHistoryDao(): ProductPriceHistoryDao
    abstract fun purchaseDao(): PurchaseDao
    abstract fun purchaseReturnDao(): PurchaseReturnDao
    abstract fun supplierPaymentDao(): SupplierPaymentDao
    abstract fun saleDao(): SaleDao
    abstract fun salesReturnDao(): SalesReturnDao
    abstract fun stockDao(): StockDao
    abstract fun financeDao(): FinanceDao
    abstract fun warrantyDao(): WarrantyDao
    abstract fun settingsAndAuditDao(): SettingsAndAuditDao
    abstract fun userDao(): UserDao
    abstract fun reportsDao(): ReportsDao
    abstract fun syncDao(): SyncDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS users (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        name TEXT NOT NULL,
                        username TEXT NOT NULL,
                        pinHash TEXT NOT NULL,
                        salt TEXT NOT NULL,
                        role TEXT NOT NULL DEFAULT 'ADMIN',
                        isActive INTEGER NOT NULL DEFAULT 1,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        lastLoginAt INTEGER NOT NULL DEFAULT 0
                    )
                """)
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_users_username ON users(username)")

                try { db.execSQL("ALTER TABLE shop_profile ADD COLUMN alternativePhone TEXT NOT NULL DEFAULT ''") } catch (_: Exception) {}
                try { db.execSQL("ALTER TABLE shop_profile ADD COLUMN logoUri TEXT NOT NULL DEFAULT ''") } catch (_: Exception) {}
                try { db.execSQL("ALTER TABLE shop_profile ADD COLUMN setupCompleted INTEGER NOT NULL DEFAULT 0") } catch (_: Exception) {}
                try { db.execSQL("ALTER TABLE shop_profile ADD COLUMN themeMode TEXT NOT NULL DEFAULT 'SYSTEM'") } catch (_: Exception) {}
                try { db.execSQL("ALTER TABLE shop_profile ADD COLUMN rememberLogin INTEGER NOT NULL DEFAULT 1") } catch (_: Exception) {}
                try { db.execSQL("ALTER TABLE shop_profile ADD COLUMN lastActiveUserId INTEGER NOT NULL DEFAULT 0") } catch (_: Exception) {}
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 1. Create subcategories
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS subcategories (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        categoryId INTEGER NOT NULL,
                        name TEXT NOT NULL,
                        description TEXT NOT NULL DEFAULT '',
                        isActive INTEGER NOT NULL DEFAULT 1,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        FOREIGN KEY(categoryId) REFERENCES categories(id) ON DELETE RESTRICT
                    )
                """)
                db.execSQL("CREATE INDEX IF NOT EXISTS index_subcategories_categoryId ON subcategories(categoryId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_subcategories_name ON subcategories(name)")

                // 2. Create units
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS units (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        name TEXT NOT NULL,
                        code TEXT NOT NULL DEFAULT 'Pcs',
                        isActive INTEGER NOT NULL DEFAULT 1,
                        createdAt INTEGER NOT NULL
                    )
                """)
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_units_name ON units(name)")

                // 3. Create product_variants
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS product_variants (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        productId INTEGER NOT NULL,
                        variantName TEXT NOT NULL,
                        sku TEXT NOT NULL DEFAULT '',
                        barcode TEXT NOT NULL DEFAULT '',
                        color TEXT NOT NULL DEFAULT '',
                        ram TEXT NOT NULL DEFAULT '',
                        storage TEXT NOT NULL DEFAULT '',
                        otherAttributes TEXT NOT NULL DEFAULT '',
                        purchasePrice REAL NOT NULL DEFAULT 0.0,
                        wholesalePrice REAL NOT NULL DEFAULT 0.0,
                        retailPrice REAL NOT NULL DEFAULT 0.0,
                        minSellingPrice REAL NOT NULL DEFAULT 0.0,
                        minStock INTEGER NOT NULL DEFAULT 2,
                        currentStock INTEGER NOT NULL DEFAULT 0,
                        isActive INTEGER NOT NULL DEFAULT 1,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        FOREIGN KEY(productId) REFERENCES products(id) ON DELETE CASCADE
                    )
                """)
                db.execSQL("CREATE INDEX IF NOT EXISTS index_product_variants_productId ON product_variants(productId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_product_variants_sku ON product_variants(sku)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_product_variants_barcode ON product_variants(barcode)")

                // 4. Create product_price_history
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS product_price_history (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        productId INTEGER NOT NULL,
                        purchasePrice REAL NOT NULL,
                        wholesalePrice REAL NOT NULL,
                        retailPrice REAL NOT NULL,
                        minSellingPrice REAL NOT NULL,
                        changedAt INTEGER NOT NULL,
                        changedBy TEXT NOT NULL DEFAULT 'Admin',
                        note TEXT NOT NULL DEFAULT '',
                        FOREIGN KEY(productId) REFERENCES products(id) ON DELETE CASCADE
                    )
                """)
                db.execSQL("CREATE INDEX IF NOT EXISTS index_product_price_history_productId ON product_price_history(productId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_product_price_history_changedAt ON product_price_history(changedAt)")

                // 5. Add columns to products
                try { db.execSQL("ALTER TABLE products ADD COLUMN subcategoryId INTEGER NOT NULL DEFAULT 0") } catch (_: Exception) {}
                try { db.execSQL("ALTER TABLE products ADD COLUMN subcategoryName TEXT NOT NULL DEFAULT ''") } catch (_: Exception) {}
                try { db.execSQL("ALTER TABLE products ADD COLUMN unitId INTEGER NOT NULL DEFAULT 0") } catch (_: Exception) {}
                try { db.execSQL("ALTER TABLE products ADD COLUMN productType TEXT NOT NULL DEFAULT 'PHYSICAL'") } catch (_: Exception) {}
                try { db.execSQL("ALTER TABLE products ADD COLUMN trackingType TEXT NOT NULL DEFAULT 'QUANTITY'") } catch (_: Exception) {}
                try { db.execSQL("ALTER TABLE products ADD COLUMN taxPercent REAL NOT NULL DEFAULT 0.0") } catch (_: Exception) {}
                try { db.execSQL("ALTER TABLE products ADD COLUMN discountPercent REAL NOT NULL DEFAULT 0.0") } catch (_: Exception) {}
                try { db.execSQL("ALTER TABLE products ADD COLUMN hasVariants INTEGER NOT NULL DEFAULT 0") } catch (_: Exception) {}
                try { db.execSQL("ALTER TABLE products ADD COLUMN hasWarranty INTEGER NOT NULL DEFAULT 0") } catch (_: Exception) {}
                try { db.execSQL("ALTER TABLE products ADD COLUMN warrantyPeriod INTEGER NOT NULL DEFAULT 0") } catch (_: Exception) {}
                try { db.execSQL("ALTER TABLE products ADD COLUMN warrantyUnit TEXT NOT NULL DEFAULT 'MONTHS'") } catch (_: Exception) {}
                try { db.execSQL("ALTER TABLE products ADD COLUMN imageUri TEXT NOT NULL DEFAULT ''") } catch (_: Exception) {}

                // 6. Add columns to categories & brands
                try { db.execSQL("ALTER TABLE categories ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0") } catch (_: Exception) {}
                try { db.execSQL("ALTER TABLE brands ADD COLUMN description TEXT NOT NULL DEFAULT ''") } catch (_: Exception) {}
                try { db.execSQL("ALTER TABLE brands ADD COLUMN logoUri TEXT NOT NULL DEFAULT ''") } catch (_: Exception) {}
                try { db.execSQL("ALTER TABLE brands ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0") } catch (_: Exception) {}
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 1. Alter suppliers table
                try { db.execSQL("ALTER TABLE suppliers ADD COLUMN supplierCode TEXT NOT NULL DEFAULT ''") } catch (_: Exception) {}
                try { db.execSQL("ALTER TABLE suppliers ADD COLUMN alternativePhone TEXT NOT NULL DEFAULT ''") } catch (_: Exception) {}
                try { db.execSQL("ALTER TABLE suppliers ADD COLUMN city TEXT NOT NULL DEFAULT ''") } catch (_: Exception) {}
                try { db.execSQL("ALTER TABLE suppliers ADD COLUMN creditLimit REAL NOT NULL DEFAULT 0.0") } catch (_: Exception) {}
                try { db.execSQL("ALTER TABLE suppliers ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0") } catch (_: Exception) {}
                try { db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_suppliers_supplierCode ON suppliers(supplierCode)") } catch (_: Exception) {}

                // 2. Alter purchases table
                try { db.execSQL("ALTER TABLE purchases ADD COLUMN purchaseNumber TEXT NOT NULL DEFAULT ''") } catch (_: Exception) {}
                try { db.execSQL("ALTER TABLE purchases ADD COLUMN supplierInvoiceNumber TEXT NOT NULL DEFAULT ''") } catch (_: Exception) {}
                try { db.execSQL("ALTER TABLE purchases ADD COLUMN itemDiscount REAL NOT NULL DEFAULT 0.0") } catch (_: Exception) {}
                try { db.execSQL("ALTER TABLE purchases ADD COLUMN invoiceDiscount REAL NOT NULL DEFAULT 0.0") } catch (_: Exception) {}
                try { db.execSQL("ALTER TABLE purchases ADD COLUMN taxAmount REAL NOT NULL DEFAULT 0.0") } catch (_: Exception) {}
                try { db.execSQL("ALTER TABLE purchases ADD COLUMN paymentStatus TEXT NOT NULL DEFAULT 'DUE'") } catch (_: Exception) {}
                try { db.execSQL("ALTER TABLE purchases ADD COLUMN createdBy TEXT NOT NULL DEFAULT 'Admin'") } catch (_: Exception) {}
                try { db.execSQL("ALTER TABLE purchases ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0") } catch (_: Exception) {}
                try { db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_purchases_purchaseNumber ON purchases(purchaseNumber)") } catch (_: Exception) {}

                // 3. Alter purchase_items table
                try { db.execSQL("ALTER TABLE purchase_items ADD COLUMN variantId INTEGER NOT NULL DEFAULT 0") } catch (_: Exception) {}
                try { db.execSQL("ALTER TABLE purchase_items ADD COLUMN tax REAL NOT NULL DEFAULT 0.0") } catch (_: Exception) {}
                try { db.execSQL("ALTER TABLE purchase_items ADD COLUMN lineTotal REAL NOT NULL DEFAULT 0.0") } catch (_: Exception) {}
                try { db.execSQL("ALTER TABLE purchase_items ADD COLUMN trackingType TEXT NOT NULL DEFAULT 'QUANTITY'") } catch (_: Exception) {}
                try { db.execSQL("ALTER TABLE purchase_items ADD COLUMN allocatedLandedCost REAL NOT NULL DEFAULT 0.0") } catch (_: Exception) {}
                try { db.execSQL("CREATE INDEX IF NOT EXISTS index_purchase_items_variantId ON purchase_items(variantId)") } catch (_: Exception) {}

                // 4. Alter product_serial_imei table
                try { db.execSQL("ALTER TABLE product_serial_imei ADD COLUMN variantId INTEGER NOT NULL DEFAULT 0") } catch (_: Exception) {}
                try { db.execSQL("ALTER TABLE product_serial_imei ADD COLUMN purchaseItemId INTEGER NOT NULL DEFAULT 0") } catch (_: Exception) {}
                try { db.execSQL("CREATE INDEX IF NOT EXISTS index_product_serial_imei_purchaseItemId ON product_serial_imei(purchaseItemId)") } catch (_: Exception) {}

                // 5. Alter stock_transactions table
                try { db.execSQL("ALTER TABLE stock_transactions ADD COLUMN variantId INTEGER NOT NULL DEFAULT 0") } catch (_: Exception) {}
                try { db.execSQL("ALTER TABLE stock_transactions ADD COLUMN serialUnitId INTEGER NOT NULL DEFAULT 0") } catch (_: Exception) {}
                try { db.execSQL("ALTER TABLE stock_transactions ADD COLUMN balanceAfter INTEGER NOT NULL DEFAULT 0") } catch (_: Exception) {}
                try { db.execSQL("ALTER TABLE stock_transactions ADD COLUMN createdBy TEXT NOT NULL DEFAULT 'Admin'") } catch (_: Exception) {}
                try { db.execSQL("CREATE INDEX IF NOT EXISTS index_stock_transactions_variantId ON stock_transactions(variantId)") } catch (_: Exception) {}

                // 6. Alter shop_profile table
                try { db.execSQL("ALTER TABLE shop_profile ADD COLUMN purchasePrefix TEXT NOT NULL DEFAULT 'PUR-'") } catch (_: Exception) {}
                try { db.execSQL("ALTER TABLE shop_profile ADD COLUMN purchaseNextNumber INTEGER NOT NULL DEFAULT 1") } catch (_: Exception) {}
                try { db.execSQL("ALTER TABLE shop_profile ADD COLUMN purchaseReturnPrefix TEXT NOT NULL DEFAULT 'PRT-'") } catch (_: Exception) {}
                try { db.execSQL("ALTER TABLE shop_profile ADD COLUMN purchaseReturnNextNumber INTEGER NOT NULL DEFAULT 1") } catch (_: Exception) {}

                // 7. Create purchase_returns table
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS purchase_returns (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        returnNumber TEXT NOT NULL,
                        purchaseId INTEGER NOT NULL,
                        supplierId INTEGER NOT NULL,
                        supplierName TEXT NOT NULL DEFAULT '',
                        returnDate INTEGER NOT NULL,
                        subtotal REAL NOT NULL DEFAULT 0.0,
                        discount REAL NOT NULL DEFAULT 0.0,
                        tax REAL NOT NULL DEFAULT 0.0,
                        totalAmount REAL NOT NULL DEFAULT 0.0,
                        refundAmount REAL NOT NULL DEFAULT 0.0,
                        supplierCredit REAL NOT NULL DEFAULT 0.0,
                        reason TEXT NOT NULL DEFAULT '',
                        status TEXT NOT NULL DEFAULT 'COMPLETED',
                        createdBy TEXT NOT NULL DEFAULT 'Admin',
                        createdAt INTEGER NOT NULL,
                        FOREIGN KEY(purchaseId) REFERENCES purchases(id) ON DELETE RESTRICT,
                        FOREIGN KEY(supplierId) REFERENCES suppliers(id) ON DELETE RESTRICT
                    )
                """)
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_purchase_returns_returnNumber ON purchase_returns(returnNumber)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_purchase_returns_purchaseId ON purchase_returns(purchaseId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_purchase_returns_supplierId ON purchase_returns(supplierId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_purchase_returns_returnDate ON purchase_returns(returnDate)")

                // 8. Create purchase_return_items table
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS purchase_return_items (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        returnId INTEGER NOT NULL,
                        purchaseItemId INTEGER NOT NULL DEFAULT 0,
                        productId INTEGER NOT NULL,
                        productName TEXT NOT NULL DEFAULT '',
                        variantId INTEGER NOT NULL DEFAULT 0,
                        quantity INTEGER NOT NULL,
                        unitCost REAL NOT NULL,
                        totalAmount REAL NOT NULL,
                        serialUnitId INTEGER NOT NULL DEFAULT 0,
                        serialOrImei TEXT NOT NULL DEFAULT '',
                        FOREIGN KEY(returnId) REFERENCES purchase_returns(id) ON DELETE CASCADE
                    )
                """)
                db.execSQL("CREATE INDEX IF NOT EXISTS index_purchase_return_items_returnId ON purchase_return_items(returnId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_purchase_return_items_productId ON purchase_return_items(productId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_purchase_return_items_purchaseItemId ON purchase_return_items(purchaseItemId)")

                // 9. Create supplier_payments table
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS supplier_payments (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        paymentNumber TEXT NOT NULL,
                        supplierId INTEGER NOT NULL,
                        supplierName TEXT NOT NULL DEFAULT '',
                        purchaseId INTEGER NOT NULL DEFAULT 0,
                        paymentDate INTEGER NOT NULL,
                        amount REAL NOT NULL,
                        paymentMethod TEXT NOT NULL DEFAULT 'CASH',
                        referenceNumber TEXT NOT NULL DEFAULT '',
                        notes TEXT NOT NULL DEFAULT '',
                        createdBy TEXT NOT NULL DEFAULT 'Admin',
                        createdAt INTEGER NOT NULL,
                        FOREIGN KEY(supplierId) REFERENCES suppliers(id) ON DELETE RESTRICT
                    )
                """)
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_supplier_payments_paymentNumber ON supplier_payments(paymentNumber)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_supplier_payments_supplierId ON supplier_payments(supplierId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_supplier_payments_purchaseId ON supplier_payments(purchaseId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_supplier_payments_paymentDate ON supplier_payments(paymentDate)")
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS supplier_ledger_entries (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        supplierId INTEGER NOT NULL,
                        transactionType TEXT NOT NULL,
                        referenceId INTEGER NOT NULL DEFAULT 0,
                        referenceNumber TEXT NOT NULL DEFAULT '',
                        amount REAL NOT NULL,
                        signedAmount REAL NOT NULL,
                        runningBalance REAL NOT NULL DEFAULT 0.0,
                        description TEXT NOT NULL DEFAULT '',
                        createdBy TEXT NOT NULL DEFAULT 'Admin',
                        createdAt INTEGER NOT NULL,
                        FOREIGN KEY(supplierId) REFERENCES suppliers(id) ON DELETE RESTRICT
                    )
                """)
                db.execSQL("CREATE INDEX IF NOT EXISTS index_supplier_ledger_entries_supplierId ON supplier_ledger_entries(supplierId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_supplier_ledger_entries_transactionType ON supplier_ledger_entries(transactionType)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_supplier_ledger_entries_createdAt ON supplier_ledger_entries(createdAt)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_supplier_ledger_entries_referenceNumber ON supplier_ledger_entries(referenceNumber)")

                // Migrate historical opening balances
                try {
                    db.execSQL("""
                        INSERT INTO supplier_ledger_entries (supplierId, transactionType, referenceId, referenceNumber, amount, signedAmount, runningBalance, description, createdBy, createdAt)
                        SELECT id, 'OPENING_BALANCE', id, 'OPENING', openingDue, openingDue, openingDue, 'Opening balance carried forward', 'System', createdAt
                        FROM suppliers WHERE openingDue > 0
                    """)
                } catch (_: Exception) {}

                // Migrate historical completed purchases
                try {
                    db.execSQL("""
                        INSERT INTO supplier_ledger_entries (supplierId, transactionType, referenceId, referenceNumber, amount, signedAmount, runningBalance, description, createdBy, createdAt)
                        SELECT supplierId, 'PURCHASE', id, purchaseNumber, totalAmount, totalAmount, totalAmount, 'Purchase Invoice #' || purchaseNumber, createdBy, purchaseDate
                        FROM purchases WHERE status = 'COMPLETED'
                    """)
                } catch (_: Exception) {}

                // Migrate historical down payments from purchases
                try {
                    db.execSQL("""
                        INSERT INTO supplier_ledger_entries (supplierId, transactionType, referenceId, referenceNumber, amount, signedAmount, runningBalance, description, createdBy, createdAt)
                        SELECT supplierId, 'SUPPLIER_PAYMENT', id, 'PAY-' || purchaseNumber, paidAmount, -paidAmount, -paidAmount, 'Down payment for purchase #' || purchaseNumber, createdBy, purchaseDate + 1
                        FROM purchases WHERE status = 'COMPLETED' AND paidAmount > 0
                    """)
                } catch (_: Exception) {}

                // Migrate historical purchase returns
                try {
                    db.execSQL("""
                        INSERT INTO supplier_ledger_entries (supplierId, transactionType, referenceId, referenceNumber, amount, signedAmount, runningBalance, description, createdBy, createdAt)
                        SELECT supplierId, 'PURCHASE_RETURN', id, returnNumber, totalAmount, -totalAmount, -totalAmount, 'Purchase Return #' || returnNumber, createdBy, returnDate
                        FROM purchase_returns WHERE status = 'COMPLETED'
                    """)
                } catch (_: Exception) {}

                // Migrate standalone supplier payments
                try {
                    db.execSQL("""
                        INSERT INTO supplier_ledger_entries (supplierId, transactionType, referenceId, referenceNumber, amount, signedAmount, runningBalance, description, createdBy, createdAt)
                        SELECT supplierId, 'SUPPLIER_PAYMENT', id, paymentNumber, amount, -amount, -amount, 'Supplier Payment #' || paymentNumber, createdBy, paymentDate
                        FROM supplier_payments WHERE paymentNumber NOT LIKE 'PAY-%'
                    """)
                } catch (_: Exception) {}
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Ensure empty strings are converted to NULL so SQLite UNIQUE constraints allow multiple NULLs
                try { db.execSQL("UPDATE product_serial_imei SET serialNumber = NULL WHERE serialNumber = '' OR TRIM(serialNumber) = ''") } catch (_: Exception) {}
                try { db.execSQL("UPDATE product_serial_imei SET imei1 = NULL WHERE imei1 = '' OR TRIM(imei1) = ''") } catch (_: Exception) {}
                try { db.execSQL("UPDATE product_serial_imei SET imei2 = NULL WHERE imei2 = '' OR TRIM(imei2) = ''") } catch (_: Exception) {}

                try { db.execSQL("DROP INDEX IF EXISTS index_product_serial_imei_serialNumber") } catch (_: Exception) {}
                try { db.execSQL("DROP INDEX IF EXISTS index_product_serial_imei_imei1") } catch (_: Exception) {}
                try { db.execSQL("DROP INDEX IF EXISTS index_product_serial_imei_imei2") } catch (_: Exception) {}

                try { db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_product_serial_imei_serialNumber ON product_serial_imei(serialNumber)") } catch (_: Exception) {}
                try { db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_product_serial_imei_imei1 ON product_serial_imei(imei1)") } catch (_: Exception) {}
                try { db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_product_serial_imei_imei2 ON product_serial_imei(imei2)") } catch (_: Exception) {}
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                try {
                    db.execSQL("CREATE INDEX IF NOT EXISTS index_stock_transactions_referenceId_referenceType ON stock_transactions(referenceId, referenceType)")
                } catch (_: Exception) {}
                try {
                    db.execSQL("CREATE INDEX IF NOT EXISTS index_stock_transactions_serialUnitId ON stock_transactions(serialUnitId)")
                } catch (_: Exception) {}
            }
        }

        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 1. Alter customers table
                try { db.execSQL("ALTER TABLE customers ADD COLUMN customerCode TEXT NOT NULL DEFAULT ''") } catch (_: Exception) {}
                try { db.execSQL("ALTER TABLE customers ADD COLUMN alternativePhone TEXT NOT NULL DEFAULT ''") } catch (_: Exception) {}
                try { db.execSQL("ALTER TABLE customers ADD COLUMN whatsApp TEXT NOT NULL DEFAULT ''") } catch (_: Exception) {}
                try { db.execSQL("ALTER TABLE customers ADD COLUMN city TEXT NOT NULL DEFAULT ''") } catch (_: Exception) {}
                try { db.execSQL("ALTER TABLE customers ADD COLUMN creditLimit REAL NOT NULL DEFAULT 0.0") } catch (_: Exception) {}
                try { db.execSQL("ALTER TABLE customers ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0") } catch (_: Exception) {}
                try { db.execSQL("UPDATE customers SET customerCode = 'CUS-' || substr('0000' || id, -4, 4) WHERE customerCode = '' OR customerCode IS NULL") } catch (_: Exception) {}
                try { db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_customers_customerCode ON customers(customerCode)") } catch (_: Exception) {}

                // 2. Alter sales table
                try { db.execSQL("ALTER TABLE sales ADD COLUMN itemDiscount REAL NOT NULL DEFAULT 0.0") } catch (_: Exception) {}
                try { db.execSQL("ALTER TABLE sales ADD COLUMN invoiceDiscount REAL NOT NULL DEFAULT 0.0") } catch (_: Exception) {}
                try { db.execSQL("ALTER TABLE sales ADD COLUMN paymentStatus TEXT NOT NULL DEFAULT 'PAID'") } catch (_: Exception) {}
                try { db.execSQL("ALTER TABLE sales ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0") } catch (_: Exception) {}
                try { db.execSQL("CREATE INDEX IF NOT EXISTS index_sales_paymentStatus ON sales(paymentStatus)") } catch (_: Exception) {}

                // 3. Alter sale_items table
                try { db.execSQL("ALTER TABLE sale_items ADD COLUMN variantId INTEGER NOT NULL DEFAULT 0") } catch (_: Exception) {}
                try { db.execSQL("ALTER TABLE sale_items ADD COLUMN tax REAL NOT NULL DEFAULT 0.0") } catch (_: Exception) {}
                try { db.execSQL("ALTER TABLE sale_items ADD COLUMN trackingType TEXT NOT NULL DEFAULT 'QUANTITY'") } catch (_: Exception) {}
                try { db.execSQL("CREATE INDEX IF NOT EXISTS index_sale_items_variantId ON sale_items(variantId)") } catch (_: Exception) {}

                // 4. Alter shop_profile table
                try { db.execSQL("ALTER TABLE shop_profile ADD COLUMN salesReturnPrefix TEXT NOT NULL DEFAULT 'SRT-'") } catch (_: Exception) {}
                try { db.execSQL("ALTER TABLE shop_profile ADD COLUMN salesReturnNextNumber INTEGER NOT NULL DEFAULT 1") } catch (_: Exception) {}
                try { db.execSQL("ALTER TABLE shop_profile ADD COLUMN customerPaymentPrefix TEXT NOT NULL DEFAULT 'CPAY-'") } catch (_: Exception) {}
                try { db.execSQL("ALTER TABLE shop_profile ADD COLUMN customerPaymentNextNumber INTEGER NOT NULL DEFAULT 1") } catch (_: Exception) {}
                try { db.execSQL("ALTER TABLE shop_profile ADD COLUMN customerCodePrefix TEXT NOT NULL DEFAULT 'CUS-'") } catch (_: Exception) {}
                try { db.execSQL("ALTER TABLE shop_profile ADD COLUMN customerNextNumber INTEGER NOT NULL DEFAULT 1") } catch (_: Exception) {}

                // 5. Create customer_ledger_entries table
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS customer_ledger_entries (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        customerId INTEGER NOT NULL,
                        transactionType TEXT NOT NULL,
                        referenceId INTEGER NOT NULL DEFAULT 0,
                        referenceNumber TEXT NOT NULL DEFAULT '',
                        amount REAL NOT NULL,
                        signedAmount REAL NOT NULL,
                        runningBalance REAL NOT NULL DEFAULT 0.0,
                        description TEXT NOT NULL DEFAULT '',
                        createdBy TEXT NOT NULL DEFAULT 'Admin',
                        createdAt INTEGER NOT NULL,
                        FOREIGN KEY(customerId) REFERENCES customers(id) ON DELETE RESTRICT
                    )
                """)
                db.execSQL("CREATE INDEX IF NOT EXISTS index_customer_ledger_entries_customerId ON customer_ledger_entries(customerId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_customer_ledger_entries_transactionType ON customer_ledger_entries(transactionType)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_customer_ledger_entries_createdAt ON customer_ledger_entries(createdAt)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_customer_ledger_entries_referenceNumber ON customer_ledger_entries(referenceNumber)")

                // 6. Create sales_returns table
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS sales_returns (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        returnNumber TEXT NOT NULL,
                        saleId INTEGER NOT NULL,
                        invoiceNumber TEXT NOT NULL DEFAULT '',
                        customerId INTEGER NOT NULL DEFAULT 0,
                        customerName TEXT NOT NULL DEFAULT 'Walk-in Customer',
                        customerPhone TEXT NOT NULL DEFAULT '',
                        returnDate INTEGER NOT NULL,
                        subtotal REAL NOT NULL DEFAULT 0.0,
                        taxAmount REAL NOT NULL DEFAULT 0.0,
                        discountAmount REAL NOT NULL DEFAULT 0.0,
                        totalAmount REAL NOT NULL DEFAULT 0.0,
                        refundAmount REAL NOT NULL DEFAULT 0.0,
                        customerCredit REAL NOT NULL DEFAULT 0.0,
                        refundPaymentMethod TEXT NOT NULL DEFAULT 'CASH',
                        reason TEXT NOT NULL DEFAULT '',
                        status TEXT NOT NULL DEFAULT 'COMPLETED',
                        createdBy TEXT NOT NULL DEFAULT 'Admin',
                        createdAt INTEGER NOT NULL,
                        FOREIGN KEY(saleId) REFERENCES sales(id) ON DELETE RESTRICT
                    )
                """)
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_sales_returns_returnNumber ON sales_returns(returnNumber)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_sales_returns_saleId ON sales_returns(saleId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_sales_returns_customerId ON sales_returns(customerId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_sales_returns_returnDate ON sales_returns(returnDate)")

                // 7. Create sales_return_items table
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS sales_return_items (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        salesReturnId INTEGER NOT NULL,
                        saleItemId INTEGER NOT NULL DEFAULT 0,
                        productId INTEGER NOT NULL,
                        productName TEXT NOT NULL DEFAULT '',
                        variantId INTEGER NOT NULL DEFAULT 0,
                        quantity INTEGER NOT NULL,
                        unitPrice REAL NOT NULL,
                        unitCostAtSale REAL NOT NULL DEFAULT 0.0,
                        totalPrice REAL NOT NULL,
                        serialUnitId INTEGER NOT NULL DEFAULT 0,
                        serialOrImei TEXT NOT NULL DEFAULT '',
                        restockOption TEXT NOT NULL DEFAULT 'RESTOCK_SELLABLE',
                        FOREIGN KEY(salesReturnId) REFERENCES sales_returns(id) ON DELETE CASCADE
                    )
                """)
                db.execSQL("CREATE INDEX IF NOT EXISTS index_sales_return_items_salesReturnId ON sales_return_items(salesReturnId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_sales_return_items_productId ON sales_return_items(productId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_sales_return_items_saleItemId ON sales_return_items(saleItemId)")

                // 8. Create customer_payments table
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS customer_payments (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        paymentNumber TEXT NOT NULL,
                        customerId INTEGER NOT NULL,
                        customerName TEXT NOT NULL DEFAULT '',
                        saleId INTEGER NOT NULL DEFAULT 0,
                        paymentDate INTEGER NOT NULL,
                        amount REAL NOT NULL,
                        paymentMethod TEXT NOT NULL DEFAULT 'CASH',
                        referenceNumber TEXT NOT NULL DEFAULT '',
                        notes TEXT NOT NULL DEFAULT '',
                        createdBy TEXT NOT NULL DEFAULT 'Admin',
                        createdAt INTEGER NOT NULL,
                        FOREIGN KEY(customerId) REFERENCES customers(id) ON DELETE RESTRICT
                    )
                """)
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_customer_payments_paymentNumber ON customer_payments(paymentNumber)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_customer_payments_customerId ON customer_payments(customerId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_customer_payments_saleId ON customer_payments(saleId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_customer_payments_paymentDate ON customer_payments(paymentDate)")

                // 9. Historical Opening Balances Migration
                try {
                    db.execSQL("""
                        INSERT INTO customer_ledger_entries (customerId, transactionType, referenceId, referenceNumber, amount, signedAmount, runningBalance, description, createdBy, createdAt)
                        SELECT id, 'OPENING_BALANCE', id, 'OPENING', openingDue, openingDue, openingDue, 'Opening balance carried forward', 'System', createdAt
                        FROM customers WHERE openingDue > 0
                    """)
                } catch (_: Exception) {}

                // 10. Historical Sales Migration
                try {
                    db.execSQL("""
                        INSERT INTO customer_ledger_entries (customerId, transactionType, referenceId, referenceNumber, amount, signedAmount, runningBalance, description, createdBy, createdAt)
                        SELECT customerId, 'SALE', id, invoiceNumber, totalAmount, totalAmount, totalAmount, 'Sale Invoice #' || invoiceNumber, salespersonName, saleDate
                        FROM sales WHERE status = 'COMPLETED' AND customerId > 0
                    """)
                } catch (_: Exception) {}

                // 11. Historical Sales Down Payments Migration
                try {
                    db.execSQL("""
                        INSERT INTO customer_ledger_entries (customerId, transactionType, referenceId, referenceNumber, amount, signedAmount, runningBalance, description, createdBy, createdAt)
                        SELECT customerId, 'CUSTOMER_PAYMENT', id, 'PAY-' || invoiceNumber, paidAmount, -paidAmount, -paidAmount, 'Payment for invoice #' || invoiceNumber, salespersonName, saleDate + 1
                        FROM sales WHERE status = 'COMPLETED' AND customerId > 0 AND paidAmount > 0
                    """)
                } catch (_: Exception) {}
            }
        }

        /** Sync tables and non-unique names (see SyncSchema). */
        val MIGRATION_8_9: Migration = SyncSchema.MIGRATION_8_9

        val ALL_MIGRATIONS: Array<Migration>
            get() = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9)

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: run {
                    // A restore chosen in Backup & Restore is swapped in before Room opens the file
                    BackupRepository.applyPendingRestore(context.applicationContext)
                    // No destructive fallback: a missing migration must fail loudly, never wipe the shop's data
                    Room.databaseBuilder(
                        context.applicationContext,
                        AppDatabase::class.java,
                        "electro_pos.db"
                    )
                        .addMigrations(*ALL_MIGRATIONS)
                        // Change triggers, flags row; after a restore the cloud link is forgotten
                        .addCallback(SyncSchema.Callback(SyncSchema.restoreMarker(context.applicationContext.noBackupFilesDir)))
                        .build()
                        .also { INSTANCE = it }
                }
            }
        }
    }
}
