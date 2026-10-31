package com.example.data.repository

import androidx.room.withTransaction
import com.example.data.local.database.AppDatabase
import com.example.data.local.entities.AuditLogEntity
import com.example.data.local.entities.PurchaseEntity
import com.example.data.local.entities.SupplierEntity
import com.example.data.local.entities.SupplierLedgerEntryEntity
import kotlinx.coroutines.flow.Flow
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong
import com.example.sync.DocNumbers

internal fun roundMoney(value: Double): Double = (value * 100.0).roundToLong() / 100.0

object SupplierLedgerSign {
    const val TYPE_PURCHASE = "PURCHASE"
    const val TYPE_PURCHASE_RETURN = "PURCHASE_RETURN"
    const val TYPE_SUPPLIER_PAYMENT = "SUPPLIER_PAYMENT"
    const val TYPE_SUPPLIER_REFUND = "SUPPLIER_REFUND"
    const val TYPE_OPENING_BALANCE = "OPENING_BALANCE"
    const val TYPE_DEBIT_ADJUSTMENT = "DEBIT_ADJUSTMENT"
    const val TYPE_CREDIT_ADJUSTMENT = "CREDIT_ADJUSTMENT"

    /**
     * Required transaction signs:
     * PURCHASE          = positive (+)
     * PURCHASE_RETURN   = negative (-)
     * SUPPLIER_PAYMENT  = negative (-)
     * SUPPLIER_REFUND   = positive (+)  (money the supplier hands back to the shop)
     * OPENING_BALANCE   = positive (+)
     * DEBIT_ADJUSTMENT  = positive (+)
     * CREDIT_ADJUSTMENT = negative (-)
     */
    fun getSignedAmount(type: String, amount: Double): Double {
        val absVal = abs(amount)
        return when (type) {
            TYPE_PURCHASE, TYPE_OPENING_BALANCE, TYPE_DEBIT_ADJUSTMENT, TYPE_SUPPLIER_REFUND -> absVal
            TYPE_PURCHASE_RETURN, TYPE_SUPPLIER_PAYMENT, TYPE_CREDIT_ADJUSTMENT -> -absVal
            else -> absVal
        }
    }
}

data class SupplierFinancialSummary(
    val openingBalance: Double = 0.0,
    val totalPurchases: Double = 0.0,
    val totalReturns: Double = 0.0,
    val totalPaid: Double = 0.0,
    val totalRefunds: Double = 0.0,
    val debitAdjustments: Double = 0.0,
    val creditAdjustments: Double = 0.0,
    val currentDue: Double = 0.0
)

data class SupplierLedgerEntry(
    val id: Long = 0,
    val date: Long,
    val reference: String,
    val transactionType: String,
    val description: String,
    val amount: Double,
    val signedAmount: Double,
    val debit: Double, // Reduces payable (Payment, Return, Credit Adj)
    val credit: Double, // Increases payable (Purchase, Opening Due, Supplier Refund, Debit Adj)
    val runningBalance: Double // Cumulative due owed to supplier (+ owed, 0 settled, - advance)
)

class SupplierRepository(private val database: AppDatabase) {
    private val supplierDao = database.supplierDao()
    private val supplierLedgerDao = database.supplierLedgerDao()
    private val auditDao = database.settingsAndAuditDao()

    val allSuppliers: Flow<List<SupplierEntity>> = supplierDao.getAllSuppliers()
    val totalSupplierDue: Flow<Double?> = supplierDao.getTotalSupplierDue()

    fun searchSuppliers(query: String): Flow<List<SupplierEntity>> = supplierDao.searchSuppliers(query)
    fun observeSupplierById(id: Long): Flow<SupplierEntity?> = supplierDao.observeSupplierById(id)
    suspend fun getSupplierById(id: Long): SupplierEntity? = supplierDao.getSupplierById(id)

