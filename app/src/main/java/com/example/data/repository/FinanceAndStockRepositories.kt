package com.example.data.repository

import androidx.room.withTransaction
import com.example.data.local.dao.PaymentMethodBalance
import com.example.data.local.database.AppDatabase
import com.example.data.local.entities.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/** Payment methods money is kept in; stored upper-case in the paymentMethod columns. */
object PaymentMethods {
    const val CASH = "CASH"
    const val BKASH = "BKASH"
    const val NAGAD = "NAGAD"
    const val ROCKET = "ROCKET"
    const val CARD = "CARD"
    const val BANK = "BANK"

    val ALL = listOf(CASH, BKASH, NAGAD, ROCKET, CARD, BANK)

    /** Upper-case, and blank (old rows) counts as cash, the same way FinanceDao compares them. */
    fun normalize(method: String?): String = method?.trim()?.uppercase().orEmpty().ifBlank { CASH }

    fun label(method: String?): String = when (val m = normalize(method)) {
        CASH -> "Cash"
        BKASH -> "bKash"
        NAGAD -> "Nagad"
        ROCKET -> "Rocket"
        CARD -> "Card"
        BANK -> "Bank"
        else -> m.lowercase().replaceFirstChar { it.uppercase() }
    }

    /** Where the money is: "Cash in drawer", "bKash", "Bank"... */
    fun balanceLabel(method: String?): String = if (normalize(method) == CASH) "Cash in drawer" else label(method)
}

/** Cash register row types (CashRegisterTransactionEntity.type) and how they read in the cash book. */
object CashEntryType {
    const val OPENING_CASH = "OPENING_CASH"
    const val SALE_CASH = "SALE_CASH"
    const val CUSTOMER_COLLECTION = "CUSTOMER_COLLECTION"
    const val SUPPLIER_PAYMENT = "SUPPLIER_PAYMENT"
    const val EXPENSE = "EXPENSE"
    const val REFUND = "REFUND"
    const val MANUAL_ADJUSTMENT = "MANUAL_ADJUSTMENT"
    /** "Move money" between methods: an OUT row and an IN row whose referenceIds point at each other. */
    const val TRANSFER = "TRANSFER"

    const val IN = "IN"
    const val OUT = "OUT"

    fun label(type: String, flowDirection: String): String = when (type) {
        OPENING_CASH -> "Opening cash"
        SALE_CASH -> "Sale"
        CUSTOMER_COLLECTION -> "Customer payment"
        SUPPLIER_PAYMENT -> "Supplier payment"
        EXPENSE -> "Expense"
        REFUND -> if (flowDirection == IN) "Refund received" else "Refund"
        MANUAL_ADJUSTMENT -> if (flowDirection == IN) "Cash in" else "Cash out"
        TRANSFER -> "Money moved"
        else -> type.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }
    }

    /** Rows typed in the cash book itself; the others belong to a sale, payment or expense and change there. */
    fun isManual(type: String): Boolean = type == OPENING_CASH || type == MANUAL_ADJUSTMENT || type == TRANSFER
}

/** What the expense form sends; the repository validates it. */
data class ExpenseInput(
    val categoryName: String,
    val title: String,
    val amount: Double,
    val paymentMethod: String = PaymentMethods.CASH,
    val expenseDate: Long = System.currentTimeMillis(),
    val referenceNo: String = "",
    val notes: String = ""
)

data class CashBookLine(
    val entry: CashRegisterTransactionEntity,
    /** Plain words: Sale, Customer payment, Expense, Cash in... */
    val label: String,
    /** Positive for money in, negative for money out. */
    val signedAmount: Double,
    /** Balance right after this row. */
    val runningBalance: Double
)

