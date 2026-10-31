package com.example.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.local.entities.SupplierEntity
import com.example.data.repository.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class SupplierListUiState(
    val suppliers: List<SupplierEntity> = emptyList(),
    val searchQuery: String = "",
    val totalSupplierDue: Double = 0.0,
    val isLoading: Boolean = false,
    val errorMessage: String? = null
)

class SupplierListViewModel(
    private val supplierRepository: SupplierRepository
) : ViewModel() {

    private val _searchQuery = MutableStateFlow("")
    val searchQuery = _searchQuery.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage = _errorMessage.asStateFlow()

    val uiState: StateFlow<SupplierListUiState> = combine(
        _searchQuery,
        supplierRepository.allSuppliers,
        supplierRepository.totalSupplierDue,
        _errorMessage
    ) { query, allSuppliers, totalDue, error ->
        val filtered = if (query.isBlank()) {
            allSuppliers
        } else {
            allSuppliers.filter {
                it.name.contains(query, ignoreCase = true) ||
                it.company.contains(query, ignoreCase = true) ||
                it.phone.contains(query, ignoreCase = true) ||
                it.supplierCode.contains(query, ignoreCase = true)
            }
        }
        SupplierListUiState(
            suppliers = filtered,
            searchQuery = query,
            totalSupplierDue = totalDue ?: 0.0,
            errorMessage = error
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = SupplierListUiState(isLoading = true)
    )

    fun onSearchQueryChanged(query: String) {
        _searchQuery.value = query
    }

    fun clearError() {
        _errorMessage.value = null
    }
}

data class AddEditSupplierFormState(
    val id: Long = 0,
    val supplierCode: String = "",
    val name: String = "",
    val company: String = "",
    val phone: String = "",
    val alternativePhone: String = "",
    val whatsApp: String = "",
    val email: String = "",
    val address: String = "",
    val city: String = "",
    val openingDue: String = "0",
    val creditLimit: String = "0",
    val notes: String = "",
    val isSaving: Boolean = false,
    val errorMessage: String? = null,
    val isSavedSuccess: Boolean = false
)

class AddEditSupplierViewModel(
    private val supplierRepository: SupplierRepository,
    private val supplierId: Long = 0
) : ViewModel() {

    private val _formState = MutableStateFlow(AddEditSupplierFormState())
    val formState = _formState.asStateFlow()

    init {
        if (supplierId > 0) {
            loadSupplier(supplierId)
        } else {
            generateCode()
        }
    }

    private fun generateCode() {
        viewModelScope.launch {
            val code = supplierRepository.generateNextSupplierCode()
            _formState.update { it.copy(supplierCode = code) }
        }
    }

    private fun loadSupplier(id: Long) {
        viewModelScope.launch {
            val s = supplierRepository.getSupplierById(id)
            if (s != null) {
                _formState.value = AddEditSupplierFormState(
                    id = s.id,
                    supplierCode = s.supplierCode,
                    name = s.name,
                    company = s.company,
                    phone = s.phone,
                    alternativePhone = s.alternativePhone,
                    whatsApp = s.whatsApp,
                    email = s.email,
                    address = s.address,
                    city = s.city,
                    openingDue = s.openingDue.toString(),
                    creditLimit = s.creditLimit.toString(),
                    notes = s.notes
                )
            }
        }
    }

    fun onNameChange(v: String) = _formState.update { it.copy(name = v) }
    fun onCompanyChange(v: String) = _formState.update { it.copy(company = v) }
    fun onPhoneChange(v: String) = _formState.update { it.copy(phone = v) }
    fun onAlternativePhoneChange(v: String) = _formState.update { it.copy(alternativePhone = v) }
    fun onWhatsAppChange(v: String) = _formState.update { it.copy(whatsApp = v) }
    fun onEmailChange(v: String) = _formState.update { it.copy(email = v) }
    fun onAddressChange(v: String) = _formState.update { it.copy(address = v) }
    fun onCityChange(v: String) = _formState.update { it.copy(city = v) }
    fun onOpeningDueChange(v: String) = _formState.update { it.copy(openingDue = v) }
    fun onCreditLimitChange(v: String) = _formState.update { it.copy(creditLimit = v) }
    fun onNotesChange(v: String) = _formState.update { it.copy(notes = v) }
    fun clearError() = _formState.update { it.copy(errorMessage = null) }

    fun saveSupplier() {
        val state = _formState.value
        if (state.name.isBlank()) {
            _formState.update { it.copy(errorMessage = "Supplier name is required") }
            return
        }
        if (state.phone.isBlank()) {
            _formState.update { it.copy(errorMessage = "Phone number is required") }
            return
        }

        val openingDue = state.openingDue.toDoubleOrNull() ?: 0.0
        val creditLimit = state.creditLimit.toDoubleOrNull() ?: 0.0

        viewModelScope.launch {
            _formState.update { it.copy(isSaving = true, errorMessage = null) }
            val supplier = SupplierEntity(
                id = state.id,
                supplierCode = state.supplierCode,
                name = state.name.trim(),
                company = state.company.trim(),
                phone = state.phone.trim(),
                alternativePhone = state.alternativePhone.trim(),
                whatsApp = state.whatsApp.trim(),
                email = state.email.trim(),
                address = state.address.trim(),
                city = state.city.trim(),
                openingDue = openingDue,
                creditLimit = creditLimit,
                notes = state.notes.trim()
            )

            supplierRepository.saveSupplier(supplier)
                .onSuccess {
                    _formState.update { it.copy(isSaving = false, isSavedSuccess = true) }
                }
                .onFailure { err ->
                    _formState.update { it.copy(isSaving = false, errorMessage = err.message ?: "Failed to save supplier") }
                }
        }
    }
}

data class SupplierDetailsUiState(
    val supplier: SupplierEntity? = null,
    val summary: SupplierFinancialSummary = SupplierFinancialSummary(),
    val ledgerEntries: List<SupplierLedgerEntry> = emptyList(),
    val isLoading: Boolean = false,
    val errorMessage: String? = null
)

class SupplierDetailsViewModel(
    val supplierId: Long,
    private val supplierRepository: SupplierRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(SupplierDetailsUiState(isLoading = true))
    val uiState = _uiState.asStateFlow()

    init {
        loadDetails()
    }

    fun loadDetails() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val supplier = supplierRepository.getSupplierById(supplierId)
            if (supplier == null) {
                _uiState.update { it.copy(isLoading = false, errorMessage = "Supplier not found") }
                return@launch
            }
            val summary = supplierRepository.getSupplierFinancialSummary(supplierId)
            val ledger = supplierRepository.getSupplierLedger(supplierId)
            _uiState.update {
                it.copy(
                    supplier = supplier,
                    summary = summary,
                    ledgerEntries = ledger,
                    isLoading = false
                )
            }
        }
    }
}

