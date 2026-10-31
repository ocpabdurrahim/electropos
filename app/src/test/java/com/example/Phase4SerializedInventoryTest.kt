package com.example

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
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
class Phase4SerializedInventoryTest {

    private lateinit var database: AppDatabase
    private lateinit var purchaseRepository: PurchaseRepository
    private lateinit var purchaseReturnRepository: PurchaseReturnRepository
    private lateinit var productRepository: ProductRepository
    private lateinit var supplierRepository: SupplierRepository

    private var supplierId: Long = 0L

    @Before
    fun setup() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        purchaseRepository = PurchaseRepository(database)
        purchaseReturnRepository = PurchaseReturnRepository(database)
        productRepository = ProductRepository(database)
        supplierRepository = SupplierRepository(database)

        // Create standard supplier
        val supplier = SupplierEntity(
            name = "Apex Mobile & Tech Distributor",
            phone = "01711223344",
            supplierCode = "SUP-APEX-01",
            currentDue = 0.0,
            openingDue = 0.0
        )
        supplierId = database.supplierDao().insertSupplier(supplier)
    }

    @After
    fun tearDown() {
        database.close()
    }

    /**
     * TEST 1: IMEI-only phone
     * IMEI1 = 111111111111111
     * IMEI2 = 222222222222222
     *
     * Invariants:
     * - Stock increases by 1
     * - ProductSerialImeiEntity is created with serialNumber = null
     * - Status is IN_STOCK
     * - Queryable by both IMEI 1 and IMEI 2
     */
    @Test
    fun testImeiOnlyPhone_entersStockAndIsQueryable() = runBlocking {
        val product = ProductEntity(
            name = "Samsung Galaxy A15",
            sku = "SAM-A15-BLK",
            trackingType = TrackingType.IMEI,
            isImeiRequired = true,
            isSerialRequired = false,
            purchasePrice = 18000.0,
            retailPrice = 21000.0,
            currentStock = 0,
            supplierId = supplierId
        )
        val productId = database.productDao().insertProduct(product)

        val purchaseDraft = PurchaseEntity(
            supplierId = supplierId,
            purchaseNumber = "PUR-2026-IMEI01",
            totalAmount = 18000.0,
            paidAmount = 0.0
        )
        val items = listOf(
            PurchaseItemDraft(
                productId = productId,
                productName = product.name,
                quantity = 1,
                unitCost = 18000.0,
                trackingType = TrackingType.IMEI,
                serialsOrImeis = listOf(
                    SerialImeiInput(
                        serialNumber = "",
                        imei1 = "111111111111111",
                        imei2 = "222222222222222"
                    )
                )
            )
        )

        val result = purchaseRepository.createPurchase(purchaseDraft, items)
        assertTrue("Purchase should succeed", result.isSuccess)

        // Verify stock updated
        val updatedProduct = database.productDao().getProductById(productId)
        assertNotNull(updatedProduct)
        assertEquals(1, updatedProduct!!.currentStock)

        // Verify serial/IMEI record
        val serials = database.productDao().getAvailableSerialsForProductSync(productId)
        assertEquals(1, serials.size)
        val unit = serials[0]
        assertEquals("111111111111111", unit.imei1)
        assertEquals("222222222222222", unit.imei2)
        assertNull("Serial should be null for IMEI-only product", unit.serialNumber)
        assertEquals(InventoryStatus.IN_STOCK, unit.status)

        // Query by IMEI 1
        val foundByImei1 = database.productDao().getSerialByImei("111111111111111")
        assertNotNull(foundByImei1)
        assertEquals(unit.id, foundByImei1!!.id)

        // Query by IMEI 2
        val foundByImei2 = database.productDao().getSerialByImei("222222222222222")
        assertNotNull(foundByImei2)
        assertEquals(unit.id, foundByImei2!!.id)
    }

    /**
     * TEST 2: Phone with both serial and IMEI
     *
     * Invariants:
     * - Requires both Serial and IMEI
     * - Stock increases by 1
     * - ProductSerialImeiEntity contains both serial and IMEI
     * - Status is IN_STOCK
     * - Queryable by Serial and by IMEI
     */
    @Test
    fun testPhoneWithBothSerialAndImei() = runBlocking {
        val product = ProductEntity(
            name = "Apple iPhone 15 Pro",
            sku = "IPHONE-15P-256",
            trackingType = TrackingType.SERIAL_AND_IMEI,
            isSerialRequired = true,
            isImeiRequired = true,
            purchasePrice = 120000.0,
            retailPrice = 135000.0,
            currentStock = 0,
            supplierId = supplierId
        )
        val productId = database.productDao().insertProduct(product)

        val purchaseDraft = PurchaseEntity(
            supplierId = supplierId,
            purchaseNumber = "PUR-2026-BOTH01",
            totalAmount = 120000.0,
            paidAmount = 120000.0
        )
        val items = listOf(
            PurchaseItemDraft(
                productId = productId,
                productName = product.name,
                quantity = 1,
                unitCost = 120000.0,
                trackingType = TrackingType.SERIAL_AND_IMEI,
                serialsOrImeis = listOf(
                    SerialImeiInput(
                        serialNumber = "SN-IP15P-998811",
                        imei1 = "358123456789012",
                        imei2 = "358123456789013"
                    )
                )
            )
        )

        val result = purchaseRepository.createPurchase(purchaseDraft, items)
        assertTrue(result.isSuccess)

        val updatedProduct = database.productDao().getProductById(productId)
        assertEquals(1, updatedProduct!!.currentStock)

        val unit = database.productDao().getBySerialNumber("SN-IP15P-998811")
        assertNotNull(unit)
        assertEquals("358123456789012", unit!!.imei1)
        assertEquals("358123456789013", unit.imei2)
        assertEquals("SN-IP15P-998811", unit.serialNumber)
        assertEquals(InventoryStatus.IN_STOCK, unit.status)

        // Also queryable by IMEI
        val byImei = database.productDao().getSerialByImei("358123456789012")
        assertNotNull(byImei)
        assertEquals(unit.id, byImei!!.id)
    }

    /**
     * TEST 3: Duplicate IMEI
     *
     * Invariants:
     * - Second purchase with duplicate IMEI1 fails safely
     * - Everything rolls back (stock unchanged, purchase not created)
     * - Direct DAO insertion with duplicate IMEI violates unique index
     */
    @Test
    fun testDuplicateImei_failsSafelyAndRollsBack() = runBlocking {
        val product = ProductEntity(
            name = "Xiaomi Redmi Note 13",
            sku = "MI-RN13-128",
            trackingType = TrackingType.IMEI,
            isImeiRequired = true,
            purchasePrice = 16000.0,
            retailPrice = 18500.0,
            currentStock = 0,
            supplierId = supplierId
        )
        val productId = database.productDao().insertProduct(product)

        // First purchase: succeeds
        val p1 = PurchaseEntity(supplierId = supplierId, purchaseNumber = "PUR-RN13-01", totalAmount = 16000.0)
        val res1 = purchaseRepository.createPurchase(
            p1,
            listOf(
                PurchaseItemDraft(
                    productId = productId,
                    productName = product.name,
                    quantity = 1,
                    unitCost = 16000.0,
                    trackingType = TrackingType.IMEI,
                    serialsOrImeis = listOf(
                        SerialImeiInput(imei1 = "869901020304050", imei2 = "869901020304051")
                    )
                )
            )
        )
        assertTrue(res1.isSuccess)
        assertEquals(1, database.productDao().getProductById(productId)!!.currentStock)

        // Second purchase: attempts to reuse the same IMEI1
        val p2 = PurchaseEntity(supplierId = supplierId, purchaseNumber = "PUR-RN13-02", totalAmount = 16000.0)
        val res2 = purchaseRepository.createPurchase(
            p2,
            listOf(
                PurchaseItemDraft(
                    productId = productId,
                    productName = product.name,
                    quantity = 1,
                    unitCost = 16000.0,
                    trackingType = TrackingType.IMEI,
                    serialsOrImeis = listOf(
                        SerialImeiInput(imei1 = "869901020304050", imei2 = "869901020304099")
                    )
                )
            )
        )
        assertTrue("Duplicate IMEI purchase must fail", res2.isFailure)
        assertTrue(res2.exceptionOrNull()?.message?.contains("already exists", ignoreCase = true) == true)

        // Verify rollback: stock remains 1, second purchase not in database
        assertEquals(1, database.productDao().getProductById(productId)!!.currentStock)
        assertNull(database.purchaseDao().getPurchaseByPurchaseNumber("PUR-RN13-02"))

        // Direct DB insert test: violates SQLite UNIQUE index on imei1
        try {
            database.productDao().insertSerialImei(
                ProductSerialImeiEntity(
                    productId = productId,
                    imei1 = "869901020304050"
                )
            )
            fail("Direct insert with duplicate IMEI1 must throw SQLiteConstraintException")
        } catch (e: SQLiteConstraintException) {
            // Expected database constraint violation
        }
    }

    /**
     * TEST 4: Duplicate Serial Number
     *
     * Invariants:
     * - Second purchase with duplicate serial fails safely
     * - Everything rolls back
     * - Direct DAO insertion with duplicate serial violates unique index
     */
    @Test
    fun testDuplicateSerial_failsSafelyAndRollsBack() = runBlocking {
        val product = ProductEntity(
            name = "TP-Link Deco X50 Mesh Router",
            sku = "TPL-DECO-X50",
            trackingType = TrackingType.SERIAL,
            isSerialRequired = true,
            purchasePrice = 8500.0,
            retailPrice = 9999.0,
            currentStock = 0,
            supplierId = supplierId
        )
        val productId = database.productDao().insertProduct(product)

        // First purchase: succeeds
        val p1 = PurchaseEntity(supplierId = supplierId, purchaseNumber = "PUR-ROUTER-01", totalAmount = 8500.0)
        val res1 = purchaseRepository.createPurchase(
            p1,
            listOf(
                PurchaseItemDraft(
                    productId = productId,
                    productName = product.name,
                    quantity = 1,
                    unitCost = 8500.0,
                    trackingType = TrackingType.SERIAL,
                    serialsOrImeis = listOf(
                        SerialImeiInput(serialNumber = "SN-DECO-882200")
                    )
                )
            )
        )
        assertTrue(res1.isSuccess)
        assertEquals(1, database.productDao().getProductById(productId)!!.currentStock)

        // Second purchase with duplicate serial
        val p2 = PurchaseEntity(supplierId = supplierId, purchaseNumber = "PUR-ROUTER-02", totalAmount = 8500.0)
        val res2 = purchaseRepository.createPurchase(
            p2,
            listOf(
                PurchaseItemDraft(
                    productId = productId,
                    productName = product.name,
                    quantity = 1,
                    unitCost = 8500.0,
                    trackingType = TrackingType.SERIAL,
                    serialsOrImeis = listOf(
                        SerialImeiInput(serialNumber = "SN-DECO-882200")
                    )
                )
            )
        )
        assertTrue("Duplicate serial purchase must fail", res2.isFailure)
        assertEquals(1, database.productDao().getProductById(productId)!!.currentStock)
        assertNull(database.purchaseDao().getPurchaseByPurchaseNumber("PUR-ROUTER-02"))

        // Direct DB constraint test
        try {
            database.productDao().insertSerialImei(
                ProductSerialImeiEntity(
                    productId = productId,
                    serialNumber = "SN-DECO-882200"
                )
            )
            fail("Direct insert with duplicate serialNumber must throw SQLiteConstraintException")
        } catch (e: SQLiteConstraintException) {
            // Expected database constraint violation
        }
    }

    /**
     * TEST 5: Purchase Cancellation
     *
     * Invariants:
     * - Cancelling a purchase sets serialized units' status to CANCELLED
     * - Reverses product stock
     * - Unit with status CANCELLED cannot be sold later
     * - Excluded from available sellable stock
     */
    @Test
    fun testPurchaseCancellation_reversesSerializedInventory() = runBlocking {
        val product = ProductEntity(
            name = "OnePlus Nord CE 4",
            sku = "OP-NORD-CE4",
            trackingType = TrackingType.SERIAL_AND_IMEI,
            isSerialRequired = true,
            isImeiRequired = true,
            purchasePrice = 28000.0,
            retailPrice = 32000.0,
            currentStock = 0,
            supplierId = supplierId
        )
        val productId = database.productDao().insertProduct(product)

        val purchaseDraft = PurchaseEntity(
            supplierId = supplierId,
            purchaseNumber = "PUR-NORD-01",
            totalAmount = 28000.0,
            paidAmount = 10000.0
        )
        val items = listOf(
            PurchaseItemDraft(
                productId = productId,
                productName = product.name,
                quantity = 1,
                unitCost = 28000.0,
                trackingType = TrackingType.SERIAL_AND_IMEI,
                serialsOrImeis = listOf(
                    SerialImeiInput(
                        serialNumber = "SN-OP-9011",
                        imei1 = "867700112233445",
                        imei2 = "867700112233446"
                    )
                )
            )
        )

        val purchaseId = purchaseRepository.createPurchase(purchaseDraft, items).getOrThrow()
        assertEquals(1, database.productDao().getProductById(productId)!!.currentStock)

        var available = database.productDao().getAvailableSerialsForProductSync(productId)
        assertEquals(1, available.size)
        assertEquals(InventoryStatus.IN_STOCK, available[0].status)

        // Cancel the purchase
        val cancelResult = purchaseRepository.cancelPurchase(purchaseId, "Supplier delivered wrong color variant")
        assertTrue("Cancellation should succeed", cancelResult.isSuccess)

        // Stock reversed to 0
        val stockAfterCancel = database.productDao().getProductById(productId)!!.currentStock
        assertEquals(0, stockAfterCancel)

        // Serialized unit status is now CANCELLED
        val unitAfterCancel = database.productDao().getBySerialNumber("SN-OP-9011")
        assertNotNull(unitAfterCancel)
        assertEquals(InventoryStatus.CANCELLED, unitAfterCancel!!.status)
        assertFalse(InventoryStatus.isSellable(unitAfterCancel.status))

        // Not in available serials
        available = database.productDao().getAvailableSerialsForProductSync(productId)
        assertEquals(0, available.size)

        // Attempting to sell this unit must affect 0 rows
        val soldRows = database.productDao().markSerialSold(
            id = unitAfterCancel.id,
            status = InventoryStatus.SOLD,
            saleId = 999L,
            invoiceNumber = "INV-TEST-001"
        )
        assertEquals(0, soldRows)
        assertEquals(InventoryStatus.CANCELLED, database.productDao().getBySerialNumber("SN-OP-9011")!!.status)
    }

    /**
     * TEST 6: Purchase Return
     * Original purchase: 2 units (Total ৳36,000)
     * Return 1 serialized unit.
     *
     * Invariants:
     * - Stock decreases from 2 to 1
     * - Supplier payable decreases by ৳18,000
     * - Returned unit status is updated to RETURNED
     * - Returned unit cannot be sold later
     * - Other unit remains IN_STOCK and sellable
     */
    @Test
    fun testPurchaseReturn_returnsOneSerializedUnitCorrectly() = runBlocking {
        val product = ProductEntity(
            name = "Samsung Galaxy A15 5G",
            sku = "SAM-A15-5G",
            trackingType = TrackingType.IMEI,
            isImeiRequired = true,
            purchasePrice = 18000.0,
            retailPrice = 21000.0,
            currentStock = 0,
            supplierId = supplierId
        )
        val productId = database.productDao().insertProduct(product)

        val purchaseDraft = PurchaseEntity(
            supplierId = supplierId,
            purchaseNumber = "PUR-A15-RET",
            totalAmount = 36000.0,
            paidAmount = 0.0
        )
        val items = listOf(
            PurchaseItemDraft(
                productId = productId,
                productName = product.name,
                quantity = 2,
                unitCost = 18000.0,
                trackingType = TrackingType.IMEI,
                serialsOrImeis = listOf(
                    SerialImeiInput(imei1 = "111111111111111", imei2 = "222222222222222"),
                    SerialImeiInput(imei1 = "333333333333333", imei2 = "444444444444444")
                )
            )
        )

        val purchaseId = purchaseRepository.createPurchase(purchaseDraft, items).getOrThrow()
        assertEquals(2, database.productDao().getProductById(productId)!!.currentStock)
        assertEquals(36000.0, database.supplierLedgerDao().calculateAuthoritativeBalance(supplierId), 0.01)

        val purchaseItems = database.purchaseDao().getPurchaseItems(purchaseId)
        assertEquals(1, purchaseItems.size)
        val purchaseItem = purchaseItems[0]

        val unitToReturn = database.productDao().getSerialByImei("111111111111111")
        assertNotNull(unitToReturn)

        // Return first unit
        val returnDraft = PurchaseReturnEntity(
            purchaseId = purchaseId,
            supplierId = supplierId,
            returnNumber = "PRT-A15-01",
            returnDate = System.currentTimeMillis(),
            totalAmount = 18000.0,
            refundAmount = 0.0,
            reason = "Defective camera lens"
        )
        val returnItems = listOf(
            PurchaseReturnItemDraft(
                purchaseItemId = purchaseItem.id,
                productId = productId,
                productName = product.name,
                returnQuantity = 1,
                unitCost = 18000.0,
                totalAmount = 18000.0,
                serialUnitId = unitToReturn!!.id,
                serialOrImei = unitToReturn.imei1 ?: ""
            )
        )

        val returnResult = purchaseReturnRepository.createPurchaseReturn(returnDraft, returnItems)
        assertTrue(returnResult.isSuccess)

        // 1. Stock decreases to 1
        val updatedProduct = database.productDao().getProductById(productId)
        assertEquals(1, updatedProduct!!.currentStock)

        // 2. Supplier payable decreases by ৳18,000 -> balance = ৳18,000
        val finalBalance = database.supplierLedgerDao().calculateAuthoritativeBalance(supplierId)
        assertEquals(18000.0, finalBalance, 0.01)

        // 3. Returned unit status is RETURNED
        val returnedUnit = database.productDao().getSerialById(unitToReturn.id)
        assertNotNull(returnedUnit)
        assertEquals(InventoryStatus.RETURNED, returnedUnit!!.status)
        assertFalse(InventoryStatus.isSellable(returnedUnit.status))

        // 4. Returned unit cannot be sold later
        val soldRows = database.productDao().markSerialSold(
            id = returnedUnit.id,
            status = InventoryStatus.SOLD,
            saleId = 101L,
            invoiceNumber = "INV-ATTEMPT-01"
        )
        assertEquals("Returned unit must not be sellable", 0, soldRows)

        // 5. Remaining unit is still IN_STOCK and sellable
        val remainingUnit = database.productDao().getSerialByImei("333333333333333")
        assertNotNull(remainingUnit)
        assertEquals(InventoryStatus.IN_STOCK, remainingUnit!!.status)
        assertTrue(InventoryStatus.isSellable(remainingUnit.status))

        val availableSerials = database.productDao().getAvailableSerialsForProductSync(productId)
        assertEquals(1, availableSerials.size)
        assertEquals("333333333333333", availableSerials[0].imei1)
    }

    /**
     * TEST 7: IMEI 1 and IMEI 2 cannot be identical
     *
     * Invariants:
     * - Attempting to provide identical IMEI 1 and IMEI 2 throws IllegalArgumentException
     * - Caught during draft validation before any DB write
     */
    @Test
    fun testIdenticalImei1AndImei2_throwsIllegalArgumentException() = runBlocking {
        val product = ProductEntity(
            name = "Realme 12 Pro",
            sku = "RME-12P-256",
            trackingType = TrackingType.IMEI,
            isImeiRequired = true,
            purchasePrice = 25000.0,
            retailPrice = 28000.0,
            currentStock = 0,
            supplierId = supplierId
        )
        val productId = database.productDao().insertProduct(product)

        val purchaseDraft = PurchaseEntity(supplierId = supplierId, purchaseNumber = "PUR-RME-01", totalAmount = 25000.0)
        val items = listOf(
            PurchaseItemDraft(
                productId = productId,
                productName = product.name,
                quantity = 1,
                unitCost = 25000.0,
                trackingType = TrackingType.IMEI,
                serialsOrImeis = listOf(
                    SerialImeiInput(imei1 = "555555555555555", imei2 = "555555555555555")
                )
            )
        )

        val result = purchaseRepository.createPurchase(purchaseDraft, items)
        assertTrue("Identical IMEI1 and IMEI2 must fail", result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("cannot be identical", ignoreCase = true) == true)
        assertEquals(0, database.productDao().getProductById(productId)!!.currentStock)
    }

    /**
     * TEST 8: Quantity-based products
     *
     * Invariants:
     * - No serialized records required or created
     * - Stock increases and decreases based strictly on quantity
     */
    @Test
    fun testQuantityBasedProduct_operatesWithoutSerials() = runBlocking {
        val product = ProductEntity(
            name = "Type-C Fast Charging Cable 1m",
            sku = "CABLE-TC-1M",
            trackingType = TrackingType.QUANTITY,
            isSerialRequired = false,
            isImeiRequired = false,
            purchasePrice = 120.0,
            retailPrice = 250.0,
            currentStock = 10,
            supplierId = supplierId
        )
        val productId = database.productDao().insertProduct(product)

        val purchaseDraft = PurchaseEntity(
            supplierId = supplierId,
            purchaseNumber = "PUR-CABLE-01",
            totalAmount = 6000.0,
            paidAmount = 6000.0
        )
        val items = listOf(
            PurchaseItemDraft(
                productId = productId,
                productName = product.name,
                quantity = 50,
                unitCost = 120.0,
                trackingType = TrackingType.QUANTITY,
                serialsOrImeis = emptyList()
            )
        )

        val result = purchaseRepository.createPurchase(purchaseDraft, items)
        assertTrue(result.isSuccess)

        val updatedProduct = database.productDao().getProductById(productId)
        assertEquals(60, updatedProduct!!.currentStock)

        val serials = database.productDao().getAvailableSerialsForProductSync(productId)
        assertTrue("No serial entries for quantity products", serials.isEmpty())
    }
}
