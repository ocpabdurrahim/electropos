package com.example.data.local.dao

import androidx.room.*
import androidx.room.withTransaction
import com.example.sync.SyncIds
import com.example.data.local.entities.*
import kotlinx.coroutines.flow.Flow

@Dao
abstract class StockDao(private val db: RoomDatabase) {
    @Query("SELECT * FROM stock_transactions WHERE productId = :productId ORDER BY createdAt DESC")
    abstract fun getTransactionsForProduct(productId: Long): Flow<List<StockTransactionEntity>>

    @Query("SELECT * FROM stock_transactions WHERE productId = :productId ORDER BY createdAt ASC, id ASC")
    abstract suspend fun getTransactionsForProductSync(productId: Long): List<StockTransactionEntity>

    @Query("SELECT * FROM stock_transactions ORDER BY createdAt DESC")
    abstract fun getAllStockTransactions(): Flow<List<StockTransactionEntity>>

    @Query("SELECT * FROM stock_transactions ORDER BY createdAt ASC, id ASC")
    abstract suspend fun getAllStockTransactionsSync(): List<StockTransactionEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertStockTransactionRaw(transaction: StockTransactionEntity): Long

    suspend fun insertStockTransaction(transaction: StockTransactionEntity): Long =
        insertStockTransactionRaw(if (transaction.id == 0L) transaction.copy(id = SyncIds.next(db, "stock_transactions")) else transaction)

    @Query("SELECT * FROM stock_transactions WHERE createdAt BETWEEN :startDate AND :endDate ORDER BY createdAt DESC")
    abstract fun getStockTransactionsBetween(startDate: Long, endDate: Long): Flow<List<StockTransactionEntity>>

    @Query("SELECT COALESCE(SUM(quantityChange), 0) FROM stock_transactions WHERE productId = :productId")
    abstract suspend fun calculateAuthoritativeStock(productId: Long): Int

    @Query("SELECT COALESCE(SUM(quantityChange), 0) FROM stock_transactions WHERE productId = :productId AND variantId = :variantId")
    abstract suspend fun calculateAuthoritativeVariantStock(productId: Long, variantId: Long): Int

    @Query("SELECT COUNT(*) FROM stock_transactions WHERE referenceType = :refType AND referenceId = :refId AND transactionType = :txType AND productId = :productId")
    abstract suspend fun countMatchingTransactions(refType: String, refId: Long, txType: String, productId: Long): Int

    @Query("SELECT COUNT(*) FROM stock_transactions WHERE referenceType = :refType AND referenceId = :refId")
    abstract suspend fun countTransactionsForReference(refType: String, refId: Long): Int
}

@Dao
abstract class FinanceDao(private val db: RoomDatabase) {
    // Payments
    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertPaymentRaw(payment: PaymentTransactionEntity): Long

    suspend fun insertPayment(payment: PaymentTransactionEntity): Long =
        insertPaymentRaw(if (payment.id == 0L) payment.copy(id = SyncIds.next(db, "payment_transactions")) else payment)

    @Query("SELECT * FROM payment_transactions ORDER BY transactionDate DESC")
    abstract fun getAllPayments(): Flow<List<PaymentTransactionEntity>>

    @Query("SELECT * FROM payment_transactions WHERE customerId = :customerId ORDER BY transactionDate DESC")
    abstract fun getPaymentsByCustomer(customerId: Long): Flow<List<PaymentTransactionEntity>>

    @Query("SELECT * FROM payment_transactions WHERE supplierId = :supplierId ORDER BY transactionDate DESC")
    abstract fun getPaymentsBySupplier(supplierId: Long): Flow<List<PaymentTransactionEntity>>

    @Query("SELECT SUM(amount) FROM payment_transactions WHERE transactionType = 'CUSTOMER_PAYMENT' AND transactionDate BETWEEN :startDate AND :endDate")
    abstract fun getTotalCustomerCollectionBetween(startDate: Long, endDate: Long): Flow<Double?>

    // Expenses
    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertExpenseRaw(expense: ExpenseEntity): Long

