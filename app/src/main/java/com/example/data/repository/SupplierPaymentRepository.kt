package com.example.data.repository

import androidx.room.withTransaction
import com.example.data.local.database.AppDatabase
import com.example.data.local.entities.AuditLogEntity
import com.example.data.local.entities.CashRegisterTransactionEntity
import com.example.data.local.entities.PaymentTransactionEntity
import com.example.data.local.entities.SupplierPaymentEntity
import kotlinx.coroutines.flow.Flow
import java.util.Calendar
import java.util.Locale
import com.example.sync.DocNumbers

class SupplierPaymentRepository(private val database: AppDatabase) {
    private val paymentDao = database.supplierPaymentDao()
    private val supplierDao = database.supplierDao()
    private val purchaseDao = database.purchaseDao()
    private val financeDao = database.financeDao()
    private val auditDao = database.settingsAndAuditDao()
    private val supplierRepository = SupplierRepository(database)

    val allPayments: Flow<List<SupplierPaymentEntity>> = paymentDao.getAllPayments()

    fun getPaymentsBySupplier(supplierId: Long): Flow<List<SupplierPaymentEntity>> =
        paymentDao.getPaymentsBySupplier(supplierId)

    suspend fun generatePaymentNumber(): String {
        val year = Calendar.getInstance().get(Calendar.YEAR)
        DocNumbers.next(database, DocNumbers.Series.SUPPLIER_PAYMENT, { till, n -> DocNumbers.supplierPayment(year, till, n) }) {
            paymentDao.getPaymentByNumber(it) != null
        }?.let { return it }
        val maxId = paymentDao.getMaxPaymentId() ?: 0L
        val count = paymentDao.getPaymentCount()
        val seq = maxOf(maxId + 1, (count + 1).toLong())
        var candidate = String.format(Locale.US, "SPAY-%d-%06d", year, seq)
        var offset = 0
        while (paymentDao.getPaymentByNumber(candidate) != null) {
            offset++
            candidate = String.format(Locale.US, "SPAY-%d-%06d", year, seq + offset)
        }
        return candidate
    }

    suspend fun recordSupplierPayment(
        supplierId: Long,
        amount: Double,
        paymentMethod: String,
        referenceNumber: String = "",
        purchaseId: Long = 0,
        notes: String = "",
        createdBy: String = "Admin"
    ): Result<Long> = runCatching {
        require(amount > 0) { "Payment amount must be greater than zero." }
        val supplier = supplierDao.getSupplierById(supplierId)
            ?: throw IllegalArgumentException("Supplier not found.")
        if (purchaseId > 0) {
            val purchase = purchaseDao.getPurchaseById(purchaseId)
            require(purchase != null && purchase.supplierId == supplierId && purchase.status != "CANCELLED") {
                "The selected purchase does not belong to this supplier."
            }
        }

        database.withTransaction {
            val paymentNumber = generatePaymentNumber()

            val paymentEntity = SupplierPaymentEntity(
                paymentNumber = paymentNumber,
                supplierId = supplierId,
                supplierName = supplier.name,
                purchaseId = purchaseId,
                paymentDate = System.currentTimeMillis(),
                amount = amount,
                paymentMethod = paymentMethod,
                referenceNumber = referenceNumber,
                notes = notes,
                createdBy = createdBy,
                createdAt = System.currentTimeMillis()
            )

            val paymentId = paymentDao.insertPayment(paymentEntity)

            // Authoritative Supplier Ledger entry for Supplier Payment (-amount)
            supplierRepository.recordLedgerTransaction(
                supplierId = supplierId,
                transactionType = SupplierLedgerSign.TYPE_SUPPLIER_PAYMENT,
                amount = amount,
                referenceId = paymentId,
                referenceNumber = paymentNumber,
                description = notes.ifBlank { "Supplier payment voucher #${paymentNumber} via $paymentMethod" },
                createdBy = createdBy
            )

            // Settle purchase invoices (a linked purchase first, then oldest-first)
            supplierRepository.reallocatePurchasePayments(supplierId)

            // Record financial transaction
            financeDao.insertPayment(
                PaymentTransactionEntity(
                    transactionType = "SUPPLIER_PAYMENT",
                    supplierId = supplierId,
                    purchaseId = purchaseId,
                    amount = amount,
                    paymentMethod = paymentMethod,
                    referenceNo = referenceNumber.ifBlank { paymentNumber },
                    notes = notes.ifBlank { "Supplier payment to ${supplier.name}" }
                )
            )

            // Record cash out transaction
            financeDao.insertCashTransaction(
                CashRegisterTransactionEntity(
                    type = "SUPPLIER_PAYMENT",
                    amount = amount,
                    flowDirection = "OUT",
                    paymentMethod = paymentMethod,
                    referenceId = paymentId,
                    referenceText = "Payment #$paymentNumber",
                    notes = "Paid to ${supplier.name}"
                )
            )

            // Audit log
            auditDao.insertAuditLog(
                AuditLogEntity(
                    userRole = "ADMIN",
                    action = "SUPPLIER_PAYMENT_RECORDED",
                    module = "SUPPLIERS",
                    recordId = paymentNumber,
                    description = "Recorded payment of ৳$amount to supplier ${supplier.name} ($paymentNumber) via $paymentMethod"
                )
            )

            paymentId
        }
    }
}
