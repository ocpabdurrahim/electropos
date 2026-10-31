package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.database.AppDatabase
import com.example.data.local.entities.*
import com.example.data.repository.*
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Product variants: creating them, buying and selling a specific variant, and category/brand renames. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VariantsTest {

    private lateinit var database: AppDatabase
    private lateinit var productRepository: ProductRepository
    private lateinit var purchaseRepository: PurchaseRepository
    private lateinit var saleRepository: SaleRepository
    private lateinit var supplierRepository: SupplierRepository
    private var supplierId = 0L
    private var productId = 0L

    @Before
    fun setup() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        productRepository = ProductRepository(database)
        purchaseRepository = PurchaseRepository(database)
        saleRepository = SaleRepository(database)
        supplierRepository = SupplierRepository(database)
        supplierId = supplierRepository.saveSupplier(SupplierEntity(name = "Vendor", phone = "01700000009")).getOrThrow()
        productId = database.productDao().insertProduct(
            ProductEntity(name = "Galaxy A15", trackingType = TrackingType.QUANTITY, retailPrice = 20000.0, purchasePrice = 0.0)
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    private suspend fun variant(name: String, retail: Double, sku: String = "", barcode: String = ""): Long =
        productRepository.saveVariant(
            ProductVariantEntity(productId = productId, variantName = name, retailPrice = retail, sku = sku, barcode = barcode)
        ).getOrThrow()

    private suspend fun buyVariant(variantId: Long, qty: Int, unitCost: Double) {
        purchaseRepository.createPurchase(
            PurchaseEntity(supplierId = supplierId),
            listOf(PurchaseItemDraft(productId = productId, productName = "Galaxy A15", variantId = variantId, variantName = "v", quantity = qty, unitCost = unitCost))
        ).getOrThrow()
    }

    @Test
    fun newVariant_startsEmpty_andMarksProductAsHavingVariants() = runBlocking {
        val id = productRepository.saveVariant(
            ProductVariantEntity(productId = productId, variantName = "8/128 Black", retailPrice = 21000.0, currentStock = 50)
        ).getOrThrow()
        assertEquals(0, database.productVariantDao().getVariantById(id)!!.currentStock)
        assertTrue(database.productDao().getProductById(productId)!!.hasVariants)
    }

    @Test
    fun duplicateVariantNameOrBarcode_isRefused() = runBlocking {
        variant("8/128 Black", 21000.0, barcode = "8800000000011")
        assertTrue(productRepository.saveVariant(ProductVariantEntity(productId = productId, variantName = "8/128 black", retailPrice = 1.0)).isFailure)
        assertTrue(productRepository.saveVariant(ProductVariantEntity(productId = productId, variantName = "8/256", retailPrice = 1.0, barcode = "8800000000011")).isFailure)
    }

    @Test
    fun buyingAndSellingAVariant_movesThatVariantsStock_andUsesItsCost() = runBlocking {
        val small = variant("8/128", 21000.0)
        val big = variant("8/256", 24000.0)
        buyVariant(small, 3, 18000.0)
        buyVariant(big, 2, 21000.0)

        assertEquals(3, database.productVariantDao().getVariantById(small)!!.currentStock)
        assertEquals(2, database.productVariantDao().getVariantById(big)!!.currentStock)
        assertEquals(5, database.productDao().getProductById(productId)!!.currentStock)
        assertEquals(21000.0, database.productVariantDao().getVariantById(big)!!.purchasePrice, 0.01)

        val saleId = saleRepository.createSale(
            SaleEntity(invoiceNumber = "", subTotal = 24000.0, totalAmount = 24000.0, paidAmount = 24000.0),
            listOf(SaleItemInput(productId = productId, productName = "Galaxy A15", variantId = big, quantity = 1, unitPrice = 24000.0))
        ).getOrThrow()

        assertEquals(1, database.productVariantDao().getVariantById(big)!!.currentStock)
        assertEquals(4, database.productDao().getProductById(productId)!!.currentStock)
        val sale = saleRepository.getSaleById(saleId)!!
        // Profit uses the 8/256's own cost (21,000), not the product's average
        assertEquals(3000.0, sale.totalProfit, 0.01)
        assertEquals("Galaxy A15 (8/256)", saleRepository.getSaleItems(saleId).single().productName)
    }

    @Test
    fun variantWithStock_cannotBeRemoved() = runBlocking {
        val id = variant("8/128", 21000.0)
        buyVariant(id, 1, 18000.0)
        assertTrue(productRepository.deactivateVariant(id).isFailure)
    }

    @Test
    fun scanningAVariantBarcode_findsTheVariant() = runBlocking {
        val id = variant("8/256 Blue", 24000.0, barcode = "8800000000028")
        val match = productRepository.findByCode("8800000000028")!!
        assertEquals(productId, match.product.id)
        assertEquals(id, match.variant!!.id)
    }

    @Test
    fun renamingCategoryAndBrand_updatesProducts_andReaddingHiddenOneReactivatesIt() = runBlocking {
        val categoryRepository = CategoryRepository(database.masterDao(), database.subcategoryDao(), database.settingsAndAuditDao())
        val brandRepository = BrandRepository(database.masterDao(), database.settingsAndAuditDao())
        val catId = categoryRepository.saveCategory(CategoryEntity(name = "Phones")).getOrThrow()
        val brandId = brandRepository.saveBrand(BrandEntity(name = "Samsung")).getOrThrow()
        database.productDao().updateProduct(
            database.productDao().getProductById(productId)!!.copy(categoryId = catId, categoryName = "Phones", brandId = brandId, brandName = "Samsung")
        )

        categoryRepository.saveCategory(database.masterDao().getCategoryById(catId)!!.copy(name = "Mobile Phones")).getOrThrow()
        brandRepository.saveBrand(database.masterDao().getBrandById(brandId)!!.copy(name = "Samsung BD")).getOrThrow()
        val product = database.productDao().getProductById(productId)!!
        assertEquals("Mobile Phones", product.categoryName)
        assertEquals("Samsung BD", product.brandName)

        val unused = categoryRepository.saveCategory(CategoryEntity(name = "Tablets")).getOrThrow()
        database.masterDao().deactivateCategory(unused)
        assertEquals(unused, categoryRepository.saveCategory(CategoryEntity(name = "tablets")).getOrThrow())
        assertTrue(database.masterDao().getCategoryById(unused)!!.isActive)
    }
}
