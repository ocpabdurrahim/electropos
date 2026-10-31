package com.example.data.repository

import com.example.data.local.dao.MasterDao
import com.example.data.local.entities.BrandEntity
import com.example.data.local.entities.CategoryEntity
import com.example.data.local.entities.CustomerEntity
import com.example.data.local.entities.SupplierEntity
import kotlinx.coroutines.flow.Flow

class MasterRepository(private val masterDao: MasterDao) {
    // Categories
    val allCategories: Flow<List<CategoryEntity>> = masterDao.getAllCategories()
    suspend fun getCategoryById(id: Long) = masterDao.getCategoryById(id)
    suspend fun saveCategory(category: CategoryEntity) = if (category.id == 0L) {
        masterDao.insertCategory(category)
    } else {
        masterDao.updateCategory(category)
        category.id
    }
    suspend fun deleteCategory(category: CategoryEntity) = masterDao.deleteCategory(category)

    // Brands
    val allBrands: Flow<List<BrandEntity>> = masterDao.getAllBrands()
    suspend fun getBrandById(id: Long) = masterDao.getBrandById(id)
    suspend fun saveBrand(brand: BrandEntity) = if (brand.id == 0L) {
        masterDao.insertBrand(brand)
    } else {
        masterDao.updateBrand(brand)
        brand.id
    }
    suspend fun deleteBrand(brand: BrandEntity) = masterDao.deleteBrand(brand)

    // Suppliers
    val allSuppliers: Flow<List<SupplierEntity>> = masterDao.getAllSuppliers()
    suspend fun getSupplierById(id: Long) = masterDao.getSupplierById(id)
    fun searchSuppliers(query: String) = masterDao.searchSuppliers(query)
    suspend fun saveSupplier(supplier: SupplierEntity) = if (supplier.id == 0L) {
        masterDao.insertSupplier(supplier)
    } else {
        masterDao.updateSupplier(supplier)
        supplier.id
    }
    suspend fun adjustSupplierDue(id: Long, delta: Double) = masterDao.adjustSupplierDue(id, delta)

    // Customers
    val allCustomers: Flow<List<CustomerEntity>> = masterDao.getAllCustomers()
    suspend fun getCustomerById(id: Long) = masterDao.getCustomerById(id)
    suspend fun getCustomerByPhone(phone: String) = masterDao.getCustomerByPhone(phone)
    fun searchCustomers(query: String) = masterDao.searchCustomers(query)
    suspend fun saveCustomer(customer: CustomerEntity) = if (customer.id == 0L) {
        masterDao.insertCustomer(customer)
    } else {
        masterDao.updateCustomer(customer)
        customer.id
    }
    suspend fun adjustCustomerDue(id: Long, delta: Double) = masterDao.adjustCustomerDue(id, delta)
}
