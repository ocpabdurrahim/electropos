package com.example.data.repository

import androidx.room.withTransaction
import com.example.data.local.database.AppDatabase
import com.example.data.local.entities.*
import kotlinx.coroutines.flow.Flow
import org.json.JSONArray
import java.util.Calendar
import com.example.sync.DocNumbers

data class SaleItemInput(
    val productId: Long,
    val productName: String,
    val variantId: Long = 0L,
    val quantity: Int,
    val unitPrice: Double,
    val discount: Double = 0.0,
    val tax: Double = 0.0,
    val trackingType: String = "QUANTITY",
    val selectedSerialImeiIds: List<Long> = emptyList(),
    val selectedSerialsOrImeis: List<String> = emptyList()
)

class SaleRepository(private val database: AppDatabase) {
    private val saleDao = database.saleDao()
    private val productDao = database.productDao()
    private val variantDao = database.productVariantDao()
    private val customerDao = database.customerDao()
    private val customerLedgerDao = database.customerLedgerDao()
    private val stockDao = database.stockDao()
    private val financeDao = database.financeDao()
    private val warrantyDao = database.warrantyDao()
    private val settingsDao = database.settingsAndAuditDao()
    private val salesReturnDao = database.salesReturnDao()

    val allSales: Flow<List<SaleEntity>> = saleDao.getAllSales()
    val totalSalesDue: Flow<Double?> = saleDao.getTotalSalesDue()

    suspend fun getSaleById(id: Long) = saleDao.getSaleById(id)
    suspend fun getSaleByInvoice(invoiceNumber: String) = saleDao.getSaleByInvoiceNumber(invoiceNumber)
    suspend fun getSaleItems(saleId: Long) = saleDao.getSaleItems(saleId)
    fun observeSaleItems(saleId: Long) = saleDao.observeSaleItems(saleId)
    fun searchSales(query: String) = saleDao.searchSales(query)
    fun getSalesBetween(startDate: Long, endDate: Long) = saleDao.getSalesBetween(startDate, endDate)
    fun getTotalSalesAmountBetween(startDate: Long, endDate: Long) = saleDao.getTotalSalesAmountBetween(startDate, endDate)
    fun getTotalProfitBetween(startDate: Long, endDate: Long) = saleDao.getTotalProfitBetween(startDate, endDate)
    fun getTopSellingProducts(limit: Int = 5) = saleDao.getTopSellingProducts(limit)

    suspend fun generateNextInvoiceNumber(): String {
        val profile = settingsDao.getShopProfileSync()
        val prefix = profile?.invoicePrefix?.ifBlank { "INV-" } ?: "INV-"
        val year = Calendar.getInstance().get(Calendar.YEAR)
        // Online: this till's own counter (INV-2026-B00042); offline the old format below
        DocNumbers.next(database, DocNumbers.Series.INVOICE, { till, n -> DocNumbers.invoice(prefix, year, till, n) }) {
            saleDao.countInvoicesMatching(it) > 0
        }?.let { return it }
        val nextNum = profile?.invoiceNextNumber ?: 1001L
        var invNum = String.format("%s%d-%06d", prefix, year, nextNum)
        var counter = nextNum
        while (saleDao.countInvoicesMatching(invNum) > 0) {
            counter++
            invNum = String.format("%s%d-%06d", prefix, year, counter)
        }
        return invNum
    }

    /** Weighted cost of one unit: the variant's own cost when it has one, else the product's. */
    private suspend fun unitCost(product: ProductEntity, variantId: Long): Double =
        if (variantId > 0) variantDao.getVariantById(variantId)?.purchasePrice?.takeIf { it > 0 } ?: product.purchasePrice
        else product.purchasePrice

