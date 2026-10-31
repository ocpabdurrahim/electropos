package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.core.utils.BarcodeUtils
import com.example.core.utils.SkuUtils
import com.example.data.local.database.AppDatabase
import com.example.data.local.entities.*
import com.example.data.repository.*
import kotlinx.coroutines.flow.first
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
class Phase3ProductMasterTest {

    private lateinit var database: AppDatabase
    private lateinit var categoryRepository: CategoryRepository
    private lateinit var brandRepository: BrandRepository
    private lateinit var unitRepository: UnitRepository
    private lateinit var productRepository: ProductRepository

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        categoryRepository = CategoryRepository(database.masterDao(), database.subcategoryDao(), database.settingsAndAuditDao())
        brandRepository = BrandRepository(database.masterDao(), database.settingsAndAuditDao())
        unitRepository = UnitRepository(database.unitDao(), database.settingsAndAuditDao())
        productRepository = ProductRepository(
            database.productDao(),
            database.stockDao(),
            database.productVariantDao(),
            database.productPriceHistoryDao(),
            database.settingsAndAuditDao()
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun testSkuAndBarcodeGenerators() {
        // Test SKU generator format
        val sku = SkuUtils.generateSku("Mobile Phones", "Samsung", "Galaxy A15", 42)
        assertEquals("MOB-SAM-GALAXY-0042", sku)

        // Test EAN-13 generator and checksum validation
        val barcode = BarcodeUtils.generateEan13(123456789L)
        assertEquals(13, barcode.length)
        assertTrue(BarcodeUtils.isValidEan13(barcode))
    }

    @Test
    fun testCategoryAndSubcategoryLifecycle() = runBlocking {
        // Create Category
        val catId = categoryRepository.saveCategory(
            CategoryEntity(name = "Mobile Accessories", description = "Covers and Cables")
        ).getOrThrow()
        assertTrue(catId > 0)

        // Create Subcategory
        val subId = categoryRepository.saveSubcategory(
            SubcategoryEntity(categoryId = catId, name = "Fast Chargers")
        ).getOrThrow()
        assertTrue(subId > 0)

        // Verify subcategory linked to category
        val subs = categoryRepository.getSubcategoriesByCategorySync(catId)
        assertEquals(1, subs.size)
        assertEquals("Fast Chargers", subs[0].name)

        // Duplicate subcategory in same category rejected
        val dupSub = categoryRepository.saveSubcategory(
            SubcategoryEntity(categoryId = catId, name = "Fast Chargers")
        )
        assertTrue(dupSub.isFailure)
    }

    @Test
    fun testBrandAndUnitCreation() = runBlocking {
        val brandId = brandRepository.saveBrand(BrandEntity(name = "Anker", originCountry = "USA")).getOrThrow()
        assertTrue(brandId > 0)

        val unitId = unitRepository.saveUnit(UnitEntity(name = "Piece", code = "Pcs")).getOrThrow()
        assertTrue(unitId > 0)

        val units = unitRepository.allUnits.first()
        assertTrue(units.any { it.name == "Piece" })
    }

    @Test
    fun testProductCreationThreeTypes() = runBlocking {
        val catId = categoryRepository.saveCategory(CategoryEntity(name = "Electronics")).getOrThrow()
        val brandId = brandRepository.saveBrand(BrandEntity(name = "Samsung")).getOrThrow()
        val routerBrandId = brandRepository.saveBrand(BrandEntity(name = "TP-Link")).getOrThrow()

        // 1. Quantity-Based Accessory (Type A: Non-serialized)
        val pQuantity = ProductEntity(
            name = "Baseus USB-C 100W Cable",
            sku = "BAS-CBL-100W",
            barcode = "695315620001",
            categoryId = catId,
            brandId = brandId,
            productType = "PHYSICAL",
            trackingType = "QUANTITY",
            purchasePrice = 300.0,
            retailPrice = 450.0,
            currentStock = 50,
            minStock = 10
        )
        val id1 = productRepository.saveProduct(pQuantity).getOrThrow()
        assertTrue(id1 > 0)

        // 2. Serialized Product (Type B: Serial e.g. Router)
        val pSerial = ProductEntity(
            name = "TP-Link Archer C6 AC1200 Router",
            sku = "TPL-ARCH-C6",
            barcode = "693536408420",
            categoryId = catId,
            brandId = routerBrandId,
            productType = "PHYSICAL",
            trackingType = "SERIAL",
            isSerialRequired = true,
            hasWarranty = true,
            warrantyPeriodMonths = 12,
            purchasePrice = 2800.0,
            retailPrice = 3400.0,
            currentStock = 0 // serialized units arrive through purchases
        )
        val id2 = productRepository.saveProduct(pSerial).getOrThrow()
        assertTrue(id2 > 0)

        // 3. Mobile Product (Type B: IMEI e.g. Samsung Galaxy A15)
        val pMobile = ProductEntity(
            name = "Samsung Galaxy A15 (8/256GB)",
            sku = "SAM-A15-256",
            barcode = "880609531099",
            categoryId = catId,
            brandId = brandId,
            productType = "PHYSICAL",
            trackingType = "IMEI",
            isImeiRequired = true,
            hasWarranty = true,
            warrantyPeriodMonths = 12,
            purchasePrice = 21000.0,
            retailPrice = 24500.0,
            currentStock = 0 // serialized units arrive through purchases
        )
        val id3 = productRepository.saveProduct(pMobile).getOrThrow()
        assertTrue(id3 > 0)

        // Duplicate SKU check
        assertTrue(productRepository.checkSkuExists("SAM-A15-256"))
        assertFalse(productRepository.checkSkuExists("NON-EXISTENT-SKU"))

        // Duplicate Barcode check
        assertTrue(productRepository.checkBarcodeExists("880609531099"))
        assertFalse(productRepository.checkBarcodeExists("000000000000"))
    }

    @Test
    fun testPriceHistoryTracking() = runBlocking {
        val catId = categoryRepository.saveCategory(CategoryEntity(name = "Peripherals")).getOrThrow()
        val brandId = brandRepository.saveBrand(BrandEntity(name = "Logitech")).getOrThrow()

        val product = ProductEntity(
            name = "Logitech B100 Mouse",
            sku = "LOG-B100-USB",
            categoryId = catId,
            brandId = brandId,
            purchasePrice = 250.0,
            retailPrice = 380.0,
            currentStock = 20
        )
        val prodId = productRepository.saveProduct(product, "Admin").getOrThrow()

        // Initial price history recorded
        val history1 = productRepository.getPriceHistory(prodId).first()
        assertEquals(1, history1.size)
        assertEquals(380.0, history1[0].retailPrice, 0.01)

        // Update retail price
        val updatedProduct = product.copy(id = prodId, retailPrice = 420.0)
        productRepository.saveProduct(updatedProduct, "Admin").getOrThrow()

        // New price history record added
        val history2 = productRepository.getPriceHistory(prodId).first()
        assertEquals(2, history2.size)
        assertEquals(420.0, history2[0].retailPrice, 0.01)
    }

    @Test
    fun testDeletionSafetyRules() = runBlocking {
        val catId = categoryRepository.saveCategory(CategoryEntity(name = "Storage")).getOrThrow()
        val brandId = brandRepository.saveBrand(BrandEntity(name = "SanDisk")).getOrThrow()

        val product = ProductEntity(
            name = "SanDisk 64GB Flash Drive",
            sku = "SD-64GB",
            categoryId = catId,
            brandId = brandId,
            currentStock = 5 // Has opening stock transaction created
        )
        val prodId = productRepository.saveProduct(product).getOrThrow()
        val savedProduct = productRepository.getProductById(prodId)!!

        // Attempting to delete a product that has opening stock transaction
        val deleteResult = productRepository.deleteProductIfUnused(savedProduct)
        // Must fail deletion and deactivate instead
        assertTrue(deleteResult.isFailure)
        assertTrue(deleteResult.exceptionOrNull()?.message?.contains("deactivated") == true)

        // Verify product isActive is now 0 (deactivated)
        val deactivated = productRepository.getProductById(prodId)
        assertFalse(deactivated?.isActive ?: true)
    }
}