    suspend fun generateNextSupplierCode(): String {
        DocNumbers.next(database, DocNumbers.Series.SUPPLIER_CODE, { till, n -> DocNumbers.supplierCode(till, n) }) {
            supplierDao.getSupplierByCode(it) != null
        }?.let { return it }
        val maxId = supplierDao.getMaxSupplierId() ?: 0L
        val count = supplierDao.getSupplierCount()
        val nextSeq = maxOf(maxId + 1, (count + 1).toLong())
        var candidate = String.format(Locale.US, "SUP-%04d", nextSeq)
        var offset = 0
        while (supplierDao.getSupplierByCode(candidate) != null) {
            offset++
            candidate = String.format(Locale.US, "SUP-%04d", nextSeq + offset)
        }
        return candidate
    }

    /**
     * Atomically records an authoritative transaction in the supplier ledger
     * and updates the supplier's cached currentDue.
     */
    suspend fun recordLedgerTransaction(
        supplierId: Long,
        transactionType: String,
        amount: Double,
        referenceId: Long = 0,
        referenceNumber: String = "",
        description: String = "",
        createdBy: String = "Admin",
        timestamp: Long = System.currentTimeMillis()
    ): SupplierLedgerEntryEntity {
        val signedAmount = SupplierLedgerSign.getSignedAmount(transactionType, amount)
        val currentAuthoritativeBalance = supplierLedgerDao.calculateAuthoritativeBalance(supplierId)
        val newRunningBalance = currentAuthoritativeBalance + signedAmount

        val entry = SupplierLedgerEntryEntity(
            supplierId = supplierId,
            transactionType = transactionType,
            referenceId = referenceId,
            referenceNumber = referenceNumber,
            amount = abs(amount),
            signedAmount = signedAmount,
            runningBalance = newRunningBalance,
            description = description,
            createdBy = createdBy,
            createdAt = timestamp
        )

        supplierLedgerDao.insertLedgerEntry(entry)

        // Keep SupplierEntity.currentDue as an authoritative derived cache
        supplierDao.setSupplierDue(supplierId, newRunningBalance, timestamp)

        return entry
    }

    /**
     * Recalculates the authoritative balance directly from the ledger and synchronizes the cache.
     */
    suspend fun recalculateAndSyncSupplierBalance(supplierId: Long): Double {
        val authoritativeBalance = supplierLedgerDao.calculateAuthoritativeBalance(supplierId)
        supplierDao.setSupplierDue(supplierId, authoritativeBalance, System.currentTimeMillis())
        return authoritativeBalance
    }

