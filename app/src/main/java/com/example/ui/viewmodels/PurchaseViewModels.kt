package com.example.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.local.entities.*
import com.example.data.repository.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class PurchaseListUiState(
    val purchases: List<PurchaseEntity> = emptyList(),
    val searchQuery: String = "",
    val selectedStatusFilter: String = "ALL", // ALL, PAID, PARTIAL, DUE, CANCELLED
    val isLoading: Boolean = false,
    val errorMessage: String? = null
)

class PurchaseListViewModel(
    private val purchaseRepository: PurchaseRepository
) : ViewModel() {

    private val _searchQuery = MutableStateFlow("")
    private val _statusFilter = MutableStateFlow("ALL")
    private val _errorMessage = MutableStateFlow<String?>(null)

    val uiState: StateFlow<PurchaseListUiState> = combine(
        _searchQuery,
        _statusFilter,
        purchaseRepository.allPurchases,
        _errorMessage
    ) { query, status, allPurchases, error ->
        val filtered = allPurchases.filter { p ->
            val matchesQuery = query.isBlank() ||
                p.purchaseNumber.contains(query, ignoreCase = true) ||
                p.supplierName.contains(query, ignoreCase = true) ||
                p.supplierInvoiceNumber.contains(query, ignoreCase = true) ||
                p.referenceNumber.contains(query, ignoreCase = true)

            val matchesStatus = when (status) {
                "ALL" -> true
                "PAID" -> p.paymentStatus == "PAID" && p.status != "CANCELLED"
                "PARTIAL" -> p.paymentStatus == "PARTIAL" && p.status != "CANCELLED"
                "DUE" -> p.paymentStatus == "DUE" && p.status != "CANCELLED"
                "CANCELLED" -> p.status == "CANCELLED"
                else -> true
            }

            matchesQuery && matchesStatus
        }

        PurchaseListUiState(
            purchases = filtered,
            searchQuery = query,
            selectedStatusFilter = status,
            errorMessage = error
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = PurchaseListUiState(isLoading = true)
    )

    fun onSearchQueryChanged(q: String) { _searchQuery.value = q }
    fun onStatusFilterChanged(status: String) { _statusFilter.value = status }
    fun clearError() { _errorMessage.value = null }
}

data class PurchaseEntryUiState(
    val purchaseNumber: String = "",
    val selectedSupplier: SupplierEntity? = null,
    val suppliers: List<SupplierEntity> = emptyList(),
    val supplierInvoiceNumber: String = "",
    val referenceNumber: String = "",
    val purchaseDate: Long = System.currentTimeMillis(),
    val paymentMethod: String = "CASH",
    val items: List<PurchaseItemDraft> = emptyList(),
    val invoiceDiscount: String = "0",
    val taxAmount: String = "0",
    val transportCost: String = "0",
    val otherCost: String = "0",
    val paidAmount: String = "0",
    val notes: String = "",
    val isSaving: Boolean = false,
    val isSavedSuccess: Boolean = false,
    val errorMessage: String? = null,
    val availableProducts: List<ProductEntity> = emptyList(),
    /** Product found by the barcode scanner, waiting for its quantity and cost. */
    val scannedProduct: ProductEntity? = null,
    val scannedVariant: ProductVariantEntity? = null,
    val variantsByProduct: Map<Long, List<ProductVariantEntity>> = emptyMap()
)

class PurchaseEntryViewModel(
    private val purchaseRepository: PurchaseRepository,
    private val supplierRepository: SupplierRepository,
    private val productRepository: ProductRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(PurchaseEntryUiState())
    val uiState = _uiState.asStateFlow()

    init {
        loadInitialData()
    }

    private fun loadInitialData() {
        viewModelScope.launch {
            val num = purchaseRepository.generatePurchaseNumber()
            supplierRepository.allSuppliers.collectLatest { sList ->
                _uiState.update { it.copy(suppliers = sList, purchaseNumber = num) }
            }
        }
        viewModelScope.launch {
            productRepository.allProducts.collectLatest { pList ->
                _uiState.update { it.copy(availableProducts = pList) }
            }
        }
        viewModelScope.launch {
            productRepository.allActiveVariants.collectLatest { variants ->
                _uiState.update { it.copy(variantsByProduct = variants.groupBy { v -> v.productId }) }
            }
        }
    }

    fun onSelectSupplier(supplier: SupplierEntity) = _uiState.update { it.copy(selectedSupplier = supplier) }
    fun onSupplierInvoiceChange(v: String) = _uiState.update { it.copy(supplierInvoiceNumber = v) }
    fun onReferenceChange(v: String) = _uiState.update { it.copy(referenceNumber = v) }
    fun onPaymentMethodChange(v: String) = _uiState.update { it.copy(paymentMethod = v) }
    fun onInvoiceDiscountChange(v: String) = _uiState.update { it.copy(invoiceDiscount = v) }
    fun onTaxAmountChange(v: String) = _uiState.update { it.copy(taxAmount = v) }
    fun onTransportCostChange(v: String) = _uiState.update { it.copy(transportCost = v) }
    fun onOtherCostChange(v: String) = _uiState.update { it.copy(otherCost = v) }
    fun onPaidAmountChange(v: String) = _uiState.update { it.copy(paidAmount = v) }
    fun onNotesChange(v: String) = _uiState.update { it.copy(notes = v) }
    fun clearError() = _uiState.update { it.copy(errorMessage = null) }

    fun onCodeScanned(code: String) {
        viewModelScope.launch {
            val match = productRepository.findByCode(code)
            if (match == null) {
                _uiState.update { it.copy(errorMessage = "No product found for '$code'. Add it under Products first.") }
            } else {
                _uiState.update { it.copy(scannedProduct = match.product, scannedVariant = match.variant, errorMessage = null) }
            }
        }
    }

    fun clearScannedProduct() = _uiState.update { it.copy(scannedProduct = null, scannedVariant = null) }

    fun addItem(item: PurchaseItemDraft) {
        _uiState.update { it.copy(items = it.items + item) }
    }

    fun removeItem(index: Int) {
        _uiState.update {
            val current = it.items.toMutableList()
            if (index in current.indices) current.removeAt(index)
            it.copy(items = current)
        }
    }

    fun getSubtotal(): Double {
        return _uiState.value.items.sumOf { (it.quantity * it.unitCost) - it.discount + it.tax }
    }

    fun getGrandTotal(): Double {
        val sub = getSubtotal()
        val invDisc = _uiState.value.invoiceDiscount.toDoubleOrNull() ?: 0.0
        val tax = _uiState.value.taxAmount.toDoubleOrNull() ?: 0.0
        val transport = _uiState.value.transportCost.toDoubleOrNull() ?: 0.0
        val other = _uiState.value.otherCost.toDoubleOrNull() ?: 0.0
        return maxOf(0.0, (sub - invDisc) + tax + transport + other)
    }

    fun getDueAmount(): Double {
        val grand = getGrandTotal()
        val paid = _uiState.value.paidAmount.toDoubleOrNull() ?: 0.0
        return maxOf(0.0, grand - paid)
    }

    fun savePurchase() {
        val state = _uiState.value
        val supplier = state.selectedSupplier
        if (supplier == null) {
            _uiState.update { it.copy(errorMessage = "Please select a supplier") }
            return
        }
        if (state.items.isEmpty()) {
            _uiState.update { it.copy(errorMessage = "Please add at least one product to the purchase") }
            return
        }

        val invDisc = state.invoiceDiscount.toDoubleOrNull() ?: 0.0
        val tax = state.taxAmount.toDoubleOrNull() ?: 0.0
        val transport = state.transportCost.toDoubleOrNull() ?: 0.0
        val other = state.otherCost.toDoubleOrNull() ?: 0.0
        val paid = state.paidAmount.toDoubleOrNull() ?: 0.0

        val purchaseDraft = PurchaseEntity(
            purchaseNumber = state.purchaseNumber,
            supplierId = supplier.id,
            supplierName = supplier.name,
            supplierInvoiceNumber = state.supplierInvoiceNumber.trim(),
            referenceNumber = state.referenceNumber.trim(),
            paymentMethod = state.paymentMethod,
            invoiceDiscount = invDisc,
            taxAmount = tax,
            transportCost = transport,
            otherCost = other,
            paidAmount = paid,
            notes = state.notes.trim()
        )

        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true, errorMessage = null) }
            purchaseRepository.createPurchase(purchaseDraft, state.items)
                .onSuccess {
                    _uiState.update { it.copy(isSaving = false, isSavedSuccess = true) }
                }
                .onFailure { err ->
                    _uiState.update { it.copy(isSaving = false, errorMessage = err.message ?: "Failed to record purchase") }
                }
        }
    }
}

