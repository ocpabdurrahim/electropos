package com.example.data.repository

import androidx.room.withTransaction
import com.example.data.local.database.AppDatabase
import com.example.data.local.entities.*
import kotlinx.coroutines.flow.Flow
import java.util.Calendar
import com.example.sync.DocNumbers

data class SalesReturnDraft(
    val saleId: Long,
    val returnDate: Long = System.currentTimeMillis(),
    val reason: String = "",
    val refundAmount: Double = 0.0,
    val customerCredit: Double = 0.0,
    val refundPaymentMethod: String = "CASH",
    val createdBy: String = "Admin"
)

data class SalesReturnItemDraft(
    val saleItemId: Long,
    val productId: Long,
    val productName: String,
    val variantId: Long = 0,
    val returnQuantity: Int,
    val unitPrice: Double,
    val totalAmount: Double,
    val serialUnitId: Long = 0,
    val serialOrImei: String = "",
    val restockOption: String = "RESTOCK_SELLABLE" // RESTOCK_SELLABLE, DEFECTIVE_DAMAGED
)

class SalesReturnRepository(private val database: AppDatabase) {
    private val salesReturnDao = database.salesReturnDao()
    private val saleDao = database.saleDao()
    private val productDao = database.productDao()
    private val variantDao = database.productVariantDao()
    private val customerDao = database.customerDao()
    private val customerLedgerDao = database.customerLedgerDao()
    private val stockDao = database.stockDao()
    private val financeDao = database.financeDao()
    private val warrantyDao = database.warrantyDao()
    private val settingsDao = database.settingsAndAuditDao()

    val allReturns: Flow<List<SalesReturnEntity>> = salesReturnDao.getAllReturns()

    fun getReturnsForSale(saleId: Long): Flow<List<SalesReturnEntity>> =
        salesReturnDao.getReturnsForSale(saleId)

    fun getReturnItems(returnId: Long): Flow<List<SalesReturnItemEntity>> =
        salesReturnDao.getReturnItems(returnId)

    suspend fun getReturnById(id: Long) = salesReturnDao.getReturnById(id)
    suspend fun getReturnedQuantityForSaleItem(saleItemId: Long) =
        salesReturnDao.getReturnedQuantityForSaleItem(saleItemId)

    /**
     * Value refunded/credited per returned unit of a sale line: what the customer actually paid for it
     * (line price after its discount, plus its tax) less its share of the invoice discount.
     */
    fun refundUnitValue(sale: SaleEntity, saleItem: SaleItemEntity): Double {
        if (saleItem.quantity <= 0) return 0.0
        val linesTotal = sale.totalAmount + sale.invoiceDiscount
        val invoiceFactor = if (linesTotal > 0) sale.totalAmount / linesTotal else 0.0
        return roundMoney((saleItem.totalPrice / saleItem.quantity) * invoiceFactor)
    }

    /** Tax included in [refundUnitValue] (it is not the shop's money, so it is not part of the profit reversed). */
    private fun refundUnitTax(sale: SaleEntity, saleItem: SaleItemEntity): Double {
        if (saleItem.quantity <= 0) return 0.0
        val linesTotal = sale.totalAmount + sale.invoiceDiscount
        val invoiceFactor = if (linesTotal > 0) sale.totalAmount / linesTotal else 0.0
        return (saleItem.tax / saleItem.quantity) * invoiceFactor
    }

    suspend fun generateNextReturnNumber(): String {
        val profile = settingsDao.getShopProfileSync()
        val prefix = profile?.salesReturnPrefix?.ifBlank { "SRT-" } ?: "SRT-"
        val year = Calendar.getInstance().get(Calendar.YEAR)
        DocNumbers.next(database, DocNumbers.Series.SALES_RETURN, { till, n -> DocNumbers.salesReturn(prefix, year, till, n) }) {
            salesReturnDao.countReturnNumberMatches(it) > 0
        }?.let { return it }
        val nextNum = profile?.salesReturnNextNumber ?: 1L
        var returnNum = String.format("%s%d-%06d", prefix, year, nextNum)
        var counter = nextNum
        while (salesReturnDao.countReturnNumberMatches(returnNum) > 0) {
            counter++
            returnNum = String.format("%s%d-%06d", prefix, year, counter)
        }
        return returnNum
    }

