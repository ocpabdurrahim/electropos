package com.example.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.local.entities.ProductEntity
import com.example.data.local.entities.ShopProfileEntity
import com.example.data.local.entities.UserEntity
import com.example.data.repository.AuthRepository
import com.example.data.repository.DashboardRepository
import com.example.data.repository.DashboardSummary
import com.example.data.repository.SampleDataAndBackupRepository
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

// ------------------- SETUP WIZARD VIEWMODEL -------------------
data class SetupWizardState(
    val step: Int = 1,
    val shopName: String = "",
    val shopAddress: String = "",
    val phone: String = "",
    val alternativePhone: String = "",
    val email: String = "",
    val website: String = "",
    val currencyCode: String = "BDT",
    val currencySymbol: String = "৳",
    val defaultTaxRate: Double = 0.0,
    val defaultDiscountRate: Double = 0.0,
    val lowStockThreshold: Int = 5,
    val adminName: String = "Store Admin",
    val username: String = "admin",
    val pin: String = "",
    val confirmPin: String = "",
    val loadSampleData: Boolean = false,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val isCompleted: Boolean = false
)

class SetupViewModel(
    private val authRepository: AuthRepository,
    private val sampleDataRepository: SampleDataAndBackupRepository
) : ViewModel() {
    private val _state = MutableStateFlow(SetupWizardState())
    val state: StateFlow<SetupWizardState> = _state.asStateFlow()

    fun updateShopName(value: String) = _state.update { it.copy(shopName = value, errorMessage = null) }
    fun updateShopAddress(value: String) = _state.update { it.copy(shopAddress = value) }
    fun updatePhone(value: String) = _state.update { it.copy(phone = value) }
    fun updateAlternativePhone(value: String) = _state.update { it.copy(alternativePhone = value) }
    fun updateEmail(value: String) = _state.update { it.copy(email = value) }
    fun updateWebsite(value: String) = _state.update { it.copy(website = value) }
    fun updateCurrency(code: String, symbol: String) = _state.update { it.copy(currencyCode = code, currencySymbol = symbol) }
    fun updateTaxRate(value: Double) = _state.update { it.copy(defaultTaxRate = value) }
    fun updateDiscountRate(value: Double) = _state.update { it.copy(defaultDiscountRate = value) }
    fun updateLowStockThreshold(value: Int) = _state.update { it.copy(lowStockThreshold = value) }
    fun updateAdminName(value: String) = _state.update { it.copy(adminName = value, errorMessage = null) }
    fun updateUsername(value: String) = _state.update { it.copy(username = value, errorMessage = null) }
    fun updatePin(value: String) = _state.update { it.copy(pin = value, errorMessage = null) }
    fun updateConfirmPin(value: String) = _state.update { it.copy(confirmPin = value, errorMessage = null) }
    fun updateLoadSampleData(value: Boolean) = _state.update { it.copy(loadSampleData = value) }

    fun nextStep() {
        val current = _state.value
        when (current.step) {
            1 -> {
                if (current.shopName.isBlank()) {
                    _state.update { it.copy(errorMessage = "Shop Name is required.") }
                    return
                }
                _state.update { it.copy(step = 2, errorMessage = null) }
            }
            2 -> {
                _state.update { it.copy(step = 3, errorMessage = null) }
            }
            3 -> {
                if (current.adminName.isBlank()) {
                    _state.update { it.copy(errorMessage = "Admin Name is required.") }
                    return
                }
                if (current.username.isBlank()) {
                    _state.update { it.copy(errorMessage = "Username is required.") }
                    return
                }
                if (current.pin.length < 4) {
                    _state.update { it.copy(errorMessage = "PIN must be at least 4 digits.") }
                    return
                }
                if (current.pin != current.confirmPin) {
                    _state.update { it.copy(errorMessage = "PIN and Confirmation do not match.") }
                    return
                }
                _state.update { it.copy(step = 4, errorMessage = null) }
            }
        }
    }

    fun previousStep() {
        if (_state.value.step > 1) {
            _state.update { it.copy(step = it.step - 1, errorMessage = null) }
        }
    }

    fun completeSetup() {
        val s = _state.value
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, errorMessage = null) }
            val profile = ShopProfileEntity(
                shopName = s.shopName.trim(),
                address = s.shopAddress.trim(),
                phone = s.phone.trim(),
                alternativePhone = s.alternativePhone.trim(),
                email = s.email.trim(),
                website = s.website.trim(),
                currencyCode = s.currencyCode,
                currencySymbol = s.currencySymbol,
                defaultTaxRate = s.defaultTaxRate,
                defaultDiscountRate = s.defaultDiscountRate,
                lowStockAlertThreshold = s.lowStockThreshold,
                setupCompleted = true
            )

            val result = authRepository.completeSetup(
                shopProfile = profile,
                adminName = s.adminName,
                adminUsername = s.username,
                pin = s.pin
            )

            result.onSuccess {
                if (s.loadSampleData) {
                    sampleDataRepository.initializeDefaultDataIfEmpty()
                    sampleDataRepository.seedSampleElectronicsData()
                } else {
                    sampleDataRepository.initializeDefaultDataIfEmpty()
                }
                _state.update { it.copy(isLoading = false, isCompleted = true) }
            }.onFailure { err ->
                _state.update { it.copy(isLoading = false, errorMessage = err.message ?: "Failed to complete setup.") }
            }
        }
    }
}

