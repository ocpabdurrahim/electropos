package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.core.utils.SecurityUtils
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

/** Money/stock fixes from the 2026-10-31 review. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReviewFixesTest {

    private lateinit var database: AppDatabase
    private lateinit var supplierRepository: SupplierRepository
    private lateinit var purchaseRepository: PurchaseRepository
    private lateinit var purchaseReturnRepository: PurchaseReturnRepository
    private lateinit var supplierPaymentRepository: SupplierPaymentRepository
    private lateinit var customerRepository: CustomerRepository
    private lateinit var saleRepository: SaleRepository
    private lateinit var salesReturnRepository: SalesReturnRepository
    private lateinit var authRepository: AuthRepository

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        supplierRepository = SupplierRepository(database)
        purchaseRepository = PurchaseRepository(database)
        purchaseReturnRepository = PurchaseReturnRepository(database)
        supplierPaymentRepository = SupplierPaymentRepository(database)
        customerRepository = CustomerRepository(database)
        saleRepository = SaleRepository(database)
        salesReturnRepository = SalesReturnRepository(database)
        authRepository = AuthRepository(database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    // ---------------------------------------------------------------- helpers

    private suspend fun supplier(name: String = "Vendor", phone: String = "01700000001"): Long =
        supplierRepository.saveSupplier(SupplierEntity(name = name, phone = phone)).getOrThrow()

    private suspend fun product(name: String, tracking: String = TrackingType.QUANTITY, cost: Double = 0.0, price: Double = 0.0): Long =
        database.productDao().insertProduct(
            ProductEntity(name = name, trackingType = tracking, purchasePrice = cost, retailPrice = price)
        )

    private suspend fun buy(
        supplierId: Long,
        productId: Long,
        qty: Int,
        unitCost: Double,
        lineDiscount: Double = 0.0,
        invoiceDiscount: Double = 0.0,
        paid: Double = 0.0,
        date: Long = System.currentTimeMillis(),
        imeis: List<String> = emptyList()
    ): Long {
        val p = database.productDao().getProductById(productId)!!
        return purchaseRepository.createPurchase(
            PurchaseEntity(supplierId = supplierId, invoiceDiscount = invoiceDiscount, paidAmount = paid, purchaseDate = date),
            listOf(
                PurchaseItemDraft(
                    productId = productId,
                    productName = p.name,
                    quantity = qty,
                    unitCost = unitCost,
                    discount = lineDiscount,
                    trackingType = p.trackingType,
                    serialsOrImeis = imeis.map { SerialImeiInput(imei1 = it) }
                )
            )
        ).getOrThrow()
    }

    private suspend fun sell(
        productId: Long,
        qty: Int,
        unitPrice: Double,
        customerId: Long? = null,
        lineDiscount: Double = 0.0,
        lineTax: Double = 0.0,
        invoiceDiscount: Double = 0.0,
        paid: Double? = null,
        unitIds: List<Long> = emptyList(),
        date: Long = System.currentTimeMillis()
    ): Long {
        val p = database.productDao().getProductById(productId)!!
        val lineTotal = qty * unitPrice - lineDiscount + lineTax
        val total = lineTotal - invoiceDiscount
        val paidAmount = paid ?: total
        val customer = customerId?.let { customerRepository.getCustomerById(it) }
        return saleRepository.createSale(
            SaleEntity(
                invoiceNumber = "",
                customerId = customerId,
                customerName = customer?.name ?: "Walk-in Customer",
                saleDate = date,
                subTotal = qty * unitPrice,
                itemDiscount = lineDiscount,
                invoiceDiscount = invoiceDiscount,
                taxAmount = lineTax,
                totalAmount = total,
                paidAmount = paidAmount,
                dueAmount = total - paidAmount
            ),
            listOf(
                SaleItemInput(
                    productId = productId,
                    productName = p.name,
                    quantity = qty,
                    unitPrice = unitPrice,
                    discount = lineDiscount,
                    tax = lineTax,
                    trackingType = p.trackingType,
                    selectedSerialImeiIds = unitIds,
                    selectedSerialsOrImeis = unitIds.map { "unit-$it" }
                )
            )
        ).getOrThrow()
    }

    private suspend fun customer(name: String, phone: String): Long =
        customerRepository.saveCustomer(CustomerEntity(name = name, phone = phone)).getOrThrow()

    // ---------------------------------------------------------------- purchases

    @Test
    fun imeiPurchaseReturn_creditsWhatWasOwed_afterLineAndInvoiceDiscount() = runBlocking {
        val s = supplier()
        val phone = product("Galaxy A15", TrackingType.IMEI)
        // 2 x 20,000 - 1,000 line discount = 39,000; - 1,000 invoice discount = 38,000 owed
        val purchaseId = buy(s, phone, 2, 20000.0, lineDiscount = 1000.0, invoiceDiscount = 1000.0,
            imeis = listOf("350000000000001", "350000000000002"))
        assertEquals(38000.0, supplierRepository.getSupplierById(s)!!.currentDue, 0.01)
        // Unit cost in stock includes the invoice discount share
        assertEquals(19000.0, database.productDao().getProductById(phone)!!.purchasePrice, 0.01)

        val line = purchaseReturnRepository.getReturnableItemsForPurchase(purchaseId).single()
        assertEquals(19000.0, line.returnUnitValue, 0.01)
        val unit = line.serialUnits.first()
        purchaseReturnRepository.createPurchaseReturn(
            PurchaseReturnEntity(purchaseId = purchaseId, supplierId = s),
            listOf(
                PurchaseReturnItemDraft(
                    purchaseItemId = line.purchaseItem.id, productId = phone, productName = "Galaxy A15",
                    returnQuantity = 1, unitCost = 99999.0, totalAmount = 99999.0, // caller's numbers are ignored
                    serialUnitId = unit.id, serialOrImei = unit.imei1 ?: ""
                )
            )
        ).getOrThrow()

        assertEquals(19000.0, supplierRepository.getSupplierById(s)!!.currentDue, 0.01)
        val purchase = purchaseRepository.getPurchaseById(purchaseId)!!
        assertEquals(19000.0, purchase.dueAmount, 0.01)
        assertEquals("DUE", purchase.paymentStatus)
    }

    @Test
    fun imeiPurchase_requiresPickedUnitOnReturn() = runBlocking {
        val s = supplier()
        val phone = product("Redmi 13", TrackingType.IMEI)
        val purchaseId = buy(s, phone, 1, 15000.0, imeis = listOf("350000000000011"))
        val line = purchaseReturnRepository.getReturnableItemsForPurchase(purchaseId).single()
        val result = purchaseReturnRepository.createPurchaseReturn(
            PurchaseReturnEntity(purchaseId = purchaseId, supplierId = s),
            listOf(PurchaseReturnItemDraft(line.purchaseItem.id, phone, "Redmi 13", returnQuantity = 1, unitCost = 0.0, totalAmount = 0.0))
        )
        assertTrue(result.isFailure)
        assertEquals(1, database.productDao().getProductById(phone)!!.currentStock)
    }

    @Test
    fun purchaseReturnWithCashRefund_isNotCreditedTwice() = runBlocking {
        val s = supplier()
        val cable = product("USB-C cable")
        val purchaseId = buy(s, cable, 10, 1000.0, paid = 10000.0)
        assertEquals(0.0, supplierRepository.getSupplierById(s)!!.currentDue, 0.01)

        val line = purchaseReturnRepository.getReturnableItemsForPurchase(purchaseId).single()
        purchaseReturnRepository.createPurchaseReturn(
            PurchaseReturnEntity(purchaseId = purchaseId, supplierId = s, refundAmount = 2000.0),
            listOf(PurchaseReturnItemDraft(line.purchaseItem.id, cable, "USB-C cable", returnQuantity = 2, unitCost = 1000.0, totalAmount = 2000.0))
        ).getOrThrow()

        // Supplier took 2 cables back and paid 2,000 cash: nobody owes anybody
        assertEquals(0.0, supplierRepository.getSupplierById(s)!!.currentDue, 0.01)
        assertEquals(8, database.productDao().getProductById(cable)!!.currentStock)
        assertEquals("PAID", purchaseRepository.getPurchaseById(purchaseId)!!.paymentStatus)
    }

    @Test
    fun supplierPayment_settlesOldestInvoiceFirst() = runBlocking {
        val s = supplier()
        val mouse = product("Mouse")
        val older = buy(s, mouse, 5, 1000.0, date = 1_000_000L)
        val newer = buy(s, mouse, 3, 1000.0, date = 2_000_000L)

        supplierPaymentRepository.recordSupplierPayment(s, 6000.0, "CASH").getOrThrow()

        val a = purchaseRepository.getPurchaseById(older)!!
        val b = purchaseRepository.getPurchaseById(newer)!!
        assertEquals("PAID", a.paymentStatus)
        assertEquals(0.0, a.dueAmount, 0.01)
        assertEquals("PARTIAL", b.paymentStatus)
        assertEquals(2000.0, b.dueAmount, 0.01)
        assertEquals(2000.0, supplierRepository.getSupplierById(s)!!.currentDue, 0.01)
    }

    @Test
    fun imeiReturnedToSupplier_canBeBoughtAgain() = runBlocking {
        val s = supplier()
        val phone = product("Nokia G21", TrackingType.IMEI)
        val purchaseId = buy(s, phone, 1, 12000.0, imeis = listOf("350000000000021"))
        val line = purchaseReturnRepository.getReturnableItemsForPurchase(purchaseId).single()
        val unit = line.serialUnits.single()
        purchaseReturnRepository.createPurchaseReturn(
            PurchaseReturnEntity(purchaseId = purchaseId, supplierId = s),
            listOf(PurchaseReturnItemDraft(line.purchaseItem.id, phone, "Nokia G21", returnQuantity = 1, unitCost = 0.0, totalAmount = 0.0,
                serialUnitId = unit.id, serialOrImei = "350000000000021"))
        ).getOrThrow()
        assertEquals(0, database.productDao().getProductById(phone)!!.currentStock)

        buy(s, phone, 1, 11500.0, imeis = listOf("350000000000021"))

        val again = database.productDao().getSerialByImei("350000000000021")!!
        assertEquals(InventoryStatus.IN_STOCK, again.status)
        assertEquals(1, database.productDao().getProductById(phone)!!.currentStock)
    }

    // ---------------------------------------------------------------- sales

    @Test
    fun salesReturn_refundsWhatTheCustomerPaid() = runBlocking {
        val s = supplier()
        val charger = product("25W charger", price = 1000.0)
        buy(s, charger, 10, 600.0, paid = 6000.0)
        // 1,000 - 100 line discount = 900; - 90 invoice discount = 810 paid
        val saleId = sell(charger, 1, 1000.0, lineDiscount = 100.0, invoiceDiscount = 90.0)
        val item = saleRepository.getSaleItems(saleId).single()
        assertEquals(810.0, salesReturnRepository.refundUnitValue(saleRepository.getSaleById(saleId)!!, item), 0.01)

        val returnId = salesReturnRepository.createSalesReturn(
            SalesReturnDraft(saleId = saleId, refundAmount = 0.0), // walk-in: always refunded in full
            listOf(SalesReturnItemDraft(item.id, charger, "25W charger", returnQuantity = 1, unitPrice = 1000.0, totalAmount = 1000.0))
        ).getOrThrow()

        val ret = salesReturnRepository.getReturnById(returnId)!!
        assertEquals(810.0, ret.totalAmount, 0.01)
        assertEquals(810.0, ret.refundAmount, 0.01)
        assertEquals(10, database.productDao().getProductById(charger)!!.currentStock)
    }

    @Test
    fun sameImei_canBeReturnedAgainAfterResale() = runBlocking {
        val s = supplier()
        val phone = product("iPhone 13", TrackingType.IMEI, price = 60000.0)
        buy(s, phone, 1, 50000.0, paid = 50000.0, imeis = listOf("350000000000031"))
        val unitId = database.productDao().getSerialByImei("350000000000031")!!.id

        val firstSale = sell(phone, 1, 60000.0, unitIds = listOf(unitId))
        val firstItem = saleRepository.getSaleItems(firstSale).single()
        salesReturnRepository.createSalesReturn(
            SalesReturnDraft(saleId = firstSale),
            listOf(SalesReturnItemDraft(firstItem.id, phone, "iPhone 13", returnQuantity = 1, unitPrice = 0.0, totalAmount = 0.0,
                serialUnitId = unitId, serialOrImei = "350000000000031"))
        ).getOrThrow()

        val secondSale = sell(phone, 1, 58000.0, unitIds = listOf(unitId))
        val secondItem = saleRepository.getSaleItems(secondSale).single()
        val second = salesReturnRepository.createSalesReturn(
            SalesReturnDraft(saleId = secondSale),
            listOf(SalesReturnItemDraft(secondItem.id, phone, "iPhone 13", returnQuantity = 1, unitPrice = 0.0, totalAmount = 0.0,
                serialUnitId = unitId, serialOrImei = "350000000000031"))
        )
        assertTrue(second.exceptionOrNull()?.message, second.isSuccess)
        assertEquals(InventoryStatus.IN_STOCK, database.productDao().getSerialById(unitId)!!.status)
    }

    @Test
    fun imeiSalesReturn_requiresPickedUnit() = runBlocking {
        val s = supplier()
        val phone = product("Vivo Y28", TrackingType.IMEI, price = 20000.0)
        buy(s, phone, 1, 17000.0, imeis = listOf("350000000000041"))
        val unitId = database.productDao().getSerialByImei("350000000000041")!!.id
        val saleId = sell(phone, 1, 20000.0, unitIds = listOf(unitId))
        val item = saleRepository.getSaleItems(saleId).single()

        val result = salesReturnRepository.createSalesReturn(
            SalesReturnDraft(saleId = saleId),
            listOf(SalesReturnItemDraft(item.id, phone, "Vivo Y28", returnQuantity = 1, unitPrice = 0.0, totalAmount = 0.0))
        )
        assertTrue(result.isFailure)
        assertEquals(0, database.productDao().getProductById(phone)!!.currentStock)
    }

    @Test
    fun customerPayment_settlesOldestInvoiceFirst() = runBlocking {
        val s = supplier()
        val cover = product("Phone cover", price = 500.0)
        buy(s, cover, 10, 200.0)
        val c = customer("Rahim", "01811000001")
        val older = sell(cover, 2, 500.0, customerId = c, paid = 0.0, date = 1_000_000L)
        val newer = sell(cover, 1, 500.0, customerId = c, paid = 0.0, date = 2_000_000L)

        customerRepository.recordCustomerPayment(c, 1200.0, "CASH").getOrThrow()

        val a = saleRepository.getSaleById(older)!!
        val b = saleRepository.getSaleById(newer)!!
        assertEquals("PAID", a.paymentStatus)
        assertEquals("PARTIAL", b.paymentStatus)
        assertEquals(300.0, b.dueAmount, 0.01)
        assertEquals(300.0, customerRepository.getCustomerById(c)!!.currentDue, 0.01)
    }

    @Test
    fun profit_excludesTaxCollected() = runBlocking {
        val s = supplier()
        val speaker = product("BT speaker", price = 1000.0)
        buy(s, speaker, 1, 600.0)
        val saleId = sell(speaker, 1, 1000.0, lineTax = 150.0)
        val sale = saleRepository.getSaleById(saleId)!!
        assertEquals(1150.0, sale.totalAmount, 0.01)
        assertEquals(400.0, sale.totalProfit, 0.01)
    }

    @Test
    fun cancelSale_refundsOnlyCounterPayment_laterPaymentsStayAsCredit() = runBlocking {
        val s = supplier()
        val ssd = product("SSD 512GB", price = 1000.0)
        buy(s, ssd, 2, 700.0)
        val c = customer("Karim", "01811000002")
        val saleId = sell(ssd, 1, 1000.0, customerId = c, paid = 200.0)
        customerRepository.recordCustomerPayment(c, 300.0, "CASH").getOrThrow()
        assertEquals(500.0, customerRepository.getCustomerById(c)!!.currentDue, 0.01)

        saleRepository.cancelSale(saleId, "Entered by mistake").getOrThrow()

        // The 300 paid later stays as the customer's credit
        assertEquals(-300.0, customerRepository.getAuthoritativeCustomerBalance(c), 0.01)
        assertEquals(2, database.productDao().getProductById(ssd)!!.currentStock)
    }

    // ---------------------------------------------------------------- security

    @Test
    fun pins_useSlowHash_andOldHashesStillVerify() {
        val salt = SecurityUtils.generateSalt()
        val hash = SecurityUtils.hashPin("4321", salt)
        assertTrue(hash.startsWith("pbkdf2$"))
        assertTrue(SecurityUtils.verifyPin("4321", salt, hash))
        assertFalse(SecurityUtils.verifyPin("1234", salt, hash))
        assertFalse(SecurityUtils.needsRehash(hash))
    }

    @Test
    fun login_locksAfterFiveWrongPins() = runBlocking {
        authRepository.completeSetup(ShopProfileEntity(shopName = "Test Shop"), "Owner", "owner", "2468").getOrThrow()
        authRepository.logout()
        repeat(5) { assertTrue(authRepository.login("owner", "0000").isFailure) }
        val locked = authRepository.login("owner", "2468")
        assertTrue(locked.isFailure)
        assertTrue(locked.exceptionOrNull()?.message?.contains("Too many") == true)
    }
}