    suspend fun createSalesReturn(
        returnDraft: SalesReturnDraft,
        itemsToReturn: List<SalesReturnItemDraft>
    ): Result<Long> = runCatching {
        database.withTransaction {
            require(itemsToReturn.isNotEmpty()) { "Return must contain at least one item" }

            val sale = saleDao.getSaleById(returnDraft.saleId)
                ?: throw IllegalArgumentException("Sale not found: ${returnDraft.saleId}")

            if (sale.status == "CANCELLED") {
                throw IllegalStateException("Cannot process return for cancelled sale #${sale.invoiceNumber}")
            }

            // 1. Validate Return Limits & Serial Units
            val saleLines = saleDao.getSaleItems(sale.id).associateBy { it.id }
            val seenUnits = mutableSetOf<Long>()
            for (item in itemsToReturn) {
                require(item.returnQuantity > 0) { "Return quantity must be greater than 0 for '${item.productName}'" }

                val saleItem = saleLines[item.saleItemId]
                    ?: throw IllegalArgumentException("Sale line item not found for product '${item.productName}'")

                // Serialized lines are returned unit by unit: the exact IMEI/serial must be picked
                val serializedLine = TrackingType.requiresSerial(saleItem.trackingType) || TrackingType.requiresImei(saleItem.trackingType)
                if (serializedLine) {
                    require(item.serialUnitId > 0 && item.returnQuantity == 1) {
                        "Pick the exact serial/IMEI unit of '${item.productName}' that is being returned."
                    }
                }

                // Serialized return validation
                if (item.serialUnitId > 0) {
                    if (!seenUnits.add(item.serialUnitId)) {
                        throw IllegalStateException("Unit '${item.serialOrImei}' was selected more than once")
                    }
                    val serialUnit = productDao.getSerialById(item.serialUnitId)
                        ?: throw IllegalStateException("Serial unit not found: ${item.serialUnitId}")

                    if (serialUnit.saleId != sale.id || serialUnit.productId != saleItem.productId) {
                        throw IllegalStateException("Serial unit '${item.serialOrImei}' was not sold under invoice #${sale.invoiceNumber}")
                    }

                    if (salesReturnDao.countSerialReturnedForSale(item.serialUnitId, sale.id) > 0) {
                        throw IllegalStateException("Serial unit '${item.serialOrImei}' has already been returned")
                    }
                }
            }
            for ((saleItemId, drafts) in itemsToReturn.groupBy { it.saleItemId }) {
                val saleItem = saleLines.getValue(saleItemId)
                val alreadyReturned = salesReturnDao.getReturnedQuantityForSaleItem(saleItemId)
                val remainingReturnable = saleItem.quantity - alreadyReturned
                val requested = drafts.sumOf { it.returnQuantity }
                if (requested > remainingReturnable) {
                    throw IllegalStateException("Cannot return $requested units of '${saleItem.productName}'. Remaining returnable quantity is only $remainingReturnable (Sold: ${saleItem.quantity}, Already Returned: $alreadyReturned).")
                }
            }

            // 2. Generate Return Number & Calculate Amounts (always from the sale line, never trusted from the caller)
            val returnNumber = generateNextReturnNumber()
            val pricedItems = itemsToReturn.map { draft ->
                val unitValue = refundUnitValue(sale, saleLines.getValue(draft.saleItemId))
                draft.copy(unitPrice = unitValue, totalAmount = roundMoney(unitValue * draft.returnQuantity))
            }
            val totalAmount = roundMoney(pricedItems.sumOf { it.totalAmount })
            val returnTax = roundMoney(itemsToReturn.sumOf { refundUnitTax(sale, saleLines.getValue(it.saleItemId)) * it.returnQuantity })
            val hasCustomerAccount = sale.customerId != null && sale.customerId > 0L
            // A walk-in customer has no account to hold a credit, so the whole value is handed back
            val refundAmount = if (hasCustomerAccount) returnDraft.refundAmount.coerceIn(0.0, totalAmount) else totalAmount
            val customerCredit = totalAmount - refundAmount

            val salesReturn = SalesReturnEntity(
                returnNumber = returnNumber,
                saleId = sale.id,
                invoiceNumber = sale.invoiceNumber,
                customerId = sale.customerId ?: 0L,
                customerName = sale.customerName,
                customerPhone = sale.customerPhone,
                returnDate = returnDraft.returnDate,
                subtotal = totalAmount,
                taxAmount = returnTax,
                totalAmount = totalAmount,
                refundAmount = refundAmount,
                customerCredit = customerCredit,
                refundPaymentMethod = returnDraft.refundPaymentMethod,
                reason = returnDraft.reason,
                status = "COMPLETED",
                createdBy = returnDraft.createdBy,
                createdAt = System.currentTimeMillis()
            )

            val returnId = salesReturnDao.insertReturn(salesReturn)

            // 3. Insert Return Items & Restock
            for (draft in pricedItems) {
                val saleItem = saleDao.getSaleItemById(draft.saleItemId)
                val costAtSale = saleItem?.unitCostAtSale ?: 0.0

                val itemEntity = SalesReturnItemEntity(
                    salesReturnId = returnId,
                    saleItemId = draft.saleItemId,
                    productId = draft.productId,
                    productName = draft.productName,
                    variantId = draft.variantId,
                    quantity = draft.returnQuantity,
                    unitPrice = draft.unitPrice,
                    unitCostAtSale = costAtSale,
                    totalPrice = draft.totalAmount,
                    serialUnitId = draft.serialUnitId,
                    serialOrImei = draft.serialOrImei,
                    restockOption = draft.restockOption
                )
                salesReturnDao.insertReturnItems(listOf(itemEntity))

                // The customer's warranty ends with the return
                if (draft.serialUnitId > 0) {
                    warrantyDao.setWarrantyStatusForUnit(sale.id, draft.serialUnitId, "RETURNED")
                } else if (salesReturnDao.getReturnedQuantityForSaleItem(draft.saleItemId) >= (saleLines[draft.saleItemId]?.quantity ?: Int.MAX_VALUE)) {
                    warrantyDao.setWarrantyStatusForLine(sale.id, draft.productId, "RETURNED")
                }

                val product = productDao.getProductById(draft.productId)
                if (product != null) {
                    val oldStock = product.currentStock

                    if (draft.restockOption == "RESTOCK_SELLABLE") {
                        val newStock = oldStock + draft.returnQuantity
                        productDao.updateStockQuantity(product.id, draft.returnQuantity)

                        if (draft.variantId > 0) {
                            variantDao.updateVariantStock(draft.variantId, draft.returnQuantity)
                        }

                        // Stock movement transaction (SALE_RETURN)
                        stockDao.insertStockTransaction(
                            StockTransactionEntity(
                                productId = product.id,
                                productName = product.name,
                                variantId = draft.variantId,
                                transactionType = StockMovementType.SALE_RETURN,
                                quantityChange = draft.returnQuantity,
                                previousStock = oldStock,
                                newStock = newStock,
                                unitCost = costAtSale,
                                referenceId = returnId,
                                referenceType = "SALES_RETURN",
                                balanceAfter = newStock,
                                createdBy = returnDraft.createdBy,
                                notes = "Sales Return #$returnNumber (Invoice #${sale.invoiceNumber})"
                            )
                        )

                        // If serialized, mark back IN_STOCK
                        if (draft.serialUnitId > 0) {
                            productDao.restoreSerialFromSale(draft.serialUnitId, sale.id, InventoryStatus.IN_STOCK)
                        }
                    } else {
                        // Mark damaged/defective: do not increment sellable stock
                        if (draft.serialUnitId > 0) {
                            productDao.markSerialReturnedFromSale(
                                id = draft.serialUnitId,
                                saleId = sale.id,
                                status = InventoryStatus.DAMAGED,
                                notes = "Returned defective by customer (#$returnNumber)"
                            )
                        }
                    }
                }
            }

            // 4. Customer Accounting & Financial Settlement
            if (sale.customerId != null && sale.customerId > 0L) {
                val customer = customerDao.getCustomerById(sale.customerId)
                if (customer != null) {
                    val prevBalance = customer.currentDue
                    // Deduct return total from customer due (or credit)
                    val newBalance = prevBalance - totalAmount

                    customerLedgerDao.insertLedgerEntry(
                        CustomerLedgerEntryEntity(
                            customerId = customer.id,
                            transactionType = "SALES_RETURN",
                            referenceId = returnId,
                            referenceNumber = returnNumber,
                            amount = totalAmount,
                            signedAmount = -totalAmount,
                            runningBalance = newBalance,
                            description = "Sales Return #$returnNumber for invoice #${sale.invoiceNumber}",
                            createdBy = returnDraft.createdBy,
                            createdAt = returnDraft.returnDate
                        )
                    )

                    // If cash was refunded out of the register to customer:
                    if (refundAmount > 0) {
                        val balanceAfterRefund = newBalance + refundAmount
                        customerLedgerDao.insertLedgerEntry(
                            CustomerLedgerEntryEntity(
                                customerId = customer.id,
                                transactionType = "CUSTOMER_REFUND",
                                referenceId = returnId,
                                referenceNumber = "REF-$returnNumber",
                                amount = refundAmount,
                                signedAmount = refundAmount,
                                runningBalance = balanceAfterRefund,
                                description = "Cash refund paid to customer for return #$returnNumber via ${returnDraft.refundPaymentMethod}",
                                createdBy = returnDraft.createdBy,
                                createdAt = returnDraft.returnDate + 1
                            )
                        )
                        customerDao.setCustomerBalance(customer.id, balanceAfterRefund)
                    } else {
                        customerDao.setCustomerBalance(customer.id, newBalance)
                    }
                    CustomerRepository(database).reallocateSalePayments(customer.id)
                }
            }

            // 5. Cash Register Outflow if cash refund given
            if (refundAmount > 0) {
                financeDao.insertCashTransaction(
                    CashRegisterTransactionEntity(
                        type = "REFUND",
                        amount = refundAmount,
                        flowDirection = "OUT",
                        paymentMethod = returnDraft.refundPaymentMethod,
                        referenceId = returnId,
                        referenceText = "Return #$returnNumber",
                        notes = "Customer return refund (${sale.customerName})",
                        date = returnDraft.returnDate
                    )
                )
            }

            // 6. Increment Sequence Counter
            settingsDao.incrementSalesReturnNumber()

            // 7. Audit Log
            settingsDao.insertAuditLog(
                AuditLogEntity(
                    userRole = returnDraft.createdBy,
                    action = "SALES_RETURN_COMPLETED",
                    module = "SALES_RETURNS",
                    recordId = returnNumber,
                    description = "Completed sales return #$returnNumber for invoice #${sale.invoiceNumber} (Total: ৳$totalAmount, Refund: ৳$refundAmount, Credit: ৳$customerCredit)"
                )
            )

            returnId
        }
    }
}
