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
import java.util.Calendar

/** Numbers people see: the old formats offline, the till code and this phone's own counter online. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DocNumbersTest {
    private lateinit var db: AppDatabase
    private val year = Calendar.getInstance().get(Calendar.YEAR)

    @Before
    fun setUp() {
        db = syncDatabase(ApplicationProvider.getApplicationContext<Context>())
        runBlocking { db.settingsAndAuditDao().saveShopProfile(ShopProfileEntity(shopName = "Shop", setupCompleted = true)) }
    }

    @After
    fun tearDown() = db.close()

    private fun goOnline(till: String) = runBlocking {
        db.syncDao().saveFlags(SyncFlagsEntity(tracking = true, block = 1, tillCode = till, shopId = "shop"))
    }

    @Test
    fun offlineTheOldFormatsStay() = runBlocking {
        assertEquals("INV-$year-001001", SaleRepository(db).generateNextInvoiceNumber())
        assertEquals("PUR-$year-000001", PurchaseRepository(db).generatePurchaseNumber())
        assertEquals("PRT-$year-000001", PurchaseReturnRepository(db).generateReturnNumber())
        assertEquals("SRT-$year-000001", SalesReturnRepository(db).generateNextReturnNumber())
        assertEquals("SPAY-$year-000001", SupplierPaymentRepository(db).generatePaymentNumber())
        assertEquals("CUS-0001", CustomerRepository(db).generateNextCustomerCode())
        assertEquals("SUP-0001", SupplierRepository(db).generateNextSupplierCode())
    }

    @Test
    fun onlineEveryNumberCarriesTheTillCode() = runBlocking {
        goOnline("B")
        assertEquals("INV-$year-B00001", SaleRepository(db).generateNextInvoiceNumber())
        assertEquals("PUR-$year-B00001", PurchaseRepository(db).generatePurchaseNumber())
        assertEquals("PRT-$year-B00001", PurchaseReturnRepository(db).generateReturnNumber())
        assertEquals("SRT-$year-B00001", SalesReturnRepository(db).generateNextReturnNumber())
        assertEquals("SPAY-$year-B00001", SupplierPaymentRepository(db).generatePaymentNumber())
        assertEquals("CUS-B0001", CustomerRepository(db).generateNextCustomerCode())
        assertEquals("SUP-B0001", SupplierRepository(db).generateNextSupplierCode())
        // Prefixes from the shop profile apply as before
        db.settingsAndAuditDao().saveShopProfile(db.settingsAndAuditDao().getShopProfileSync()!!.copy(invoicePrefix = "MSM-", customerCodePrefix = "C-"))
        assertEquals("MSM-$year-B00001", SaleRepository(db).generateNextInvoiceNumber())
        assertEquals("C-B0001", CustomerRepository(db).generateNextCustomerCode())
    }

    @Test
    fun onlineCountersMoveOnAndSkipNumbersAlreadyTaken() = runBlocking {
        goOnline("C")
        val customers = CustomerRepository(db)
        val first = customers.saveCustomer(CustomerEntity(name = "One", phone = "01700000001")).getOrThrow()
        assertEquals("CUS-C0001", customers.getCustomerById(first)!!.customerCode)
        // A record from the cloud already uses the next number (e.g. this till's own, sent before a reinstall)
        db.syncDao().saveFlags(db.syncDao().flags()!!.copy(applying = true))
        db.customerDao().insertCustomer(CustomerEntity(id = SyncIds.firstId(7), customerCode = "CUS-C0002", name = "From cloud", phone = "01700000009"))
        db.syncDao().saveFlags(db.syncDao().flags()!!.copy(applying = false))
        val second = customers.saveCustomer(CustomerEntity(name = "Two", phone = "01700000002")).getOrThrow()
        assertEquals("CUS-C0003", customers.getCustomerById(second)!!.customerCode)

        val paymentId = customers.recordCustomerPayment(first, 100.0, "CASH").getOrThrow()
        assertEquals("CPAY-C00001", db.customerPaymentDao().getPaymentById(paymentId)!!.paymentNumber)
        val again = customers.recordCustomerPayment(first, 50.0, "CASH").getOrThrow()
        assertEquals("CPAY-C00002", db.customerPaymentDao().getPaymentById(again)!!.paymentNumber)
        // Only a peek: asking twice without using the number gives the same one
        assertEquals("CUS-C0004", customers.generateNextCustomerCode())
        assertEquals("CUS-C0004", customers.generateNextCustomerCode())
    }

    @Test
    fun tillCodesSkipTheWebsW() {
        assertEquals("A", TillCodes.forIndex(0))
        assertEquals("V", TillCodes.forIndex(21))
        assertEquals("X", TillCodes.forIndex(22))
        assertEquals("Z", TillCodes.forIndex(24))
        assertEquals("AA", TillCodes.forIndex(25))
        assertEquals("AB", TillCodes.forIndex(26))
        assertEquals("AW", TillCodes.forIndex(47))
        assertEquals("AZ", TillCodes.forIndex(50))
        assertEquals("BA", TillCodes.forIndex(51))
        assertEquals("ZZ", TillCodes.forIndex(25 + 26 * 26 - 1))
        assertEquals("AAA", TillCodes.forIndex(25 + 26 * 26))
        assertEquals(false, (0L until 200L).map { TillCodes.forIndex(it) }.contains(TillCodes.WEB))
    }
}