    suspend fun saveSupplier(supplier: SupplierEntity): Result<Long> = runCatching {
        // Validate
        require(supplier.name.isNotBlank()) { "Supplier name is required" }
        require(supplier.phone.isNotBlank()) { "Phone number is required" }

        val code = if (supplier.supplierCode.isBlank()) {
            generateNextSupplierCode()
        } else {
            val existing = supplierDao.getSupplierByCode(supplier.supplierCode)
            if (existing != null && existing.id != supplier.id) {
                throw IllegalArgumentException("Supplier code '${supplier.supplierCode}' already exists.")
            }
            supplier.supplierCode
        }

        database.withTransaction {
            if (supplier.id == 0L) {
                // New supplier creation
                val toSave = supplier.copy(
                    supplierCode = code,
                    currentDue = supplier.openingDue, // Initial cached balance equals opening balance
                    updatedAt = System.currentTimeMillis()
                )
                val newId = supplierDao.insertSupplier(toSave)

                // Opening due (+) is owed to the supplier; a negative opening balance is an advance already paid to them
                if (supplier.openingDue != 0.0) {
                    val isAdvance = supplier.openingDue < 0
                    recordLedgerTransaction(
                        supplierId = newId,
                        transactionType = if (isAdvance) SupplierLedgerSign.TYPE_CREDIT_ADJUSTMENT else SupplierLedgerSign.TYPE_OPENING_BALANCE,
                        amount = abs(supplier.openingDue),
                        referenceId = newId,
                        referenceNumber = "OPENING",
                        description = if (isAdvance) "Opening advance paid to supplier" else "Opening balance carried forward",
                        createdBy = "Admin",
                        timestamp = supplier.createdAt
                    )
                }

                auditDao.insertAuditLog(
                    AuditLogEntity(
                        userRole = "ADMIN",
                        action = "SUPPLIER_CREATED",
                        module = "SUPPLIERS",
                        recordId = code,
                        description = "Created supplier ${supplier.name} ($code) with opening due ৳${supplier.openingDue}"
                    )
                )

                newId
            } else {
                // Update existing supplier
                val existing = supplierDao.getSupplierById(supplier.id)
                    ?: throw IllegalArgumentException("Supplier not found")

                val openingDifference = supplier.openingDue - existing.openingDue

                val toSave = supplier.copy(
                    supplierCode = code,
                    currentDue = existing.currentDue + openingDifference,
                    isActive = existing.isActive,
                    createdAt = existing.createdAt,
                    updatedAt = System.currentTimeMillis()
                )
                supplierDao.updateSupplier(toSave)

                // If opening balance changed, record an adjustment in the ledger
                if (openingDifference != 0.0) {
                    val isPositive = openingDifference > 0
                    val type = if (isPositive) SupplierLedgerSign.TYPE_DEBIT_ADJUSTMENT else SupplierLedgerSign.TYPE_CREDIT_ADJUSTMENT
                    recordLedgerTransaction(
                        supplierId = supplier.id,
                        transactionType = type,
                        amount = abs(openingDifference),
                        referenceId = supplier.id,
                        referenceNumber = "ADJ-OPENING",
                        description = "Opening balance adjustment from ৳${existing.openingDue} to ৳${supplier.openingDue}",
                        createdBy = "Admin"
                    )
                }

                // Resync to guarantee 100% cache precision
                recalculateAndSyncSupplierBalance(supplier.id)

                auditDao.insertAuditLog(
                    AuditLogEntity(
                        userRole = "ADMIN",
                        action = "SUPPLIER_UPDATED",
                        module = "SUPPLIERS",
                        recordId = code,
                        description = "Updated supplier ${supplier.name} ($code)"
                    )
                )

                supplier.id
            }
        }
    }

    /**
     * Record a manual adjustment for a supplier.
     * Debit Adjustment (+) = increases amount shop owes to supplier.
     * Credit Adjustment (-) = decreases amount shop owes to supplier.
     */
    suspend fun recordAdjustment(
        supplierId: Long,
        amount: Double,
        isDebit: Boolean,
        reason: String,
        createdBy: String = "Admin"
    ): Result<Long> = runCatching {
        require(amount > 0) { "Adjustment amount must be positive" }
        val supplier = supplierDao.getSupplierById(supplierId)
            ?: throw IllegalArgumentException("Supplier not found")

        database.withTransaction {
            val type = if (isDebit) SupplierLedgerSign.TYPE_DEBIT_ADJUSTMENT else SupplierLedgerSign.TYPE_CREDIT_ADJUSTMENT
            val refNo = String.format(Locale.US, "ADJ-%d", System.currentTimeMillis() % 1000000)
            val entry = recordLedgerTransaction(
                supplierId = supplierId,
                transactionType = type,
                amount = amount,
                referenceId = 0,
                referenceNumber = refNo,
                description = reason.ifBlank { if (isDebit) "Debit adjustment (liability increase)" else "Credit adjustment (liability decrease)" },
                createdBy = createdBy
            )

            auditDao.insertAuditLog(
                AuditLogEntity(
                    userRole = "ADMIN",
                    action = if (isDebit) "SUPPLIER_DEBIT_ADJUSTMENT" else "SUPPLIER_CREDIT_ADJUSTMENT",
                    module = "SUPPLIERS",
                    recordId = refNo,
                    description = "${if (isDebit) "Debit (+)" else "Credit (-)"} adjustment of ৳$amount for ${supplier.name}: $reason"
                )
            )

            entry.id
        }
    }