    suspend fun insertExpense(expense: ExpenseEntity): Long =
        insertExpenseRaw(if (expense.id == 0L) expense.copy(id = SyncIds.next(db, "expenses")) else expense)

    @Query("SELECT * FROM expenses ORDER BY expenseDate DESC")
    abstract fun getAllExpenses(): Flow<List<ExpenseEntity>>

    @Query("SELECT * FROM expenses WHERE expenseDate BETWEEN :startDate AND :endDate ORDER BY expenseDate DESC")
    abstract fun getExpensesBetween(startDate: Long, endDate: Long): Flow<List<ExpenseEntity>>

    @Query("SELECT SUM(amount) FROM expenses WHERE expenseDate BETWEEN :startDate AND :endDate")
    abstract fun getTotalExpensesBetween(startDate: Long, endDate: Long): Flow<Double?>

    @Query("SELECT * FROM expense_categories ORDER BY name ASC")
    abstract fun getExpenseCategories(): Flow<List<ExpenseCategoryEntity>>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertExpenseCategoryRaw(category: ExpenseCategoryEntity): Long

    @Query("SELECT COUNT(*) FROM expense_categories WHERE name = :name")
    abstract suspend fun countExpenseCategoriesNamed(name: String): Int

    /** Skips a name that is already in the list (returns -1), as the old unique name index did. */
    suspend fun insertExpenseCategory(category: ExpenseCategoryEntity): Long = db.withTransaction {
        if (countExpenseCategoriesNamed(category.name) > 0) -1L
        else insertExpenseCategoryRaw(if (category.id == 0L) category.copy(id = SyncIds.next(db, "expense_categories")) else category)
    }

    // Cash Register
    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertCashTransactionRaw(entry: CashRegisterTransactionEntity): Long

    suspend fun insertCashTransaction(entry: CashRegisterTransactionEntity): Long =
        insertCashTransactionRaw(if (entry.id == 0L) entry.copy(id = SyncIds.next(db, "cash_register_transactions")) else entry)

    @Query("SELECT * FROM cash_register_transactions ORDER BY date DESC")
    abstract fun getAllCashTransactions(): Flow<List<CashRegisterTransactionEntity>>

    @Query("SELECT * FROM cash_register_transactions WHERE date BETWEEN :startDate AND :endDate ORDER BY date DESC")
    abstract fun getCashTransactionsBetween(startDate: Long, endDate: Long): Flow<List<CashRegisterTransactionEntity>>

    /** Cash in the drawer only; bKash, Nagad, card and bank money is not cash. */
    @Query("""
        SELECT 
            COALESCE(SUM(CASE WHEN flowDirection = 'IN' THEN amount ELSE -amount END), 0.0) 
        FROM cash_register_transactions
        WHERE paymentMethod = 'CASH' OR paymentMethod = ''
    """)
    abstract fun getCurrentCashBalance(): Flow<Double>

    @Query("""
        SELECT paymentMethod AS paymentMethod,
            COALESCE(SUM(CASE WHEN flowDirection = 'IN' THEN amount ELSE -amount END), 0.0) AS balance
        FROM cash_register_transactions
        GROUP BY paymentMethod
    """)
    abstract fun getBalanceByPaymentMethod(): Flow<List<PaymentMethodBalance>>

    // ---- Expenses: edit / delete / categories ----

    @Query("SELECT * FROM expenses WHERE id = :id LIMIT 1")
    abstract suspend fun getExpenseById(id: Long): ExpenseEntity?

    @Update
    abstract suspend fun updateExpense(expense: ExpenseEntity): Int

    @Query("DELETE FROM expenses WHERE id = :id")
    abstract suspend fun deleteExpenseById(id: Long): Int

    @Query("SELECT * FROM expenses WHERE categoryName = :categoryName")
    abstract suspend fun getExpensesByCategorySync(categoryName: String): List<ExpenseEntity>

