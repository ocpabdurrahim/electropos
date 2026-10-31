package com.example.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.core.utils.BarcodeUtils
import com.example.core.utils.SkuUtils
import com.example.data.local.entities.*
import com.example.data.repository.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

// ------------------- PRODUCT LIST VIEWMODEL -------------------
enum class ProductSortOption {
    NAME_ASC, NAME_DESC, PRICE_ASC, PRICE_DESC, STOCK_ASC, STOCK_DESC, NEWEST
}

enum class StockFilterOption {
    ALL, IN_STOCK, LOW_STOCK, OUT_OF_STOCK
}

data class ProductListFilterState(
    val query: String = "",
    val categoryId: Long? = null,
    val brandId: Long? = null,
    val subcategoryId: Long? = null,
    val trackingType: String? = null, // QUANTITY, SERIAL, IMEI, SERIAL_AND_IMEI
    val stockFilter: StockFilterOption = StockFilterOption.ALL,
    val hasWarrantyOnly: Boolean = false,
    val sortOption: ProductSortOption = ProductSortOption.NAME_ASC
)

data class ProductListUiState(
    val isLoading: Boolean = true,
    val products: List<ProductEntity> = emptyList(),
    val categories: List<CategoryEntity> = emptyList(),
    val brands: List<BrandEntity> = emptyList(),
    val filter: ProductListFilterState = ProductListFilterState(),
    val message: String? = null
)

