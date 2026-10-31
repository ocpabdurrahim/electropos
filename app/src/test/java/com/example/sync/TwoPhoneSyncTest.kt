package com.example.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.entities.*
import com.example.data.repository.PurchaseItemDraft
import com.example.data.repository.SaleItemInput
import com.example.data.repository.SerialImeiInput
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Calendar

/**
 * Two phones meeting in the in-memory cloud: A puts the shop online, B joins as a manager.
 * Covers caches recomputed on the receiving side, last arrival wins, and IMEI clashes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TwoPhoneSyncTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var cloud: FakeCloud
    private lateinit var a: TestPhone
    private lateinit var b: TestPhone
    private lateinit var seed: TestPhone.Seed
    private lateinit var shopId: String
    private val year = Calendar.getInstance().get(Calendar.YEAR)

    @Before
    fun setUp() {
        cloud = FakeCloud()
        a = TestPhone(context, cloud, "ocpabdurrahim@gmail.com", "Phone A")
        seed = a.seedShop("Masum Electronics")
        a.sync.start()
        runBlocking { a.sync.putShopOnline() }
        eventually("A uploaded its shop") { a.pending() == 0 }
        shopId = a.flags().shopId!!
        runBlocking { a.sync.addMember("manager@shop.com", ShopRoles.MANAGER) }

        b = TestPhone(context, cloud, "manager@shop.com", "Phone B")
        b.seedShop("B's own test shop") // wiped by joining
        b.sync.start()
        val shop = runBlocking { b.sync.findMyShops() }.single()
        assertEquals(ShopRoles.MANAGER, shop.role)
        runBlocking { b.sync.joinShop(shop) }
        eventually("B downloaded the shop") { b.flags().let { !it.downloading && it.tracking } }
    }

    @After
    fun tearDown() {
        a.close()
        b.close()
    }

    @Test
    fun joiningGivesTheSecondPhoneTheShopANewBlockAndTillB() = runBlocking {
        assertEquals(1L, b.flags().block)
        assertEquals("B", b.flags().tillCode)
        assertEquals(1, b.safetyCopies)
        assertEquals(1, b.downloads)
        // B's own records are gone, the shop's arrived (same ids as on A, ids are global)
        assertEquals("Masum Electronics", b.db.settingsAndAuditDao().getShopProfileSync()!!.shopName)
        assertEquals(listOf("Charger 20W"), b.db.productDao().getAllProductsSync().map { it.name })
        assertEquals(10, b.db.productDao().getProductById(seed.productId)!!.currentStock)
        assertEquals("owner", b.db.userDao().getUserById(1)!!.username)
        // The wipe sent nothing: A's product is still alive in the cloud
        assertEquals(false, cloud.doc("shops/$shopId/products/${seed.productId}")!!["_del"])
        assertEquals("Charger 20W", cloud.doc("shops/$shopId/products/${seed.productId}")!!["name"])
        // Block and till were taken with the counter
        assertEquals(2L, cloud.doc("shops/$shopId/meta/counters")!!["nextBlock"])
        assertEquals(2L, cloud.doc("shops/$shopId/meta/counters")!!["nextPhone"])
        val device = cloud.doc("shops/$shopId/devices/1")!!
        assertEquals("B", device["tillCode"])
        assertEquals("manager@shop.com", device["email"])
        assertEquals("PHONE", device["kind"])
        assertEquals(0, b.pending())
        // New rows on B take ids of block 1
        val catId = b.db.masterDao().insertCategory(CategoryEntity(name = "From B"))
        assertEquals(SyncIds.firstId(1), catId)
    }

    @Test
    fun aSaleOnPhoneAUpdatesStockDuesAndPaidAmountsOnPhoneB() = runBlocking {
        val saleId = a.sales.createSale(
            SaleEntity(invoiceNumber = "", customerId = seed.customerId, customerName = "Rahim Telecom", subTotal = 1000.0, totalAmount = 1000.0, paidAmount = 400.0, dueAmount = 600.0),
            listOf(SaleItemInput(productId = seed.productId, productName = "Charger 20W", quantity = 2, unitPrice = 500.0))
        ).getOrThrow()
        val invoice = a.db.saleDao().getSaleById(saleId)!!.invoiceNumber
        assertEquals("INV-$year-A00001", invoice)

        eventually("B has the sale with its items") {
            b.db.saleDao().getSaleById(saleId) != null && b.db.saleDao().getSaleItems(saleId).size == 1
        }
        eventually("B worked out stock, due and the invoice's paid/due") {
            val p = b.db.productDao().getProductById(seed.productId)!!
            val c = b.db.customerDao().getCustomerById(seed.customerId)!!
            val s = b.db.saleDao().getSaleById(saleId)!!
            p.currentStock == 8 && c.currentDue == 600.0 && s.paidAmount == 400.0 && s.dueAmount == 600.0 && s.paymentStatus == "PARTIAL"
        }
        val ledger = b.db.customerLedgerDao().getLedgerEntriesForCustomerSync(seed.customerId)
        assertEquals(listOf(1000.0, 600.0), ledger.map { it.runningBalance })
        // Nothing echoes back, and A never re-sent the product or customer for their caches
        assertEquals(0, b.pending())
        eventually("A uploaded everything") { a.pending() == 0 }
        assertEquals(10L, cloud.doc("shops/$shopId/products/${seed.productId}")!!["currentStock"])
        assertEquals(0.0, cloud.doc("shops/$shopId/customers/${seed.customerId}")!!["currentDue"])
    }

    @Test
    fun offlineEditsOnBothPhonesEndWithTheLastOneToArrive() = runBlocking {
        a.store.goOffline()
        b.store.goOffline()
        val onA = a.db.customerDao().getCustomerById(seed.customerId)!!
        a.db.customerDao().updateCustomer(onA.copy(name = "Name from A", updatedAt = System.currentTimeMillis()))
        val onB = b.db.customerDao().getCustomerById(seed.customerId)!!
        b.db.customerDao().updateCustomer(onB.copy(name = "Name from B", address = "Dhaka", updatedAt = System.currentTimeMillis()))

        b.store.goOnline()
        eventually("B's edit reached the cloud") { cloud.doc("shops/$shopId/customers/${seed.customerId}")!!["name"] == "Name from B" }
        a.store.goOnline()
        eventually("A's edit arrived last") { cloud.doc("shops/$shopId/customers/${seed.customerId}")!!["name"] == "Name from A" }
        eventually("both phones show the last arrival") {
            a.db.customerDao().getCustomerById(seed.customerId)!!.name == "Name from A" &&
                b.db.customerDao().getCustomerById(seed.customerId)!!.name == "Name from A"
        }
        // Whole documents: B's address went with B's version
        assertEquals("", b.db.customerDao().getCustomerById(seed.customerId)!!.address)
        eventually("nothing left to send") { a.pending() == 0 && b.pending() == 0 }
    }

    @Test
    fun theSameImeiBoughtOnBothPhonesOfflineBecomesASyncIssue() = runBlocking {
        val supplierId = a.suppliers.saveSupplier(SupplierEntity(name = "Dhaka Gadgets", phone = "01811000000")).getOrThrow()
        val phoneId = a.products.saveProduct(ProductEntity(name = "Galaxy A15", trackingType = TrackingType.IMEI, retailPrice = 18500.0)).getOrThrow()
        eventually("B knows the supplier and the product") {
            b.db.supplierDao().getSupplierById(supplierId) != null && b.db.productDao().getProductById(phoneId) != null
        }
        a.store.goOffline()
        b.store.goOffline()
        val imei = "356789012345678"
        for (phone in listOf(a, b)) {
            phone.purchases.createPurchase(
                PurchaseEntity(supplierId = supplierId),
                listOf(
                    PurchaseItemDraft(
                        productId = phoneId, productName = "Galaxy A15", quantity = 1, unitCost = 16000.0,
                        trackingType = TrackingType.IMEI, serialsOrImeis = listOf(SerialImeiInput(imei1 = imei))
                    )
                )
            ).getOrThrow()
        }
        // Each till numbers its own purchases, so only the IMEI clashes
        assertNotNull(a.db.purchaseDao().getPurchaseByPurchaseNumber("PUR-$year-A00001"))
        assertNotNull(b.db.purchaseDao().getPurchaseByPurchaseNumber("PUR-$year-B00001"))

        b.store.goOnline()
        eventually("B uploaded") { b.pending() == 0 }
        a.store.goOnline()
        eventually("A uploaded") { a.pending() == 0 }
        eventually("both phones list the clash") {
            a.conflicts().any { it.tbl == "product_serial_imei" && it.kind == SyncApplier.KIND_DUPLICATE } &&
                b.conflicts().any { it.tbl == "product_serial_imei" && it.kind == SyncApplier.KIND_DUPLICATE }
        }
        assertTrue(a.conflicts().first().message.contains(imei))
        // Each phone keeps its own unit; the other purchase arrived anyway
        assertEquals(1, a.db.productDao().countSerialsForProduct(phoneId))
        eventually("purchases crossed over") {
            a.db.purchaseDao().getPurchaseByPurchaseNumber("PUR-$year-B00001") != null &&
                b.db.purchaseDao().getPurchaseByPurchaseNumber("PUR-$year-A00001") != null
        }
        eventually("A's stock counts both purchases") { a.db.productDao().getProductById(phoneId)!!.currentStock == 2 }
    }

    @Test
    fun aDeactivatedMembersChangesWaitOnThePhoneInsteadOfBeingDropped() = runBlocking {
        a.sync.setMemberActive("manager@shop.com", false)
        cloud.refuse = { _, email -> email == "manager@shop.com" } // what the rules do for an inactive member
        val catId = b.db.masterDao().insertCategory(CategoryEntity(name = "Made while deactivated"))
        eventually("B reports the problem") { b.sync.status.value.error != null }
        assertEquals(1, b.pending())
        assertTrue(b.conflicts().isEmpty())

        a.sync.setMemberActive("manager@shop.com", true)
        cloud.refuse = { _, _ -> false }
        b.sync.syncNow()
        eventually("B's change went up after all") { b.pending() == 0 && cloud.doc("shops/$shopId/categories/$catId") != null }
    }

    @Test
    fun aChangeTheServerRefusesIsListedAndTheRestStillGoesUp() = runBlocking {
        cloud.refuse = { path, _ -> path.contains("/users/") }
        val user = b.db.userDao().getUserById(1)!!
        b.db.userDao().updateUser(user.copy(name = "Renamed on B"))
        val catId = b.db.masterDao().insertCategory(CategoryEntity(name = "Accessories"))
        eventually("B's outbox emptied") { b.pending() == 0 }
        assertTrue(b.conflicts().any { it.tbl == "users" && it.kind == SyncApplier.KIND_REJECTED })
        assertNotNull(cloud.doc("shops/$shopId/categories/$catId"))
        assertEquals("Owner", cloud.doc("shops/$shopId/users/1")!!["name"])
    }
}
