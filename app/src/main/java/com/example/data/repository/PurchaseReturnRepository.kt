package com.example.data.repository

import androidx.room.withTransaction
import com.example.data.local.database.AppDatabase
import com.example.data.local.entities.*
import kotlinx.coroutines.flow.Flow
import java.util.Calendar
import java.util.Locale
import com.example.sync.DocNumbers

data class PurchaseReturnItemDraft(
    val purchaseItemId: Long,
    val productId: Long,
    val productName: String,
    val variantId: Long = 0,
    val returnQuantity: Int,
    val unitCost: Double,
    val totalAmount: Double,
    val serialUnitId: Long = 0,
    val serialOrImei: String = ""
)

data class ReturnableItemInfo(
    val purchaseItem: PurchaseItemEntity,
    val product: ProductEntity,
    val alreadyReturnedQty: Int,
    val availableReturnQty: Int,
    val serialUnits: List<ProductSerialImeiEntity>,
    /** What the supplier is credited per unit returned: line net after line + invoice discount, plus tax share. */
    val returnUnitValue: Double = purchaseItem.unitCost,
    val isSerialized: Boolean = serialUnits.isNotEmpty()
)

class PurchaseReturnRepository(private val database: AppDatabase) {
    private val returnDao = database.purchaseReturnDao()
    private val purchaseDao = database.purchaseDao()
    private val productDao = database.productDao()
    private val variantDao = database.productVariantDao()
    private val supplierDao = database.supplierDao()
    private val stockDao = database.stockDao()
    private val financeDao = database.financeDao()
    private val auditDao = database.settingsAndAuditDao()
    private val supplierRepository = SupplierRepository(database)

    val allReturns: Flow<List<PurchaseReturnEntity>> = returnDao.getAllReturns()

    fun observeReturnById(id: Long) = returnDao.getAllReturns() // Can be queried by list
    suspend fun getReturnById(id: Long): PurchaseReturnEntity? = returnDao.getReturnById(id)
    suspend fun getReturnItems(returnId: Long): List<PurchaseReturnItemEntity> = returnDao.getReturnItems(returnId)
    fun observeReturnItems(returnId: Long): Flow<List<PurchaseReturnItemEntity>> = returnDao.observeReturnItems(returnId)
    fun getReturnsForPurchase(purchaseId: Long): Flow<List<PurchaseReturnEntity>> = returnDao.getReturnsForPurchase(purchaseId)

    suspend fun generateReturnNumber(): String {
        val year = Calendar.getInstance().get(Calendar.YEAR)
        DocNumbers.next(database, DocNumbers.Series.PURCHASE_RETURN, { till, n -> DocNumbers.purchaseReturn(year, till, n) }) {
            returnDao.getReturnByNumber(it) != null
        }?.let { return it }
        val maxId = returnDao.getMaxReturnId() ?: 0L
        val count = returnDao.getReturnCount()
        val seq = maxOf(maxId + 1, (count + 1).toLong())
        var candidate = String.format(Locale.US, "PRT-%d-%06d", year, seq)
        var offset = 0
        while (returnDao.getReturnByNumber(candidate) != null) {
            offset++
            candidate = String.format(Locale.US, "PRT-%d-%06d", year, seq + offset)
        }
        return candidate
    }

    /**
     * Value credited per returned unit of a purchase line: the line's net (after its own discount, plus its tax)
     * adjusted by its share of the invoice discount and invoice tax. Transport/other costs are not refunded.
     */
    fun returnUnitValue(purchase: PurchaseEntity, item: PurchaseItemEntity): Double {
        if (item.quantity <= 0) return 0.0
        val lineNet = if (item.lineTotal > 0 || item.unitCost == 0.0) item.lineTotal
            else (item.quantity * item.unitCost) - item.discount + item.tax
        val lineShare = if (purchase.subTotal > 0) lineNet / purchase.subTotal else 0.0
        val invoiceAdjustment = (purchase.taxAmount - purchase.invoiceDiscount) * lineShare
        return roundMoney(((lineNet + invoiceAdjustment) / item.quantity).coerceAtLeast(0.0))
    }