data class PurchaseDetailsUiState(
    val purchase: PurchaseEntity? = null,
    val items: List<PurchaseItemEntity> = emptyList(),
    val returns: List<PurchaseReturnEntity> = emptyList(),
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val isCancelledSuccess: Boolean = false
)

class PurchaseDetailsViewModel(
    val purchaseId: Long,
    private val purchaseRepository: PurchaseRepository,
    private val purchaseReturnRepository: PurchaseReturnRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(PurchaseDetailsUiState(isLoading = true))
    val uiState = _uiState.asStateFlow()

    init {
        loadDetails()
    }

    fun loadDetails() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val p = purchaseRepository.getPurchaseById(purchaseId)
            if (p == null) {
                _uiState.update { it.copy(isLoading = false, errorMessage = "Purchase not found") }
                return@launch
            }
            val items = purchaseRepository.getPurchaseItems(purchaseId)
            val returns = purchaseReturnRepository.getReturnsForPurchase(purchaseId).firstOrNull() ?: emptyList()
            _uiState.update {
                it.copy(
                    purchase = p,
                    items = items,
                    returns = returns,
                    isLoading = false
                )
            }
        }
    }

    fun cancelPurchase(reason: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            purchaseRepository.cancelPurchase(purchaseId, reason)
                .onSuccess {
                    _uiState.update { it.copy(isLoading = false, isCancelledSuccess = true) }
                    loadDetails()
                }
                .onFailure { err ->
                    _uiState.update { it.copy(isLoading = false, errorMessage = err.message ?: "Failed to cancel purchase") }
                }
        }
    }
}