// ------------------- LOGIN VIEWMODEL -------------------
data class LoginState(
    val username: String = "admin",
    val pin: String = "",
    val isPinVisible: Boolean = false,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val isSuccess: Boolean = false,
    val shopProfile: ShopProfileEntity? = null
)

class LoginViewModel(
    private val authRepository: AuthRepository
) : ViewModel() {
    private val _state = MutableStateFlow(LoginState())
    val state: StateFlow<LoginState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            authRepository.shopProfile.collect { profile ->
                _state.update { it.copy(shopProfile = profile) }
            }
        }
    }

    fun updateUsername(value: String) = _state.update { it.copy(username = value, errorMessage = null) }
    fun updatePin(value: String) = _state.update { it.copy(pin = value, errorMessage = null) }
    fun togglePinVisibility() = _state.update { it.copy(isPinVisible = !it.isPinVisible) }

    fun login() {
        val s = _state.value
        if (s.username.isBlank() || s.pin.isBlank()) {
            _state.update { it.copy(errorMessage = "Please enter both username and PIN.") }
            return
        }

        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, errorMessage = null) }
            val result = authRepository.login(s.username, s.pin)
            result.onSuccess {
                _state.update { it.copy(isLoading = false, isSuccess = true) }
            }.onFailure { err ->
                _state.update { it.copy(isLoading = false, errorMessage = err.message ?: "Invalid username or PIN.") }
            }
        }
    }
}

// ------------------- DASHBOARD VIEWMODEL -------------------
data class DashboardUiState(
    val isLoading: Boolean = true,
    val summary: DashboardSummary = DashboardSummary(),
    val lowStockProducts: List<ProductEntity> = emptyList(),
    val outOfStockProducts: List<ProductEntity> = emptyList(),
    val currentUser: UserEntity? = null,
    val shopProfile: ShopProfileEntity? = null
)

class DashboardViewModel(
    private val dashboardRepository: DashboardRepository,
    private val authRepository: AuthRepository
) : ViewModel() {
    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                dashboardRepository.getDashboardSummaryStream(),
                dashboardRepository.lowStockProducts,
                dashboardRepository.outOfStockProducts,
                authRepository.currentUser,
                dashboardRepository.shopProfile
            ) { summary, lowStock, outOfStock, user, profile ->
                DashboardUiState(
                    isLoading = false,
                    summary = summary,
                    lowStockProducts = lowStock,
                    outOfStockProducts = outOfStock,
                    currentUser = user,
                    shopProfile = profile
                )
            }.collect { state ->
                _uiState.value = state
            }
        }
    }
}

