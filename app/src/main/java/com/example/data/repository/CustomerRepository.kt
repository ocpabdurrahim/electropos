package com.example.data.repository

import androidx.room.withTransaction
import com.example.data.local.database.AppDatabase
import com.example.data.local.entities.AuditLogEntity
import com.example.data.local.entities.CashRegisterTransactionEntity
import com.example.data.local.entities.CustomerEntity
import com.example.data.local.entities.CustomerLedgerEntryEntity
import com.example.data.local.entities.CustomerPaymentEntity
import com.example.data.local.entities.PaymentTransactionEntity
import com.example.data.local.entities.SaleEntity
import kotlinx.coroutines.flow.Flow
import com.example.sync.DocNumbers

class CustomerRepository(private val database: AppDatabase) {
    private val customerDao = database.customerDao()
    private val customerLedgerDao = database.customerLedgerDao()
    private val customerPaymentDao = database.customerPaymentDao()
    private val settingsDao = database.settingsAndAuditDao()
    private val financeDao = database.financeDao()

    val allActiveCustomers: Flow<List<CustomerEntity>> = customerDao.getAllActiveCustomers()
    val allCustomers: Flow<List<CustomerEntity>> = customerDao.getAllCustomers()
    val totalCustomerDue: Flow<Double?> = customerDao.getTotalCustomerDue()

    fun observeCustomerById(id: Long): Flow<CustomerEntity?> = customerDao.observeCustomerById(id)
    suspend fun getCustomerById(id: Long): CustomerEntity? = customerDao.getCustomerById(id)
    suspend fun getCustomerByPhone(phone: String): CustomerEntity? = customerDao.getCustomerByPhone(phone)
    fun searchCustomers(query: String): Flow<List<CustomerEntity>> = customerDao.searchCustomers(query)

    fun getLedgerEntriesForCustomer(customerId: Long): Flow<List<CustomerLedgerEntryEntity>> =
        customerLedgerDao.getLedgerEntriesForCustomer(customerId)

    fun getPaymentsForCustomer(customerId: Long): Flow<List<CustomerPaymentEntity>> =
        customerPaymentDao.getPaymentsForCustomer(customerId)

    suspend fun generateNextCustomerCode(): String {
        val profile = settingsDao.getShopProfileSync()
        val prefix = profile?.customerCodePrefix?.ifBlank { "CUS-" } ?: "CUS-"
        DocNumbers.next(database, DocNumbers.Series.CUSTOMER_CODE, { till, n -> DocNumbers.customerCode(prefix, till, n) }) {
            customerDao.countCustomerCodeOccurrences(it) > 0
        }?.let { return it }
        val nextNum = profile?.customerNextNumber ?: 1L
        var code = String.format("%s%04d", prefix, nextNum)
        var counter = nextNum
        while (customerDao.countCustomerCodeOccurrences(code) > 0) {
            counter++
            code = String.format("%s%04d", prefix, counter)
        }
        return code
    }

