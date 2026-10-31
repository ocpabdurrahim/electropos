package com.example.data.local.dao

import androidx.room.*
import com.example.sync.SyncIds
import com.example.data.local.entities.BrandEntity
import com.example.data.local.entities.CategoryEntity
import com.example.data.local.entities.CustomerEntity
import com.example.data.local.entities.SupplierEntity
import kotlinx.coroutines.flow.Flow

@Dao
abstract class MasterDao(private val db: RoomDatabase) {
    // Categories
    @Query("SELECT * FROM categories WHERE isActive = 1 ORDER BY name ASC")
    abstract fun getAllCategories(): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories WHERE id = :id LIMIT 1")
    abstract suspend fun getCategoryById(id: Long): CategoryEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertCategoryRaw(category: CategoryEntity): Long

    suspend fun insertCategory(category: CategoryEntity): Long =
        insertCategoryRaw(if (category.id == 0L) category.copy(id = SyncIds.next(db, "categories")) else category)

    @Query("SELECT * FROM categories WHERE name = :name COLLATE NOCASE LIMIT 1")
    abstract suspend fun getCategoryByName(name: String): CategoryEntity?

    @Update
    abstract suspend fun updateCategory(category: CategoryEntity)

    @Delete
    abstract suspend fun deleteCategory(category: CategoryEntity)

    @Query("UPDATE categories SET isActive = 0 WHERE id = :id")
    abstract suspend fun deactivateCategory(id: Long)

    @Query("UPDATE categories SET isActive = 1, updatedAt = :timestamp WHERE id = :id")
    abstract suspend fun reactivateCategory(id: Long, timestamp: Long = System.currentTimeMillis())

    /** Products keep a copy of the category name for lists and invoices. */
    @Query("UPDATE products SET categoryName = :name WHERE categoryId = :categoryId")
    abstract suspend fun renameCategoryOnProducts(categoryId: Long, name: String)

    @Query("SELECT COUNT(*) FROM products WHERE categoryId = :categoryId AND isActive = 1")
    abstract suspend fun countProductsInCategory(categoryId: Long): Int

    @Query("SELECT COUNT(*) FROM subcategories WHERE categoryId = :categoryId AND isActive = 1")
    abstract suspend fun countSubcategoriesInCategory(categoryId: Long): Int

    // Brands
    @Query("SELECT * FROM brands WHERE isActive = 1 ORDER BY name ASC")
    abstract fun getAllBrands(): Flow<List<BrandEntity>>

    @Query("SELECT * FROM brands WHERE id = :id LIMIT 1")
    abstract suspend fun getBrandById(id: Long): BrandEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertBrandRaw(brand: BrandEntity): Long

    suspend fun insertBrand(brand: BrandEntity): Long =
        insertBrandRaw(if (brand.id == 0L) brand.copy(id = SyncIds.next(db, "brands")) else brand)

    @Query("SELECT * FROM brands WHERE name = :name COLLATE NOCASE LIMIT 1")
    abstract suspend fun getBrandByName(name: String): BrandEntity?

    @Update
    abstract suspend fun updateBrand(brand: BrandEntity)

    @Delete
    abstract suspend fun deleteBrand(brand: BrandEntity)

    @Query("UPDATE brands SET isActive = 0 WHERE id = :id")
    abstract suspend fun deactivateBrand(id: Long)

    @Query("UPDATE brands SET isActive = 1, updatedAt = :timestamp WHERE id = :id")
    abstract suspend fun reactivateBrand(id: Long, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE products SET brandName = :name WHERE brandId = :brandId")
    abstract suspend fun renameBrandOnProducts(brandId: Long, name: String)

    @Query("SELECT COUNT(*) FROM products WHERE brandId = :brandId AND isActive = 1")
    abstract suspend fun countProductsInBrand(brandId: Long): Int

    // Suppliers
    @Query("SELECT * FROM suppliers WHERE isActive = 1 ORDER BY name ASC")
    abstract fun getAllSuppliers(): Flow<List<SupplierEntity>>

    @Query("SELECT * FROM suppliers WHERE id = :id LIMIT 1")
    abstract suspend fun getSupplierById(id: Long): SupplierEntity?

    @Query("SELECT * FROM suppliers WHERE name LIKE '%' || :query || '%' OR phone LIKE '%' || :query || '%' OR company LIKE '%' || :query || '%'")
    abstract fun searchSuppliers(query: String): Flow<List<SupplierEntity>>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertSupplierRaw(supplier: SupplierEntity): Long

    suspend fun insertSupplier(supplier: SupplierEntity): Long =
        insertSupplierRaw(if (supplier.id == 0L) supplier.copy(id = SyncIds.next(db, "suppliers")) else supplier)

    @Update
    abstract suspend fun updateSupplier(supplier: SupplierEntity)

    @Query("UPDATE suppliers SET currentDue = currentDue + :delta WHERE id = :id")
    abstract suspend fun adjustSupplierDue(id: Long, delta: Double)

    // Customers
    @Query("SELECT * FROM customers WHERE isActive = 1 ORDER BY name ASC")
    abstract fun getAllCustomers(): Flow<List<CustomerEntity>>

    @Query("SELECT * FROM customers WHERE id = :id LIMIT 1")
    abstract suspend fun getCustomerById(id: Long): CustomerEntity?

    @Query("SELECT * FROM customers WHERE phone = :phone LIMIT 1")
    abstract suspend fun getCustomerByPhone(phone: String): CustomerEntity?

    @Query("SELECT * FROM customers WHERE name LIKE '%' || :query || '%' OR phone LIKE '%' || :query || '%'")
    abstract fun searchCustomers(query: String): Flow<List<CustomerEntity>>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertCustomerRaw(customer: CustomerEntity): Long

    suspend fun insertCustomer(customer: CustomerEntity): Long =
        insertCustomerRaw(if (customer.id == 0L) customer.copy(id = SyncIds.next(db, "customers")) else customer)

    @Update
    abstract suspend fun updateCustomer(customer: CustomerEntity)

    @Query("UPDATE customers SET currentDue = currentDue + :delta WHERE id = :id")
    abstract suspend fun adjustCustomerDue(id: Long, delta: Double)

    @Query("SELECT SUM(currentDue) FROM customers WHERE isActive = 1")
    abstract fun getTotalCustomerDue(): Flow<Double?>

    @Query("SELECT SUM(currentDue) FROM suppliers WHERE isActive = 1")
    abstract fun getTotalSupplierDue(): Flow<Double?>
}