    /**
     * Record a supplier refund.
     * Supplier Refund (+) = supplier pays cash back to the shop, using up the credit they owed the shop.
     */
    suspend fun recordSupplierRefund(
        supplierId: Long,
        amount: Double,
        referenceNumber: String = "",
        reason: String = "",
        createdBy: String = "Admin"
    ): Result<Long> = runCatching {
        require(amount > 0) { "Refund amount must be positive" }
        val supplier = supplierDao.getSupplierById(supplierId)
            ?: throw IllegalArgumentException("Supplier not found")

        database.withTransaction {
            val refNo = referenceNumber.ifBlank { String.format(Locale.US, "REF-%d", System.currentTimeMillis() % 1000000) }
            val entry = recordLedgerTransaction(
                supplierId = supplierId,
                transactionType = SupplierLedgerSign.TYPE_SUPPLIER_REFUND,
                amount = amount,
                referenceId = 0,
                referenceNumber = refNo,
                description = reason.ifBlank { "Supplier refund received" },
                createdBy = createdBy
            )

            auditDao.insertAuditLog(
                AuditLogEntity(
                    userRole = "ADMIN",
                    action = "SUPPLIER_REFUND_RECORDED",
                    module = "SUPPLIERS",
                    recordId = refNo,
                    description = "Recorded refund of ৳$amount from supplier ${supplier.name}"
                )
            )

            entry.id
        }
    }

    /**
     * Recomputes paid/due/status of every active purchase invoice of a supplier from the ledger.
     *
     * Payments recorded against a purchase (e.g. the down payment) settle that purchase first,
     * less any cash the supplier refunded on its returns. Everything else is applied oldest-first:
     * the supplier's outstanding ledger balance is spread over the newest unpaid invoices.
     * Must be called inside the caller's transaction after the ledger entries are written.
     */
    suspend fun reallocatePurchasePayments(supplierId: Long) {
        val purchaseDao = database.purchaseDao()
        val purchases = purchaseDao.getPurchasesBySupplierSync(supplierId)
            .sortedWith(compareBy<PurchaseEntity>({ it.purchaseDate }, { it.id }))
        if (purchases.isEmpty()) return

        val returns = database.purchaseReturnDao().getReturnsBySupplierSync(supplierId)
        val returnedByPurchase = returns.groupBy { it.purchaseId }.mapValues { (_, r) -> r.sumOf { it.totalAmount } }
        val refundedByPurchase = returns.groupBy { it.purchaseId }.mapValues { (_, r) -> r.sumOf { it.refundAmount } }
        val linkedPaidByPurchase = database.supplierPaymentDao().getPaymentsBySupplierSync(supplierId)
            .filter { it.purchaseId > 0 }
            .groupBy { it.purchaseId }
            .mapValues { (_, p) -> p.sumOf { it.amount } }

        val net = purchases.associate { p -> p.id to roundMoney((p.totalAmount - (returnedByPurchase[p.id] ?: 0.0)).coerceAtLeast(0.0)) }
        val unpaidAfterLinked = purchases.associate { p ->
            val linked = ((linkedPaidByPurchase[p.id] ?: 0.0) - (refundedByPurchase[p.id] ?: 0.0)).coerceAtLeast(0.0)
            p.id to (net.getValue(p.id) - linked).coerceAtLeast(0.0)
        }

        var outstanding = supplierLedgerDao.calculateAuthoritativeBalance(supplierId).coerceAtLeast(0.0)
        val now = System.currentTimeMillis()
        for (p in purchases.asReversed()) {
            val due = roundMoney(minOf(unpaidAfterLinked.getValue(p.id), outstanding))
            outstanding = (outstanding - due).coerceAtLeast(0.0)
            val total = net.getValue(p.id)
            val paid = roundMoney((total - due).coerceAtLeast(0.0))
            val status = when {
                due <= 0.005 -> "PAID"
                paid > 0.005 -> "PARTIAL"
                else -> "DUE"
            }
            if (abs(p.paidAmount - paid) > 0.005 || abs(p.dueAmount - due) > 0.005 || p.paymentStatus != status) {
                purchaseDao.setPurchasePaymentState(p.id, paid, due, status, now)
            }
        }
    }

