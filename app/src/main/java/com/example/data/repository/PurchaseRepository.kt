package com.example.data.repository

import androidx.room.withTransaction
import com.example.data.local.database.AppDatabase
import com.example.data.local.entities.*
import kotlinx.coroutines.flow.Flow
import org.json.JSONArray
import java.util.Calendar
import java.util.Locale
import com.example.sync.DocNumbers

data class PurchaseItemDraft(
    val productId: Long,
    val productName: String,
    val variantId: Long = 0,
    val variantName: String = "",
    val quantity: Int,
    val unitCost: Double,
    val discount: Double = 0.0,
    val tax: Double = 0.0,
    val trackingType: String = "QUANTITY", // QUANTITY, SERIAL, IMEI, SERIAL_AND_IMEI
    val serialsOrImeis: List<SerialImeiInput> = emptyList()
)

data class SerialImeiInput(
    val serialNumber: String = "",
    val imei1: String = "",
    val imei2: String = ""
)

class PurchaseRepository(private val database: AppDatabase) {
    private val purchaseDao = database.purchaseDao()
    private val productDao = database.productDao()
    private val variantDao = database.productVariantDao()
    private val supplierDao = database.supplierDao()
    private val masterDao = database.masterDao()
    private val stockDao = database.stockDao()
    private val financeDao = database.financeDao()
    private val auditDao = database.settingsAndAuditDao()
    private val priceHistoryDao = database.productPriceHistoryDao()
    private val supplierRepository = SupplierRepository(database)

    val allPurchases: Flow<List<PurchaseEntity>> = purchaseDao.getAllPurchases()

    fun searchPurchases(query: String): Flow<List<PurchaseEntity>> = purchaseDao.searchPurchases(query)
    fun observePurchaseById(id: Long): Flow<PurchaseEntity?> = purchaseDao.observePurchaseById(id)
    suspend fun getPurchaseById(id: Long): PurchaseEntity? = purchaseDao.getPurchaseById(id)
    suspend fun getPurchaseItems(purchaseId: Long): List<PurchaseItemEntity> = purchaseDao.getPurchaseItems(purchaseId)
    fun observePurchaseItems(purchaseId: Long): Flow<List<PurchaseItemEntity>> = purchaseDao.observePurchaseItems(purchaseId)
    fun getPurchasesBetween(startDate: Long, endDate: Long): Flow<List<PurchaseEntity>> = purchaseDao.getPurchasesBetween(startDate, endDate)
    fun getTotalPurchaseAmountBetween(startDate: Long, endDate: Long): Flow<Double?> = purchaseDao.getTotalPurchaseAmountBetween(startDate, endDate)

    suspend fun generatePurchaseNumber(): String {
        val year = Calendar.getInstance().get(Calendar.YEAR)
        DocNumbers.next(database, DocNumbers.Series.PURCHASE, { till, n -> DocNumbers.purchase(year, till, n) }) {
            purchaseDao.getPurchaseByPurchaseNumber(it) != null
        }?.let { return it }
        val maxId = purchaseDao.getMaxPurchaseId() ?: 0L
        val count = purchaseDao.getPurchaseCount()
        val seq = maxOf(maxId + 1, (count + 1).toLong())
        var candidate = String.format(Locale.US, "PUR-%d-%06d", year, seq)
        var offset = 0
        while (purchaseDao.getPurchaseByPurchaseNumber(candidate) != null) {
            offset++
            candidate = String.format(Locale.US, "PUR-%d-%06d", year, seq + offset)
        }
        return candidate
    }