    suspend fun saveCustomer(customer: CustomerEntity): Result<Long> = runCatching {
        database.withTransaction {
            require(customer.name.isNotBlank()) { "Customer name cannot be empty" }
            require(customer.phone.isNotBlank()) { "Customer phone cannot be empty" }

            val cleanPhone = customer.phone.trim()
            val existingByPhone = customerDao.getCustomerByPhone(cleanPhone)
            if (existingByPhone != null && existingByPhone.id != customer.id && existingByPhone.isActive) {
                throw IllegalArgumentException("Phone $cleanPhone already belongs to customer '${existingByPhone.name}' (${existingByPhone.customerCode}).")
            }

            if (customer.id == 0L) {
                // New Customer
                val finalCode = if (customer.customerCode.isBlank()) {
                    generateNextCustomerCode()
                } else {
                    val trimmed = customer.customerCode.trim()
                    if (customerDao.countCustomerCodeOccurrences(trimmed) > 0) {
                        throw IllegalArgumentException("Customer code '$trimmed' is already in use")
                    }
                    trimmed
                }

                val newCustomer = customer.copy(
                    customerCode = finalCode,
                    currentDue = customer.openingDue,
                    createdAt = System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis()
                )

                val customerId = customerDao.insertCustomer(newCustomer)
                settingsDao.incrementCustomerNumber()

                // Create Opening Balance Ledger Entry if openingDue != 0
                if (customer.openingDue != 0.0) {
                    val entry = CustomerLedgerEntryEntity(
                        customerId = customerId,
                        transactionType = "OPENING_BALANCE",
                        referenceId = customerId,
                        referenceNumber = "OPENING",
                        amount = kotlin.math.abs(customer.openingDue),
                        signedAmount = customer.openingDue,
                        runningBalance = customer.openingDue,
                        description = "Initial opening balance carried forward",
                        createdBy = "Admin",
                        createdAt = System.currentTimeMillis()
                    )
                    customerLedgerDao.insertLedgerEntry(entry)
                }

                settingsDao.insertAuditLog(
                    AuditLogEntity(
                        userRole = "ADMIN",
                        action = "CUSTOMER_CREATED",
                        module = "CUSTOMERS",
                        recordId = finalCode,
                        description = "Created customer '${newCustomer.name}' ($finalCode) with opening due ৳${customer.openingDue}"
                    )
                )

                customerId
            } else {
                // Update Existing Customer
                val existing = customerDao.getCustomerById(customer.id)
                    ?: throw IllegalArgumentException("Customer not found: ${customer.id}")

                val finalCode = customer.customerCode.trim().ifBlank { existing.customerCode }
                if (customerDao.countCustomerCodeOccurrences(finalCode, customer.id) > 0) {
                    throw IllegalArgumentException("Customer code '$finalCode' is already in use by another customer")
                }

                // If opening due changed, adjust ledger and balance
                var balanceAdjustment = 0.0
                if (existing.openingDue != customer.openingDue) {
                    balanceAdjustment = customer.openingDue - existing.openingDue
                    customerLedgerDao.insertLedgerEntry(
                        CustomerLedgerEntryEntity(
                            customerId = customer.id,
                            transactionType = "DEBIT_ADJUSTMENT",
                            referenceId = customer.id,
                            referenceNumber = "ADJ-OPENING",
                            amount = kotlin.math.abs(balanceAdjustment),
                            signedAmount = balanceAdjustment,
                            runningBalance = existing.currentDue + balanceAdjustment,
                            description = "Opening balance adjusted from ৳${existing.openingDue} to ৳${customer.openingDue}",
                            createdBy = "Admin",
                            createdAt = System.currentTimeMillis()
                        )
                    )
                }

                val updatedCustomer = customer.copy(
                    customerCode = finalCode,
                    phone = cleanPhone,
                    currentDue = existing.currentDue + balanceAdjustment,
                    isActive = existing.isActive,
                    createdAt = existing.createdAt,
                    updatedAt = System.currentTimeMillis()
                )

                customerDao.updateCustomer(updatedCustomer)
                if (balanceAdjustment != 0.0) reallocateSalePayments(customer.id)

                settingsDao.insertAuditLog(
                    AuditLogEntity(
                        userRole = "ADMIN",
                        action = "CUSTOMER_UPDATED",
                        module = "CUSTOMERS",
                        recordId = finalCode,
                        description = "Updated customer '${updatedCustomer.name}' ($finalCode)"
                    )
                )

                customer.id
            }
        }
    }

    suspend fun deactivateCustomer(customerId: Long): Result<Unit> = runCatching {
        database.withTransaction {
            val customer = customerDao.getCustomerById(customerId)
                ?: throw IllegalArgumentException("Customer not found: $customerId")

            customerDao.deactivateCustomer(customerId)

            settingsDao.insertAuditLog(
                AuditLogEntity(
                    userRole = "ADMIN",
                    action = "CUSTOMER_DEACTIVATED",
                    module = "CUSTOMERS",
                    recordId = customer.customerCode,
                    description = "Deactivated customer '${customer.name}' (${customer.customerCode})"
                )
            )
        }
    }