data class PurchaseReturnUiState(
    val purchase: PurchaseEntity? = null,
    val returnableItems: List<ReturnableItemInfo> = emptyList(),
    val returnSelections: Map<Long, Int> = emptyMap(), // purchaseItemId -> returnQty
    val selectedSerials: Set<Long> = emptySet(), // serialUnitIds to return
    val reason: String = "",
    val refundAmount: String = "0",
    val isSubmitting: Boolean = false,
    val isSuccess: Boolean = false,
    val errorMessage: String? = null
)

class PurchaseReturnViewModel(
    val purchaseId: Long,
    private val purchaseRepository: PurchaseRepository,
    private val returnRepository: PurchaseReturnRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(PurchaseReturnUiState())
    val uiState = _uiState.asStateFlow()

    init {
        loadData()
    }

    private fun loadData() {
        viewModelScope.launch {
            val p = purchaseRepository.getPurchaseById(purchaseId)
            val items = returnRepository.getReturnableItemsForPurchase(purchaseId)
            _uiState.update { it.copy(purchase = p, returnableItems = items) }
        }
    }

    fun onQuantityChange(purchaseItemId: Long, qty: Int) {
        _uiState.update { state ->
            val updated = state.returnSelections.toMutableMap()
            if (qty <= 0) updated.remove(purchaseItemId) else updated[purchaseItemId] = qty
            state.copy(returnSelections = updated)
        }
    }

    fun onToggleSerial(serialId: Long, purchaseItemId: Long) {
        _uiState.update { state ->
            val set = state.selectedSerials.toMutableSet()
            if (set.contains(serialId)) set.remove(serialId) else set.add(serialId)

            // Sync quantity for this serialized item
            val item = state.returnableItems.firstOrNull { it.purchaseItem.id == purchaseItemId }
            val countForLine = item?.serialUnits?.count { set.contains(it.id) } ?: 0
            val updated = state.returnSelections.toMutableMap()
            if (countForLine <= 0) updated.remove(purchaseItemId) else updated[purchaseItemId] = countForLine

            state.copy(selectedSerials = set, returnSelections = updated)
        }
    }

    fun onReasonChange(v: String) = _uiState.update { it.copy(reason = v) }
    fun onRefundAmountChange(v: String) = _uiState.update { it.copy(refundAmount = v) }
    fun clearError() = _uiState.update { it.copy(errorMessage = null) }

    fun getTotalReturnAmount(): Double {
        val state = _uiState.value
        return state.returnSelections.entries.sumOf { (itemId, qty) ->
            val line = state.returnableItems.firstOrNull { it.purchaseItem.id == itemId }
            val cost = line?.returnUnitValue ?: 0.0
            cost * qty
        }
    }

    fun submitReturn() {
        val state = _uiState.value
        val purchase = state.purchase
        if (purchase == null) return

        if (state.returnSelections.isEmpty() && state.selectedSerials.isEmpty()) {
            _uiState.update { it.copy(errorMessage = "Please select at least one unit/item to return.") }
            return
        }

        val drafts = mutableListOf<PurchaseReturnItemDraft>()

        for ((itemId, qty) in state.returnSelections) {
            val line = state.returnableItems.firstOrNull { it.purchaseItem.id == itemId } ?: continue
            val isSerialized = line.isSerialized

            if (isSerialized) {
                // Add individual serialized items
                val units = line.serialUnits.filter { state.selectedSerials.contains(it.id) }
                for (u in units) {
                    drafts.add(
                        PurchaseReturnItemDraft(
                            purchaseItemId = itemId,
                            productId = line.product.id,
                            productName = line.product.name,
                            variantId = line.purchaseItem.variantId,
                            returnQuantity = 1,
                            unitCost = line.returnUnitValue,
                            totalAmount = line.returnUnitValue,
                            serialUnitId = u.id,
                            serialOrImei = u.imei1?.ifBlank { null } ?: u.serialNumber ?: ""
                        )
                    )
                }
            } else {
                drafts.add(
                    PurchaseReturnItemDraft(
                        purchaseItemId = itemId,
                        productId = line.product.id,
                        productName = line.product.name,
                        variantId = line.purchaseItem.variantId,
                        returnQuantity = qty,
                        unitCost = line.returnUnitValue,
                        totalAmount = line.returnUnitValue * qty
                    )
                )
            }
        }

        val refund = state.refundAmount.toDoubleOrNull() ?: 0.0

        val returnEntity = PurchaseReturnEntity(
            purchaseId = purchase.id,
            supplierId = purchase.supplierId,
            supplierName = purchase.supplierName,
            refundAmount = refund,
            reason = state.reason.trim()
        )

        viewModelScope.launch {
            _uiState.update { it.copy(isSubmitting = true, errorMessage = null) }
            returnRepository.createPurchaseReturn(returnEntity, drafts)
                .onSuccess {
                    _uiState.update { it.copy(isSubmitting = false, isSuccess = true) }
                }
                .onFailure { err ->
                    _uiState.update { it.copy(isSubmitting = false, errorMessage = err.message ?: "Failed to process return") }
                }
        }
    }
}
