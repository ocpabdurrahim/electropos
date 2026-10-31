package com.example.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.local.entities.*
import com.example.data.repository.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class POSCartItem(
    val cartItemId: String = java.util.UUID.randomUUID().toString(),
    val product: ProductEntity,
    val variant: ProductVariantEntity? = null,
    val quantity: Int = 1,
    val unitPrice: Double = product.retailPrice,
    val discount: Double = 0.0,
    val taxPercent: Double = product.taxPercent,
    val selectedSerialImeiIds: List<Long> = emptyList(),
    val selectedSerialsOrImeis: List<String> = emptyList()
) {
    val lineGross: Double get() = quantity * unitPrice
    val lineTax: Double get() = (lineGross - discount) * (taxPercent / 100.0)
    val lineTotal: Double get() = (lineGross - discount + lineTax).coerceAtLeast(0.0)
    val requiresSerials: Boolean
        get() = TrackingType.requiresSerial(product.trackingType) || TrackingType.requiresImei(product.trackingType)
}

data class POSUiState(
    val cartItems: List<POSCartItem> = emptyList(),
    val selectedCustomer: CustomerEntity? = null,
    val searchQuery: String = "",
    val selectedCategory: String? = null,
    val products: List<ProductEntity> = emptyList(),
    val invoiceDiscount: Double = 0.0,
    val invoiceDiscountInput: String = "",
    val paidAmountInput: String = "",
    val paymentMethod: String = "CASH",
    val notes: String = "",
    val isCheckingOut: Boolean = false,
    val isCompleted: Boolean = false,
    val completedInvoiceNumber: String? = null,
    val completedSaleId: Long? = null,
    val errorMessage: String? = null,
    val serialPickerTargetItem: POSCartItem? = null,
    val availableSerialsForPicker: List<ProductSerialImeiEntity> = emptyList(),
    val isSerialPickerOpen: Boolean = false,
    val variantsByProduct: Map<Long, List<ProductVariantEntity>> = emptyMap(),
    /** Product whose variant the cashier is choosing (dialog open). */
    val variantPickerProduct: ProductEntity? = null
) {
    /** Units in stock that were bought before the product got variants (sold as "no variant"). */
    fun unassignedStock(product: ProductEntity): Int =
        (product.currentStock - (variantsByProduct[product.id] ?: emptyList()).sumOf { it.currentStock }).coerceAtLeast(0)

    val subtotal: Double get() = cartItems.sumOf { it.lineGross }
    val itemDiscounts: Double get() = cartItems.sumOf { it.discount }
    val totalTax: Double get() = cartItems.sumOf { it.lineTax }
    val grandTotal: Double get() = (cartItems.sumOf { it.lineTotal } - invoiceDiscount).coerceAtLeast(0.0)
    val paidAmount: Double get() = paidAmountInput.toDoubleOrNull() ?: 0.0
    val dueAmount: Double get() = (grandTotal - paidAmount).coerceAtLeast(0.0)
    val changeAmount: Double get() = (paidAmount - grandTotal).coerceAtLeast(0.0)
    val totalItemsCount: Int get() = cartItems.sumOf { it.quantity }
}

