package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.core.utils.DateTimeUtils
import com.example.core.utils.SecurityUtils
import com.example.data.local.database.AppDatabase
import com.example.data.local.entities.*
import com.example.data.repository.AuthRepository
import com.example.data.repository.DashboardRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Phase2BusinessLogicTest {

    private lateinit var database: AppDatabase
    private lateinit var authRepository: AuthRepository
    private lateinit var dashboardRepository: DashboardRepository

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        authRepository = AuthRepository(database)
        dashboardRepository = DashboardRepository(database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun testSecurityUtilsHashingAndVerification() {
        val salt = SecurityUtils.generateSalt()
        val pin = "1234"
        val hash = SecurityUtils.hashPin(pin, salt)

        // Same PIN and salt produces same hash
        assertTrue(SecurityUtils.verifyPin("1234", salt, hash))
        // Incorrect PIN fails
        assertFalse(SecurityUtils.verifyPin("9999", salt, hash))
        // Never stores plain text
        assertNotEquals("1234", hash)
    }

    @Test
    fun testFirstRunDetectionAndSetupCompletion() = runBlocking {
        // Initial state: setup not completed
        val initialProfile = authRepository.shopProfile.firstOrNull()
        assertTrue(initialProfile == null || !initialProfile.setupCompleted)

        val profile = ShopProfileEntity(
            shopName = "ElectroHub Electronics",
            currencyCode = "BDT",
            currencySymbol = "৳",
            address = "Dhaka",
            phone = "01700000000"
        )

        // Complete setup
        val result = authRepository.completeSetup(
            shopProfile = profile,
            adminName = "Shop Manager",
            adminUsername = "admin",
            pin = "5678"
        )
        assertTrue(result.isSuccess)

        // Verify setup status in database
        val savedProfile = authRepository.shopProfile.first()
        assertNotNull(savedProfile)
        assertEquals("ElectroHub Electronics", savedProfile?.shopName)
        assertTrue(savedProfile?.setupCompleted == true)

        // Verify current user logged in
        val currentUser = authRepository.currentUser.value
        assertNotNull(currentUser)
        assertEquals("admin", currentUser?.username)
        assertEquals("ADMIN", currentUser?.role)
        assertTrue(authRepository.isLoggedIn.value)
    }

    @Test
    fun testLoginSuccessAndFailure() = runBlocking {
        val profile = ShopProfileEntity(shopName = "Gadget World", setupCompleted = true)
        authRepository.completeSetup(profile, "Owner", "gadget_admin", "4321")

        // Log out first
        authRepository.logout()
        assertNull(authRepository.currentUser.value)
        assertFalse(authRepository.isLoggedIn.value)

        // Attempt login with wrong PIN
        val failedResult = authRepository.login("gadget_admin", "0000")
        assertTrue(failedResult.isFailure)
        assertEquals("Invalid username or PIN.", failedResult.exceptionOrNull()?.message)

        // Attempt login with correct PIN
        val successResult = authRepository.login("gadget_admin", "4321")
        assertTrue(successResult.isSuccess)
        assertEquals("gadget_admin", successResult.getOrNull()?.username)
        assertTrue(authRepository.isLoggedIn.value)
    }

    @Test
    fun testPinChangeWorkflow() = runBlocking {
        val profile = ShopProfileEntity(shopName = "Tech Zone")
        authRepository.completeSetup(profile, "Admin", "tech_admin", "1111")
        val user = authRepository.currentUser.value!!

        // Attempt change with wrong old PIN
        val wrongOld = authRepository.changePin(user.id, "9999", "2222")
        assertTrue(wrongOld.isFailure)

        // Successful PIN change
        val correctChange = authRepository.changePin(user.id, "1111", "2222")
        assertTrue(correctChange.isSuccess)

        // Verify login with new PIN works
        authRepository.logout()
        val newLogin = authRepository.login("tech_admin", "2222")
        assertTrue(newLogin.isSuccess)
    }

    @Test
    fun testDashboardAggregations() = runBlocking {
        // Seed customers with due
        val c1 = CustomerEntity(customerCode = "CUS-001", name = "Customer 1", phone = "0171", currentDue = 2500.0)
        val c2 = CustomerEntity(customerCode = "CUS-002", name = "Customer 2", phone = "0172", currentDue = 1500.0)
        database.masterDao().insertCustomer(c1)
        database.masterDao().insertCustomer(c2)

        // Seed category, brand, and suppliers with due
        val catId = database.masterDao().insertCategory(CategoryEntity(name = "Accessories"))
        val brandId = database.masterDao().insertBrand(BrandEntity(name = "Generic"))
        val s1Id = database.masterDao().insertSupplier(SupplierEntity(name = "Supplier 1", phone = "0181", currentDue = 8000.0))

        // Seed products referencing valid category, brand, and supplier
        val p1 = ProductEntity(name = "Earphone", categoryId = catId, brandId = brandId, supplierId = s1Id, currentStock = 2, minStock = 5, purchasePrice = 200.0)
        val p2 = ProductEntity(name = "Mouse", categoryId = catId, brandId = brandId, supplierId = s1Id, currentStock = 0, minStock = 5, purchasePrice = 300.0)
        val p3 = ProductEntity(name = "Keyboard", categoryId = catId, brandId = brandId, supplierId = s1Id, currentStock = 10, minStock = 5, purchasePrice = 500.0)
        database.productDao().insertProduct(p1)
        database.productDao().insertProduct(p2)
        database.productDao().insertProduct(p3)

        // Verify database dues and counts
        val totalCustomerDue = database.masterDao().getTotalCustomerDue().first()
        assertEquals(4000.0, totalCustomerDue ?: 0.0, 0.01)

        val totalSupplierDue = database.masterDao().getTotalSupplierDue().first()
        assertEquals(8000.0, totalSupplierDue ?: 0.0, 0.01)

        val lowStockCount = database.productDao().getLowStockCount().first()
        assertEquals(1, lowStockCount) // only p1 (stock 2 <= min 5 and > 0)

        val outOfStockCount = database.productDao().getOutOfStockCount().first()
        assertEquals(1, outOfStockCount) // only p2 (stock 0)

        // Stock value = (2 * 200) + (0 * 300) + (10 * 500) = 400 + 5000 = 5400
        val stockValue = database.productDao().getTotalStockValue().first()
        assertEquals(5400.0, stockValue ?: 0.0, 0.01)
    }
}