data class CashBookPage(
    /** The payment method shown, or null for every method together. */
    val method: String?,
    /** Balance before the first row of the period. */
    val openingBalance: Double,
    val totalIn: Double,
    val totalOut: Double,
    val closingBalance: Double,
    /** Oldest first, so the running balance reads top to bottom. */
    val lines: List<CashBookLine>
) {
    companion object {
        val EMPTY = CashBookPage(PaymentMethods.CASH, 0.0, 0.0, 0.0, 0.0, emptyList())

        fun build(method: String?, openingBalance: Double, rowsOldestFirst: List<CashRegisterTransactionEntity>): CashBookPage {
            var running = openingBalance
            var totalIn = 0.0
            var totalOut = 0.0
            val lines = rowsOldestFirst.map { row ->
                val isIn = row.flowDirection == CashEntryType.IN
                if (isIn) totalIn += row.amount else totalOut += row.amount
                running += if (isIn) row.amount else -row.amount
                CashBookLine(
                    entry = row,
                    label = CashEntryType.label(row.type, row.flowDirection),
                    signedAmount = if (isIn) row.amount else -row.amount,
                    runningBalance = roundTaka(running)
                )
            }
            return CashBookPage(
                method = method,
                openingBalance = roundTaka(openingBalance),
                totalIn = roundTaka(totalIn),
                totalOut = roundTaka(totalOut),
                closingBalance = roundTaka(running),
                lines = lines
            )
        }
    }
}

private fun roundTaka(value: Double): Double = Math.round(value * 100.0) / 100.0

class FinanceRepository(private val database: AppDatabase) {
    private val financeDao = database.financeDao()
    private val masterDao = database.masterDao()
    private val auditDao = database.settingsAndAuditDao()

    val allPayments: Flow<List<PaymentTransactionEntity>> = financeDao.getAllPayments()
    val allExpenses: Flow<List<ExpenseEntity>> = financeDao.getAllExpenses()
    val expenseCategories: Flow<List<ExpenseCategoryEntity>> = financeDao.getExpenseCategories()
    val allCashTransactions: Flow<List<CashRegisterTransactionEntity>> = financeDao.getAllCashTransactions()
    val currentCashBalance: Flow<Double> = financeDao.getCurrentCashBalance()

    /** Category name to the number of expenses using it. */
    val expenseCategoryUsage: Flow<Map<String, Int>> =
        financeDao.getExpenseCategoryUsage().map { rows -> rows.associate { it.categoryName to it.expenseCount } }

    /** Balance per method: Cash first, then the usual methods (0 when unused), then anything else found. */
    val balancesByMethod: Flow<List<PaymentMethodBalance>> =
        financeDao.getBalanceByNormalizedPaymentMethod().map { rows -> orderBalances(rows) }

    /** Methods that already have an opening balance (it can be set once per method). */
    val methodsWithOpeningEntry: Flow<Set<String>> =
        financeDao.getMethodsWithOpeningEntry().map { it.toSet() }

    /**
     * Cash book for [start]..[end] (inclusive): opening balance, rows oldest first with a running balance,
     * totals and closing balance. [method] null means every method together.
     */
    fun cashBook(method: String?, start: Long, end: Long): Flow<CashBookPage> {
        val m = method?.let { PaymentMethods.normalize(it) }
        val opening = if (m == null) financeDao.getBalanceBefore(start) else financeDao.getMethodBalanceBefore(m, start)
        val rows = if (m == null) financeDao.getCashTransactionsBetweenAsc(start, end)
        else financeDao.getCashTransactionsForMethodBetween(m, start, end)
        return combine(opening, rows) { openingBalance, list -> CashBookPage.build(m, openingBalance, list) }
    }

    suspend fun getMethodBalance(method: String): Double = roundTaka(financeDao.getMethodBalanceSync(PaymentMethods.normalize(method)))

    @Deprecated(
        "Skips the customer ledger and invoice allocation. Use CustomerRepository.recordCustomerPayment instead.",
        level = DeprecationLevel.WARNING
    )
    suspend fun recordCustomerPayment(
        customerId: Long,
        customerName: String,
        amount: Double,
        paymentMethod: String,
        referenceNo: String,
        notes: String
    ): Long = database.withTransaction {
        val paymentId = financeDao.insertPayment(
            PaymentTransactionEntity(
                transactionType = "CUSTOMER_PAYMENT",
                customerId = customerId,
                amount = amount,
                paymentMethod = paymentMethod,
                referenceNo = referenceNo,
                notes = notes
            )
        )

        // Decrease customer due
        masterDao.adjustCustomerDue(customerId, -amount)

        // Cash register IN
        financeDao.insertCashTransaction(
            CashRegisterTransactionEntity(
                type = "CUSTOMER_COLLECTION",
                amount = amount,
                flowDirection = "IN",
                paymentMethod = paymentMethod,
                referenceId = paymentId,
                referenceText = "Due collection",
                notes = "Received from $customerName"
            )
        )

        auditDao.insertAuditLog(
            AuditLogEntity(
                userRole = "ADMIN",
                action = "CUSTOMER_PAYMENT_RECEIVED",
                module = "FINANCE",
                recordId = paymentId.toString(),
                description = "Received ৳$amount from $customerName via $paymentMethod"
            )
        )

        paymentId
    }

