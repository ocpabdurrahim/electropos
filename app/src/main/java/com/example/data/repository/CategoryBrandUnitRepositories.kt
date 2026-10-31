package com.example.data.repository

import com.example.data.local.dao.MasterDao
import com.example.data.local.dao.SettingsAndAuditDao
import com.example.data.local.dao.SubcategoryDao
import com.example.data.local.dao.UnitDao
import com.example.data.local.entities.AuditLogEntity
import com.example.data.local.entities.BrandEntity
import com.example.data.local.entities.CategoryEntity
import com.example.data.local.entities.SubcategoryEntity
import com.example.data.local.entities.UnitEntity
import kotlinx.coroutines.flow.Flow

class CategoryRepository(
    private val masterDao: MasterDao,
    private val subcategoryDao: SubcategoryDao,
    private val auditDao: SettingsAndAuditDao
) {
    val allCategories: Flow<List<CategoryEntity>> = masterDao.getAllCategories()

    suspend fun getCategoryById(id: Long) = masterDao.getCategoryById(id)

    suspend fun saveCategory(category: CategoryEntity, userName: String = "Admin"): Result<Long> = runCatching {
        if (category.name.isBlank()) {
            throw IllegalArgumentException("Category name cannot be empty.")
        }
        // The name is unique; inserting a duplicate would silently replace (delete) the existing category
        val sameName = masterDao.getCategoryByName(category.name.trim())
        if (sameName != null && sameName.id != category.id) {
            // Adding a name that was removed earlier simply brings that category back
            if (!sameName.isActive && category.id == 0L) {
                masterDao.reactivateCategory(sameName.id)
                return@runCatching sameName.id
            }
            throw IllegalArgumentException("Category '${sameName.name}' already exists.")
        }
        val id = if (category.id == 0L) {
            val generatedId = masterDao.insertCategory(category)
            auditDao.insertAuditLog(
                AuditLogEntity(
                    userRole = "ADMIN",
                    action = "CATEGORY_CREATED",
                    module = "CATEGORIES",
                    recordId = generatedId.toString(),
                    description = "Created category '${category.name}' by $userName"
                )
            )
            generatedId
        } else {
            masterDao.updateCategory(category.copy(name = category.name.trim(), updatedAt = System.currentTimeMillis()))
            masterDao.renameCategoryOnProducts(category.id, category.name.trim())
            auditDao.insertAuditLog(
                AuditLogEntity(
                    userRole = "ADMIN",
                    action = "CATEGORY_UPDATED",
                    module = "CATEGORIES",
                    recordId = category.id.toString(),
                    description = "Updated category '${category.name}' by $userName"
                )
            )
            category.id
        }
        id
    }

    suspend fun deleteCategoryIfUnused(category: CategoryEntity, userName: String = "Admin"): Result<Unit> = runCatching {
        val productCount = masterDao.countProductsInCategory(category.id)
        if (productCount > 0) {
            // Cannot delete physically, deactivate instead
            masterDao.deactivateCategory(category.id)
            auditDao.insertAuditLog(
                AuditLogEntity(
                    userRole = "ADMIN",
                    action = "CATEGORY_DEACTIVATED",
                    module = "CATEGORIES",
                    recordId = category.id.toString(),
                    description = "Category '${category.name}' deactivated (contains $productCount products) by $userName"
                )
            )
            throw IllegalStateException("Category contains $productCount products. It has been deactivated instead of permanently deleted.")
        } else {
            masterDao.deleteCategory(category)
            auditDao.insertAuditLog(
                AuditLogEntity(
                    userRole = "ADMIN",
                    action = "CATEGORY_DELETED",
                    module = "CATEGORIES",
                    recordId = category.id.toString(),
                    description = "Permanently deleted unused category '${category.name}' by $userName"
                )
            )
        }
    }

    suspend fun countProductsInCategory(categoryId: Long): Int = masterDao.countProductsInCategory(categoryId)

    // Subcategories
    fun getSubcategoriesByCategory(categoryId: Long): Flow<List<SubcategoryEntity>> =
        subcategoryDao.getSubcategoriesByCategory(categoryId)

    suspend fun getSubcategoriesByCategorySync(categoryId: Long): List<SubcategoryEntity> =
        subcategoryDao.getSubcategoriesByCategorySync(categoryId)

    fun getAllSubcategories(): Flow<List<SubcategoryEntity>> = subcategoryDao.getAllSubcategories()

    suspend fun saveSubcategory(subcategory: SubcategoryEntity, userName: String = "Admin"): Result<Long> = runCatching {
        if (subcategory.name.isBlank()) throw IllegalArgumentException("Subcategory name cannot be empty.")
        if (subcategory.categoryId <= 0L) throw IllegalArgumentException("Valid parent category must be selected.")

        val existing = subcategoryDao.getSubcategoryByName(subcategory.categoryId, subcategory.name.trim())
        if (existing != null && existing.id != subcategory.id) {
            throw IllegalArgumentException("A subcategory with this name already exists in this category.")
        }

        val id = if (subcategory.id == 0L) {
            val genId = subcategoryDao.insertSubcategory(subcategory)
            auditDao.insertAuditLog(
                AuditLogEntity(
                    userRole = "ADMIN",
                    action = "SUBCATEGORY_CREATED",
                    module = "CATEGORIES",
                    recordId = genId.toString(),
                    description = "Created subcategory '${subcategory.name}' by $userName"
                )
            )
            genId
        } else {
            subcategoryDao.updateSubcategory(subcategory.copy(name = subcategory.name.trim(), updatedAt = System.currentTimeMillis()))
            subcategoryDao.renameSubcategoryOnProducts(subcategory.id, subcategory.name.trim())
            subcategory.id
        }
        id
    }

    suspend fun deleteSubcategoryIfUnused(subcategory: SubcategoryEntity, userName: String = "Admin"): Result<Unit> = runCatching {
        val productCount = subcategoryDao.countProductsInSubcategory(subcategory.id)
        if (productCount > 0) {
            subcategoryDao.deactivateSubcategory(subcategory.id)
            throw IllegalStateException("Subcategory contains $productCount products. It has been deactivated instead of deleted.")
        } else {
            subcategoryDao.deleteSubcategory(subcategory)
        }
    }
}