    suspend fun createSale(
        sale: SaleEntity,
        items: List<SaleItemInput>
    ): Result<Long> = runCatching {
        database.withTransaction {
            require(items.isNotEmpty()) { "Sale must contain at least one item" }

            // 1. Validate Customer & Credit Rules
            val effectiveCustomerId = if (sale.customerId != null && sale.customerId > 0L) sale.customerId else null
            if (sale.dueAmount > 0.001) {
                if (effectiveCustomerId == null) {
                    throw IllegalStateException("Credit/Due sales require an identifiable customer. Walk-in customer cannot have unpaid due.")
                }
                val customer = customerDao.getCustomerById(effectiveCustomerId)
                    ?: throw IllegalArgumentException("Customer not found: $effectiveCustomerId")
                if (!customer.isActive) {
                    throw IllegalStateException("Customer '${customer.name}' is deactivated and cannot make credit purchases.")
                }
                if (customer.creditLimit > 0 && (customer.currentDue + sale.dueAmount) > customer.creditLimit) {
                    throw IllegalStateException("Customer '${customer.name}' would exceed credit limit of ৳${customer.creditLimit}. Current due: ৳${customer.currentDue}, New due: ৳${customer.currentDue + sale.dueAmount}")
                }
            }

            // 2. Validate Stock & Serial/IMEI Units
            val seenSerialIds = mutableSetOf<Long>()
            var totalCOGS = 0.0

            for (input in items) {
                require(input.quantity > 0) { "Item '${input.productName}' quantity must be greater than 0" }
                val product = productDao.getProductById(input.productId)
                    ?: throw IllegalStateException("Product not found: ${input.productName}")

                if (product.currentStock < input.quantity) {
                    throw IllegalStateException("Insufficient stock for '${product.name}'. Available: ${product.currentStock}, Requested: ${input.quantity}")
                }

                if (input.variantId > 0) {
                    val variant = variantDao.getVariantById(input.variantId)
                        ?: throw IllegalStateException("Variant not found for item: ${input.productName}")
                    if (variant.currentStock < input.quantity) {
                        throw IllegalStateException("Insufficient stock for variant '${variant.variantName}'. Available: ${variant.currentStock}, Requested: ${input.quantity}")
                    }
                }

                // Serial/IMEI validations
                if (TrackingType.requiresSerial(product.trackingType) || TrackingType.requiresImei(product.trackingType)) {
                    if (input.selectedSerialImeiIds.size != input.quantity) {
                        throw IllegalStateException("Please select exactly ${input.quantity} serialized/IMEI unit(s) for '${product.name}' (selected: ${input.selectedSerialImeiIds.size})")
                    }

                    for (serialId in input.selectedSerialImeiIds) {
                        if (!seenSerialIds.add(serialId)) {
                            throw IllegalStateException("Duplicate serialized unit selected in cart")
                        }
                        val unit = productDao.getSerialById(serialId)
                            ?: throw IllegalStateException("Serialized unit ID $serialId not found")
                        if (unit.productId != product.id) {
                            throw IllegalStateException("Serial unit '${unit.serialNumber ?: unit.imei1}' does not belong to product '${product.name}'")
                        }
                        if (input.variantId > 0 && unit.variantId != input.variantId) {
                            throw IllegalStateException("Unit '${unit.imei1 ?: unit.serialNumber}' is a different variant of '${product.name}'")
                        }
                        if (unit.status != InventoryStatus.IN_STOCK) {
                            throw IllegalStateException("Serialized unit '${unit.serialNumber ?: unit.imei1}' is not in stock (current status: ${unit.status})")
                        }
                    }
                }

                totalCOGS += (unitCost(product, input.variantId) * input.quantity)
            }

            // 3. Invoice Number & Payment Status
            val invoiceNumber = if (sale.invoiceNumber.isBlank()) {
                generateNextInvoiceNumber()
            } else {
                val trimmed = sale.invoiceNumber.trim()
                if (saleDao.countInvoicesMatching(trimmed) > 0) {
                    throw IllegalArgumentException("Invoice number '$trimmed' is already used")
                }
                trimmed
            }

            val paymentStatus = when {
                sale.dueAmount <= 0.001 -> "PAID"
                sale.paidAmount <= 0.001 -> "DUE"
                else -> "PARTIAL"
            }

            // Tax collected belongs to the government, not to the shop's profit
            val finalProfit = sale.totalAmount - sale.taxAmount - totalCOGS
            val finalSale = sale.copy(
                invoiceNumber = invoiceNumber,
                customerId = effectiveCustomerId,
                paymentStatus = paymentStatus,
                totalCostOfGoodsSold = totalCOGS,
                totalProfit = finalProfit,
                createdAt = if (sale.saleDate > 0) sale.saleDate else System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis()
            )

            // 4. Insert Sale Master
            val saleId = saleDao.insertSale(finalSale)

            // 5. Insert Sale Items, deduct stock, update serials and warranty
            for (input in items) {
                val product = productDao.getProductById(input.productId)!!
                val itemCostAtSale = unitCost(product, input.variantId)
                val lineGross = input.quantity * input.unitPrice
                val lineTotal = (lineGross - input.discount + input.tax).coerceAtLeast(0.0)
                val itemProfit = lineTotal - input.tax - (itemCostAtSale * input.quantity)

                val serialJsonArray = JSONArray()
                input.selectedSerialsOrImeis.forEach { serialJsonArray.put(it) }

                // Invoices show which variant was sold, e.g. "Galaxy A15 (8/256GB Blue)"
                val variantName = if (input.variantId > 0) variantDao.getVariantById(input.variantId)?.variantName else null
                val saleItem = SaleItemEntity(
                    saleId = saleId,
                    productId = product.id,
                    productName = product.name + (variantName?.let { " ($it)" } ?: ""),
                    variantId = input.variantId,
                    quantity = input.quantity,
                    unitPrice = input.unitPrice,
                    unitCostAtSale = itemCostAtSale,
                    discount = input.discount,
                    tax = input.tax,
                    totalPrice = lineTotal,
                    profit = itemProfit,
                    trackingType = product.trackingType,
                    serialImeisJson = serialJsonArray.toString()
                )
                saleDao.insertSaleItems(listOf(saleItem))

                // Deduct stock from Product
                val previousStock = product.currentStock
                val newStock = previousStock - input.quantity
                productDao.updateStockQuantity(product.id, -input.quantity)

                // Deduct stock from Variant if applicable
                if (input.variantId > 0) {
                    variantDao.updateVariantStock(input.variantId, -input.quantity)
                }

                // Record stock movement transaction (SALE)
                stockDao.insertStockTransaction(
                    StockTransactionEntity(
                        productId = product.id,
                        productName = product.name,
                        variantId = input.variantId,
                        transactionType = StockMovementType.SALE,
                        quantityChange = -input.quantity,
                        previousStock = previousStock,
                        newStock = newStock,
                        unitCost = itemCostAtSale,
                        referenceId = saleId,
                        referenceType = "SALE",
                        balanceAfter = newStock,
                        createdBy = finalSale.salespersonName,
                        notes = "Sale Invoice #$invoiceNumber"
                    )
                )

                // Mark serialized/IMEI units as SOLD
                for (serialId in input.selectedSerialImeiIds) {
                    val updatedCount = productDao.markSerialSold(
                        id = serialId,
                        status = InventoryStatus.SOLD,
                        saleId = saleId,
                        invoiceNumber = invoiceNumber
                    )
                    if (updatedCount == 0) {
                        throw IllegalStateException("Failed to mark serial ID $serialId as SOLD. Possible concurrency collision.")
                    }
                }

                // Create Warranty Record(s) if warranty period > 0: one per serialized unit, otherwise one per line
                if (product.warrantyPeriodMonths > 0) {
                    val calEnd = com.example.core.utils.WarrantyUtils.endDate(product)
                    val units: List<Pair<Long, String>> = if (input.selectedSerialImeiIds.isNotEmpty()) {
                        input.selectedSerialImeiIds.mapIndexed { i, id -> id to (input.selectedSerialsOrImeis.getOrNull(i) ?: "") }
                    } else listOf(0L to "")
                    for ((unitId, label) in units) {
                        warrantyDao.insertWarranty(
                            WarrantyRecordEntity(
                                saleId = saleId,
                                invoiceNumber = invoiceNumber,
                                customerId = finalSale.customerId ?: 0L,
                                customerName = finalSale.customerName,
                                customerPhone = finalSale.customerPhone,
                                productId = product.id,
                                productName = product.name,
                                serialImeiId = unitId,
                                serialOrImei = label,
                                warrantyPeriodMonths = product.warrantyPeriodMonths,
                                warrantyStartDate = System.currentTimeMillis(),
                                warrantyEndDate = calEnd,
                                status = "ACTIVE"
                            )
                        )
                    }
                }
            }

            // 6. Customer Accounting & Ledger
            if (finalSale.customerId != null && finalSale.customerId > 0L) {
                val customer = customerDao.getCustomerById(finalSale.customerId)
                    ?: throw IllegalArgumentException("Customer not found: ${finalSale.customerId}")

                val balanceBeforeSale = customer.currentDue

                // Entry A: SALE (+totalAmount)
                val balanceAfterSale = balanceBeforeSale + finalSale.totalAmount
                customerLedgerDao.insertLedgerEntry(
                    CustomerLedgerEntryEntity(
                        customerId = customer.id,
                        transactionType = "SALE",
                        referenceId = saleId,
                        referenceNumber = invoiceNumber,
                        amount = finalSale.totalAmount,
                        signedAmount = finalSale.totalAmount,
                        runningBalance = balanceAfterSale,
                        description = "Sale Invoice #$invoiceNumber",
                        createdBy = finalSale.salespersonName,
                        createdAt = finalSale.saleDate
                    )
                )

                // Entry B: CUSTOMER_PAYMENT (-paidAmount) if paid > 0
                val finalBalance = if (finalSale.paidAmount > 0) {
                    val balanceAfterPay = balanceAfterSale - finalSale.paidAmount
                    customerLedgerDao.insertLedgerEntry(
                        CustomerLedgerEntryEntity(
                            customerId = customer.id,
                            transactionType = "CUSTOMER_PAYMENT",
                            referenceId = saleId,
                            referenceNumber = "PAY-$invoiceNumber",
                            amount = finalSale.paidAmount,
                            signedAmount = -finalSale.paidAmount,
                            runningBalance = balanceAfterPay,
                            description = "Down payment for sale invoice #$invoiceNumber via ${finalSale.paymentMethod}",
                            createdBy = finalSale.salespersonName,
                            createdAt = finalSale.saleDate + 1
                        )
                    )
                    balanceAfterPay
                } else {
                    balanceAfterSale
                }

                // Update customer cached due, then settle invoices (applies any advance the customer had)
                customerDao.setCustomerBalance(customer.id, finalBalance)
                CustomerRepository(database).reallocateSalePayments(customer.id)
            }

            // 7. Finance & Cash Register Tracking for Paid Portion
            if (finalSale.paidAmount > 0) {
                financeDao.insertPayment(
                    PaymentTransactionEntity(
                        transactionType = "CUSTOMER_PAYMENT",
                        customerId = finalSale.customerId ?: 0L,
                        saleId = saleId,
                        amount = finalSale.paidAmount,
                        paymentMethod = finalSale.paymentMethod,
                        referenceNo = invoiceNumber,
                        notes = "Payment for sale invoice $invoiceNumber"
                    )
                )

                financeDao.insertCashTransaction(
                    CashRegisterTransactionEntity(
                        type = "SALE_CASH",
                        amount = finalSale.paidAmount,
                        flowDirection = "IN",
                        paymentMethod = finalSale.paymentMethod,
                        referenceId = saleId,
                        referenceText = "Invoice #$invoiceNumber",
                        notes = "POS collection from ${finalSale.customerName}"
                    )
                )
            }

            // 8. Increment Next Invoice Sequence Counter
            settingsDao.incrementInvoiceNumber()

            // 9. Audit Log
            settingsDao.insertAuditLog(
                AuditLogEntity(
                    userRole = finalSale.salespersonName,
                    action = "SALE_COMPLETED",
                    module = "SALES",
                    recordId = invoiceNumber,
                    description = "Completed sale #$invoiceNumber for ${finalSale.customerName}: ৳${finalSale.totalAmount} (Paid: ৳${finalSale.paidAmount}, Due: ৳${finalSale.dueAmount})"
                )
            )

            saleId
        }
    }