    @Deprecated(
        "Skips the supplier ledger and purchase allocation. Use SupplierPaymentRepository.recordSupplierPayment instead.",
        level = DeprecationLevel.WARNING
    )
    suspend fun recordSupplierPayment(
        supplierId: Long,
        supplierName: String,
        amount: Double,
        paymentMethod: String,
        referenceNo: String,
        notes: String
    ): Long = database.withTransaction {
        val paymentId = financeDao.insertPayment(
            PaymentTransactionEntity(
                transactionType = "SUPPLIER_PAYMENT",
                supplierId = supplierId,
                amount = amount,
                paymentMethod = paymentMethod,
                referenceNo = referenceNo,
                notes = notes
            )
        )

        // Decrease supplier due
        masterDao.adjustSupplierDue(supplierId, -amount)

        // Cash register OUT
        financeDao.insertCashTransaction(
            CashRegisterTransactionEntity(
                type = "SUPPLIER_PAYMENT",
                amount = amount,
                flowDirection = "OUT",
                paymentMethod = paymentMethod,
                referenceId = paymentId,
                referenceText = "Supplier payment",
                notes = "Paid to $supplierName"
            )
        )

        auditDao.insertAuditLog(
            AuditLogEntity(
                userRole = "ADMIN",
                action = "SUPPLIER_PAYMENT_MADE",
                module = "FINANCE",
                recordId = paymentId.toString(),
                description = "Paid ৳$amount to $supplierName via $paymentMethod"
            )
        )

        paymentId
    }

    /** Older entry point (dated now); same as [addExpense] but throws on invalid input. */
    suspend fun recordExpense(
        categoryName: String,
        title: String,
        amount: Double,
        paymentMethod: String,
        referenceNo: String,
        notes: String
    ): Long = addExpense(
        ExpenseInput(
            categoryName = categoryName,
            title = title,
            amount = amount,
            paymentMethod = paymentMethod,
            referenceNo = referenceNo,
            notes = notes
        )
    ).getOrThrow()

    // ---------------------------------------------------------------------------------------------
    // Expenses. Each expense has exactly one cash register OUT row (type EXPENSE, referenceId =
    // expense id, same method and date), written in the same transaction as the expense.
    // ---------------------------------------------------------------------------------------------

    suspend fun getExpenseById(id: Long): ExpenseEntity? = financeDao.getExpenseById(id)

    suspend fun addExpense(input: ExpenseInput, createdBy: String = "Admin"): Result<Long> = runCatching {
        val clean = cleanExpense(input)
        database.withTransaction {
            val categoryName = ensureCategory(clean.categoryName)
            val draft = ExpenseEntity(
                categoryName = categoryName,
                title = clean.title,
                amount = clean.amount,
                paymentMethod = clean.paymentMethod,
                referenceNo = clean.referenceNo,
                expenseDate = clean.expenseDate,
                notes = clean.notes
            )
            val expenseId = financeDao.insertExpense(draft)
            financeDao.insertCashTransaction(expenseCashRow(draft.copy(id = expenseId), existing = null))
            audit(
                createdBy, "EXPENSE_RECORDED", expenseId,
                "Recorded expense '${clean.title}' ($categoryName) for ${money(clean.amount)} via ${PaymentMethods.label(clean.paymentMethod)}"
            )
            expenseId
        }
    }

    suspend fun updateExpense(id: Long, input: ExpenseInput, createdBy: String = "Admin"): Result<Unit> = runCatching {
        val clean = cleanExpense(input)
        database.withTransaction {
            val old = financeDao.getExpenseById(id) ?: throw IllegalArgumentException("This expense no longer exists.")
            val categoryName = ensureCategory(clean.categoryName)
            val updated = old.copy(
                categoryName = categoryName,
                title = clean.title,
                amount = clean.amount,
                paymentMethod = clean.paymentMethod,
                referenceNo = clean.referenceNo,
                expenseDate = clean.expenseDate,
                notes = clean.notes
            )
            financeDao.updateExpense(updated)
            syncExpenseCashRow(updated, createIfMissing = true)
            audit(
                createdBy, "EXPENSE_UPDATED", id,
                "Changed expense '${old.title}': ${money(old.amount)} via ${PaymentMethods.label(old.paymentMethod)} " +
                    "to '${updated.title}' ${money(updated.amount)} via ${PaymentMethods.label(updated.paymentMethod)}"
            )
        }
    }

