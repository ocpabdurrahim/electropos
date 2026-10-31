package com.example.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.local.entities.CustomerEntity
import com.example.data.local.entities.CustomerLedgerEntryEntity
import com.example.data.local.entities.CustomerPaymentEntity
import com.example.data.local.entities.SaleEntity
import com.example.data.repository.CustomerRepository
import com.example.data.repository.SaleRepository
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class CustomerListUiState(
    val customers: List<CustomerEntity> = emptyList(),
    val searchQuery: String = "",
    val totalCustomerDue: Double = 0.0,
    val isLoading: Boolean = false
)

class CustomerListViewModel(
    private val customerRepository: CustomerRepository
) : ViewModel() {
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    val uiState: StateFlow<CustomerListUiState> = combine(
        _searchQuery.flatMapLatest { query ->
            if (query.isBlank()) customerRepository.allCustomers
            else customerRepository.searchCustomers(query)
        },
        customerRepository.totalCustomerDue
    ) { customerList, totalDue ->
        CustomerListUiState(
            customers = customerList,
            searchQuery = _searchQuery.value,
            totalCustomerDue = totalDue ?: 0.0,
            isLoading = false
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), CustomerListUiState(isLoading = true))

    fun onSearchQueryChanged(query: String) {
        _searchQuery.value = query
    }
}

data class AddEditCustomerUiState(
    val id: Long = 0L,
    val customerCode: String = "",
    val name: String = "",
    val phone: String = "",
    val alternativePhone: String = "",
    val whatsApp: String = "",
    val email: String = "",
    val address: String = "",
    val city: String = "",
    val customerType: String = "REGULAR",
    val openingDue: String = "",
    val creditLimit: String = "",
    val notes: String = "",
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val errorMessage: String? = null,
    val isSuccess: Boolean = false
)

class AddEditCustomerViewModel(
    private val customerRepository: CustomerRepository,
    private val customerId: Long
) : ViewModel() {
    private val _uiState = MutableStateFlow(AddEditCustomerUiState(id = customerId))
    val uiState: StateFlow<AddEditCustomerUiState> = _uiState.asStateFlow()

    init {
        if (customerId > 0L) {
            loadCustomer()
        } else {
            generateCode()
        }
    }

    private fun generateCode() {
        viewModelScope.launch {
            val code = customerRepository.generateNextCustomerCode()
            _uiState.update { it.copy(customerCode = code) }
        }
    }

    private fun loadCustomer() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val customer = customerRepository.getCustomerById(customerId)
            if (customer != null) {
                _uiState.update {
                    it.copy(
                        customerCode = customer.customerCode,
                        name = customer.name,
                        phone = customer.phone,
                        alternativePhone = customer.alternativePhone,
                        whatsApp = customer.whatsApp,
                        email = customer.email,
                        address = customer.address,
                        city = customer.city,
                        customerType = customer.customerType,
                        openingDue = if (customer.openingDue != 0.0) customer.openingDue.toString() else "",
                        creditLimit = if (customer.creditLimit != 0.0) customer.creditLimit.toString() else "",
                        notes = customer.notes,
                        isLoading = false
                    )
                }
            } else {
                _uiState.update { it.copy(isLoading = false, errorMessage = "Customer not found") }
            }
        }
    }

    fun onNameChanged(v: String) = _uiState.update { it.copy(name = v) }
    fun onPhoneChanged(v: String) = _uiState.update { it.copy(phone = v) }
    fun onAlternativePhoneChanged(v: String) = _uiState.update { it.copy(alternativePhone = v) }
    fun onWhatsAppChanged(v: String) = _uiState.update { it.copy(whatsApp = v) }
    fun onEmailChanged(v: String) = _uiState.update { it.copy(email = v) }
    fun onAddressChanged(v: String) = _uiState.update { it.copy(address = v) }
    fun onCityChanged(v: String) = _uiState.update { it.copy(city = v) }
    fun onCustomerTypeChanged(v: String) = _uiState.update { it.copy(customerType = v) }
    fun onOpeningDueChanged(v: String) = _uiState.update { it.copy(openingDue = v) }
    fun onCreditLimitChanged(v: String) = _uiState.update { it.copy(creditLimit = v) }
    fun onNotesChanged(v: String) = _uiState.update { it.copy(notes = v) }

    fun saveCustomer() {
        val s = _uiState.value
        if (s.name.isBlank()) {
            _uiState.update { it.copy(errorMessage = "Customer name is required") }
            return
        }
        if (s.phone.isBlank()) {
            _uiState.update { it.copy(errorMessage = "Phone number is required") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true, errorMessage = null) }
            val opening = s.openingDue.toDoubleOrNull() ?: 0.0
            val limit = s.creditLimit.toDoubleOrNull() ?: 0.0

            val entity = CustomerEntity(
                id = customerId,
                customerCode = s.customerCode.trim(),
                name = s.name.trim(),
                phone = s.phone.trim(),
                alternativePhone = s.alternativePhone.trim(),
                whatsApp = s.whatsApp.trim(),
                email = s.email.trim(),
                address = s.address.trim(),
                city = s.city.trim(),
                customerType = s.customerType,
                openingDue = opening,
                currentDue = opening,
                creditLimit = limit,
                notes = s.notes.trim()
            )

            val result = customerRepository.saveCustomer(entity)
            if (result.isSuccess) {
                _uiState.update { it.copy(isSaving = false, isSuccess = true) }
            } else {
                _uiState.update {
                    it.copy(
                        isSaving = false,
                        errorMessage = result.exceptionOrNull()?.message ?: "Failed to save customer"
                    )
                }
            }
        }
    }
}