// ------------------- SHOP PROFILE VIEWMODEL -------------------
data class ShopProfileEditState(
    val shopName: String = "",
    val slogan: String = "",
    val address: String = "",
    val phone: String = "",
    val alternativePhone: String = "",
    val email: String = "",
    val website: String = "",
    val logoUri: String = "",
    val currencySymbol: String = "৳",
    val currencyCode: String = "BDT",
    val invoicePrefix: String = "INV-",
    val invoiceNextNumber: Long = 1001,
    val defaultTaxRate: Double = 0.0,
    val defaultDiscountRate: Double = 0.0,
    val lowStockThreshold: Int = 5,
    val isLoading: Boolean = false,
    val isSaved: Boolean = false,
    val message: String? = null
)

class ShopProfileViewModel(
    private val authRepository: AuthRepository
) : ViewModel() {
    private val _state = MutableStateFlow(ShopProfileEditState())
    val state: StateFlow<ShopProfileEditState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            authRepository.shopProfile.firstOrNull()?.let { p ->
                _state.value = ShopProfileEditState(
                    shopName = p.shopName,
                    slogan = p.slogan,
                    address = p.address,
                    phone = p.phone,
                    alternativePhone = p.alternativePhone,
                    email = p.email,
                    website = p.website,
                    logoUri = p.logoUri,
                    currencySymbol = p.currencySymbol,
                    currencyCode = p.currencyCode,
                    invoicePrefix = p.invoicePrefix,
                    invoiceNextNumber = p.invoiceNextNumber,
                    defaultTaxRate = p.defaultTaxRate,
                    defaultDiscountRate = p.defaultDiscountRate,
                    lowStockThreshold = p.lowStockAlertThreshold
                )
            }
        }
    }

    fun updateShopName(v: String) = _state.update { it.copy(shopName = v) }
    fun updateSlogan(v: String) = _state.update { it.copy(slogan = v) }
    fun updateAddress(v: String) = _state.update { it.copy(address = v) }
    fun updatePhone(v: String) = _state.update { it.copy(phone = v) }
    fun updateAlternativePhone(v: String) = _state.update { it.copy(alternativePhone = v) }
    fun updateEmail(v: String) = _state.update { it.copy(email = v) }
    fun updateWebsite(v: String) = _state.update { it.copy(website = v) }
    fun updateLogoUri(uri: String) = _state.update { it.copy(logoUri = uri) }
    fun updateCurrency(code: String, symbol: String) = _state.update { it.copy(currencyCode = code, currencySymbol = symbol) }
    fun updateTaxRate(v: Double) = _state.update { it.copy(defaultTaxRate = v) }
    fun updateDiscountRate(v: Double) = _state.update { it.copy(defaultDiscountRate = v) }
    fun updateLowStockThreshold(v: Int) = _state.update { it.copy(lowStockThreshold = v) }
    fun updateInvoicePrefix(v: String) = _state.update { it.copy(invoicePrefix = v) }
    fun updateInvoiceNextNumber(v: Long) = _state.update { it.copy(invoiceNextNumber = v) }

    fun save() {
        val s = _state.value
        if (s.shopName.isBlank()) {
            _state.update { it.copy(message = "Shop Name cannot be empty.") }
            return
        }

        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, message = null) }
            val existing = authRepository.shopProfile.firstOrNull() ?: ShopProfileEntity()
            val updated = existing.copy(
                shopName = s.shopName.trim(),
                slogan = s.slogan.trim(),
                address = s.address.trim(),
                phone = s.phone.trim(),
                alternativePhone = s.alternativePhone.trim(),
                email = s.email.trim(),
                website = s.website.trim(),
                logoUri = s.logoUri,
                currencyCode = s.currencyCode,
                currencySymbol = s.currencySymbol,
                invoicePrefix = s.invoicePrefix.trim(),
                invoiceNextNumber = s.invoiceNextNumber,
                defaultTaxRate = s.defaultTaxRate,
                defaultDiscountRate = s.defaultDiscountRate,
                lowStockAlertThreshold = s.lowStockThreshold
            )
            val res = authRepository.updateShopProfile(updated)
            res.onSuccess {
                _state.update { it.copy(isLoading = false, isSaved = true, message = "Profile updated successfully.") }
            }.onFailure { err ->
                _state.update { it.copy(isLoading = false, message = err.message ?: "Failed to update profile.") }
            }
        }
    }
}