    /** Deletes the expense and its cash register row, so the money is back in the balance. */
    suspend fun deleteExpense(id: Long, createdBy: String = "Admin"): Result<Unit> = runCatching {
        database.withTransaction {
            val old = financeDao.getExpenseById(id) ?: throw IllegalArgumentException("This expense no longer exists.")
            financeDao.deleteCashTransactionsForReference(CashEntryType.EXPENSE, id)
            financeDao.deleteExpenseById(id)
            audit(
                createdBy, "EXPENSE_DELETED", id,
                "Deleted expense '${old.title}' (${old.categoryName}) of ${money(old.amount)} via ${PaymentMethods.label(old.paymentMethod)}"
            )
        }
    }

    private fun cleanExpense(input: ExpenseInput): ExpenseInput {
        val category = input.categoryName.trim()
        require(category.isNotEmpty()) { "Choose a category." }
        val amount = cleanAmount(input.amount)
        return input.copy(
            categoryName = category,
            title = input.title.trim().ifEmpty { category },
            amount = amount,
            paymentMethod = PaymentMethods.normalize(input.paymentMethod),
            referenceNo = input.referenceNo.trim(),
            notes = input.notes.trim(),
            expenseDate = if (input.expenseDate > 0L) input.expenseDate else System.currentTimeMillis()
        )
    }

    /** Category as stored (existing spelling wins); a new name is added to the list. */
    private suspend fun ensureCategory(name: String): String {
        financeDao.findExpenseCategoryByName(name)?.let { return it.name }
        financeDao.insertExpenseCategory(ExpenseCategoryEntity(name = name))
        return name
    }

    private fun expenseCashRow(expense: ExpenseEntity, existing: CashRegisterTransactionEntity?) =
        CashRegisterTransactionEntity(
            id = existing?.id ?: 0L,
            type = CashEntryType.EXPENSE,
            amount = expense.amount,
            flowDirection = CashEntryType.OUT,
            paymentMethod = expense.paymentMethod,
            referenceId = expense.id,
            referenceText = expense.title,
            notes = listOf(expense.categoryName, expense.notes).filter { it.isNotBlank() }.joinToString(" - "),
            date = expense.expenseDate,
            createdAt = existing?.createdAt ?: System.currentTimeMillis()
        )

    /** Makes the expense's cash row match it: updates the first, removes duplicates, adds one if missing. */
    private suspend fun syncExpenseCashRow(expense: ExpenseEntity, createIfMissing: Boolean) {
        val rows = financeDao.getCashTransactionsForReference(CashEntryType.EXPENSE, expense.id)
        if (rows.isEmpty()) {
            if (createIfMissing) financeDao.insertCashTransaction(expenseCashRow(expense, existing = null))
            return
        }
        financeDao.updateCashTransaction(expenseCashRow(expense, rows.first()))
        rows.drop(1).forEach { financeDao.deleteCashTransactionById(it.id) }
    }

    // ---------------------------------------------------------------------------------------------
    // Expense categories
    // ---------------------------------------------------------------------------------------------

    suspend fun addExpenseCategory(name: String, createdBy: String = "Admin"): Result<Long> = runCatching {
        val clean = cleanCategoryName(name)
        database.withTransaction {
            require(financeDao.findExpenseCategoryByName(clean) == null) { "\"$clean\" is already in the list." }
            val id = financeDao.insertExpenseCategory(ExpenseCategoryEntity(name = clean))
            audit(createdBy, "EXPENSE_CATEGORY_ADDED", id, "Added expense category '$clean'")
            id
        }
    }