    @Query("SELECT COUNT(*) FROM expenses WHERE categoryName = :categoryName")
    abstract suspend fun countExpensesInCategory(categoryName: String): Int

    @Query("UPDATE expenses SET categoryName = :newName WHERE categoryName = :oldName")
    abstract suspend fun renameCategoryOnExpenses(oldName: String, newName: String): Int

    @Query("SELECT categoryName AS categoryName, COUNT(*) AS expenseCount FROM expenses GROUP BY categoryName")
    abstract fun getExpenseCategoryUsage(): Flow<List<ExpenseCategoryUsage>>

    @Query("SELECT * FROM expense_categories WHERE id = :id LIMIT 1")
    abstract suspend fun getExpenseCategoryById(id: Long): ExpenseCategoryEntity?

    @Query("SELECT * FROM expense_categories WHERE name = :name COLLATE NOCASE LIMIT 1")
    abstract suspend fun findExpenseCategoryByName(name: String): ExpenseCategoryEntity?

    @Query("UPDATE expense_categories SET name = :newName WHERE id = :id")
    abstract suspend fun renameExpenseCategory(id: Long, newName: String): Int

    @Query("DELETE FROM expense_categories WHERE id = :id")
    abstract suspend fun deleteExpenseCategoryById(id: Long): Int

    // ---- Cash register: linked rows, manual entries, cash book ----
    // A blank payment method is old cash data, so methods are compared as
    // CASE WHEN TRIM(paymentMethod) = '' THEN 'CASH' ELSE UPPER(TRIM(paymentMethod)) END

    @Query("SELECT * FROM cash_register_transactions WHERE id = :id LIMIT 1")
    abstract suspend fun getCashTransactionById(id: Long): CashRegisterTransactionEntity?

    @Update
    abstract suspend fun updateCashTransaction(entry: CashRegisterTransactionEntity): Int

    @Query("DELETE FROM cash_register_transactions WHERE id = :id")
    abstract suspend fun deleteCashTransactionById(id: Long): Int

    @Query("SELECT * FROM cash_register_transactions WHERE type = :type AND referenceId = :referenceId ORDER BY id ASC")
    abstract suspend fun getCashTransactionsForReference(type: String, referenceId: Long): List<CashRegisterTransactionEntity>

    @Query("DELETE FROM cash_register_transactions WHERE type = :type AND referenceId = :referenceId")
    abstract suspend fun deleteCashTransactionsForReference(type: String, referenceId: Long): Int

    @Query("""
        SELECT * FROM cash_register_transactions
        WHERE date BETWEEN :startDate AND :endDate
        ORDER BY date ASC, id ASC
    """)
    abstract fun getCashTransactionsBetweenAsc(startDate: Long, endDate: Long): Flow<List<CashRegisterTransactionEntity>>

    @Query("""
        SELECT * FROM cash_register_transactions
        WHERE date BETWEEN :startDate AND :endDate
          AND (CASE WHEN TRIM(paymentMethod) = '' THEN 'CASH' ELSE UPPER(TRIM(paymentMethod)) END) = :method
        ORDER BY date ASC, id ASC
    """)
    abstract fun getCashTransactionsForMethodBetween(method: String, startDate: Long, endDate: Long): Flow<List<CashRegisterTransactionEntity>>

    /** All money (every method) before [before]; the opening line of a cash book page. */
    @Query("""
        SELECT COALESCE(SUM(CASE WHEN flowDirection = 'IN' THEN amount ELSE -amount END), 0.0)
        FROM cash_register_transactions
        WHERE date < :before
    """)
    abstract fun getBalanceBefore(before: Long): Flow<Double>

    @Query("""
        SELECT COALESCE(SUM(CASE WHEN flowDirection = 'IN' THEN amount ELSE -amount END), 0.0)
        FROM cash_register_transactions
        WHERE date < :before
          AND (CASE WHEN TRIM(paymentMethod) = '' THEN 'CASH' ELSE UPPER(TRIM(paymentMethod)) END) = :method
    """)
    abstract fun getMethodBalanceBefore(method: String, before: Long): Flow<Double>