    private fun isSerializedLine(item: PurchaseItemEntity): Boolean =
        item.trackingType == TrackingType.SERIAL || item.trackingType == TrackingType.IMEI || item.trackingType == TrackingType.SERIAL_AND_IMEI

    suspend fun getReturnableItemsForPurchase(purchaseId: Long): List<ReturnableItemInfo> {
        val purchase = purchaseDao.getPurchaseById(purchaseId) ?: return emptyList()
        val items = purchaseDao.getPurchaseItems(purchaseId)
        val result = mutableListOf<ReturnableItemInfo>()

        for (item in items) {
            val product = productDao.getProductById(item.productId) ?: continue
            val returnedQty = returnDao.getTotalReturnedQuantityForPurchaseItem(item.id) ?: 0
            val available = (item.quantity - returnedQty).coerceAtLeast(0)

            // Units bought on THIS line that are still IN_STOCK (older rows without a line id fall back to the purchase)
            val serials = productDao.getAvailableSerialsForProductSync(product.id)
                .filter { unit ->
                    if (unit.purchaseItemId > 0) unit.purchaseItemId == item.id
                    else unit.purchaseId == purchaseId
                }

            result.add(
                ReturnableItemInfo(
                    purchaseItem = item,
                    product = product,
                    alreadyReturnedQty = returnedQty,
                    availableReturnQty = available,
                    serialUnits = serials,
                    returnUnitValue = returnUnitValue(purchase, item),
                    isSerialized = isSerializedLine(item) || serials.isNotEmpty()
                )
            )
        }
        return result
    }