    /** Renames the category and every expense filed under it. */
    suspend fun renameExpenseCategory(id: Long, newName: String, createdBy: String = "Admin"): Result<Unit> = runCatching {
        val clean = cleanCategoryName(newName)
        database.withTransaction {
            val category = financeDao.getExpenseCategoryById(id) ?: throw IllegalArgumentException("This category no longer exists.")
            if (category.name == clean) return@withTransaction
            val clash = financeDao.findExpenseCategoryByName(clean)
            require(clash == null || clash.id == id) { "\"$clean\" is already in the list." }
            financeDao.renameExpenseCategory(id, clean)
            financeDao.renameCategoryOnExpenses(category.name, clean)
            financeDao.getExpensesByCategorySync(clean).forEach { syncExpenseCashRow(it, createIfMissing = false) }
            audit(createdBy, "EXPENSE_CATEGORY_RENAMED", id, "Renamed expense category '${category.name}' to '$clean'")
        }
    }

    /** Only a category no expense uses can be deleted. */
    suspend fun deleteExpenseCategory(id: Long, createdBy: String = "Admin"): Result<Unit> = runCatching {
        database.withTransaction {
            val category = financeDao.getExpenseCategoryById(id) ?: throw IllegalArgumentException("This category no longer exists.")
            val used = financeDao.countExpensesInCategory(category.name)
            require(used == 0) {
                "\"${category.name}\" is used by $used expense${if (used == 1) "" else "s"}, so it can't be deleted. You can rename it."
            }
            financeDao.deleteExpenseCategoryById(id)
            audit(createdBy, "EXPENSE_CATEGORY_DELETED", id, "Deleted expense category '${category.name}'")
        }
    }

    private fun cleanCategoryName(name: String): String {
        val clean = name.trim().replace(Regex("\\s+"), " ")
        require(clean.isNotEmpty()) { "Type a category name." }
        require(clean.length <= 40) { "Keep the name under 40 letters." }
        return clean
    }

    // ---------------------------------------------------------------------------------------------
    // Cash book: entries typed by hand
    // ---------------------------------------------------------------------------------------------

    /** Owner adds change money (IN) or takes money out (OUT); type MANUAL_ADJUSTMENT. */
    suspend fun addManualCashEntry(
        flowDirection: String,
        paymentMethod: String,
        amount: Double,
        note: String,
        date: Long = System.currentTimeMillis(),
        createdBy: String = "Admin"
    ): Result<Long> = runCatching {
        val direction = flowDirection.trim().uppercase()
        require(direction == CashEntryType.IN || direction == CashEntryType.OUT) { "Choose money in or money out." }
        val value = cleanAmount(amount)
        val method = PaymentMethods.normalize(paymentMethod)
        database.withTransaction {
            val label = CashEntryType.label(CashEntryType.MANUAL_ADJUSTMENT, direction)
            val id = financeDao.insertCashTransaction(
                CashRegisterTransactionEntity(
                    type = CashEntryType.MANUAL_ADJUSTMENT,
                    amount = value,
                    flowDirection = direction,
                    paymentMethod = method,
                    referenceText = label,
                    notes = note.trim(),
                    date = date
                )
            )
            audit(createdBy, "CASH_MANUAL_ENTRY", id, "$label ${money(value)} (${PaymentMethods.label(method)}) ${note.trim()}".trim())
            id
        }
    }

    /**
     * Money a method held when the shop started using the app (type OPENING_CASH), once per method.
     * It is dated just before that method's first entry, so every later running balance includes it.
     */
    suspend fun setOpeningBalance(
        amount: Double,
        paymentMethod: String = PaymentMethods.CASH,
        note: String = "",
        createdBy: String = "Admin"
    ): Result<Long> = runCatching {
        val value = cleanAmount(amount)
        val method = PaymentMethods.normalize(paymentMethod)
        database.withTransaction {
            require(financeDao.countOpeningEntriesForMethod(method) == 0) {
                "${PaymentMethods.label(method)} already has an opening balance."
            }
            val now = System.currentTimeMillis()
            val earliest = financeDao.getEarliestCashDateForMethod(method)
            val date = if (earliest != null && earliest <= now) earliest - 1 else now
            val id = financeDao.insertCashTransaction(
                CashRegisterTransactionEntity(
                    type = CashEntryType.OPENING_CASH,
                    amount = value,
                    flowDirection = CashEntryType.IN,
                    paymentMethod = method,
                    referenceText = "Opening balance",
                    notes = note.trim(),
                    date = date
                )
            )
            audit(createdBy, "CASH_OPENING_SET", id, "Set opening balance of ${PaymentMethods.label(method)} to ${money(value)}")
            id
        }
    }