    /**
     * Computes the authoritative supplier financial summary directly from the supplier ledger.
     * Formula:
     * Supplier Balance = Opening Balance + Purchases - Purchase Returns - Supplier Payments + Supplier Refunds + Debit Adjustments - Credit Adjustments
     */
    suspend fun getSupplierFinancialSummary(supplierId: Long): SupplierFinancialSummary {
        val supplier = supplierDao.getSupplierById(supplierId) ?: return SupplierFinancialSummary()
        val entries = supplierLedgerDao.getLedgerEntriesForSupplierSync(supplierId)

        var openingBalance = 0.0
        var totalPurchases = 0.0
        var totalReturns = 0.0
        var totalPaid = 0.0
        var totalRefunds = 0.0
        var debitAdjustments = 0.0
        var creditAdjustments = 0.0

        for (e in entries) {
            when (e.transactionType) {
                SupplierLedgerSign.TYPE_OPENING_BALANCE -> openingBalance += e.amount
                SupplierLedgerSign.TYPE_PURCHASE -> totalPurchases += e.amount
                SupplierLedgerSign.TYPE_PURCHASE_RETURN -> totalReturns += e.amount
                SupplierLedgerSign.TYPE_SUPPLIER_PAYMENT -> totalPaid += e.amount
                SupplierLedgerSign.TYPE_SUPPLIER_REFUND -> totalRefunds += e.amount
                SupplierLedgerSign.TYPE_DEBIT_ADJUSTMENT -> debitAdjustments += e.amount
                SupplierLedgerSign.TYPE_CREDIT_ADJUSTMENT -> creditAdjustments += e.amount
            }
        }

        // Authoritative Balance = Opening + Purchases - Returns - Payments + Refunds + Debit Adj - Credit Adj
        val authoritativeBalance = openingBalance + totalPurchases - totalReturns - totalPaid + totalRefunds + debitAdjustments - creditAdjustments

        // Keep cached balance in sync if any drift existed
        if (abs(supplier.currentDue - authoritativeBalance) > 0.001) {
            supplierDao.setSupplierDue(supplierId, authoritativeBalance)
        }

        return SupplierFinancialSummary(
            openingBalance = openingBalance,
            totalPurchases = totalPurchases,
            totalReturns = totalReturns,
            totalPaid = totalPaid,
            totalRefunds = totalRefunds,
            debitAdjustments = debitAdjustments,
            creditAdjustments = creditAdjustments,
            currentDue = authoritativeBalance
        )
    }

    /**
     * Returns the authoritative chronological ledger entries for a supplier directly from the ledger table.
     */
    suspend fun getSupplierLedger(supplierId: Long): List<SupplierLedgerEntry> {
        val entries = supplierLedgerDao.getLedgerEntriesForSupplierSync(supplierId)

        return entries.map { entry ->
            val isCreditToSupplier = entry.signedAmount > 0 // increases payable
            val isDebitToSupplier = entry.signedAmount < 0 // decreases payable

            SupplierLedgerEntry(
                id = entry.id,
                date = entry.createdAt,
                reference = entry.referenceNumber,
                transactionType = entry.transactionType,
                description = entry.description,
                amount = entry.amount,
                signedAmount = entry.signedAmount,
                credit = if (isCreditToSupplier) entry.amount else 0.0,
                debit = if (isDebitToSupplier) entry.amount else 0.0,
                runningBalance = entry.runningBalance
            )
        }
    }
}