data class SupplierPaymentFormState(
    val supplierId: Long,
    val supplierName: String = "",
    val currentDue: Double = 0.0,
    val paymentAmount: String = "",
    val paymentMethod: String = "CASH",
    val reference: String = "",
    val notes: String = "",
    val isSubmitting: Boolean = false,
    val isSuccess: Boolean = false,
    val errorMessage: String? = null,
    val advanceConfirmedFor: Double? = null
)

class SupplierPaymentViewModel(
    val supplierId: Long,
    private val supplierRepository: SupplierRepository,
    private val paymentRepository: SupplierPaymentRepository
) : ViewModel() {

    private val _formState = MutableStateFlow(SupplierPaymentFormState(supplierId = supplierId))
    val formState = _formState.asStateFlow()

    init {
        loadSupplier()
    }

    private fun loadSupplier() {
        viewModelScope.launch {
            val s = supplierRepository.getSupplierById(supplierId)
            if (s != null) {
                val summary = supplierRepository.getSupplierFinancialSummary(supplierId)
                _formState.update {
                    it.copy(
                        supplierName = s.name,
                        currentDue = summary.currentDue,
                        paymentAmount = if (summary.currentDue > 0) com.example.core.utils.CurrencyUtils.toInput(summary.currentDue) else ""
                    )
                }
            }
        }
    }

    fun onPaymentAmountChange(v: String) = _formState.update { it.copy(paymentAmount = v, advanceConfirmedFor = null) }
    fun onPaymentMethodChange(v: String) = _formState.update { it.copy(paymentMethod = v) }
    fun onReferenceChange(v: String) = _formState.update { it.copy(reference = v) }
    fun onNotesChange(v: String) = _formState.update { it.copy(notes = v) }
    fun clearError() = _formState.update { it.copy(errorMessage = null) }

    fun submitPayment() {
        val state = _formState.value
        val amount = state.paymentAmount.toDoubleOrNull() ?: 0.0
        if (amount <= 0) {
            _formState.update { it.copy(errorMessage = "Payment amount must be greater than zero") }
            return
        }
        val extra = amount - state.currentDue.coerceAtLeast(0.0)
        if (extra > 0.005 && state.advanceConfirmedFor != amount) {
            _formState.update {
                it.copy(
                    advanceConfirmedFor = amount,
                    errorMessage = "This is ৳${com.example.core.utils.CurrencyUtils.toInput(extra)} more than the current due. " +
                        "The extra will be kept as an advance with this supplier. Tap \"Confirm Payment Voucher\" again to go ahead."
                )
            }
            return
        }

        viewModelScope.launch {
            _formState.update { it.copy(isSubmitting = true, errorMessage = null) }
            paymentRepository.recordSupplierPayment(
                supplierId = supplierId,
                amount = amount,
                paymentMethod = state.paymentMethod,
                referenceNumber = state.reference,
                notes = state.notes
            ).onSuccess {
                _formState.update { it.copy(isSubmitting = false, isSuccess = true) }
            }.onFailure { err ->
                _formState.update { it.copy(isSubmitting = false, errorMessage = err.message ?: "Failed to process payment") }
            }
        }
    }
}