data class CustomerDetailsUiState(
    val customer: CustomerEntity? = null,
    val sales: List<SaleEntity> = emptyList(),
    val payments: List<CustomerPaymentEntity> = emptyList(),
    val ledgerEntries: List<CustomerLedgerEntryEntity> = emptyList(),
    val authoritativeBalance: Double = 0.0,
    val totalPurchasedAmount: Double = 0.0,
    val totalPaidAmount: Double = 0.0,
    val isLoading: Boolean = false,
    val errorMessage: String? = null
)

class CustomerDetailsViewModel(
    private val customerId: Long,
    private val customerRepository: CustomerRepository,
    private val saleRepository: SaleRepository
) : ViewModel() {
    private val _uiState = MutableStateFlow(CustomerDetailsUiState(isLoading = true))
    val uiState: StateFlow<CustomerDetailsUiState> = _uiState.asStateFlow()

    init {
        loadData()
    }

    private fun loadData() {
        viewModelScope.launch {
            combine(
                customerRepository.observeCustomerById(customerId),
                saleRepository.allSales.map { list -> list.filter { it.customerId == customerId } },
                customerRepository.getPaymentsForCustomer(customerId),
                customerRepository.getLedgerEntriesForCustomer(customerId)
            ) { customer, sales, payments, ledger ->
                val authBalance = customerRepository.getAuthoritativeCustomerBalance(customerId)
                val totalPurchased = sales.filter { it.status != "CANCELLED" }.sumOf { it.totalAmount }
                // Every taka received is a CUSTOMER_PAYMENT ledger entry (counter payments and later payments alike);
                // sale.paidAmount already includes allocated later payments, so adding it would count them twice
                val totalPaid = ledger.filter { it.transactionType == "CUSTOMER_PAYMENT" }.sumOf { it.amount } -
                    ledger.filter { it.transactionType == "CUSTOMER_REFUND" }.sumOf { it.amount }

                CustomerDetailsUiState(
                    customer = customer,
                    sales = sales,
                    payments = payments,
                    ledgerEntries = ledger,
                    authoritativeBalance = authBalance,
                    totalPurchasedAmount = totalPurchased,
                    totalPaidAmount = totalPaid,
                    isLoading = false
                )
            }.catch { e ->
                _uiState.update { it.copy(isLoading = false, errorMessage = e.message) }
            }.collect { state ->
                _uiState.value = state
            }
        }
    }

    fun deactivateCustomer(onComplete: () -> Unit) {
        viewModelScope.launch {
            customerRepository.deactivateCustomer(customerId)
            onComplete()
        }
    }

    fun syncBalance() {
        viewModelScope.launch {
            val synced = customerRepository.syncCustomerBalance(customerId)
            _uiState.update { it.copy(authoritativeBalance = synced) }
        }
    }
}

data class CustomerPaymentUiState(
    val customer: CustomerEntity? = null,
    val amount: String = "",
    val paymentMethod: String = "CASH",
    val referenceNumber: String = "",
    val notes: String = "",
    val isSaving: Boolean = false,
    val isSuccess: Boolean = false,
    val errorMessage: String? = null
)

class CustomerPaymentViewModel(
    private val customerId: Long,
    private val customerRepository: CustomerRepository
) : ViewModel() {
    private val _uiState = MutableStateFlow(CustomerPaymentUiState())
    val uiState: StateFlow<CustomerPaymentUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val customer = customerRepository.getCustomerById(customerId)
            _uiState.update {
                it.copy(
                    customer = customer,
                    amount = if (customer != null && customer.currentDue > 0) com.example.core.utils.CurrencyUtils.toInput(customer.currentDue) else ""
                )
            }
        }
    }

    fun onAmountChanged(v: String) = _uiState.update { it.copy(amount = v) }
    fun onPaymentMethodChanged(v: String) = _uiState.update { it.copy(paymentMethod = v) }
    fun onReferenceNumberChanged(v: String) = _uiState.update { it.copy(referenceNumber = v) }
    fun onNotesChanged(v: String) = _uiState.update { it.copy(notes = v) }

    fun submitPayment() {
        val s = _uiState.value
        val amountNum = s.amount.toDoubleOrNull()
        if (amountNum == null || amountNum <= 0) {
            _uiState.update { it.copy(errorMessage = "Please enter a valid amount greater than 0") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true, errorMessage = null) }
            val result = customerRepository.recordCustomerPayment(
                customerId = customerId,
                amount = amountNum,
                paymentMethod = s.paymentMethod,
                referenceNumber = s.referenceNumber.trim(),
                notes = s.notes.trim()
            )

            if (result.isSuccess) {
                _uiState.update { it.copy(isSaving = false, isSuccess = true) }
            } else {
                _uiState.update {
                    it.copy(
                        isSaving = false,
                        errorMessage = result.exceptionOrNull()?.message ?: "Failed to record payment"
                    )
                }
            }
        }
    }
}