    suspend fun recordCustomerPayment(
        customerId: Long,
        amount: Double,
        paymentMethod: String,
        referenceNumber: String = "",
        notes: String = "",
        saleId: Long = 0L,
        paymentDate: Long = System.currentTimeMillis(),
        createdBy: String = "Admin"
    ): Result<Long> = runCatching {
        database.withTransaction {
            require(amount > 0) { "Payment amount must be greater than 0" }
            val customer = customerDao.getCustomerById(customerId)
                ?: throw IllegalArgumentException("Customer not found: $customerId")

            val profile = settingsDao.getShopProfileSync()
            val prefix = profile?.customerPaymentPrefix?.ifBlank { "CPAY-" } ?: "CPAY-"
            val paymentVoucherNo = DocNumbers.next(database, DocNumbers.Series.CUSTOMER_PAYMENT, { till, n -> DocNumbers.customerPayment(prefix, till, n) }) {
                customerPaymentDao.countPaymentNumberMatches(it) > 0
            } ?: run {
                var counter = profile?.customerPaymentNextNumber ?: 1L
                var number = String.format("%s%06d", prefix, counter)
                while (customerPaymentDao.countPaymentNumberMatches(number) > 0) {
                    counter++
                    number = String.format("%s%06d", prefix, counter)
                }
                number
            }
            settingsDao.incrementCustomerPaymentNumber()
            if (saleId > 0) {
                val sale = database.saleDao().getSaleById(saleId)
                require(sale != null && sale.customerId == customerId && sale.status != "CANCELLED") {
                    "The selected invoice does not belong to this customer."
                }
            }

            // 1. Insert CustomerPaymentEntity
            val paymentEntity = CustomerPaymentEntity(
                paymentNumber = paymentVoucherNo,
                customerId = customer.id,
                customerName = customer.name,
                saleId = saleId,
                paymentDate = paymentDate,
                amount = amount,
                paymentMethod = paymentMethod,
                referenceNumber = referenceNumber,
                notes = notes,
                createdBy = createdBy,
                createdAt = System.currentTimeMillis()
            )
            val paymentId = customerPaymentDao.insertPayment(paymentEntity)

            // 2. Insert CustomerLedgerEntryEntity
            val previousBalance = customer.currentDue
            val newBalance = previousBalance - amount
            val ledgerEntry = CustomerLedgerEntryEntity(
                customerId = customer.id,
                transactionType = "CUSTOMER_PAYMENT",
                referenceId = paymentId,
                referenceNumber = paymentVoucherNo,
                amount = amount,
                signedAmount = -amount, // Reduces customer debt
                runningBalance = newBalance,
                description = "Customer payment via $paymentMethod" + if (notes.isNotBlank()) " - $notes" else "",
                createdBy = createdBy,
                createdAt = paymentDate
            )
            customerLedgerDao.insertLedgerEntry(ledgerEntry)

            // 3. Update customer cached balance and settle invoices (linked invoice first, then oldest-first)
            customerDao.setCustomerBalance(customer.id, newBalance)
            reallocateSalePayments(customer.id)

            // 4. Record into Finance & Cash Register
            financeDao.insertPayment(
                PaymentTransactionEntity(
                    transactionType = "CUSTOMER_PAYMENT",
                    customerId = customer.id,
                    saleId = saleId,
                    amount = amount,
                    paymentMethod = paymentMethod,
                    referenceNo = paymentVoucherNo,
                    notes = notes,
                    transactionDate = paymentDate
                )
            )

            financeDao.insertCashTransaction(
                CashRegisterTransactionEntity(
                    type = "CUSTOMER_COLLECTION",
                    amount = amount,
                    flowDirection = "IN",
                    paymentMethod = paymentMethod,
                    referenceId = paymentId,
                    referenceText = "Payment #$paymentVoucherNo",
                    notes = "Collection from ${customer.name}",
                    date = paymentDate
                )
            )

            // 5. Audit Log
            settingsDao.insertAuditLog(
                AuditLogEntity(
                    userRole = createdBy,
                    action = "CUSTOMER_PAYMENT_CREATED",
                    module = "CUSTOMERS",
                    recordId = paymentVoucherNo,
                    description = "Recorded payment of ৳$amount from ${customer.name} via $paymentMethod (#$paymentVoucherNo)"
                )
            )

            paymentId
        }
    }