class ProductListViewModel(
    private val productRepository: ProductRepository,
    private val categoryRepository: CategoryRepository,
    private val brandRepository: BrandRepository
) : ViewModel() {
    private val _filterState = MutableStateFlow(ProductListFilterState())
    private val _message = MutableStateFlow<String?>(null)

    val uiState: StateFlow<ProductListUiState> = combine(
        productRepository.allProducts,
        categoryRepository.allCategories,
        brandRepository.allBrands,
        _filterState,
        _message
    ) { allProducts, categories, brands, filter, message ->
        var filtered = allProducts

        // 1. Text Query Filter
        if (filter.query.isNotBlank()) {
            val q = filter.query.trim().lowercase()
            filtered = filtered.filter { p ->
                p.name.lowercase().contains(q) ||
                p.sku.lowercase().contains(q) ||
                p.barcode.lowercase().contains(q) ||
                p.brandName.lowercase().contains(q) ||
                p.model.lowercase().contains(q) ||
                p.categoryName.lowercase().contains(q)
            }
        }

        // 2. Category & Subcategory Filter
        if (filter.categoryId != null && filter.categoryId > 0) {
            filtered = filtered.filter { it.categoryId == filter.categoryId }
        }
        if (filter.subcategoryId != null && filter.subcategoryId > 0) {
            filtered = filtered.filter { it.subcategoryId == filter.subcategoryId }
        }

        // 3. Brand Filter
        if (filter.brandId != null && filter.brandId > 0) {
            filtered = filtered.filter { it.brandId == filter.brandId }
        }

        // 4. Tracking Type Filter
        if (!filter.trackingType.isNullOrBlank()) {
            filtered = filtered.filter { it.trackingType == filter.trackingType }
        }

        // 5. Stock Status Filter
        when (filter.stockFilter) {
            StockFilterOption.IN_STOCK -> filtered = filtered.filter { it.currentStock > it.minStock }
            StockFilterOption.LOW_STOCK -> filtered = filtered.filter { it.currentStock in 1..it.minStock }
            StockFilterOption.OUT_OF_STOCK -> filtered = filtered.filter { it.currentStock <= 0 }
            StockFilterOption.ALL -> { /* no-op */ }
        }

        // 6. Warranty Filter
        if (filter.hasWarrantyOnly) {
            filtered = filtered.filter { it.hasWarranty || it.warrantyPeriodMonths > 0 }
        }

        // 7. Sorting
        filtered = when (filter.sortOption) {
            ProductSortOption.NAME_ASC -> filtered.sortedBy { it.name.lowercase() }
            ProductSortOption.NAME_DESC -> filtered.sortedByDescending { it.name.lowercase() }
            ProductSortOption.PRICE_ASC -> filtered.sortedBy { it.retailPrice }
            ProductSortOption.PRICE_DESC -> filtered.sortedByDescending { it.retailPrice }
            ProductSortOption.STOCK_ASC -> filtered.sortedBy { it.currentStock }
            ProductSortOption.STOCK_DESC -> filtered.sortedByDescending { it.currentStock }
            ProductSortOption.NEWEST -> filtered.sortedByDescending { it.createdAt }
        }

        ProductListUiState(
            isLoading = false,
            products = filtered,
            categories = categories,
            brands = brands,
            filter = filter,
            message = message
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ProductListUiState())

    fun updateSearchQuery(query: String) = _filterState.update { it.copy(query = query) }
    fun setCategoryFilter(categoryId: Long?) = _filterState.update { it.copy(categoryId = categoryId, subcategoryId = null) }
    fun setBrandFilter(brandId: Long?) = _filterState.update { it.copy(brandId = brandId) }
    fun setStockFilter(option: StockFilterOption) = _filterState.update { it.copy(stockFilter = option) }
    fun setTrackingTypeFilter(type: String?) = _filterState.update { it.copy(trackingType = type) }
    fun setSortOption(sort: ProductSortOption) = _filterState.update { it.copy(sortOption = sort) }
    fun resetFilters() = _filterState.update { ProductListFilterState() }
    fun clearMessage() { _message.value = null }

    fun deleteOrDeactivateProduct(product: ProductEntity) {
        viewModelScope.launch {
            val res = productRepository.deleteProductIfUnused(product)
            res.onSuccess {
                _message.value = "Product deleted successfully."
            }.onFailure { err ->
                _message.value = err.message ?: "Could not delete product."
            }
        }
    }
}

// ------------------- PRODUCT FORM VIEWMODEL -------------------
data class ProductFormState(
    val id: Long = 0,
    val name: String = "",
    val categoryId: Long = 0,
    val categoryName: String = "",
    val subcategoryId: Long = 0,
    val subcategoryName: String = "",
    val brandId: Long = 0,
    val brandName: String = "",
    val model: String = "",
    val description: String = "",
    val unitId: Long = 0,
    val unit: String = "Pcs",
    val color: String = "",
    val variant: String = "",
    val productType: String = "PHYSICAL", // PHYSICAL, SERVICE
    val trackingType: String = "QUANTITY", // QUANTITY, SERIAL, IMEI, SERIAL_AND_IMEI
    val purchasePrice: Double = 0.0,
    val wholesalePrice: Double = 0.0,
    val retailPrice: Double = 0.0,
    val minSellingPrice: Double = 0.0,
    val currentStock: Int = 0,
    val minStock: Int = 5,
    val maxStock: Int = 1000,
    val hasVariants: Boolean = false,
    val hasWarranty: Boolean = false,
    val warrantyPeriod: Int = 0,
    val warrantyUnit: String = "MONTHS",
    val warrantyType: String = "SERVICE",
    val sku: String = "",
    val barcode: String = "",
    val imageUri: String = "",
    val isActive: Boolean = true,
    val isLoading: Boolean = false,
    val isSaved: Boolean = false,
    val errorMessage: String? = null,
    val categories: List<CategoryEntity> = emptyList(),
    val subcategories: List<SubcategoryEntity> = emptyList(),
    val brands: List<BrandEntity> = emptyList(),
    val units: List<UnitEntity> = emptyList()
)

class ProductFormViewModel(
    private val productRepository: ProductRepository,
    private val categoryRepository: CategoryRepository,
    private val brandRepository: BrandRepository,
    private val unitRepository: UnitRepository
) : ViewModel() {
    private val _state = MutableStateFlow(ProductFormState())
    val state: StateFlow<ProductFormState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                categoryRepository.allCategories,
                brandRepository.allBrands,
                unitRepository.allUnits
            ) { cats, brands, units ->
                Triple(cats, brands, units)
            }.collect { (cats, brands, units) ->
                _state.update {
                    it.copy(
                        categories = cats,
                        brands = brands,
                        units = units,
                        unitId = if (it.unitId == 0L && units.isNotEmpty()) units.first().id else it.unitId,
                        unit = if (it.unit.isBlank() && units.isNotEmpty()) units.first().code else it.unit
                    )
                }
            }
        }
    }

    fun loadProduct(productId: Long) {
        // Already loaded (e.g. the screen was recreated on rotation): keep the user's unsaved edits
        if (productId <= 0 || _state.value.id == productId) return
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true) }
            val p = productRepository.getProductById(productId)
            if (p != null) {
                // load subcategories for category
                val subs = categoryRepository.getSubcategoriesByCategorySync(p.categoryId)
                _state.update {
                    it.copy(
                        id = p.id,
                        name = p.name,
                        categoryId = p.categoryId,
                        categoryName = p.categoryName,
                        subcategoryId = p.subcategoryId,
                        subcategoryName = p.subcategoryName,
                        brandId = p.brandId,
                        brandName = p.brandName,
                        model = p.model,
                        description = p.description,
                        unitId = p.unitId,
                        unit = p.unit,
                        color = p.color,
                        variant = p.variant,
                        productType = p.productType,
                        trackingType = p.trackingType,
                        purchasePrice = p.purchasePrice,
                        wholesalePrice = p.wholesalePrice,
                        retailPrice = p.retailPrice,
                        minSellingPrice = p.minSellingPrice,
                        currentStock = p.currentStock,
                        minStock = p.minStock,
                        maxStock = p.maxStock,
                        hasVariants = p.hasVariants,
                        hasWarranty = p.hasWarranty || p.warrantyPeriodMonths > 0,
                        warrantyPeriod = if (p.warrantyPeriod > 0) p.warrantyPeriod else p.warrantyPeriodMonths,
                        warrantyUnit = p.warrantyUnit,
                        warrantyType = p.warrantyType,
                        sku = p.sku,
                        barcode = p.barcode,
                        imageUri = p.imageUri,
                        isActive = p.isActive,
                        subcategories = subs,
                        isLoading = false
                    )
                }
            }
        }
    }

    fun updateName(v: String) = _state.update { it.copy(name = v, errorMessage = null) }
    fun updateCategory(cat: CategoryEntity) {
        viewModelScope.launch {
            val subs = categoryRepository.getSubcategoriesByCategorySync(cat.id)
            _state.update {
                it.copy(
                    categoryId = cat.id,
                    categoryName = cat.name,
                    subcategoryId = 0,
                    subcategoryName = "",
                    subcategories = subs,
                    errorMessage = null
                )
            }
        }
    }
    fun updateSubcategory(sub: SubcategoryEntity) = _state.update { it.copy(subcategoryId = sub.id, subcategoryName = sub.name) }
    fun updateBrand(brand: BrandEntity) = _state.update { it.copy(brandId = brand.id, brandName = brand.name) }
    fun updateModel(v: String) = _state.update { it.copy(model = v) }
    fun updateDescription(v: String) = _state.update { it.copy(description = v) }
    fun updateUnit(unit: UnitEntity) = _state.update { it.copy(unitId = unit.id, unit = unit.code) }
    fun updateProductType(v: String) = _state.update { it.copy(productType = v) }
    fun updateTrackingType(v: String) = _state.update { it.copy(trackingType = v) }
    fun updatePurchasePrice(v: Double) = _state.update { it.copy(purchasePrice = v) }
    fun updateWholesalePrice(v: Double) = _state.update { it.copy(wholesalePrice = v) }
    fun updateRetailPrice(v: Double) = _state.update { it.copy(retailPrice = v) }
    fun updateMinSellingPrice(v: Double) = _state.update { it.copy(minSellingPrice = v) }
    fun updateCurrentStock(v: Int) = _state.update { it.copy(currentStock = v) }
    fun updateMinStock(v: Int) = _state.update { it.copy(minStock = v) }
    fun updateMaxStock(v: Int) = _state.update { it.copy(maxStock = v) }
    fun updateHasWarranty(v: Boolean) = _state.update { it.copy(hasWarranty = v) }
    fun updateWarrantyPeriod(v: Int) = _state.update { it.copy(warrantyPeriod = v) }
    fun updateWarrantyUnit(v: String) = _state.update { it.copy(warrantyUnit = v) }
    fun updateWarrantyType(v: String) = _state.update { it.copy(warrantyType = v) }
    fun updateSku(v: String) = _state.update { it.copy(sku = v, errorMessage = null) }
    fun updateBarcode(v: String) = _state.update { it.copy(barcode = v, errorMessage = null) }
    fun updateImageUri(v: String) = _state.update { it.copy(imageUri = v) }

    fun generateAutoSku() {
        val s = _state.value
        val autoSku = SkuUtils.generateSku(
            categoryName = s.categoryName.ifBlank { "PRD" },
            brandName = s.brandName.ifBlank { "GEN" },
            model = s.model.ifBlank { s.name }
        )
        _state.update { it.copy(sku = autoSku) }
    }

    fun generateAutoBarcode() {
        val autoBarcode = BarcodeUtils.generateEan13()
        _state.update { it.copy(barcode = autoBarcode) }
    }

    fun saveProduct() {
        val s = _state.value
        if (s.name.isBlank()) {
            _state.update { it.copy(errorMessage = "Product Name is required.") }
            return
        }
        if (s.categoryId <= 0) {
            _state.update { it.copy(errorMessage = "Please select a Category.") }
            return
        }
        if (s.retailPrice < 0 || s.purchasePrice < 0) {
            _state.update { it.copy(errorMessage = "Prices cannot be negative.") }
            return
        }
        if (s.currentStock < 0 || s.minStock < 0) {
            _state.update { it.copy(errorMessage = "Stock cannot be negative.") }
            return
        }
        if (s.maxStock < s.minStock) {
            _state.update { it.copy(errorMessage = "Maximum stock must be greater than or equal to minimum stock.") }
            return
        }

        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, errorMessage = null) }

            // Months (rounded up) is kept for older screens; the exact length uses warrantyPeriod + warrantyUnit
            val months = when (s.warrantyUnit) {
                "YEARS" -> s.warrantyPeriod * 12
                "DAYS" -> ((s.warrantyPeriod + 29) / 30).coerceAtLeast(1)
                else -> s.warrantyPeriod
            }

            // Editing starts from the saved product so fields this form doesn't show (supplier, tax, notes...) survive
            val base = if (s.id > 0) productRepository.getProductById(s.id) else null
            val entity = (base ?: ProductEntity(name = s.name.trim())).copy(
                id = s.id,
                name = s.name.trim(),
                sku = s.sku.trim(),
                barcode = s.barcode.trim(),
                categoryId = s.categoryId,
                categoryName = s.categoryName,
                subcategoryId = s.subcategoryId,
                subcategoryName = s.subcategoryName,
                brandId = s.brandId,
                brandName = s.brandName,
                model = s.model.trim(),
                description = s.description.trim(),
                unitId = s.unitId,
                unit = s.unit,
                color = s.color,
                variant = s.variant,
                productType = s.productType,
                trackingType = s.trackingType,
                purchasePrice = s.purchasePrice,
                wholesalePrice = s.wholesalePrice,
                retailPrice = s.retailPrice,
                minSellingPrice = s.minSellingPrice,
                currentStock = s.currentStock,
                minStock = s.minStock,
                maxStock = s.maxStock,
                openingStock = if (s.id == 0L) s.currentStock else (base?.openingStock ?: 0),
                hasVariants = s.hasVariants,
                hasWarranty = s.hasWarranty,
                warrantyPeriod = s.warrantyPeriod,
                warrantyUnit = s.warrantyUnit,
                warrantyPeriodMonths = if (s.hasWarranty) months else 0,
                warrantyType = s.warrantyType,
                isSerialRequired = s.trackingType == "SERIAL" || s.trackingType == "SERIAL_AND_IMEI",
                isImeiRequired = s.trackingType == "IMEI" || s.trackingType == "SERIAL_AND_IMEI",
                imageUri = s.imageUri,
                isActive = s.isActive
            )

            val result = productRepository.saveProduct(entity)
            result.onSuccess {
                _state.update { it.copy(isLoading = false, isSaved = true) }
            }.onFailure { err ->
                _state.update { it.copy(isLoading = false, errorMessage = err.message ?: "Failed to save product.") }
            }
        }
    }
}