    /**
     * Moves money between methods (e.g. Cash to Bank deposit): an OUT row and an IN row of type TRANSFER,
     * whose referenceIds point at each other. The total of all money stays the same. Returns the OUT row id.
     */
    suspend fun moveMoney(
        fromMethod: String,
        toMethod: String,
        amount: Double,
        note: String,
        date: Long = System.currentTimeMillis(),
        createdBy: String = "Admin"
    ): Result<Long> = runCatching {
        val from = PaymentMethods.normalize(fromMethod)
        val to = PaymentMethods.normalize(toMethod)
        require(from != to) { "Choose two different places to move the money between." }
        val value = cleanAmount(amount)
        database.withTransaction {
            val text = "${PaymentMethods.label(from)} → ${PaymentMethods.label(to)}"
            val outRow = CashRegisterTransactionEntity(
                type = CashEntryType.TRANSFER,
                amount = value,
                flowDirection = CashEntryType.OUT,
                paymentMethod = from,
                referenceText = text,
                notes = note.trim(),
                date = date
            )
            val outId = financeDao.insertCashTransaction(outRow)
            val inId = financeDao.insertCashTransaction(
                outRow.copy(flowDirection = CashEntryType.IN, paymentMethod = to, referenceId = outId)
            )
            financeDao.updateCashTransaction(outRow.copy(id = outId, referenceId = inId))
            audit(createdBy, "CASH_MOVED", outId, "Moved ${money(value)} $text ${note.trim()}".trim())
            outId
        }
    }

    /** Removes an entry typed in the cash book (both halves of a "move money"). Other rows change where they were made. */
    suspend fun deleteManualCashEntry(id: Long, createdBy: String = "Admin"): Result<Unit> = runCatching {
        database.withTransaction {
            val row = financeDao.getCashTransactionById(id) ?: throw IllegalArgumentException("This entry no longer exists.")
            require(CashEntryType.isManual(row.type)) {
                val where = when (row.type) {
                    CashEntryType.EXPENSE -> "Expenses"
                    CashEntryType.SUPPLIER_PAYMENT -> "the supplier's page"
                    CashEntryType.CUSTOMER_COLLECTION -> "the customer's page"
                    else -> "the sale or purchase it came from"
                }
                "This ${CashEntryType.label(row.type, row.flowDirection).lowercase()} entry can only be changed from $where."
            }
            financeDao.deleteCashTransactionById(row.id)
            if (row.type == CashEntryType.TRANSFER && row.referenceId > 0) {
                val other = financeDao.getCashTransactionById(row.referenceId)
                if (other != null && other.type == CashEntryType.TRANSFER && other.referenceId == row.id) {
                    financeDao.deleteCashTransactionById(other.id)
                }
            }
            audit(
                createdBy, "CASH_ENTRY_DELETED", row.id,
                "Deleted ${CashEntryType.label(row.type, row.flowDirection)} ${money(row.amount)} (${PaymentMethods.label(row.paymentMethod)})"
            )
        }
    }

    private fun cleanAmount(amount: Double): Double {
        require(amount.isFinite() && amount > 0.0) { "Amount must be more than 0." }
        val rounded = roundTaka(amount)
        require(rounded > 0.0) { "Amount must be more than 0." }
        require(rounded < 1_000_000_000.0) { "That amount is too large." }
        return rounded
    }

    private fun orderBalances(rows: List<PaymentMethodBalance>): List<PaymentMethodBalance> {
        val byMethod = rows.groupBy { PaymentMethods.normalize(it.paymentMethod) }
            .mapValues { (_, list) -> roundTaka(list.sumOf { it.balance }) }
        val standard = PaymentMethods.ALL.map { PaymentMethodBalance(it, byMethod[it] ?: 0.0) }
        val others = byMethod.keys.filter { it !in PaymentMethods.ALL }.sorted()
            .map { PaymentMethodBalance(it, byMethod.getValue(it)) }
        return standard + others
    }

    private fun money(amount: Double): String = com.example.core.utils.CurrencyUtils.formatBDT(amount)

    private suspend fun audit(createdBy: String, action: String, recordId: Long, description: String) {
        auditDao.insertAuditLog(
            AuditLogEntity(
                userRole = createdBy,
                action = action,
                module = "FINANCE",
                recordId = recordId.toString(),
                description = description
            )
        )
    }