    @Query("""
        SELECT COALESCE(SUM(CASE WHEN flowDirection = 'IN' THEN amount ELSE -amount END), 0.0)
        FROM cash_register_transactions
        WHERE (CASE WHEN TRIM(paymentMethod) = '' THEN 'CASH' ELSE UPPER(TRIM(paymentMethod)) END) = :method
    """)
    abstract suspend fun getMethodBalanceSync(method: String): Double

    /** Same as [getBalanceByPaymentMethod], but blank counts as CASH and names are upper-cased. */
    @Query("""
        SELECT (CASE WHEN TRIM(paymentMethod) = '' THEN 'CASH' ELSE UPPER(TRIM(paymentMethod)) END) AS paymentMethod,
            COALESCE(SUM(CASE WHEN flowDirection = 'IN' THEN amount ELSE -amount END), 0.0) AS balance
        FROM cash_register_transactions
        GROUP BY (CASE WHEN TRIM(paymentMethod) = '' THEN 'CASH' ELSE UPPER(TRIM(paymentMethod)) END)
    """)
    abstract fun getBalanceByNormalizedPaymentMethod(): Flow<List<PaymentMethodBalance>>

    @Query("""
        SELECT MIN(date) FROM cash_register_transactions
        WHERE (CASE WHEN TRIM(paymentMethod) = '' THEN 'CASH' ELSE UPPER(TRIM(paymentMethod)) END) = :method
    """)
    abstract suspend fun getEarliestCashDateForMethod(method: String): Long?

    @Query("""
        SELECT COUNT(*) FROM cash_register_transactions
        WHERE type = 'OPENING_CASH'
          AND (CASE WHEN TRIM(paymentMethod) = '' THEN 'CASH' ELSE UPPER(TRIM(paymentMethod)) END) = :method
    """)
    abstract suspend fun countOpeningEntriesForMethod(method: String): Int

    @Query("""
        SELECT DISTINCT (CASE WHEN TRIM(paymentMethod) = '' THEN 'CASH' ELSE UPPER(TRIM(paymentMethod)) END)
        FROM cash_register_transactions
        WHERE type = 'OPENING_CASH'
    """)
    abstract fun getMethodsWithOpeningEntry(): Flow<List<String>>
}

@Dao
abstract class WarrantyDao(private val db: RoomDatabase) {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertWarrantyRaw(record: WarrantyRecordEntity): Long

    suspend fun insertWarranty(record: WarrantyRecordEntity): Long =
        insertWarrantyRaw(if (record.id == 0L) record.copy(id = SyncIds.next(db, "warranty_records")) else record)

    @Update
    abstract suspend fun updateWarranty(record: WarrantyRecordEntity)

    @Query("SELECT * FROM warranty_records ORDER BY warrantyEndDate ASC")
    abstract fun getAllWarranties(): Flow<List<WarrantyRecordEntity>>

    @Query("UPDATE warranty_records SET status = :status WHERE saleId = :saleId AND status = 'ACTIVE'")
    abstract suspend fun setWarrantyStatusForSale(saleId: Long, status: String)

    @Query("UPDATE warranty_records SET status = :status WHERE saleId = :saleId AND serialImeiId = :serialImeiId AND status = 'ACTIVE'")
    abstract suspend fun setWarrantyStatusForUnit(saleId: Long, serialImeiId: Long, status: String)

    @Query("UPDATE warranty_records SET status = :status WHERE saleId = :saleId AND productId = :productId AND serialImeiId = 0 AND status = 'ACTIVE'")
    abstract suspend fun setWarrantyStatusForLine(saleId: Long, productId: Long, status: String)

    @Query("SELECT * FROM warranty_records WHERE status = 'ACTIVE' AND warrantyEndDate <= :thresholdDate AND warrantyEndDate >= :now ORDER BY warrantyEndDate ASC")
    abstract fun getExpiringSoonWarranties(now: Long, thresholdDate: Long): Flow<List<WarrantyRecordEntity>>