    suspend fun createPurchaseReturn(
        returnDraft: PurchaseReturnEntity,
        itemsToReturn: List<PurchaseReturnItemDraft>
    ): Result<Long> = runCatching {
        database.withTransaction {
            require(itemsToReturn.isNotEmpty()) { "At least one item must be selected for return." }

            val purchase = purchaseDao.getPurchaseById(returnDraft.purchaseId)
                ?: throw IllegalArgumentException("Purchase #${returnDraft.purchaseId} not found.")

            val returnNumber = if (returnDraft.returnNumber.isNotBlank()) {
                val existing = returnDao.getReturnByNumber(returnDraft.returnNumber.trim())
                if (existing != null) {
                    throw IllegalArgumentException("Purchase return #${returnDraft.returnNumber.trim()} already exists.")
                }
                returnDraft.returnNumber.trim()
            } else {
                generateReturnNumber()
            }

            if (purchase.status == "CANCELLED") {
                throw IllegalStateException("Purchase #${purchase.purchaseNumber} is cancelled; nothing can be returned.")
            }
            val purchaseLines = purchaseDao.getPurchaseItems(purchase.id).associateBy { it.id }

            // Validate per draft line: serialized lines must name a unit bought on that line, never the same unit twice
            val seenUnits = mutableSetOf<Long>()
            for (item in itemsToReturn) {
                require(item.returnQuantity > 0) { "Return quantity must be greater than zero for ${item.productName}" }
                val purchaseItem = purchaseLines[item.purchaseItemId]
                    ?: throw IllegalArgumentException("Purchase line not found for ${item.productName}")

                if (isSerializedLine(purchaseItem)) {
                    require(item.serialUnitId > 0 && item.returnQuantity == 1) {
                        "Pick the exact serial/IMEI unit of '${item.productName}' to return."
                    }
                }
                if (item.serialUnitId > 0) {
                    if (!seenUnits.add(item.serialUnitId)) {
                        throw IllegalArgumentException("Unit '${item.serialOrImei}' was selected more than once.")
                    }
                    val serialUnit = productDao.getSerialById(item.serialUnitId)
                    if (serialUnit == null || serialUnit.status != InventoryStatus.IN_STOCK) {
                        throw IllegalStateException("Serial unit '${item.serialOrImei}' is not in stock or cannot be returned.")
                    }
                    val belongsToLine = if (serialUnit.purchaseItemId > 0) serialUnit.purchaseItemId == purchaseItem.id
                        else serialUnit.purchaseId == purchase.id
                    if (!belongsToLine || serialUnit.productId != purchaseItem.productId) {
                        throw IllegalArgumentException("Unit '${item.serialOrImei}' was not bought on this purchase line.")
                    }
                }
            }

            // Validate totals per purchase line, per product and per variant (several drafts can share one line)
            for ((purchaseItemId, drafts) in itemsToReturn.groupBy { it.purchaseItemId }) {
                val purchaseItem = purchaseLines.getValue(purchaseItemId)
                val returnedSoFar = returnDao.getTotalReturnedQuantityForPurchaseItem(purchaseItemId) ?: 0
                val maxAvailable = purchaseItem.quantity - returnedSoFar
                val requested = drafts.sumOf { it.returnQuantity }
                if (requested > maxAvailable) {
                    throw IllegalArgumentException(
                        "Cannot return $requested of '${purchaseItem.productName}'. Only $maxAvailable available to return (Purchased: ${purchaseItem.quantity}, Already Returned: $returnedSoFar)."
                    )
                }
            }
            for ((productId, drafts) in itemsToReturn.groupBy { it.productId }) {
                val product = productDao.getProductById(productId)
                    ?: throw IllegalArgumentException("Product '${drafts.first().productName}' not found")
                val requested = drafts.sumOf { it.returnQuantity }
                if (product.currentStock < requested) {
                    throw IllegalStateException("Cannot return $requested units of '${product.name}'. Current physical stock is only ${product.currentStock}.")
                }
            }
            for ((variantId, drafts) in itemsToReturn.filter { it.variantId > 0 }.groupBy { it.variantId }) {
                val variant = variantDao.getVariantById(variantId) ?: continue
                val requested = drafts.sumOf { it.returnQuantity }
                if (variant.currentStock < requested) {
                    throw IllegalStateException("Cannot return $requested units of variant '${variant.variantName}'. Current variant stock is only ${variant.currentStock}.")
                }
            }

            // The credit is always computed here from the purchase line, never trusted from the caller
            val pricedItems = itemsToReturn.map { draft ->
                val unitValue = returnUnitValue(purchase, purchaseLines.getValue(draft.purchaseItemId))
                draft.copy(unitCost = unitValue, totalAmount = roundMoney(unitValue * draft.returnQuantity))
            }
            val totalAmount = roundMoney(pricedItems.sumOf { it.totalAmount })
            val refundAmount = returnDraft.refundAmount.coerceIn(0.0, totalAmount)
            val supplierCredit = totalAmount - refundAmount

            val returnEntity = returnDraft.copy(
                returnNumber = returnNumber,
                purchaseId = purchase.id,
                supplierId = purchase.supplierId,
                supplierName = purchase.supplierName,
                subtotal = totalAmount,
                totalAmount = totalAmount,
                refundAmount = refundAmount,
                supplierCredit = supplierCredit,
                status = "COMPLETED",
                createdAt = System.currentTimeMillis()
            )

            val returnId = returnDao.insertReturn(returnEntity)

            val returnItemEntities = pricedItems.map { draft ->
                PurchaseReturnItemEntity(
                    returnId = returnId,
                    purchaseItemId = draft.purchaseItemId,
                    productId = draft.productId,
                    productName = draft.productName,
                    variantId = draft.variantId,
                    quantity = draft.returnQuantity,
                    unitCost = draft.unitCost,
                    totalAmount = draft.totalAmount,
                    serialUnitId = draft.serialUnitId,
                    serialOrImei = draft.serialOrImei
                )
            }
            returnDao.insertReturnItems(returnItemEntities)

            // Reduce stock and update serial statuses
            for (draft in pricedItems) {
                val product = productDao.getProductById(draft.productId)
                if (product != null) {
                    val oldStock = product.currentStock
                    val newStock = oldStock - draft.returnQuantity

                    productDao.updateStockQuantity(product.id, -draft.returnQuantity)

                    if (draft.variantId > 0) {
                        variantDao.updateVariantStock(draft.variantId, -draft.returnQuantity)
                    }

                    stockDao.insertStockTransaction(
                        StockTransactionEntity(
                            productId = product.id,
                            productName = product.name,
                            variantId = draft.variantId,
                            transactionType = StockMovementType.PURCHASE_RETURN,
                            quantityChange = -draft.returnQuantity,
                            previousStock = oldStock,
                            newStock = newStock,
                            unitCost = draft.unitCost,
                            referenceId = returnId,
                            referenceType = "PURCHASE_RETURN",
                            serialUnitId = draft.serialUnitId,
                            balanceAfter = newStock,
                            createdBy = returnDraft.createdBy,
                            notes = "Return #$returnNumber (Purchase #${purchase.purchaseNumber}): ${draft.serialOrImei.ifBlank { "${draft.returnQuantity} units" }}"
                        )
                    )
                }

                // If serialized unit, set status to RETURNED
                if (draft.serialUnitId > 0) {
                    val unit = productDao.getSerialById(draft.serialUnitId)
                    if (unit != null) {
                        productDao.updateSerialImei(
                            unit.copy(
                                status = InventoryStatus.RETURNED,
                                notes = "Returned via Return #$returnNumber to supplier ${purchase.supplierName}",
                                updatedAt = System.currentTimeMillis()
                            )
                        )
                    }
                }
            }

            // Authoritative Supplier Ledger entry for Purchase Return (-totalAmount)
            supplierRepository.recordLedgerTransaction(
                supplierId = purchase.supplierId,
                transactionType = SupplierLedgerSign.TYPE_PURCHASE_RETURN,
                amount = totalAmount,
                referenceId = returnId,
                referenceNumber = returnNumber,
                description = "Purchase Return #$returnNumber (Purchase #${purchase.purchaseNumber}): ${returnDraft.reason.ifBlank { "Goods returned to vendor" }}",
                createdBy = returnDraft.createdBy,
                timestamp = returnDraft.returnDate
            )

            // Cash handed back by the supplier uses up the credit the return created
            if (refundAmount > 0) {
                supplierRepository.recordLedgerTransaction(
                    supplierId = purchase.supplierId,
                    transactionType = SupplierLedgerSign.TYPE_SUPPLIER_REFUND,
                    amount = refundAmount,
                    referenceId = returnId,
                    referenceNumber = returnNumber,
                    description = "Cash refund received for return #$returnNumber",
                    createdBy = returnDraft.createdBy,
                    timestamp = returnDraft.returnDate + 1
                )
            }
            supplierRepository.reallocatePurchasePayments(purchase.supplierId)

            // If cash refund was received from supplier, log it into cash register
            if (refundAmount > 0) {
                financeDao.insertCashTransaction(
                    CashRegisterTransactionEntity(
                        type = "REFUND",
                        amount = refundAmount,
                        flowDirection = "IN",
                        paymentMethod = "CASH",
                        referenceId = returnId,
                        referenceText = "Return #$returnNumber",
                        notes = "Cash refund received from supplier for return #$returnNumber"
                    )
                )
            }

            // Audit log
            auditDao.insertAuditLog(
                AuditLogEntity(
                    userRole = "ADMIN",
                    action = "PURCHASE_RETURN_CREATED",
                    module = "PURCHASES",
                    recordId = returnNumber,
                    description = "Processed purchase return #$returnNumber for purchase #${purchase.purchaseNumber} totaling ৳$totalAmount"
                )
            )

            returnId
        }
    }
}