    /** Money taken against one sale: its down payment at the counter plus payments recorded for that invoice. */
    suspend fun paidAgainstSale(sale: SaleEntity): Double {
        val customerId = sale.customerId ?: return sale.paidAmount
        val downPayment = customerLedgerDao.getLedgerEntriesForCustomerSync(customerId)
            .filter { it.transactionType == "CUSTOMER_PAYMENT" && it.referenceId == sale.id && it.referenceNumber == "PAY-${sale.invoiceNumber}" }
            .sumOf { it.amount }
        val linkedPayments = customerPaymentDao.getPaymentsForCustomerSync(customerId)
            .filter { it.saleId == sale.id }
            .sumOf { it.amount }
        return downPayment + linkedPayments
    }

    /**
     * Recomputes paid/due/status of every active invoice of a customer from the ledger and re-syncs the cached due.
     * Money taken against an invoice settles it first (less cash refunded on its returns); everything else
     * is applied oldest-first, i.e. the outstanding ledger balance sits on the newest unpaid invoices.
     * Call inside the caller's transaction after the ledger entries are written.
     */
    suspend fun reallocateSalePayments(customerId: Long) {
        val saleDao = database.saleDao()
        val balance = customerLedgerDao.calculateCustomerBalance(customerId)
        customerDao.setCustomerBalance(customerId, balance)

        val sales = saleDao.getActiveSalesForCustomerSync(customerId)
        if (sales.isEmpty()) return
        val returns = database.salesReturnDao().getCompletedReturnsForCustomerSync(customerId)
        val returnedBySale = returns.groupBy { it.saleId }.mapValues { (_, r) -> r.sumOf { it.totalAmount } }
        val refundedBySale = returns.groupBy { it.saleId }.mapValues { (_, r) -> r.sumOf { it.refundAmount } }

        val ledger = customerLedgerDao.getLedgerEntriesForCustomerSync(customerId)
        val linkedPayments = customerPaymentDao.getPaymentsForCustomerSync(customerId).filter { it.saleId > 0 }

        var outstanding = balance.coerceAtLeast(0.0)
        val now = System.currentTimeMillis()
        for (sale in sales.asReversed()) {
            val net = roundMoney((sale.totalAmount - (returnedBySale[sale.id] ?: 0.0)).coerceAtLeast(0.0))
            val takenAtSale = ledger
                .filter { it.transactionType == "CUSTOMER_PAYMENT" && it.referenceId == sale.id && it.referenceNumber == "PAY-${sale.invoiceNumber}" }
                .sumOf { it.amount } + linkedPayments.filter { it.saleId == sale.id }.sumOf { it.amount }
            val linked = (takenAtSale - (refundedBySale[sale.id] ?: 0.0)).coerceAtLeast(0.0)
            val unpaid = (net - linked).coerceAtLeast(0.0)

            val due = roundMoney(minOf(unpaid, outstanding))
            outstanding = (outstanding - due).coerceAtLeast(0.0)
            val paid = roundMoney((net - due).coerceAtLeast(0.0))
            val status = when {
                due <= 0.005 -> "PAID"
                paid > 0.005 -> "PARTIAL"
                else -> "DUE"
            }
            if (kotlin.math.abs(sale.paidAmount - paid) > 0.005 || kotlin.math.abs(sale.dueAmount - due) > 0.005 || sale.paymentStatus != status) {
                saleDao.setSalePaymentState(sale.id, paid, due, status, now)
            }
        }
    }

    suspend fun getAuthoritativeCustomerBalance(customerId: Long): Double =
        customerLedgerDao.calculateCustomerBalance(customerId)

    suspend fun syncCustomerBalance(customerId: Long): Double = database.withTransaction {
        val authoritativeBalance = customerLedgerDao.calculateCustomerBalance(customerId)
        customerDao.setCustomerBalance(customerId, authoritativeBalance)
        authoritativeBalance
    }
}