// ------------------- SETTINGS VIEWMODEL -------------------
data class SettingsState(
    val currentPin: String = "",
    val newPin: String = "",
    val confirmNewPin: String = "",
    val themeMode: String = "SYSTEM",
    val rememberLogin: Boolean = false,
    val isLoading: Boolean = false,
    val message: String? = null,
    val isPinChanged: Boolean = false,
    val isResetDone: Boolean = false
)

class SettingsViewModel(
    private val authRepository: AuthRepository,
    /** Runs before a reset wipes the data (keeps a safety copy); a failure cancels the reset. */
    private val beforeReset: suspend () -> Result<Unit> = { Result.success(Unit) }
) : ViewModel() {
    private val _state = MutableStateFlow(SettingsState())
    val state: StateFlow<SettingsState> = _state.asStateFlow()

    val currentUser = authRepository.currentUser
    val shopProfile = authRepository.shopProfile

    init {
        viewModelScope.launch {
            authRepository.shopProfile.collect { profile ->
                if (profile != null) {
                    _state.update {
                        it.copy(
                            themeMode = profile.themeMode,
                            rememberLogin = profile.rememberLogin
                        )
                    }
                }
            }
        }
    }

    fun updateCurrentPin(v: String) = _state.update { it.copy(currentPin = v, message = null) }
    fun updateNewPin(v: String) = _state.update { it.copy(newPin = v, message = null) }
    fun updateConfirmNewPin(v: String) = _state.update { it.copy(confirmNewPin = v, message = null) }

    fun changePin() {
        val s = _state.value
        val user = currentUser.value ?: return

        if (s.newPin.length < 4) {
            _state.update { it.copy(message = "New PIN must be at least 4 digits.") }
            return
        }
        if (s.newPin != s.confirmNewPin) {
            _state.update { it.copy(message = "New PIN and confirmation do not match.") }
            return
        }

        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, message = null) }
            val res = authRepository.changePin(user.id, s.currentPin, s.newPin)
            res.onSuccess {
                _state.update {
                    it.copy(
                        isLoading = false,
                        isPinChanged = true,
                        currentPin = "",
                        newPin = "",
                        confirmNewPin = "",
                        message = "PIN changed successfully."
                    )
                }
            }.onFailure { err ->
                _state.update { it.copy(isLoading = false, message = err.message ?: "Failed to change PIN.") }
            }
        }
    }

    fun setThemeMode(mode: String) {
        viewModelScope.launch {
            authRepository.updateThemeMode(mode)
            _state.update { it.copy(themeMode = mode) }
        }
    }

    fun setRememberLogin(remember: Boolean) {
        viewModelScope.launch {
            authRepository.updateRememberLogin(remember)
            _state.update { it.copy(rememberLogin = remember) }
        }
    }

    fun logout(onLoggedOut: () -> Unit) {
        viewModelScope.launch {
            authRepository.logout()
            onLoggedOut()
        }
    }

    fun resetApplication(onResetCompleted: () -> Unit) {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true) }
            val safetyCopy = beforeReset()
            if (safetyCopy.isFailure) {
                _state.update {
                    it.copy(isLoading = false, message = "Reset cancelled: the safety copy could not be saved (${safetyCopy.exceptionOrNull()?.message}).")
                }
                return@launch
            }
            authRepository.resetApplication()
            _state.update { it.copy(isLoading = false, isResetDone = true) }
            onResetCompleted()
        }
    }
}