class POSViewModel(
    private val saleRepository: SaleRepository,
    private val productRepository: ProductRepository,
    private val customerRepository: CustomerRepository,
    private val salespersonName: () -> String = { "Admin" }
) : ViewModel() {
    private val _uiState = MutableStateFlow(POSUiState())
    val uiState: StateFlow<POSUiState> = _uiState.asStateFlow()

    val activeCustomers: Flow<List<CustomerEntity>> = customerRepository.allActiveCustomers

    init {
        loadProducts()
    }

    private fun loadProducts() {
        viewModelScope.launch {
            productRepository.activeProducts.collect { productList ->
                _uiState.update { it.copy(products = productList) }
            }
        }
        viewModelScope.launch {
            productRepository.allActiveVariants.collect { variants ->
                _uiState.update { it.copy(variantsByProduct = variants.groupBy { v -> v.productId }) }
            }
        }
    }

    /** Add button: products with variants first ask which variant. */
    fun requestAdd(product: ProductEntity) {
        if (_uiState.value.variantsByProduct[product.id].isNullOrEmpty()) addToCart(product)
        else _uiState.update { it.copy(variantPickerProduct = product) }
    }

    fun pickVariant(variant: ProductVariantEntity?) {
        val product = _uiState.value.variantPickerProduct ?: return
        _uiState.update { it.copy(variantPickerProduct = null) }
        addToCart(product, variant)
    }

    fun dismissVariantPicker() = _uiState.update { it.copy(variantPickerProduct = null) }

    /** Stock available for one cart line: the variant's own stock, or the product's stock not assigned to a variant. */
    private fun availableFor(state: POSUiState, product: ProductEntity, variant: ProductVariantEntity?): Int {
        val variants = state.variantsByProduct[product.id].orEmpty()
        return when {
            variant != null -> variants.firstOrNull { it.id == variant.id }?.currentStock ?: variant.currentStock
            variants.isNotEmpty() -> state.unassignedStock(product)
            else -> product.currentStock
        }
    }

    fun onSearchQueryChanged(q: String) {
        _uiState.update { it.copy(searchQuery = q) }
    }

    fun onCategorySelected(cat: String?) {
        _uiState.update { it.copy(selectedCategory = cat) }
    }

    fun selectCustomer(customer: CustomerEntity?) {
        _uiState.update { it.copy(selectedCustomer = customer) }
    }

    fun addToCart(product: ProductEntity, variant: ProductVariantEntity? = null) {
        _uiState.update { state ->
            val existingIndex = state.cartItems.indexOfFirst {
                it.product.id == product.id && it.variant?.id == variant?.id
            }

            val available = availableFor(state, product, variant)
            val label = product.name + (variant?.let { " (${it.variantName})" } ?: "")
            if (existingIndex >= 0 && !TrackingType.requiresSerial(product.trackingType) && !TrackingType.requiresImei(product.trackingType)) {
                val current = state.cartItems[existingIndex]
                val newQty = current.quantity + 1
                if (newQty > available) {
                    return@update state.copy(errorMessage = "Insufficient stock for '$label' (Available: $available)")
                }
                val updatedList = state.cartItems.toMutableList()
                updatedList[existingIndex] = current.copy(quantity = newQty)
                state.copy(cartItems = updatedList, errorMessage = null)
            } else {
                if (available < 1) {
                    return@update state.copy(errorMessage = "'$label' is out of stock")
                }
                val newItem = POSCartItem(
                    product = product,
                    variant = variant,
                    quantity = 1,
                    unitPrice = variant?.retailPrice?.takeIf { it > 0 } ?: product.retailPrice
                )
                state.copy(cartItems = state.cartItems + newItem, errorMessage = null)
            }
        }
    }

    /** Adds whatever a scanned/typed code points to: a product, a variant, or one exact IMEI/serial unit. */
    fun addByCode(code: String) {
        viewModelScope.launch {
            val match = productRepository.findByCode(code)
            when {
                match == null -> _uiState.update { it.copy(errorMessage = "No product found for '$code'") }
                match.unit != null -> addSerialUnitToCart(match.product, match.unit, match.variant)
                else -> addToCart(match.product, match.variant)
            }
        }
    }

    private fun addSerialUnitToCart(product: ProductEntity, unit: ProductSerialImeiEntity, variant: ProductVariantEntity? = null) {
        val label = unit.imei1?.takeIf { it.isNotBlank() } ?: unit.serialNumber ?: "SN-${unit.id}"
        if (unit.status != InventoryStatus.IN_STOCK) {
            _uiState.update { it.copy(errorMessage = "'$label' is not in stock (status: ${unit.status})") }
            return
        }
        _uiState.update { state ->
            val index = state.cartItems.indexOfFirst { it.product.id == product.id && it.variant?.id == variant?.id }
            if (index >= 0) {
                val line = state.cartItems[index]
                if (line.selectedSerialImeiIds.contains(unit.id)) {
                    return@update state.copy(errorMessage = "'$label' is already in the cart")
                }
                val newQty = maxOf(line.quantity, line.selectedSerialImeiIds.size + 1)
                if (newQty > product.currentStock) {
                    return@update state.copy(errorMessage = "Insufficient stock for '${product.name}' (Available: ${product.currentStock})")
                }
                val updated = state.cartItems.toMutableList()
                updated[index] = line.copy(
                    quantity = newQty,
                    selectedSerialImeiIds = line.selectedSerialImeiIds + unit.id,
                    selectedSerialsOrImeis = line.selectedSerialsOrImeis + label
                )
                state.copy(cartItems = updated, errorMessage = null)
            } else {
                val newItem = POSCartItem(
                    product = product,
                    variant = variant,
                    quantity = 1,
                    unitPrice = variant?.retailPrice?.takeIf { it > 0 } ?: product.retailPrice,
                    selectedSerialImeiIds = listOf(unit.id),
                    selectedSerialsOrImeis = listOf(label)
                )
                state.copy(cartItems = state.cartItems + newItem, errorMessage = null)
            }
        }
    }

    fun updateQuantity(cartItemId: String, newQty: Int) {
        if (newQty <= 0) {
            removeFromCart(cartItemId)
            return
        }
        _uiState.update { state ->
            val updated = state.cartItems.map { item ->
                if (item.cartItemId == cartItemId) {
                    val available = availableFor(state, item.product, item.variant)
                    if (newQty > available) {
                        return@update state.copy(errorMessage = "Cannot exceed available stock ($available) for '${item.product.name}'")
                    }
                    if (item.requiresSerials && newQty < item.selectedSerialImeiIds.size) {
                        item.copy(
                            quantity = newQty,
                            selectedSerialImeiIds = item.selectedSerialImeiIds.take(newQty),
                            selectedSerialsOrImeis = item.selectedSerialsOrImeis.take(newQty)
                        )
                    } else {
                        item.copy(quantity = newQty)
                    }
                } else item
            }
            state.copy(cartItems = updated, errorMessage = null)
        }
    }

    fun updateItemDiscount(cartItemId: String, discount: Double) {
        _uiState.update { state ->
            val updated = state.cartItems.map { item ->
                if (item.cartItemId == cartItemId) item.copy(discount = discount.coerceIn(0.0, item.lineGross))
                else item
            }
            state.copy(cartItems = updated)
        }
    }

    /** Changes the selling price and line discount of a cart line; returns an error message or null when applied. */
    fun updateItemPrice(cartItemId: String, unitPrice: Double, discount: Double): String? {
        val item = _uiState.value.cartItems.find { it.cartItemId == cartItemId } ?: return null
        if (unitPrice <= 0) return "Price must be more than zero"
        if (discount < 0) return "Discount cannot be negative"
        val gross = unitPrice * item.quantity
        if (discount > gross) return "Discount cannot be more than the line total"
        val minPrice = item.variant?.minSellingPrice?.takeIf { it > 0 } ?: item.product.minSellingPrice
        val effectiveUnit = (gross - discount) / item.quantity
        if (minPrice > 0 && effectiveUnit + 0.005 < minPrice) {
            return "Below the minimum selling price (${com.example.core.utils.CurrencyUtils.format(minPrice)} each)"
        }
        _uiState.update { state ->
            state.copy(cartItems = state.cartItems.map {
                if (it.cartItemId == cartItemId) it.copy(unitPrice = unitPrice, discount = discount) else it
            }, errorMessage = null)
        }
        return null
    }

    fun removeFromCart(cartItemId: String) {
        _uiState.update { state ->
            state.copy(cartItems = state.cartItems.filterNot { it.cartItemId == cartItemId })
        }
    }

    fun clearCart() {
        _uiState.update { it.copy(cartItems = emptyList(), invoiceDiscount = 0.0, invoiceDiscountInput = "", paidAmountInput = "", notes = "", errorMessage = null) }
    }

    fun onInvoiceDiscountChanged(discount: Double) {
        _uiState.update { it.copy(invoiceDiscount = discount.coerceAtLeast(0.0)) }
    }

    fun onInvoiceDiscountInputChanged(text: String) {
        _uiState.update { state ->
            val linesTotal = state.cartItems.sumOf { it.lineTotal }
            state.copy(
                invoiceDiscountInput = text,
                invoiceDiscount = (text.toDoubleOrNull() ?: 0.0).coerceIn(0.0, linesTotal)
            )
        }
    }

    fun onPaidAmountInputChanged(v: String) {
        _uiState.update { it.copy(paidAmountInput = v) }
    }

    fun setQuickPaidExact() {
        _uiState.update { it.copy(paidAmountInput = com.example.core.utils.CurrencyUtils.toInput(it.grandTotal)) }
    }

    fun onPaymentMethodChanged(method: String) {
        _uiState.update { it.copy(paymentMethod = method) }
    }

    fun onNotesChanged(notes: String) {
        _uiState.update { it.copy(notes = notes) }
    }

    fun openSerialPicker(cartItemId: String) {
        val item = _uiState.value.cartItems.find { it.cartItemId == cartItemId } ?: return
        viewModelScope.launch {
            // Only units of the chosen variant (or units without one, for a "no variant" line)
            val hasVariants = _uiState.value.variantsByProduct[item.product.id].orEmpty().isNotEmpty()
            val serials = productRepository.getAvailableSerialsForProductSync(item.product.id)
                .filter { !hasVariants || it.variantId == (item.variant?.id ?: 0L) }
            _uiState.update {
                it.copy(
                    serialPickerTargetItem = item,
                    availableSerialsForPicker = serials,
                    isSerialPickerOpen = true
                )
            }
        }
    }

    fun closeSerialPicker() {
        _uiState.update { it.copy(isSerialPickerOpen = false, serialPickerTargetItem = null) }
    }

    fun toggleSerialSelection(serial: ProductSerialImeiEntity) {
        val target = _uiState.value.serialPickerTargetItem ?: return
        val currentIds = target.selectedSerialImeiIds.toMutableList()
        val currentLabels = target.selectedSerialsOrImeis.toMutableList()
        val label = serial.imei1?.takeIf { it.isNotBlank() } ?: serial.serialNumber ?: "SN-${serial.id}"

        if (currentIds.contains(serial.id)) {
            val idx = currentIds.indexOf(serial.id)
            currentIds.removeAt(idx)
            currentLabels.removeAt(idx)
        } else {
            if (currentIds.size >= target.quantity) {
                // If capacity full, replace the last or warn
                return
            }
            currentIds.add(serial.id)
            currentLabels.add(label)
        }

        val updatedTarget = target.copy(
            selectedSerialImeiIds = currentIds,
            selectedSerialsOrImeis = currentLabels
        )

        _uiState.update { state ->
            val updatedCart = state.cartItems.map { if (it.cartItemId == target.cartItemId) updatedTarget else it }
            state.copy(cartItems = updatedCart, serialPickerTargetItem = updatedTarget)
        }
    }

    fun completeSale() {
        val s = _uiState.value
        if (s.cartItems.isEmpty()) {
            _uiState.update { it.copy(errorMessage = "Cart is empty") }
            return
        }

        // Check serialized units fulfilled
        for (item in s.cartItems) {
            if (item.requiresSerials && item.selectedSerialImeiIds.size != item.quantity) {
                _uiState.update {
                    it.copy(errorMessage = "Please select all serials/IMEIs for '${item.product.name}' (${item.selectedSerialImeiIds.size}/${item.quantity} selected)")
                }
                return
            }
        }

        // Walk-in credit restriction
        if (s.dueAmount > 0.001 && s.selectedCustomer == null) {
            _uiState.update { it.copy(errorMessage = "Walk-in customers cannot have unpaid credit. Please select a registered customer or pay in full.") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isCheckingOut = true, errorMessage = null) }

            val saleEntity = SaleEntity(
                invoiceNumber = "", // Auto-generated
                customerId = s.selectedCustomer?.id ?: 0L,
                customerName = s.selectedCustomer?.name ?: "Walk-in Customer",
                customerPhone = s.selectedCustomer?.phone ?: "",
                saleDate = System.currentTimeMillis(),
                subTotal = s.subtotal,
                itemDiscount = s.itemDiscounts,
                invoiceDiscount = s.invoiceDiscount,
                discountAmount = s.itemDiscounts + s.invoiceDiscount,
                taxAmount = s.totalTax,
                totalAmount = s.grandTotal,
                paidAmount = s.paidAmount.coerceAtMost(s.grandTotal),
                dueAmount = s.dueAmount,
                paymentStatus = if (s.dueAmount <= 0.001) "PAID" else if (s.paidAmount <= 0.001) "DUE" else "PARTIAL",
                paymentMethod = s.paymentMethod,
                notes = s.notes,
                status = "COMPLETED",
                salespersonName = salespersonName()
            )

            val itemsInput = s.cartItems.map { item ->
                SaleItemInput(
                    productId = item.product.id,
                    productName = item.product.name,
                    variantId = item.variant?.id ?: 0L,
                    quantity = item.quantity,
                    unitPrice = item.unitPrice,
                    discount = item.discount,
                    tax = item.lineTax,
                    trackingType = item.product.trackingType,
                    selectedSerialImeiIds = item.selectedSerialImeiIds,
                    selectedSerialsOrImeis = item.selectedSerialsOrImeis
                )
            }

            val result = saleRepository.createSale(saleEntity, itemsInput)
            if (result.isSuccess) {
                val saleId = result.getOrThrow()
                val createdSale = saleRepository.getSaleById(saleId)
                _uiState.update {
                    it.copy(
                        isCheckingOut = false,
                        isCompleted = true,
                        completedSaleId = saleId,
                        completedInvoiceNumber = createdSale?.invoiceNumber
                    )
                }
            } else {
                _uiState.update {
                    it.copy(
                        isCheckingOut = false,
                        errorMessage = result.exceptionOrNull()?.message ?: "Sale failed to complete"
                    )
                }
            }
        }
    }

    fun resetSaleState() {
        // Keep the product list: its collector from init is still running
        _uiState.value = POSUiState(products = _uiState.value.products)
    }
}