    suspend fun cancelSale(saleId: Long, reason: String, cancelledBy: String = "Admin"): Result<Unit> = runCatching {
        database.withTransaction {
            val sale = saleDao.getSaleById(saleId)
                ?: throw IllegalArgumentException("Sale not found: $saleId")

            if (sale.status == "CANCELLED") {
                throw IllegalStateException("Sale #${sale.invoiceNumber} is already cancelled")
            }

            // Check if any returns exist for this sale
            val existingReturns = salesReturnDao.getReturnsForSaleSync(saleId)
            if (existingReturns.isNotEmpty()) {
                throw IllegalStateException("Cannot cancel sale #${sale.invoiceNumber} because it has ${existingReturns.size} completed return(s). Please review return records.")
            }

            // 1. Mark Sale CANCELLED
            saleDao.updateSaleStatus(saleId, "CANCELLED")

            // 2. Reverse stock for each line item
            val items = saleDao.getSaleItems(saleId)
            for (item in items) {
                val product = productDao.getProductById(item.productId)
                if (product != null) {
                    val prevStock = product.currentStock
                    val newStock = prevStock + item.quantity
                    productDao.updateStockQuantity(product.id, item.quantity)

                    if (item.variantId > 0) {
                        variantDao.updateVariantStock(item.variantId, item.quantity)
                    }

                    // Reverse stock transaction
                    stockDao.insertStockTransaction(
                        StockTransactionEntity(
                            productId = product.id,
                            productName = product.name,
                            variantId = item.variantId,
                            transactionType = StockMovementType.SALE_RETURN,
                            quantityChange = item.quantity,
                            previousStock = prevStock,
                            newStock = newStock,
                            unitCost = item.unitCostAtSale,
                            referenceId = saleId,
                            referenceType = "SALE_CANCELLED",
                            balanceAfter = newStock,
                            createdBy = cancelledBy,
                            notes = "Cancelled Sale #${sale.invoiceNumber}: $reason"
                        )
                    )
                }
            }

            // 3. Restore serialized/IMEI units back to IN_STOCK
            val soldSerials = productDao.getSerialsForSale(saleId)
            for (serial in soldSerials) {
                productDao.restoreSerialFromSale(serial.id, saleId, InventoryStatus.IN_STOCK)
            }

            // Warranty no longer applies
            warrantyDao.setWarrantyStatusForSale(saleId, "CANCELLED")

            // 4. Reverse Customer Accounting: the whole invoice goes away and the money paid at the counter
            // is handed back. Later payments stay on the customer's account and settle their other invoices.
            var refundedAtCancel = sale.paidAmount
            if (sale.customerId != null && sale.customerId > 0L) {
                val customer = customerDao.getCustomerById(sale.customerId)
                if (customer != null) {
                    val paidAtCounter = CustomerRepository(database).paidAgainstSale(sale)
                    refundedAtCancel = paidAtCounter
                    val now = System.currentTimeMillis()
                    val afterCancel = customerLedgerDao.calculateCustomerBalance(customer.id) - sale.totalAmount

                    customerLedgerDao.insertLedgerEntry(
                        CustomerLedgerEntryEntity(
                            customerId = customer.id,
                            transactionType = "SALE_CANCEL",
                            referenceId = saleId,
                            referenceNumber = "CAN-${sale.invoiceNumber}",
                            amount = sale.totalAmount,
                            signedAmount = -sale.totalAmount,
                            runningBalance = afterCancel,
                            description = "Reversal for cancelled sale #${sale.invoiceNumber} ($reason)",
                            createdBy = cancelledBy,
                            createdAt = now
                        )
                    )
                    if (paidAtCounter > 0) {
                        customerLedgerDao.insertLedgerEntry(
                            CustomerLedgerEntryEntity(
                                customerId = customer.id,
                                transactionType = "CUSTOMER_REFUND",
                                referenceId = saleId,
                                referenceNumber = "REF-CAN-${sale.invoiceNumber}",
                                amount = paidAtCounter,
                                signedAmount = paidAtCounter,
                                runningBalance = afterCancel + paidAtCounter,
                                description = "Money paid on cancelled sale #${sale.invoiceNumber} handed back",
                                createdBy = cancelledBy,
                                createdAt = now + 1
                            )
                        )
                    }

                    customerDao.setCustomerBalance(customer.id, customerLedgerDao.calculateCustomerBalance(customer.id))
                    CustomerRepository(database).reallocateSalePayments(customer.id)
                }
            }

            // 5. Reverse Cash Outflow if payment was collected
            if (refundedAtCancel > 0) {
                financeDao.insertCashTransaction(
                    CashRegisterTransactionEntity(
                        type = "REFUND",
                        amount = refundedAtCancel,
                        flowDirection = "OUT",
                        paymentMethod = sale.paymentMethod,
                        referenceId = saleId,
                        referenceText = "Cancel #${sale.invoiceNumber}",
                        notes = "Cash refund for cancelled sale (${sale.customerName})",
                        date = System.currentTimeMillis()
                    )
                )
            }

            // 6. Audit Log
            settingsDao.insertAuditLog(
                AuditLogEntity(
                    userRole = cancelledBy,
                    action = "SALE_CANCELLED",
                    module = "SALES",
                    recordId = sale.invoiceNumber,
                    description = "Cancelled sale #${sale.invoiceNumber} for ${sale.customerName}. Reason: $reason"
                )
            )
        }
    }
}