class BrandRepository(
    private val masterDao: MasterDao,
    private val auditDao: SettingsAndAuditDao
) {
    val allBrands: Flow<List<BrandEntity>> = masterDao.getAllBrands()

    suspend fun getBrandById(id: Long) = masterDao.getBrandById(id)

    suspend fun saveBrand(brand: BrandEntity, userName: String = "Admin"): Result<Long> = runCatching {
        if (brand.name.isBlank()) throw IllegalArgumentException("Brand name cannot be empty.")
        val sameName = masterDao.getBrandByName(brand.name.trim())
        if (sameName != null && sameName.id != brand.id) {
            if (!sameName.isActive && brand.id == 0L) {
                masterDao.reactivateBrand(sameName.id)
                return@runCatching sameName.id
            }
            throw IllegalArgumentException("Brand '${sameName.name}' already exists.")
        }

        val id = if (brand.id == 0L) {
            val generatedId = masterDao.insertBrand(brand)
            auditDao.insertAuditLog(
                AuditLogEntity(
                    userRole = "ADMIN",
                    action = "BRAND_CREATED",
                    module = "BRANDS",
                    recordId = generatedId.toString(),
                    description = "Created brand '${brand.name}' by $userName"
                )
            )
            generatedId
        } else {
            masterDao.updateBrand(brand.copy(name = brand.name.trim(), updatedAt = System.currentTimeMillis()))
            masterDao.renameBrandOnProducts(brand.id, brand.name.trim())
            auditDao.insertAuditLog(
                AuditLogEntity(
                    userRole = "ADMIN",
                    action = "BRAND_UPDATED",
                    module = "BRANDS",
                    recordId = brand.id.toString(),
                    description = "Updated brand '${brand.name}' by $userName"
                )
            )
            brand.id
        }
        id
    }

    suspend fun deleteBrandIfUnused(brand: BrandEntity, userName: String = "Admin"): Result<Unit> = runCatching {
        val productCount = masterDao.countProductsInBrand(brand.id)
        if (productCount > 0) {
            masterDao.deactivateBrand(brand.id)
            throw IllegalStateException("Brand is used by $productCount products. It has been deactivated instead of deleted.")
        } else {
            masterDao.deleteBrand(brand)
            auditDao.insertAuditLog(
                AuditLogEntity(
                    userRole = "ADMIN",
                    action = "BRAND_DELETED",
                    module = "BRANDS",
                    recordId = brand.id.toString(),
                    description = "Deleted unused brand '${brand.name}' by $userName"
                )
            )
        }
    }

    suspend fun countProductsInBrand(brandId: Long) = masterDao.countProductsInBrand(brandId)
}

class UnitRepository(
    private val unitDao: UnitDao,
    private val auditDao: SettingsAndAuditDao
) {
    val allUnits: Flow<List<UnitEntity>> = unitDao.getAllUnits()

    suspend fun getAllUnitsSync(): List<UnitEntity> {
        val units = unitDao.getAllUnitsSync()
        // A linked phone's units come from the cloud shop; seeding here would add duplicates
        return if (units.isEmpty() && !unitDao.isCloudLinked()) {
            val defaults = listOf(
                UnitEntity(name = "Piece", code = "Pcs"),
                UnitEntity(name = "Box", code = "Box"),
                UnitEntity(name = "Pack", code = "Pk"),
                UnitEntity(name = "Meter", code = "Mtr"),
                UnitEntity(name = "Set", code = "Set")
            )
            defaults.forEach { unitDao.insertUnit(it) }
            unitDao.getAllUnitsSync()
        } else {
            units
        }
    }

    suspend fun saveUnit(unit: UnitEntity, userName: String = "Admin"): Result<Long> = runCatching {
        if (unit.name.isBlank()) throw IllegalArgumentException("Unit name cannot be empty.")
        val id = if (unit.id == 0L) {
            val genId = unitDao.insertUnit(unit)
            auditDao.insertAuditLog(
                AuditLogEntity(
                    userRole = "ADMIN",
                    action = "UNIT_CREATED",
                    module = "UNITS",
                    recordId = genId.toString(),
                    description = "Created measurement unit '${unit.name}'"
                )
            )
            genId
        } else {
            unitDao.updateUnit(unit)
            unit.id
        }
        id
    }

    suspend fun deactivateUnit(id: Long) = unitDao.deactivateUnit(id)
}
