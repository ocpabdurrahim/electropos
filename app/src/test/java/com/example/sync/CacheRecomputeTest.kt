package com.example.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.database.AppDatabase
import com.example.data.local.entities.*
import com.example.data.repository.*
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The caches another device recomputes come out exactly as this phone's own repositories wrote them. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CacheRecomputeTest {
    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        db = syncDatabase(ApplicationProvider.getApplicationContext<Context>())
    }

    @After
    fun tearDown() = db.close()

    private suspend fun supplier(): Long = SupplierRepository(db).saveSupplier(SupplierEntity(name = "Vendor", phone = "01800000000")).getOrThrow()

    private suspend fun buy(supplierId: Long, productId: Long, qty: Int, cost: Double, variantId: Long = 0, transport: Double = 0.0, invoiceDiscount: Double = 0.0): Long =
        PurchaseRepository(db).createPurchase(
            PurchaseEntity(supplierId = supplierId, transportCost = transport, invoiceDiscount = invoiceDiscount),
            listOf(PurchaseItemDraft(productId = productId, productName = "P", variantId = variantId, quantity = qty, unitCost = cost))
        ).getOrThrow()

    @Test
    fun weightedCostReplayMatchesWhatThePurchasesWrote() = runBlocking {
        val s = supplier()
        val productId = ProductRepository(db).saveProduct(ProductEntity(name = "Router", purchasePrice = 100.0, retailPrice = 200.0, currentStock = 5)).getOrThrow()
        buy(s, productId, 5, 140.0, transport = 50.0)
        val second = buy(s, productId, 3, 120.0, invoiceDiscount = 30.0)
        SaleRepository(db).createSale(
            SaleEntity(invoiceNumber = "", subTotal = 400.0, totalAmount = 400.0, paidAmount = 400.0),
            listOf(SaleItemInput(productId = productId, productName = "Router", quantity = 2, unitPrice = 200.0))
        ).getOrThrow()
        buy(s, productId, 4, 133.33)
        PurchaseRepository(db).cancelPurchase(second, "wrong invoice").getOrThrow()

        val product = db.productDao().getProductById(productId)!!
        val moves = db.stockDao().getTransactionsForProductSync(productId)
        assertEquals(product.purchasePrice, WeightedCost.replay(moves, -1.0, restoreOnCancel = true), 0.0)
        assertEquals(product.currentStock, db.stockDao().calculateAuthoritativeStock(productId))
        // Without any purchase the entered cost stays
        val plain = ProductRepository(db).saveProduct(ProductEntity(name = "Sticker", purchasePrice = 7.5, currentStock = 3)).getOrThrow()
        assertEquals(7.5, WeightedCost.replay(db.stockDao().getTransactionsForProductSync(plain), 7.5, restoreOnCancel = true), 0.0)
    }

    @Test
    fun variantCostReplayMatchesToo() = runBlocking {
        val s = supplier()
        val products = ProductRepository(db)
        val productId = products.saveProduct(ProductEntity(name = "Galaxy", retailPrice = 20000.0)).getOrThrow()
        val variantId = products.saveVariant(ProductVariantEntity(productId = productId, variantName = "8/128", purchasePrice = 15000.0)).getOrThrow()
        buy(s, productId, 2, 15500.0, variantId = variantId)
        val later = buy(s, productId, 2, 16100.0, variantId = variantId)
        PurchaseRepository(db).cancelPurchase(later, "returned unopened").getOrThrow()

        val variant = db.productVariantDao().getVariantById(variantId)!!
        val moves = db.stockDao().getTransactionsForProductSync(productId).filter { it.variantId == variantId }
        assertEquals(variant.purchasePrice, WeightedCost.replay(moves, -1.0, restoreOnCancel = false), 0.0)
        assertEquals(db.productDao().getProductById(productId)!!.purchasePrice, WeightedCost.replay(db.stockDao().getTransactionsForProductSync(productId), -1.0, true), 0.0)
    }

    @Test
    fun recomputeRepairsEveryCacheFromTheRecords() = runBlocking {
        val s = supplier()
        val productId = ProductRepository(db).saveProduct(ProductEntity(name = "Cable", purchasePrice = 50.0, retailPrice = 90.0, currentStock = 10)).getOrThrow()
        val purchaseId = buy(s, productId, 10, 60.0)
        val customers = CustomerRepository(db)
        val customerId = customers.saveCustomer(CustomerEntity(name = "Karim", phone = "01900000000", openingDue = 100.0)).getOrThrow()
        val saleId = SaleRepository(db).createSale(
            SaleEntity(invoiceNumber = "", customerId = customerId, customerName = "Karim", subTotal = 450.0, totalAmount = 450.0, paidAmount = 200.0, dueAmount = 250.0),
            listOf(SaleItemInput(productId = productId, productName = "Cable", quantity = 5, unitPrice = 90.0))
        ).getOrThrow()
        val truth = Triple(db.productDao().getProductById(productId)!!, db.customerDao().getCustomerById(customerId)!!, db.saleDao().getSaleById(saleId)!!)
        val ledger = db.customerLedgerDao().getLedgerEntriesForCustomerSync(customerId).map { it.runningBalance }
        val purchase = db.purchaseDao().getPurchaseById(purchaseId)!!

        // What another device's documents might carry: stale caches
        val sql = db.openHelper.writableDatabase
        sql.execSQL("UPDATE products SET currentStock = 999, purchasePrice = 1 WHERE id = $productId")
        sql.execSQL("UPDATE customers SET currentDue = 0 WHERE id = $customerId")
        sql.execSQL("UPDATE sales SET paidAmount = 0, dueAmount = 0, paymentStatus = 'PAID' WHERE id = $saleId")
        sql.execSQL("UPDATE customer_ledger_entries SET runningBalance = 0")
        sql.execSQL("UPDATE purchases SET paidAmount = 1, dueAmount = 1, paymentStatus = 'PARTIAL'")
        sql.execSQL("UPDATE suppliers SET currentDue = 0")

        CacheRecompute(db).all()

        val p = db.productDao().getProductById(productId)!!
        assertEquals(truth.first.currentStock, p.currentStock)
        assertEquals(truth.first.purchasePrice, p.purchasePrice, 0.0)
        assertEquals(truth.second.currentDue, db.customerDao().getCustomerById(customerId)!!.currentDue, 0.0)
        val sale = db.saleDao().getSaleById(saleId)!!
        assertEquals(listOf(truth.third.paidAmount, truth.third.dueAmount), listOf(sale.paidAmount, sale.dueAmount))
        assertEquals(truth.third.paymentStatus, sale.paymentStatus)
        assertEquals(ledger, db.customerLedgerDao().getLedgerEntriesForCustomerSync(customerId).map { it.runningBalance })
        val p2 = db.purchaseDao().getPurchaseById(purchaseId)!!
        assertEquals(listOf(purchase.paidAmount, purchase.dueAmount), listOf(p2.paidAmount, p2.dueAmount))
        assertEquals(purchase.paymentStatus, p2.paymentStatus)
        assertEquals(600.0, db.supplierDao().getSupplierById(s)!!.currentDue, 0.0)
    }
}