// ------------------- PRODUCT DETAILS VIEWMODEL -------------------
data class ProductDetailsUiState(
    val isLoading: Boolean = true,
    val product: ProductEntity? = null,
    val variants: List<ProductVariantEntity> = emptyList(),
    val priceHistory: List<ProductPriceHistoryEntity> = emptyList(),
    val message: String? = null
)

class ProductDetailsViewModel(
    private val productId: Long,
    private val productRepository: ProductRepository,
    private val stockRepository: StockRepository? = null,
    private val operatorName: () -> String = { "Admin" }
) : ViewModel() {
    private val _uiState = MutableStateFlow(ProductDetailsUiState())
    val uiState: StateFlow<ProductDetailsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                productRepository.observeProductById(productId),
                productRepository.getVariantsForProduct(productId),
                productRepository.getPriceHistory(productId)
            ) { product, variants, history ->
                ProductDetailsUiState(
                    isLoading = false,
                    product = product,
                    variants = variants,
                    priceHistory = history
                )
            }.collect { state ->
                _uiState.value = state
            }
        }
    }

    fun deactivateProduct() {
        val p = _uiState.value.product ?: return
        viewModelScope.launch {
            productRepository.deactivateProduct(p.id)
            _uiState.update { it.copy(message = "Product deactivated.") }
        }
    }

    /** Adds or edits a variant; [onDone] gets an error message, or null when saved. */
    fun saveVariant(variant: ProductVariantEntity, onDone: (String?) -> Unit) {
        viewModelScope.launch {
            onDone(productRepository.saveVariant(variant.copy(productId = productId), operatorName()).exceptionOrNull()?.message)
        }
    }

    fun deactivateVariant(variantId: Long, onDone: (String?) -> Unit) {
        viewModelScope.launch {
            onDone(productRepository.deactivateVariant(variantId).exceptionOrNull()?.message)
        }
    }

    /** Stock count correction for quantity-tracked products (serialized units change only through purchases/sales). */
    fun adjustStock(delta: Int, reason: String, notes: String, variantId: Long, onDone: (String?) -> Unit) {
        val repo = stockRepository ?: return onDone("Stock adjustment is not available here.")
        if (delta == 0) return onDone("Enter how many units to add or remove.")
        viewModelScope.launch {
            onDone(repo.adjustStock(productId, delta, reason, notes.trim(), variantId, operatorName()).exceptionOrNull()?.message)
        }
    }
}