data class SalesHistoryUiState(
    val sales: List<SaleEntity> = emptyList(),
    val searchQuery: String = "",
    val statusFilter: String = "ALL", // ALL, COMPLETED, CANCELLED
    val paymentFilter: String = "ALL", // ALL, PAID, PARTIAL, DUE
    val totalSalesAmount: Double = 0.0,
    val totalDueAmount: Double = 0.0,
    val isLoading: Boolean = false
)

class SalesHistoryViewModel(
    private val saleRepository: SaleRepository
) : ViewModel() {
    private val _searchQuery = MutableStateFlow("")
    private val _statusFilter = MutableStateFlow("ALL")
    private val _paymentFilter = MutableStateFlow("ALL")

    val uiState: StateFlow<SalesHistoryUiState> = combine(
        saleRepository.allSales,
        _searchQuery,
        _statusFilter,
        _paymentFilter
    ) { allSales, query, status, payment ->
        var filtered = allSales

        if (query.isNotBlank()) {
            val q = query.trim().lowercase()
            filtered = filtered.filter {
                it.invoiceNumber.lowercase().contains(q) ||
                it.customerName.lowercase().contains(q) ||
                it.customerPhone.lowercase().contains(q)
            }
        }

        if (status != "ALL") {
            filtered = filtered.filter { it.status == status }
        }

        if (payment != "ALL") {
            filtered = filtered.filter { it.paymentStatus == payment }
        }

        val totalAmount = filtered.filter { it.status != "CANCELLED" }.sumOf { it.totalAmount }
        val totalDue = filtered.filter { it.status != "CANCELLED" }.sumOf { it.dueAmount }

        SalesHistoryUiState(
            sales = filtered,
            searchQuery = query,
            statusFilter = status,
            paymentFilter = payment,
            totalSalesAmount = totalAmount,
            totalDueAmount = totalDue,
            isLoading = false
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SalesHistoryUiState(isLoading = true))

    fun onSearchQueryChanged(q: String) { _searchQuery.value = q }
    fun onStatusFilterChanged(s: String) { _statusFilter.value = s }
    fun onPaymentFilterChanged(p: String) { _paymentFilter.value = p }
}

data class SaleDetailsUiState(
    val sale: SaleEntity? = null,
    val items: List<SaleItemEntity> = emptyList(),
    val serials: List<ProductSerialImeiEntity> = emptyList(),
    val returns: List<SalesReturnEntity> = emptyList(),
    val isLoading: Boolean = false,
    val isCancelling: Boolean = false,
    val isCancelledSuccess: Boolean = false,
    val errorMessage: String? = null
)

class SaleDetailsViewModel(
    private val saleId: Long,
    private val saleRepository: SaleRepository,
    private val salesReturnRepository: SalesReturnRepository
) : ViewModel() {
    private val _uiState = MutableStateFlow(SaleDetailsUiState(isLoading = true))
    val uiState: StateFlow<SaleDetailsUiState> = _uiState.asStateFlow()

    init {
        loadData()
    }

    private fun loadData() {
        viewModelScope.launch {
            combine(
                saleRepository.allSales.map { list -> list.find { it.id == saleId } },
                saleRepository.observeSaleItems(saleId),
                salesReturnRepository.getReturnsForSale(saleId)
            ) { sale, items, returns ->
                SaleDetailsUiState(
                    sale = sale,
                    items = items,
                    returns = returns,
                    isLoading = false
                )
            }.collect { state ->
                _uiState.value = state
            }
        }
    }

    fun cancelSale(reason: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isCancelling = true, errorMessage = null) }
            val result = saleRepository.cancelSale(saleId, reason)
            if (result.isSuccess) {
                _uiState.update { it.copy(isCancelling = false, isCancelledSuccess = true) }
            } else {
                _uiState.update {
                    it.copy(
                        isCancelling = false,
                        errorMessage = result.exceptionOrNull()?.message ?: "Failed to cancel sale"
                    )
                }
            }
        }
    }
}