    fun getExpensesBetween(start: Long, end: Long) = financeDao.getExpensesBetween(start, end)
    fun getTotalExpensesBetween(start: Long, end: Long) = financeDao.getTotalExpensesBetween(start, end)
    fun getTotalCustomerCollectionBetween(start: Long, end: Long) = financeDao.getTotalCustomerCollectionBetween(start, end)
    fun getPaymentsByCustomer(customerId: Long) = financeDao.getPaymentsByCustomer(customerId)
    fun getPaymentsBySupplier(supplierId: Long) = financeDao.getPaymentsBySupplier(supplierId)
}

data class StockDiscrepancy(
    val productId: Long,
    val productName: String,
    val cachedStock: Int,
    val ledgerStock: Int,
    val discrepancy: Int
)

data class SerializedStockDiscrepancy(
    val productId: Long,
    val productName: String,
    val cachedStock: Int,
    val serialsInStockCount: Int,
    val discrepancy: Int
)

data class NegativeStockItem(
    val productId: Long,
    val productName: String,
    val variantId: Long?,
    val currentStock: Int
)

data class StockIntegrityReport(
    val negativeStockItems: List<NegativeStockItem>,
    val cachedVsLedgerDiscrepancies: List<StockDiscrepancy>,
    val serializedDiscrepancies: List<SerializedStockDiscrepancy>,
    val orphanedTransactionsCount: Int,
    val isHealthy: Boolean
)

class StockRepository(private val database: AppDatabase) {
    private val stockDao = database.stockDao()
    private val productDao = database.productDao()
    private val auditDao = database.settingsAndAuditDao()

    val allStockTransactions: Flow<List<StockTransactionEntity>> = stockDao.getAllStockTransactions()
    fun getTransactionsForProduct(productId: Long) = stockDao.getTransactionsForProduct(productId)
    fun getTransactionsBetween(start: Long, end: Long) = stockDao.getStockTransactionsBetween(start, end)

    suspend fun getAuthoritativeStock(productId: Long): Int = stockDao.calculateAuthoritativeStock(productId)

    suspend fun getAuthoritativeVariantStock(productId: Long, variantId: Long): Int =
        stockDao.calculateAuthoritativeVariantStock(productId, variantId)

    suspend fun adjustStock(
        productId: Long,
        quantityDelta: Int, // e.g. +5 or -2
        reason: String, // StockMovementType constants: ADJUSTMENT_MANUAL, ADJUSTMENT_FOUND, ADJUSTMENT_DAMAGE, ADJUSTMENT_LOST, INTERNAL_USE
        notes: String,
        variantId: Long = 0L,
        createdBy: String = "Admin"
    ): Result<Unit> = runCatching {
        database.withTransaction {
            val product = productDao.getProductById(productId)
                ?: throw IllegalStateException("Product not found")

            val previousStock = product.currentStock
            val newStock = previousStock + quantityDelta
            if (newStock < 0) {
                throw IllegalStateException("Stock cannot become negative. Current: $previousStock, Delta: $quantityDelta")
            }

            var previousVariantStock = 0
            var newVariantStock = 0
            if (variantId > 0) {
                val variant = database.productVariantDao().getVariantById(variantId)
                    ?: throw IllegalStateException("Variant $variantId not found for product $productId")
                if (variant.productId != productId) {
                    throw IllegalStateException("Variant $variantId does not belong to product $productId")
                }
                previousVariantStock = variant.currentStock
                newVariantStock = previousVariantStock + quantityDelta
                if (newVariantStock < 0) {
                    throw IllegalStateException("Variant stock cannot become negative. Current: $previousVariantStock, Delta: $quantityDelta")
                }
                database.productVariantDao().updateVariantStock(variantId, quantityDelta)
            }

            productDao.updateStockQuantity(productId, quantityDelta)

            stockDao.insertStockTransaction(
                StockTransactionEntity(
                    productId = productId,
                    productName = product.name,
                    variantId = variantId,
                    transactionType = reason,
                    quantityChange = quantityDelta,
                    previousStock = previousStock,
                    newStock = newStock,
                    unitCost = product.purchasePrice,
                    referenceType = "MANUAL_ADJUSTMENT",
                    createdBy = createdBy,
                    notes = notes
                )
            )

            auditDao.insertAuditLog(
                AuditLogEntity(
                    userRole = createdBy,
                    action = "STOCK_ADJUSTED",
                    module = "INVENTORY",
                    recordId = productId.toString(),
                    description = "Adjusted ${product.name} stock by $quantityDelta ($reason). Notes: $notes"
                )
            )
        }
    }