// ------------------- CATEGORY VIEWMODEL -------------------
data class CategoryUiState(
    val categories: List<CategoryEntity> = emptyList(),
    val selectedCategory: CategoryEntity? = null,
    val subcategories: List<SubcategoryEntity> = emptyList(),
    val message: String? = null,
    val isSaving: Boolean = false
)

class CategoryViewModel(
    private val categoryRepository: CategoryRepository
) : ViewModel() {
    private val _uiState = MutableStateFlow(CategoryUiState())
    val uiState: StateFlow<CategoryUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            categoryRepository.allCategories.collect { cats ->
                _uiState.update { it.copy(categories = cats) }
            }
        }
    }

    fun selectCategory(category: CategoryEntity) {
        viewModelScope.launch {
            val subs = categoryRepository.getSubcategoriesByCategorySync(category.id)
            _uiState.update { it.copy(selectedCategory = category, subcategories = subs) }
        }
    }

    fun saveCategory(name: String, desc: String, onDone: () -> Unit) {
        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true, message = null) }
            val entity = CategoryEntity(name = name.trim(), description = desc.trim())
            val res = categoryRepository.saveCategory(entity)
            res.onSuccess {
                _uiState.update { it.copy(isSaving = false, message = "Category saved.") }
                onDone()
            }.onFailure { err ->
                _uiState.update { it.copy(isSaving = false, message = err.message) }
            }
        }
    }

    /** Rename / re-describe an existing category (products keep showing the new name). */
    fun updateCategory(category: CategoryEntity, name: String, desc: String, onDone: () -> Unit) {
        viewModelScope.launch {
            categoryRepository.saveCategory(category.copy(name = name.trim(), description = desc.trim()))
                .onSuccess {
                    _uiState.update { it.copy(message = "Category updated.", selectedCategory = null) }
                    onDone()
                }
                .onFailure { err -> _uiState.update { it.copy(message = err.message) } }
        }
    }

    /** Deletes an unused category; one with products is hidden instead (the message says which). */
    fun removeCategory(category: CategoryEntity, onDone: () -> Unit) {
        viewModelScope.launch {
            val result = categoryRepository.deleteCategoryIfUnused(category)
            _uiState.update {
                it.copy(
                    message = result.exceptionOrNull()?.message ?: "Category '${category.name}' deleted.",
                    selectedCategory = null
                )
            }
            onDone()
        }
    }

    fun updateSubcategory(sub: SubcategoryEntity, name: String, onDone: () -> Unit) {
        viewModelScope.launch {
            categoryRepository.saveSubcategory(sub.copy(name = name.trim()))
                .onSuccess {
                    val subs = categoryRepository.getSubcategoriesByCategorySync(sub.categoryId)
                    _uiState.update { it.copy(subcategories = subs, message = "Subcategory updated.") }
                    onDone()
                }
                .onFailure { err -> _uiState.update { it.copy(message = err.message) } }
        }
    }

    fun removeSubcategory(sub: SubcategoryEntity, onDone: () -> Unit) {
        viewModelScope.launch {
            val result = categoryRepository.deleteSubcategoryIfUnused(sub)
            val subs = categoryRepository.getSubcategoriesByCategorySync(sub.categoryId)
            _uiState.update {
                it.copy(subcategories = subs, message = result.exceptionOrNull()?.message ?: "Subcategory '${sub.name}' deleted.")
            }
            onDone()
        }
    }

    fun saveSubcategory(categoryId: Long, name: String, desc: String, onDone: () -> Unit) {
        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true, message = null) }
            val entity = SubcategoryEntity(categoryId = categoryId, name = name.trim(), description = desc.trim())
            val res = categoryRepository.saveSubcategory(entity)
            res.onSuccess {
                val updatedSubs = categoryRepository.getSubcategoriesByCategorySync(categoryId)
                _uiState.update { it.copy(isSaving = false, subcategories = updatedSubs, message = "Subcategory saved.") }
                onDone()
            }.onFailure { err ->
                _uiState.update { it.copy(isSaving = false, message = err.message) }
            }
        }
    }

    fun clearMessage() { _uiState.update { it.copy(message = null) } }
}