    @Query("SELECT * FROM warranty_records WHERE warrantyEndDate < :now OR status = 'EXPIRED' ORDER BY warrantyEndDate DESC")
    abstract fun getExpiredWarranties(now: Long): Flow<List<WarrantyRecordEntity>>

    @Query("""
        SELECT * FROM warranty_records 
        WHERE serialOrImei LIKE '%' || :query || '%' 
           OR customerPhone LIKE '%' || :query || '%' 
           OR customerName LIKE '%' || :query || '%' 
           OR invoiceNumber LIKE '%' || :query || '%'
        ORDER BY warrantyEndDate ASC
    """)
    abstract fun searchWarranties(query: String): Flow<List<WarrantyRecordEntity>>
}

@Dao
abstract class SettingsAndAuditDao(private val db: RoomDatabase) {
    @Query("SELECT * FROM shop_profile WHERE id = 1 LIMIT 1")
    abstract fun getShopProfile(): Flow<ShopProfileEntity?>

    @Query("SELECT * FROM shop_profile WHERE id = 1 LIMIT 1")
    abstract suspend fun getShopProfileSync(): ShopProfileEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertShopProfileRaw(profile: ShopProfileEntity)

    @Update
    abstract suspend fun updateShopProfileRaw(profile: ShopProfileEntity): Int

    /** The profile is one row (id 1): changed in place, inserted only the first time. */
    suspend fun saveShopProfile(profile: ShopProfileEntity) {
        db.withTransaction { if (updateShopProfileRaw(profile) == 0) insertShopProfileRaw(profile) }
    }

    // Phone-only settings (never synced) are changed on their own, so an older copy of the
    // profile can't write back shop details that arrived from another device meanwhile.
    @Query("UPDATE shop_profile SET themeMode = :mode WHERE id = 1")
    abstract suspend fun setThemeMode(mode: String)

    @Query("UPDATE shop_profile SET rememberLogin = :remember WHERE id = 1")
    abstract suspend fun setRememberLogin(remember: Boolean)

    @Query("UPDATE shop_profile SET lastActiveUserId = :userId WHERE id = 1")
    abstract suspend fun setLastActiveUserId(userId: Long)

    @Query("UPDATE shop_profile SET invoiceNextNumber = invoiceNextNumber + 1 WHERE id = 1")
    abstract suspend fun incrementInvoiceNumber()

    @Query("UPDATE shop_profile SET purchaseNextNumber = purchaseNextNumber + 1 WHERE id = 1")
    abstract suspend fun incrementPurchaseNumber()

    @Query("UPDATE shop_profile SET purchaseReturnNextNumber = purchaseReturnNextNumber + 1 WHERE id = 1")
    abstract suspend fun incrementPurchaseReturnNumber()

    @Query("UPDATE shop_profile SET salesReturnNextNumber = salesReturnNextNumber + 1 WHERE id = 1")
    abstract suspend fun incrementSalesReturnNumber()

    @Query("UPDATE shop_profile SET customerPaymentNextNumber = customerPaymentNextNumber + 1 WHERE id = 1")
    abstract suspend fun incrementCustomerPaymentNumber()

    @Query("UPDATE shop_profile SET customerNextNumber = customerNextNumber + 1 WHERE id = 1")
    abstract suspend fun incrementCustomerNumber()

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertAuditLogRaw(log: AuditLogEntity): Long

    suspend fun insertAuditLog(log: AuditLogEntity): Long =
        insertAuditLogRaw(if (log.id == 0L) log.copy(id = SyncIds.next(db, "audit_logs")) else log)

    @Query("SELECT * FROM audit_logs ORDER BY timestamp DESC LIMIT :limit")
    abstract fun getRecentAuditLogs(limit: Int = 100): Flow<List<AuditLogEntity>>
}

data class PaymentMethodBalance(
    val paymentMethod: String,
    val balance: Double
)

/** How many expenses use a category name (a category in use can't be deleted). */
data class ExpenseCategoryUsage(
    val categoryName: String,
    val expenseCount: Int
)