data class SalesReturnItemSelection(
    val saleItem: SaleItemEntity,
    val alreadyReturnedQty: Int,
    /** What the customer actually paid per unit (after line + invoice discount, incl. tax). */
    val unitRefundValue: Double = saleItem.unitPrice,
    val returnQuantity: Int = 0,
    val isSelected: Boolean = false,
    val selectedSerialIds: List<Long> = emptyList(),
    val availableSerials: List<ProductSerialImeiEntity> = emptyList()
) {
    val isSerialized: Boolean
        get() = TrackingType.requiresSerial(saleItem.trackingType) || TrackingType.requiresImei(saleItem.trackingType)
    val remainingReturnable: Int get() = (saleItem.quantity - alreadyReturnedQty).coerceAtLeast(0)
    val returnSubtotal: Double get() = returnQuantity * unitRefundValue
}

fun ProductSerialImeiEntity.displayLabel(): String =
    imei1?.takeIf { it.isNotBlank() } ?: serialNumber?.takeIf { it.isNotBlank() } ?: "Unit-$id"

data class SalesReturnUiState(
    val sale: SaleEntity? = null,
    val itemSelections: List<SalesReturnItemSelection> = emptyList(),
    val reason: String = "",
    val refundAmount: String = "",
    val refundMethod: String = "CASH",
    val restockOption: String = "RESTOCK_SELLABLE", // RESTOCK_SELLABLE, DEFECTIVE_DAMAGED
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val isSuccess: Boolean = false,
    val errorMessage: String? = null
) {
    val totalReturnAmount: Double get() = itemSelections.filter { it.isSelected }.sumOf { it.returnSubtotal }
    /** Walk-in buyers have no account to keep a credit, so they always get the full value back. */
    val isWalkIn: Boolean get() = (sale?.customerId ?: 0L) <= 0L
    val refundAmountDouble: Double
        get() = if (isWalkIn) totalReturnAmount else (refundAmount.toDoubleOrNull() ?: 0.0).coerceIn(0.0, totalReturnAmount)
    val customerCredit: Double get() = (totalReturnAmount - refundAmountDouble).coerceAtLeast(0.0)
}