// ------------------- BRAND VIEWMODEL -------------------
data class BrandUiState(
    val brands: List<BrandEntity> = emptyList(),
    val message: String? = null,
    val isSaving: Boolean = false
)

class BrandViewModel(
    private val brandRepository: BrandRepository
) : ViewModel() {
    private val _uiState = MutableStateFlow(BrandUiState())
    val uiState: StateFlow<BrandUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            brandRepository.allBrands.collect { brands ->
                _uiState.update { it.copy(brands = brands) }
            }
        }
    }

    fun updateBrand(brand: BrandEntity, name: String, origin: String, onDone: () -> Unit) {
        viewModelScope.launch {
            brandRepository.saveBrand(brand.copy(name = name.trim(), originCountry = origin.trim()))
                .onSuccess {
                    _uiState.update { it.copy(message = "Brand updated.") }
                    onDone()
                }
                .onFailure { err -> _uiState.update { it.copy(message = err.message) } }
        }
    }

    fun removeBrand(brand: BrandEntity, onDone: () -> Unit) {
        viewModelScope.launch {
            val result = brandRepository.deleteBrandIfUnused(brand)
            _uiState.update { it.copy(message = result.exceptionOrNull()?.message ?: "Brand '${brand.name}' deleted.") }
            onDone()
        }
    }

    fun saveBrand(name: String, origin: String, onDone: () -> Unit) {
        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true, message = null) }
            val entity = BrandEntity(name = name.trim(), originCountry = origin.trim())
            val res = brandRepository.saveBrand(entity)
            res.onSuccess {
                _uiState.update { it.copy(isSaving = false, message = "Brand saved.") }
                onDone()
            }.onFailure { err ->
                _uiState.update { it.copy(isSaving = false, message = err.message) }
            }
        }
    }

    fun clearMessage() { _uiState.update { it.copy(message = null) } }
}

// ------------------- UNIT VIEWMODEL -------------------
data class UnitUiState(
    val units: List<UnitEntity> = emptyList(),
    val message: String? = null
)

class UnitViewModel(
    private val unitRepository: UnitRepository
) : ViewModel() {
    private val _uiState = MutableStateFlow(UnitUiState())
    val uiState: StateFlow<UnitUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            unitRepository.allUnits.collect { units ->
                _uiState.update { it.copy(units = units) }
            }
        }
    }

    fun saveUnit(name: String, code: String, onDone: () -> Unit) {
        viewModelScope.launch {
            val res = unitRepository.saveUnit(UnitEntity(name = name.trim(), code = code.trim()))
            res.onSuccess {
                _uiState.update { it.copy(message = "Unit saved.") }
                onDone()
            }.onFailure { err ->
                _uiState.update { it.copy(message = err.message) }
            }
        }
    }
}