    suspend fun createPurchase(
        purchaseDraft: PurchaseEntity,
        items: List<PurchaseItemDraft>
    ): Result<Long> = runCatching {
        database.withTransaction {
            // Pre-validations
            require(items.isNotEmpty()) { "Purchase must have at least one product." }
            val supplier = supplierDao.getSupplierById(purchaseDraft.supplierId)
                ?: throw IllegalArgumentException("Supplier not found.")

            // Validate serials and IMEIs across all draft items.
            // A unit that went back to the supplier (RETURNED) or whose purchase was cancelled can be bought again;
            // its existing row is reused so the unique serial/IMEI indexes stay valid.
            val seenSerials = mutableSetOf<String>()
            val seenImeis = mutableSetOf<String>()
            val reusableUnits = mutableMapOf<Pair<Int, Int>, ProductSerialImeiEntity>()

            items.forEachIndexed { itemIndex, item ->
                require(item.quantity > 0) { "Quantity must be greater than zero for ${item.productName}" }
                require(item.unitCost >= 0) { "Unit cost cannot be negative for ${item.productName}" }

                val requiresSerial = item.trackingType == "SERIAL" || item.trackingType == "SERIAL_AND_IMEI"
                val requiresImei = item.trackingType == "IMEI" || item.trackingType == "SERIAL_AND_IMEI"

                if (requiresSerial || requiresImei) {
                    require(item.serialsOrImeis.size == item.quantity) {
                        "Product '${item.productName}' requires ${item.quantity} serial/IMEI entries (provided: ${item.serialsOrImeis.size})."
                    }

                    item.serialsOrImeis.forEachIndexed { inputIndex, sInput ->
                        val serial = sInput.serialNumber.trim()
                        val imei1 = sInput.imei1.trim()
                        val imei2 = sInput.imei2.trim()

                        if (requiresSerial) {
                            require(serial.isNotBlank()) { "Serial number is required for ${item.productName}" }
                        }
                        if (requiresImei) {
                            require(imei1.isNotBlank()) { "IMEI 1 is required for ${item.productName}" }
                        }
                        if (serial.isNotBlank() && !seenSerials.add(serial)) {
                            throw IllegalArgumentException("Duplicate serial number '$serial' entered in this purchase.")
                        }
                        if (imei2.isNotBlank() && imei1 == imei2) {
                            throw IllegalArgumentException("IMEI 1 and IMEI 2 cannot be identical ($imei1).")
                        }
                        for (imei in listOf(imei1, imei2).filter { it.isNotBlank() }) {
                            if (!seenImeis.add(imei)) {
                                throw IllegalArgumentException("Duplicate IMEI '$imei' entered in this purchase.")
                            }
                        }

                        val existingUnits = listOfNotNull(
                            serial.takeIf { it.isNotBlank() }?.let { productDao.getBySerialNumber(it) },
                            imei1.takeIf { it.isNotBlank() }?.let { productDao.getSerialByImei(it) },
                            imei2.takeIf { it.isNotBlank() }?.let { productDao.getSerialByImei(it) }
                        ).distinctBy { it.id }
                        val label = listOf(imei1, serial).firstOrNull { it.isNotBlank() } ?: imei2

                        if (existingUnits.size > 1) {
                            throw IllegalArgumentException("'$label' matches more than one existing unit in inventory. Check the serial and IMEI numbers.")
                        }
                        val existing = existingUnits.firstOrNull()
                        if (existing != null) {
                            if (existing.status == InventoryStatus.RETURNED || existing.status == InventoryStatus.CANCELLED) {
                                reusableUnits[itemIndex to inputIndex] = existing
                            } else {
                                throw IllegalArgumentException("'$label' already exists in inventory (status: ${existing.status}).")
                            }
                        }
                    }
                }
            }

            // Calculate Subtotal & Line Totals
            val subtotal = items.sumOf { (it.quantity * it.unitCost) - it.discount + it.tax }
            val itemDiscounts = items.sumOf { it.discount }
            val grandTotal = maxOf(0.0, (subtotal - purchaseDraft.invoiceDiscount) + purchaseDraft.taxAmount + purchaseDraft.transportCost + purchaseDraft.otherCost)
            val paidAmount = purchaseDraft.paidAmount.coerceIn(0.0, grandTotal)
            val dueAmount = grandTotal - paidAmount

            val paymentStatus = when {
                paidAmount >= grandTotal && grandTotal > 0 -> "PAID"
                paidAmount > 0 -> "PARTIAL"
                else -> "DUE"
            }

            val purchaseNumber = if (purchaseDraft.purchaseNumber.isNotBlank()) {
                val existing = purchaseDao.getPurchaseByPurchaseNumber(purchaseDraft.purchaseNumber.trim())
                if (existing != null) {
                    throw IllegalArgumentException("Purchase #${purchaseDraft.purchaseNumber.trim()} already exists.")
                }
                purchaseDraft.purchaseNumber.trim()
            } else {
                generatePurchaseNumber()
            }

            val totalAdditionalCost = purchaseDraft.transportCost + purchaseDraft.otherCost

            val purchaseToSave = purchaseDraft.copy(
                purchaseNumber = purchaseNumber,
                invoiceNumber = purchaseNumber,
                supplierName = supplier.name,
                subTotal = subtotal,
                itemDiscount = itemDiscounts,
                discountAmount = itemDiscounts + purchaseDraft.invoiceDiscount,
                totalAmount = grandTotal,
                paidAmount = paidAmount,
                dueAmount = dueAmount,
                paymentStatus = paymentStatus,
                status = "COMPLETED",
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis()
            )

            val purchaseId = purchaseDao.insertPurchase(purchaseToSave)

            // Process each item line
            for ((itemIndex, item) in items.withIndex()) {
                val product = productDao.getProductById(item.productId)
                    ?: throw IllegalStateException("Product not found: ${item.productName}")

                val lineGross = item.quantity * item.unitCost
                val lineTotal = (lineGross - item.discount) + item.tax

                // Proportional Landed Cost Allocation (transport + other), plus this line's share of the
                // invoice discount and invoice tax so the stock cost matches what is owed to the supplier
                val lineShare = if (subtotal > 0) lineTotal / subtotal else (1.0 / items.size)
                val allocatedLanded = totalAdditionalCost * lineShare
                val invoiceAdjustment = (purchaseDraft.taxAmount - purchaseDraft.invoiceDiscount) * lineShare
                val effectiveLandedUnitCost = ((lineTotal + allocatedLanded + invoiceAdjustment) / item.quantity).coerceAtLeast(0.0)

                // Weighted Average Cost Calculation
                val oldStock = product.currentStock.coerceAtLeast(0)
                val newUnits = item.quantity
                val oldAvgCost = product.purchasePrice
                val totalUnits = oldStock + newUnits
                val newWeightedCost = if (totalUnits > 0) {
                    ((oldStock * oldAvgCost) + (newUnits * effectiveLandedUnitCost)) / totalUnits
                } else {
                    effectiveLandedUnitCost
                }

                // Insert purchase line item
                val serialJsonArray = JSONArray()
                item.serialsOrImeis.forEach { s ->
                    val obj = org.json.JSONObject()
                    obj.put("serial", s.serialNumber)
                    obj.put("imei1", s.imei1)
                    obj.put("imei2", s.imei2)
                    serialJsonArray.put(obj)
                }

                val lineItemEntity = PurchaseItemEntity(
                    purchaseId = purchaseId,
                    productId = item.productId,
                    productName = product.name + (item.variantName.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""),
                    variantId = item.variantId,
                    quantity = item.quantity,
                    unitCost = item.unitCost,
                    discount = item.discount,
                    tax = item.tax,
                    lineTotal = lineTotal,
                    totalCost = lineTotal,
                    trackingType = item.trackingType,
                    allocatedLandedCost = allocatedLanded,
                    serialImeisJson = serialJsonArray.toString()
                )

                val purchaseItemId = purchaseDao.insertPurchaseItems(listOf(lineItemEntity)).first()

                // Update product stock and purchase price
                productDao.updateStockQuantity(product.id, newUnits)
                productDao.updatePurchaseCost(product.id, newWeightedCost)

                // If variant exists, update variant stock & weighted cost (re-read after the stock update so it is not overwritten)
                if (item.variantId > 0) {
                    val variant = variantDao.getVariantById(item.variantId)
                    if (variant != null) {
                        val oldVariantStock = variant.currentStock.coerceAtLeast(0)
                        val variantUnits = oldVariantStock + newUnits
                        val variantCost = if (variantUnits > 0) {
                            ((oldVariantStock * variant.purchasePrice) + (newUnits * effectiveLandedUnitCost)) / variantUnits
                        } else effectiveLandedUnitCost
                        variantDao.updateVariantStock(variant.id, newUnits)
                        variantDao.getVariantById(variant.id)?.let { fresh ->
                            variantDao.updateVariant(fresh.copy(purchasePrice = variantCost))
                        }
                    }
                }

                // Record Product Price History
                priceHistoryDao.insertPriceHistory(
                    ProductPriceHistoryEntity(
                        productId = product.id,
                        purchasePrice = newWeightedCost,
                        wholesalePrice = product.wholesalePrice,
                        retailPrice = product.retailPrice,
                        minSellingPrice = product.minSellingPrice,
                        changedBy = purchaseDraft.createdBy,
                        note = "Purchase #${purchaseNumber} (+${item.quantity} units @ ৳${effectiveLandedUnitCost.toInt()})"
                    )
                )

                // Stock Movement Transaction
                stockDao.insertStockTransaction(
                    StockTransactionEntity(
                        productId = product.id,
                        productName = product.name,
                        variantId = item.variantId,
                        transactionType = "PURCHASE",
                        quantityChange = newUnits,
                        previousStock = oldStock,
                        newStock = oldStock + newUnits,
                        unitCost = effectiveLandedUnitCost,
                        referenceId = purchaseId,
                        referenceType = "PURCHASE",
                        balanceAfter = oldStock + newUnits,
                        createdBy = purchaseDraft.createdBy,
                        notes = "Purchase #${purchaseNumber} from ${supplier.name}"
                    )
                )

                // Serial/IMEI Units creation
                item.serialsOrImeis.forEachIndexed { inputIndex, sInput ->
                    val cleanSerial = sInput.serialNumber.trim().ifEmpty { null }
                    val cleanImei1 = sInput.imei1.trim().ifEmpty { null }
                    val cleanImei2 = sInput.imei2.trim().ifEmpty { null }
                    val warrantyEnd = if (product.warrantyPeriodMonths > 0) {
                        com.example.core.utils.WarrantyUtils.endDate(product)
                    } else 0L

                    val reused = reusableUnits[itemIndex to inputIndex]
                    if (reused != null) {
                        productDao.updateSerialImei(
                            reused.copy(
                                productId = product.id,
                                variantId = item.variantId,
                                purchaseItemId = purchaseItemId,
                                serialNumber = cleanSerial,
                                imei1 = cleanImei1,
                                imei2 = cleanImei2,
                                ram = product.variant,
                                color = product.color,
                                purchasePrice = effectiveLandedUnitCost,
                                sellingPrice = product.retailPrice,
                                supplierId = supplier.id,
                                purchaseId = purchaseId,
                                saleId = 0,
                                invoiceNumber = purchaseNumber,
                                status = InventoryStatus.IN_STOCK,
                                warrantyPeriodMonths = product.warrantyPeriodMonths,
                                warrantyStartDate = System.currentTimeMillis(),
                                warrantyEndDate = warrantyEnd,
                                notes = "Bought again in purchase #$purchaseNumber (was ${reused.status})",
                                updatedAt = System.currentTimeMillis()
                            )
                        )
                        return@forEachIndexed
                    }

                    productDao.insertSerialImei(
                        ProductSerialImeiEntity(
                            productId = product.id,
                            variantId = item.variantId,
                            purchaseItemId = purchaseItemId,
                            serialNumber = cleanSerial,
                            imei1 = cleanImei1,
                            imei2 = cleanImei2,
                            ram = product.variant,
                            storage = "",
                            color = product.color,
                            purchasePrice = effectiveLandedUnitCost,
                            sellingPrice = product.retailPrice,
                            supplierId = supplier.id,
                            purchaseId = purchaseId,
                            invoiceNumber = purchaseNumber,
                            status = InventoryStatus.IN_STOCK,
                            warrantyPeriodMonths = product.warrantyPeriodMonths,
                            warrantyStartDate = System.currentTimeMillis(),
                            warrantyEndDate = warrantyEnd
                        )
                    )
                }
            }

            // Authoritative Supplier Ledger entry for Gross Purchase (+grandTotal)
            supplierRepository.recordLedgerTransaction(
                supplierId = supplier.id,
                transactionType = SupplierLedgerSign.TYPE_PURCHASE,
                amount = grandTotal,
                referenceId = purchaseId,
                referenceNumber = purchaseNumber,
                description = "Purchase Invoice #${purchaseNumber} (Supplier Inv: ${purchaseDraft.supplierInvoiceNumber.ifBlank { "N/A" }})",
                createdBy = purchaseDraft.createdBy,
                timestamp = purchaseDraft.purchaseDate
            )

            // Record Payment if paidAmount > 0: Voucher & Ledger entry (-paidAmount)
            if (paidAmount > 0) {
                val paymentVoucherNo = String.format(Locale.US, "PAY-%s", purchaseNumber)
                database.supplierPaymentDao().insertPayment(
                    SupplierPaymentEntity(
                        paymentNumber = paymentVoucherNo,
                        supplierId = supplier.id,
                        supplierName = supplier.name,
                        purchaseId = purchaseId,
                        paymentDate = purchaseDraft.purchaseDate,
                        amount = paidAmount,
                        paymentMethod = purchaseDraft.paymentMethod,
                        referenceNumber = purchaseNumber,
                        notes = "Down payment for purchase $purchaseNumber",
                        createdBy = purchaseDraft.createdBy
                    )
                )

                // Authoritative Supplier Ledger entry for Down Payment
                supplierRepository.recordLedgerTransaction(
                    supplierId = supplier.id,
                    transactionType = SupplierLedgerSign.TYPE_SUPPLIER_PAYMENT,
                    amount = paidAmount,
                    referenceId = purchaseId,
                    referenceNumber = paymentVoucherNo,
                    description = "Down payment for purchase #${purchaseNumber} via ${purchaseDraft.paymentMethod}",
                    createdBy = purchaseDraft.createdBy,
                    timestamp = purchaseDraft.purchaseDate + 1
                )

                financeDao.insertPayment(
                    PaymentTransactionEntity(
                        transactionType = "SUPPLIER_PAYMENT",
                        supplierId = supplier.id,
                        purchaseId = purchaseId,
                        amount = paidAmount,
                        paymentMethod = purchaseDraft.paymentMethod,
                        referenceNo = purchaseNumber,
                        notes = "Paid for purchase $purchaseNumber"
                    )
                )

                financeDao.insertCashTransaction(
                    CashRegisterTransactionEntity(
                        type = "SUPPLIER_PAYMENT",
                        amount = paidAmount,
                        flowDirection = "OUT",
                        paymentMethod = purchaseDraft.paymentMethod,
                        referenceId = purchaseId,
                        referenceText = "Purchase #$purchaseNumber",
                        notes = "Payment to ${supplier.name}"
                    )
                )
            }

            // Apply any supplier advance / keep invoice paid-due status in line with the ledger
            supplierRepository.reallocatePurchasePayments(supplier.id)

            // Audit Log
            auditDao.insertAuditLog(
                AuditLogEntity(
                    userRole = "ADMIN",
                    action = "PURCHASE_COMPLETED",
                    module = "PURCHASES",
                    recordId = purchaseNumber,
                    description = "Completed purchase #$purchaseNumber from ${supplier.name} for ৳$grandTotal (Paid: ৳$paidAmount, Due: ৳$dueAmount)"
                )
            )

            purchaseId
        }
    }