class SalesReturnViewModel(
    private val saleId: Long,
    private val saleRepository: SaleRepository,
    private val salesReturnRepository: SalesReturnRepository,
    private val productRepository: ProductRepository,
    private val operatorName: () -> String = { "Admin" }
) : ViewModel() {
    private val _uiState = MutableStateFlow(SalesReturnUiState(isLoading = true))
    val uiState: StateFlow<SalesReturnUiState> = _uiState.asStateFlow()

    init {
        loadData()
    }

    private fun loadData() {
        viewModelScope.launch {
            val sale = saleRepository.getSaleById(saleId)
            val items = saleRepository.getSaleItems(saleId)
            val soldUnits = productRepository.getSerialsForSaleSync(saleId).filter { it.status == InventoryStatus.SOLD }

            val selections = items.map { item ->
                val alreadyReturned = salesReturnRepository.getReturnedQuantityForSaleItem(item.id)
                SalesReturnItemSelection(
                    saleItem = item,
                    alreadyReturnedQty = alreadyReturned,
                    unitRefundValue = sale?.let { salesReturnRepository.refundUnitValue(it, item) } ?: item.unitPrice,
                    // Units of this product sold on this invoice and not yet returned
                    availableSerials = soldUnits.filter { it.productId == item.productId }
                )
            }

            _uiState.update {
                it.copy(
                    sale = sale,
                    itemSelections = selections,
                    isLoading = false
                )
            }
        }
    }

    fun toggleItemSelection(saleItemId: Long) {
        _uiState.update { state ->
            val updated = state.itemSelections.map { sel ->
                if (sel.saleItem.id == saleItemId) {
                    val newSelected = !sel.isSelected
                    when {
                        !newSelected -> sel.copy(isSelected = false, returnQuantity = 0, selectedSerialIds = emptyList())
                        sel.isSerialized -> sel.copy(isSelected = true, returnQuantity = sel.selectedSerialIds.size)
                        else -> sel.copy(isSelected = true, returnQuantity = if (sel.returnQuantity == 0) 1 else sel.returnQuantity)
                    }
                } else sel
            }
            state.copy(itemSelections = updated)
        }
    }

    fun updateReturnQuantity(saleItemId: Long, qty: Int) {
        _uiState.update { state ->
            val updated = state.itemSelections.map { sel ->
                if (sel.saleItem.id == saleItemId && !sel.isSerialized) {
                    val clamped = qty.coerceIn(0, sel.remainingReturnable)
                    sel.copy(returnQuantity = clamped, isSelected = clamped > 0)
                } else sel
            }
            state.copy(itemSelections = updated)
        }
    }

    fun toggleSerialUnit(saleItemId: Long, serialId: Long) {
        _uiState.update { state ->
            val updated = state.itemSelections.map { sel ->
                if (sel.saleItem.id == saleItemId) {
                    val ids = sel.selectedSerialIds.toMutableList()
                    if (ids.contains(serialId)) ids.remove(serialId)
                    else if (ids.size < sel.remainingReturnable) ids.add(serialId)
                    sel.copy(selectedSerialIds = ids, returnQuantity = ids.size, isSelected = true)
                } else sel
            }
            state.copy(itemSelections = updated, errorMessage = null)
        }
    }

    fun onReasonChanged(r: String) = _uiState.update { it.copy(reason = r) }
    fun onRefundAmountChanged(a: String) = _uiState.update { it.copy(refundAmount = a) }
    fun onRefundMethodChanged(m: String) = _uiState.update { it.copy(refundMethod = m) }
    fun onRestockOptionChanged(o: String) = _uiState.update { it.copy(restockOption = o) }

    fun submitReturn() {
        val s = _uiState.value
        val missingUnits = s.itemSelections.firstOrNull { it.isSelected && it.isSerialized && it.selectedSerialIds.isEmpty() }
        if (missingUnits != null) {
            _uiState.update { it.copy(errorMessage = "Tick the IMEI/serial of each '${missingUnits.saleItem.productName}' being returned") }
            return
        }
        val selectedItems = s.itemSelections.filter { it.isSelected && it.returnQuantity > 0 }
        if (selectedItems.isEmpty()) {
            _uiState.update { it.copy(errorMessage = "Please select at least one item to return") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true, errorMessage = null) }

            val draft = SalesReturnDraft(
                saleId = saleId,
                returnDate = System.currentTimeMillis(),
                reason = s.reason.trim(),
                refundAmount = s.refundAmountDouble,
                customerCredit = s.customerCredit,
                refundPaymentMethod = s.refundMethod,
                createdBy = operatorName()
            )

            val itemDrafts = selectedItems.flatMap { sel ->
                if (sel.isSerialized) {
                    sel.selectedSerialIds.map { unitId ->
                        SalesReturnItemDraft(
                            saleItemId = sel.saleItem.id,
                            productId = sel.saleItem.productId,
                            productName = sel.saleItem.productName,
                            variantId = sel.saleItem.variantId,
                            returnQuantity = 1,
                            unitPrice = sel.unitRefundValue,
                            totalAmount = sel.unitRefundValue,
                            serialUnitId = unitId,
                            serialOrImei = sel.availableSerials.firstOrNull { it.id == unitId }?.displayLabel() ?: "",
                            restockOption = s.restockOption
                        )
                    }
                } else {
                    listOf(
                        SalesReturnItemDraft(
                            saleItemId = sel.saleItem.id,
                            productId = sel.saleItem.productId,
                            productName = sel.saleItem.productName,
                            variantId = sel.saleItem.variantId,
                            returnQuantity = sel.returnQuantity,
                            unitPrice = sel.unitRefundValue,
                            totalAmount = sel.returnSubtotal,
                            restockOption = s.restockOption
                        )
                    )
                }
            }

            val result = salesReturnRepository.createSalesReturn(draft, itemDrafts)
            if (result.isSuccess) {
                _uiState.update { it.copy(isSaving = false, isSuccess = true) }
            } else {
                _uiState.update {
                    it.copy(
                        isSaving = false,
                        errorMessage = result.exceptionOrNull()?.message ?: "Failed to process return"
                    )
                }
            }
        }
    }
}