    suspend fun auditAndSyncProductStock(productId: Long): StockDiscrepancy? = database.withTransaction {
        val product = productDao.getProductById(productId) ?: return@withTransaction null
        val ledgerStock = stockDao.calculateAuthoritativeStock(productId)
        if (product.currentStock != ledgerStock) {
            val discrepancy = StockDiscrepancy(
                productId = productId,
                productName = product.name,
                cachedStock = product.currentStock,
                ledgerStock = ledgerStock,
                discrepancy = product.currentStock - ledgerStock
            )
            productDao.setStockQuantity(productId, ledgerStock)
            auditDao.insertAuditLog(
                AuditLogEntity(
                    userRole = "SYSTEM",
                    action = "STOCK_LEDGER_RESYNC",
                    module = "INVENTORY",
                    recordId = productId.toString(),
                    description = "Resynced cached stock for ${product.name} from ${product.currentStock} to ledger balance $ledgerStock"
                )
            )
            discrepancy
        } else {
            null
        }
    }

    suspend fun auditStockIntegrity(): StockIntegrityReport = database.withTransaction {
        val negativeProducts = productDao.getNegativeStockProducts().map {
            NegativeStockItem(it.id, it.name, null, it.currentStock)
        }
        val negativeVariants = database.productVariantDao().getNegativeStockVariants().map {
            NegativeStockItem(it.productId, it.variantName, it.id, it.currentStock)
        }
        val allNegative = negativeProducts + negativeVariants

        val allProducts = productDao.getAllProductsSync()
        val ledgerDiscrepancies = mutableListOf<StockDiscrepancy>()
        val serializedDiscrepancies = mutableListOf<SerializedStockDiscrepancy>()

        for (p in allProducts) {
            val ledgerStock = stockDao.calculateAuthoritativeStock(p.id)
            if (p.currentStock != ledgerStock) {
                ledgerDiscrepancies.add(
                    StockDiscrepancy(
                        productId = p.id,
                        productName = p.name,
                        cachedStock = p.currentStock,
                        ledgerStock = ledgerStock,
                        discrepancy = p.currentStock - ledgerStock
                    )
                )
            }

            if (p.trackingType != "QUANTITY") {
                val availableSerials = productDao.countAvailableSerialsForProductSync(p.id)
                if (p.currentStock != availableSerials) {
                    serializedDiscrepancies.add(
                        SerializedStockDiscrepancy(
                            productId = p.id,
                            productName = p.name,
                            cachedStock = p.currentStock,
                            serialsInStockCount = availableSerials,
                            discrepancy = p.currentStock - availableSerials
                        )
                    )
                }
            }
        }

        val allTx = stockDao.getAllStockTransactionsSync()
        val validProductIds = allProducts.map { it.id }.toSet()
        val orphanedCount = allTx.count { it.productId !in validProductIds }

        StockIntegrityReport(
            negativeStockItems = allNegative,
            cachedVsLedgerDiscrepancies = ledgerDiscrepancies,
            serializedDiscrepancies = serializedDiscrepancies,
            orphanedTransactionsCount = orphanedCount,
            isHealthy = allNegative.isEmpty() && ledgerDiscrepancies.isEmpty() && serializedDiscrepancies.isEmpty() && orphanedCount == 0
        )
    }

    suspend fun syncAllProductStocks(): List<StockDiscrepancy> = database.withTransaction {
        val allProducts = productDao.getAllProductsSync()
        val fixed = mutableListOf<StockDiscrepancy>()
        for (p in allProducts) {
            val ledgerStock = stockDao.calculateAuthoritativeStock(p.id)
            if (p.currentStock != ledgerStock) {
                val disc = StockDiscrepancy(p.id, p.name, p.currentStock, ledgerStock, p.currentStock - ledgerStock)
                productDao.setStockQuantity(p.id, ledgerStock)
                fixed.add(disc)
            }
        }
        fixed
    }
}