    /** Cost per unit that went into stock for a purchase line (line net + landed costs + invoice discount/tax share). */
    fun effectiveUnitCost(purchase: PurchaseEntity, item: PurchaseItemEntity): Double {
        if (item.quantity <= 0) return 0.0
        val lineShare = if (purchase.subTotal > 0) item.lineTotal / purchase.subTotal else 0.0
        val invoiceAdjustment = (purchase.taxAmount - purchase.invoiceDiscount) * lineShare
        return ((item.lineTotal + item.allocatedLandedCost + invoiceAdjustment) / item.quantity).coerceAtLeast(0.0)
    }

    suspend fun cancelPurchase(purchaseId: Long, reason: String, cancelledBy: String = "Admin"): Result<Unit> = runCatching {
        database.withTransaction {
            val purchase = purchaseDao.getPurchaseById(purchaseId)
                ?: throw IllegalArgumentException("Purchase not found")
            if (purchase.status == "CANCELLED") {
                throw IllegalStateException("Purchase is already cancelled")
            }

            // Check if purchase has existing purchase returns
            val existingReturns = database.purchaseReturnDao().getReturnsForPurchaseSync(purchase.id)
            if (existingReturns.isNotEmpty()) {
                throw IllegalStateException("Cannot cancel purchase #${purchase.purchaseNumber} because it has ${existingReturns.size} existing purchase return(s). Please review return records.")
            }

            // Check if any serialized units from this purchase were already sold
            val purchaseSerials = productDao.getSerialsForPurchase(purchase.id)
            if (purchaseSerials.any { it.status == InventoryStatus.SOLD }) {
                throw IllegalStateException("Cannot cancel purchase #${purchase.purchaseNumber} because some serialized items have already been sold.")
            }

            val items = purchaseDao.getPurchaseItems(purchaseId)

            // Verify sufficient physical stock exists for every line before reducing
            for (item in items) {
                val product = productDao.getProductById(item.productId)
                    ?: throw IllegalStateException("Product not found for item: ${item.productName}")
                if (product.currentStock < item.quantity) {
                    throw IllegalStateException("Cannot cancel purchase #${purchase.purchaseNumber}. Required stock to reverse: ${item.quantity}, but only ${product.currentStock} units remain in stock for '${product.name}'.")
                }
                if (item.variantId > 0) {
                    val variant = variantDao.getVariantById(item.variantId)
                    if (variant != null && variant.currentStock < item.quantity) {
                        throw IllegalStateException("Cannot cancel purchase #${purchase.purchaseNumber}. Required variant stock to reverse: ${item.quantity}, but only ${variant.currentStock} units remain in stock for variant '${variant.variantName}'.")
                    }
                }
            }

            // Reverse stock and record stock movements
            for (item in items) {
                val product = productDao.getProductById(item.productId)!!
                val oldStock = product.currentStock
                val newStock = oldStock - item.quantity
                productDao.updateStockQuantity(product.id, -item.quantity)

                // Take this purchase back out of the weighted-average cost
                val cancelledUnitCost = effectiveUnitCost(purchase, item)
                if (newStock > 0) {
                    val restoredCost = ((oldStock * product.purchasePrice) - (item.quantity * cancelledUnitCost)) / newStock
                    if (restoredCost > 0) productDao.updatePurchaseCost(product.id, restoredCost)
                }

                if (item.variantId > 0) {
                    variantDao.updateVariantStock(item.variantId, -item.quantity)
                }

                stockDao.insertStockTransaction(
                    StockTransactionEntity(
                        productId = product.id,
                        productName = product.name,
                        variantId = item.variantId,
                        transactionType = StockMovementType.PURCHASE_CANCELLED,
                        quantityChange = -item.quantity,
                        previousStock = oldStock,
                        newStock = newStock,
                        unitCost = item.unitCost,
                        referenceId = purchase.id,
                        referenceType = "PURCHASE",
                        balanceAfter = newStock,
                        createdBy = cancelledBy,
                        notes = "Reversal for cancelled purchase #${purchase.purchaseNumber}. Reason: $reason"
                    )
                )
            }

            // Mark all serial units for this purchase as CANCELLED
            for (s in purchaseSerials) {
                productDao.updateSerialImei(
                    s.copy(
                        status = InventoryStatus.CANCELLED,
                        notes = "Purchase #${purchase.purchaseNumber} cancelled: $reason",
                        updatedAt = System.currentTimeMillis()
                    )
                )
            }

            // Reverse purchase liabilities in authoritative supplier ledger
            supplierRepository.recordLedgerTransaction(
                supplierId = purchase.supplierId,
                transactionType = SupplierLedgerSign.TYPE_CREDIT_ADJUSTMENT,
                amount = purchase.totalAmount,
                referenceId = purchase.id,
                referenceNumber = "REV-${purchase.purchaseNumber}",
                description = "Reversal of cancelled purchase #${purchase.purchaseNumber}",
                createdBy = cancelledBy
            )

            // Money paid against this purchase (its down payment) is treated as refunded by the supplier.
            // Other supplier payments stay on the supplier account and settle their other invoices.
            val paidOnPurchase = database.supplierPaymentDao().getPaymentsBySupplierSync(purchase.supplierId)
                .filter { it.purchaseId == purchase.id }
                .sumOf { it.amount }

            // If cash was paid, record payment reversal adjustment in ledger
            if (paidOnPurchase > 0) {
                supplierRepository.recordLedgerTransaction(
                    supplierId = purchase.supplierId,
                    transactionType = SupplierLedgerSign.TYPE_DEBIT_ADJUSTMENT,
                    amount = paidOnPurchase,
                    referenceId = purchase.id,
                    referenceNumber = "REF-${purchase.purchaseNumber}",
                    description = "Reversal of payment on cancelled purchase #${purchase.purchaseNumber}",
                    createdBy = cancelledBy
                )
            }

            // If cash was paid, record cash refund back into cash register
            if (paidOnPurchase > 0) {
                financeDao.insertCashTransaction(
                    CashRegisterTransactionEntity(
                        type = "REFUND",
                        amount = paidOnPurchase,
                        flowDirection = "IN",
                        paymentMethod = purchase.paymentMethod,
                        referenceId = purchase.id,
                        referenceText = "Reversal of purchase #${purchase.purchaseNumber}",
                        notes = "Supplier refunded paid amount on purchase cancellation"
                    )
                )
            }

            purchaseDao.updatePurchaseStatus(purchase.id, "CANCELLED")
            supplierRepository.reallocatePurchasePayments(purchase.supplierId)

            auditDao.insertAuditLog(
                AuditLogEntity(
                    userRole = "ADMIN",
                    action = "PURCHASE_CANCELLED",
                    module = "PURCHASES",
                    recordId = purchase.purchaseNumber,
                    description = "Cancelled purchase #${purchase.purchaseNumber}. Reason: $reason"
                )
            )
        }
    }
}
