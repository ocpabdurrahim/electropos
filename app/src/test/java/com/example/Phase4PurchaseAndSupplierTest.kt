package com.example

import android.content.Context
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
class Phase4PurchaseAndSupplierTest {

    private lateinit var database: AppDatabase
    private lateinit var supplierRepository: SupplierRepository
    private lateinit var purchaseRepository: PurchaseRepository
    private lateinit var purchaseReturnRepository: PurchaseReturnRepository
    private lateinit var supplierPaymentRepository: SupplierPaymentRepository

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
    }

    @After
    fun tearDown() {
        database.close()
    }

    /**
     * Scenario 73: Critical Test Scenario
     * PRODUCT: Samsung Galaxy A15, Tracking: IMEI, Stock: 0
     * PURCHASE: Supplier: ABC Mobile Distributor, Qty: 2, Unit Cost: ৳18,000,
     *           IMEI 1: 111111111111111, IMEI 2: 222222222222222
     * After completion:
     * - Product stock = 2
     * - IMEI 1 -> IN_STOCK, IMEI 2 -> IN_STOCK
     * - Supplier payable: ৳36,000
     * Then payment: ৳20,000 -> Supplier due: ৳16,000
     * Then return: IMEI 1
     * After return: Product stock = 1, IMEI 1 -> RETURNED_TO_SUPPLIER, Supplier due reduces appropriately.
     */
    @Test
    fun testScenario73_SamsungGalaxyA15ImeiPurchasePaymentAndReturn() = runBlocking {
        // 1. Create Supplier
        val supplierId = supplierRepository.saveSupplier(
            SupplierEntity(
                name = "ABC Mobile Distributor",
                company = "ABC Telecom Ltd.",
                phone = "01711999999",
                openingDue = 0.0
            )
        ).getOrThrow()

        // 2. Create Product (Samsung Galaxy A15 with IMEI tracking, initial stock = 0)
        val productId = database.productDao().insertProduct(
            ProductEntity(
                name = "Samsung Galaxy A15",
                sku = "SAM-A15-BLK",
                trackingType = "IMEI",
                purchasePrice = 18000.0,
                retailPrice = 21000.0,
                currentStock = 0,
                supplierId = supplierId,
                supplierName = "ABC Mobile Distributor"
            )
        )

        // 3. Complete Purchase of 2 units
        val purchaseDraft = PurchaseEntity(
            supplierId = supplierId,
            paidAmount = 0.0 // Full credit purchase initially
        )
        val purchaseItems = listOf(
            PurchaseItemDraft(
                productId = productId,
                productName = "Samsung Galaxy A15",
                quantity = 2,
                unitCost = 18000.0,
                trackingType = "IMEI",
                serialsOrImeis = listOf(
                    SerialImeiInput(imei1 = "111111111111111"),
                    SerialImeiInput(imei1 = "222222222222222")
                )
            )
        )
        val purchaseId = purchaseRepository.createPurchase(purchaseDraft, purchaseItems).getOrThrow()
        val purchase = purchaseRepository.getPurchaseById(purchaseId)!!

        // Assert Purchase Result
        val productAfterPurchase = database.productDao().getProductById(productId)!!
        assertEquals(2, productAfterPurchase.currentStock)

        val imei1Unit = database.productDao().getSerialByImei("111111111111111")!!
        val imei2Unit = database.productDao().getSerialByImei("222222222222222")!!
        assertEquals("IN_STOCK", imei1Unit.status)
        assertEquals("IN_STOCK", imei2Unit.status)

        val supplierAfterPurchase = supplierRepository.getSupplierById(supplierId)!!
        assertEquals(36000.0, supplierAfterPurchase.currentDue, 0.01)

        // 4. Record Payment: ৳20,000
        supplierPaymentRepository.recordSupplierPayment(
            supplierId = supplierId,
            amount = 20000.0,
            paymentMethod = "CASH",
            purchaseId = purchaseId
        ).getOrThrow()

        val supplierAfterPayment = supplierRepository.getSupplierById(supplierId)!!
        assertEquals(16000.0, supplierAfterPayment.currentDue, 0.01)

        // 5. Return IMEI 1
        val purchaseItemEntity = purchaseRepository.getPurchaseItems(purchaseId).first()
        val returnDraft = PurchaseReturnEntity(
            purchaseId = purchaseId,
            supplierId = supplierId,
            reason = "Customer return - defective display"
        )
        val returnItems = listOf(
            PurchaseReturnItemDraft(
                purchaseItemId = purchaseItemEntity.id,
                productId = productId,
                productName = "Samsung Galaxy A15",
                returnQuantity = 1,
                unitCost = 18000.0,
                totalAmount = 18000.0,
                serialUnitId = imei1Unit.id,
                serialOrImei = "111111111111111"
            )
        )
        purchaseReturnRepository.createPurchaseReturn(returnDraft, returnItems).getOrThrow()

        // 6. Assertions after return
        val productAfterReturn = database.productDao().getProductById(productId)!!
        assertEquals(1, productAfterReturn.currentStock)

        val imei1AfterReturn = database.productDao().getSerialByImei("111111111111111")!!
        assertEquals(InventoryStatus.RETURNED, imei1AfterReturn.status)

        val imei2AfterReturn = database.productDao().getSerialByImei("222222222222222")!!
        assertEquals("IN_STOCK", imei2AfterReturn.status)

        // Supplier due reduced by ৳18,000 (16,000 - 18,000 = -2,000 or credit)
        val supplierAfterReturn = supplierRepository.getSupplierById(supplierId)!!
        assertEquals(-2000.0, supplierAfterReturn.currentDue, 0.01)
    }

    /**
     * Scenario 74: Second Test Scenario
     * PRODUCT: USB-C Cable, Tracking: QUANTITY, Existing stock: 50
     * Purchase: 100 units, Cost: ৳100
     * After purchase: Stock = 150, Purchase total = ৳10,000
     * Then return: 20 units -> Stock = 130, Supplier payable reduced by ৳2,000
     */
    @Test
    fun testScenario74_UsbCableQuantityPurchaseAndReturn() = runBlocking {
        val supplierId = supplierRepository.saveSupplier(
            SupplierEntity(
                name = "Star Tech Wholesale",
                phone = "01811223344",
                openingDue = 0.0
            )
        ).getOrThrow()

        val productId = database.productDao().insertProduct(
            ProductEntity(
                name = "USB-C Cable",
                trackingType = "QUANTITY",
                purchasePrice = 100.0,
                currentStock = 50
            )
        )

        // Purchase 100 units @ ৳100
        val purchaseDraft = PurchaseEntity(
            supplierId = supplierId,
            paidAmount = 0.0
        )
        val purchaseItems = listOf(
            PurchaseItemDraft(
                productId = productId,
                productName = "USB-C Cable",
                quantity = 100,
                unitCost = 100.0,
                trackingType = "QUANTITY"
            )
        )
        val purchaseId = purchaseRepository.createPurchase(purchaseDraft, purchaseItems).getOrThrow()

        val prodAfterPurchase = database.productDao().getProductById(productId)!!
        assertEquals(150, prodAfterPurchase.currentStock)

        val purchase = purchaseRepository.getPurchaseById(purchaseId)!!
        assertEquals(10000.0, purchase.totalAmount, 0.01)

        val supplierAfterPurchase = supplierRepository.getSupplierById(supplierId)!!
        assertEquals(10000.0, supplierAfterPurchase.currentDue, 0.01)

        // Return 20 units
        val purchaseItem = purchaseRepository.getPurchaseItems(purchaseId).first()
        val returnDraft = PurchaseReturnEntity(
            purchaseId = purchaseId,
            supplierId = supplierId,
            reason = "Excess shipment"
        )
        val returnItems = listOf(
            PurchaseReturnItemDraft(
                purchaseItemId = purchaseItem.id,
                productId = productId,
                productName = "USB-C Cable",
                returnQuantity = 20,
                unitCost = 100.0,
                totalAmount = 2000.0
            )
        )
        purchaseReturnRepository.createPurchaseReturn(returnDraft, returnItems).getOrThrow()

        val prodAfterReturn = database.productDao().getProductById(productId)!!
        assertEquals(130, prodAfterReturn.currentStock)

        val supplierAfterReturn = supplierRepository.getSupplierById(supplierId)!!
        assertEquals(8000.0, supplierAfterReturn.currentDue, 0.01)
    }

    /**
     * Scenario 75: Third Test — Weighted Average Costing
     * Existing: Stock = 10, Average Cost = ৳100
     * Purchase: 20 units, Cost = ৳130
     * Expected: New Stock = 30, New Average Cost: (10×100 + 20×130) / 30 = ৳120
     */
    @Test
    fun testScenario75_WeightedAverageCostCalculation() = runBlocking {
        val supplierId = supplierRepository.saveSupplier(
            SupplierEntity(
                name = "Anker Bangladesh",
                phone = "01999887766"
            )
        ).getOrThrow()

        val productId = database.productDao().insertProduct(
            ProductEntity(
                name = "Anker PowerPort III",
                trackingType = "QUANTITY",
                purchasePrice = 100.0,
                currentStock = 10
            )
        )

        // Purchase 20 units @ ৳130
        val purchaseDraft = PurchaseEntity(
            supplierId = supplierId,
            paidAmount = 2600.0
        )
        val purchaseItems = listOf(
            PurchaseItemDraft(
                productId = productId,
                productName = "Anker PowerPort III",
                quantity = 20,
                unitCost = 130.0,
                trackingType = "QUANTITY"
            )
        )
        purchaseRepository.createPurchase(purchaseDraft, purchaseItems).getOrThrow()

        val productAfterPurchase = database.productDao().getProductById(productId)!!
        assertEquals(30, productAfterPurchase.currentStock)
        // (10 * 100 + 20 * 130) / 30 = (1000 + 2600) / 30 = 3600 / 30 = 120.0
        assertEquals(120.0, productAfterPurchase.purchasePrice, 0.01)
    }

    /**
     * Scenario 76: Fourth Test — Partial Payment
     * Purchase: Grand Total = ৳50,000, Paid: ৳20,000
     * Expected: Payment Status = PARTIAL, Supplier Due = ৳30,000
     * Then payment: ৳30,000
     * Expected: Supplier Due = ৳0, Payment Status = PAID
     */
    @Test
    fun testScenario76_PartialPaymentTransitionToPaid() = runBlocking {
        val supplierId = supplierRepository.saveSupplier(
            SupplierEntity(
                name = "Xiaomi Global Distribution",
                phone = "01788776655",
                openingDue = 0.0
            )
        ).getOrThrow()

        val productId = database.productDao().insertProduct(
            ProductEntity(
                name = "Redmi Note 13",
                trackingType = "QUANTITY",
                purchasePrice = 25000.0,
                currentStock = 0
            )
        )

        val purchaseDraft = PurchaseEntity(
            supplierId = supplierId,
            paidAmount = 20000.0, // Partial payment
            paymentMethod = "BKASH"
        )
        val purchaseItems = listOf(
            PurchaseItemDraft(
                productId = productId,
                productName = "Redmi Note 13",
                quantity = 2,
                unitCost = 25000.0
            )
        )
        val purchaseId = purchaseRepository.createPurchase(purchaseDraft, purchaseItems).getOrThrow()

        val purchase1 = purchaseRepository.getPurchaseById(purchaseId)!!
        assertEquals(50000.0, purchase1.totalAmount, 0.01)
        assertEquals(20000.0, purchase1.paidAmount, 0.01)
        assertEquals(30000.0, purchase1.dueAmount, 0.01)
        assertEquals("PARTIAL", purchase1.paymentStatus)

        val supplier1 = supplierRepository.getSupplierById(supplierId)!!
        assertEquals(30000.0, supplier1.currentDue, 0.01)

        // Pay remaining ৳30,000
        supplierPaymentRepository.recordSupplierPayment(
            supplierId = supplierId,
            amount = 30000.0,
            paymentMethod = "BANK",
            purchaseId = purchaseId
        ).getOrThrow()

        val supplier2 = supplierRepository.getSupplierById(supplierId)!!
        assertEquals(0.0, supplier2.currentDue, 0.01)

        val purchase2 = purchaseRepository.getPurchaseById(purchaseId)!!
        assertEquals(0.0, purchase2.dueAmount, 0.01)
        assertEquals("PAID", purchase2.paymentStatus)
    }

    /**
     * Scenario 77: Purchase Return Test
     * Purchase: 100 cables × ৳100
     * Return: 25 cables
     * Expected: Stock decreases by 25, Supplier payable decreases by ৳2,500,
     * original purchase remains unchanged, return transaction appears separately.
     */
    @Test
    fun testScenario77_PurchaseReturnIntegrity() = runBlocking {
        val supplierId = supplierRepository.saveSupplier(
            SupplierEntity(name = "Cable Mart", phone = "01511223344")
        ).getOrThrow()

        val productId = database.productDao().insertProduct(
            ProductEntity(name = "Cat6 Network Cable", trackingType = "QUANTITY", currentStock = 0, purchasePrice = 100.0)
        )

        val purchaseId = purchaseRepository.createPurchase(
            PurchaseEntity(supplierId = supplierId, paidAmount = 0.0),
            listOf(PurchaseItemDraft(productId = productId, productName = "Cat6 Network Cable", quantity = 100, unitCost = 100.0))
        ).getOrThrow()

        val originalPurchase = purchaseRepository.getPurchaseById(purchaseId)!!
        assertEquals(10000.0, originalPurchase.totalAmount, 0.01)

        val item = purchaseRepository.getPurchaseItems(purchaseId).first()

        // Return 25 cables
        val returnId = purchaseReturnRepository.createPurchaseReturn(
            PurchaseReturnEntity(purchaseId = purchaseId, supplierId = supplierId),
            listOf(
                PurchaseReturnItemDraft(
                    purchaseItemId = item.id,
                    productId = productId,
                    productName = "Cat6 Network Cable",
                    returnQuantity = 25,
                    unitCost = 100.0,
                    totalAmount = 2500.0
                )
            )
        ).getOrThrow()

        // Stock must be 75
        val product = database.productDao().getProductById(productId)!!
        assertEquals(75, product.currentStock)

        // Supplier due must be 7500
        val supplier = supplierRepository.getSupplierById(supplierId)!!
        assertEquals(7500.0, supplier.currentDue, 0.01)

        // Original purchase total must remain unchanged (10000.0)
        val originalPurchaseAfterReturn = purchaseRepository.getPurchaseById(purchaseId)!!
        assertEquals(10000.0, originalPurchaseAfterReturn.totalAmount, 0.01)

        // Return record appears separately
        val returnRecord = purchaseReturnRepository.getReturnById(returnId)!!
        assertEquals(2500.0, returnRecord.totalAmount, 0.01)
    }

    /**
     * Landed Cost Proportional Allocation Test
     */
    @Test
    fun testLandedCostProportionalAllocation() = runBlocking {
        val supplierId = supplierRepository.saveSupplier(
            SupplierEntity(name = "Direct Importers", phone = "01700112233")
        ).getOrThrow()

        val p1 = database.productDao().insertProduct(
            ProductEntity(name = "Router AC1200", currentStock = 0, purchasePrice = 0.0)
        )
        val p2 = database.productDao().insertProduct(
            ProductEntity(name = "Gigabit Switch", currentStock = 0, purchasePrice = 0.0)
        )

        // Line 1: 10 routers @ ৳1000 = ৳10,000
        // Line 2: 10 switches @ ৳2000 = ৳20,000
        // Subtotal = ৳30,000
        // Transport = ৳3,000. Landed cost split:
        // Line 1 share: 10,000 / 30,000 = 1/3 -> ৳1,000 additional cost -> (10,000 + 1,000)/10 = ৳1,100 landed unit cost
        // Line 2 share: 20,000 / 30,000 = 2/3 -> ৳2,000 additional cost -> (20,000 + 2,000)/10 = ৳2,200 landed unit cost
        purchaseRepository.createPurchase(
            PurchaseEntity(supplierId = supplierId, transportCost = 3000.0, paidAmount = 33000.0),
            listOf(
                PurchaseItemDraft(productId = p1, productName = "Router AC1200", quantity = 10, unitCost = 1000.0),
                PurchaseItemDraft(productId = p2, productName = "Gigabit Switch", quantity = 10, unitCost = 2000.0)
            )
        ).getOrThrow()

        val r1 = database.productDao().getProductById(p1)!!
        val r2 = database.productDao().getProductById(p2)!!

        assertEquals(1100.0, r1.purchasePrice, 0.01)
        assertEquals(2200.0, r2.purchasePrice, 0.01)
    }

    /**
     * Audit Test 1: Authoritative Supplier Balance Formula Verification
     * Formula:
     * Supplier Balance = Opening Balance + Purchases - Purchase Returns - Supplier Payments - Supplier Refunds + Debit Adjustments - Credit Adjustments
     * Example from prompt:
     * Opening balance = 10,000
     * Purchase = 30,000
     * Purchase return = 5,000
     * Supplier payment = 15,000
     * Final supplier payable must be:
     * 10,000 + 30,000 - 5,000 - 15,000 = 20,000
     */
    @Test
    fun testSupplierBalanceAuditConventionAndFormula() = runBlocking {
        // 1. Opening balance: 10,000
        val supplierId = supplierRepository.saveSupplier(
            SupplierEntity(
                name = "Apex Electronics Importers",
                phone = "01999887766",
                openingDue = 10000.0
            )
        ).getOrThrow()

        val product1 = database.productDao().insertProduct(
            ProductEntity(name = "Smart Watch X1", purchasePrice = 1000.0, currentStock = 0)
        )

        // Verify initial state
        var supplier = supplierRepository.getSupplierById(supplierId)!!
        assertEquals(10000.0, supplier.currentDue, 0.01)

        // 2. Purchase: 30,000 (30 units @ ৳1000) with paidAmount = 0
        val purchaseDraft = PurchaseEntity(
            supplierId = supplierId,
            paidAmount = 0.0
        )
        val purchaseItems = listOf(
            PurchaseItemDraft(
                productId = product1,
                productName = "Smart Watch X1",
                quantity = 30,
                unitCost = 1000.0
            )
        )
        val purchaseId = purchaseRepository.createPurchase(purchaseDraft, purchaseItems).getOrThrow()

        supplier = supplierRepository.getSupplierById(supplierId)!!
        assertEquals(40000.0, supplier.currentDue, 0.01) // 10,000 + 30,000 = 40,000

        // 3. Purchase return: 5,000 (5 units @ ৳1000)
        val purchaseItemEntity = purchaseRepository.getPurchaseItems(purchaseId).first()
        val returnDraft = PurchaseReturnEntity(
            purchaseId = purchaseId,
            supplierId = supplierId,
            reason = "Excess ordered units"
        )
        val returnItems = listOf(
            PurchaseReturnItemDraft(
                purchaseItemId = purchaseItemEntity.id,
                productId = product1,
                productName = "Smart Watch X1",
                returnQuantity = 5,
                unitCost = 1000.0,
                totalAmount = 5000.0
            )
        )
        purchaseReturnRepository.createPurchaseReturn(returnDraft, returnItems).getOrThrow()

        supplier = supplierRepository.getSupplierById(supplierId)!!
        assertEquals(35000.0, supplier.currentDue, 0.01) // 40,000 - 5,000 = 35,000

        // 4. Supplier payment: 15,000
        supplierPaymentRepository.recordSupplierPayment(
            supplierId = supplierId,
            amount = 15000.0,
            paymentMethod = "BANK",
            purchaseId = purchaseId,
            notes = "Partial payment via Bank transfer"
        ).getOrThrow()

        // 5. Final supplier payable must be: 10,000 + 30,000 - 5,000 - 15,000 = 20,000
        supplier = supplierRepository.getSupplierById(supplierId)!!
        assertEquals(20000.0, supplier.currentDue, 0.01)

        // Authoritative Ledger verification
        val ledger = supplierRepository.getSupplierLedger(supplierId)
        assertEquals(4, ledger.size)

        // Check required transaction signs
        val openingEntry = ledger[0]
        assertEquals("OPENING_BALANCE", openingEntry.transactionType)
        assertEquals(10000.0, openingEntry.signedAmount, 0.01)
        assertEquals(10000.0, openingEntry.runningBalance, 0.01)

        val purchaseEntry = ledger[1]
        assertEquals("PURCHASE", purchaseEntry.transactionType)
        assertEquals(30000.0, purchaseEntry.signedAmount, 0.01)
        assertEquals(40000.0, purchaseEntry.runningBalance, 0.01)

        val returnEntry = ledger[2]
        assertEquals("PURCHASE_RETURN", returnEntry.transactionType)
        assertEquals(-5000.0, returnEntry.signedAmount, 0.01)
        assertEquals(35000.0, returnEntry.runningBalance, 0.01)

        val paymentEntry = ledger[3]
        assertEquals("SUPPLIER_PAYMENT", paymentEntry.transactionType)
        assertEquals(-15000.0, paymentEntry.signedAmount, 0.01)
        assertEquals(20000.0, paymentEntry.runningBalance, 0.01)

        // Direct SQL aggregation check
        val authoritativeBalance = database.supplierLedgerDao().calculateAuthoritativeBalance(supplierId)
        assertEquals(20000.0, authoritativeBalance, 0.01)

        // Financial summary check
        val summary = supplierRepository.getSupplierFinancialSummary(supplierId)
        assertEquals(10000.0, summary.openingBalance, 0.01)
        assertEquals(30000.0, summary.totalPurchases, 0.01)
        assertEquals(5000.0, summary.totalReturns, 0.01)
        assertEquals(15000.0, summary.totalPaid, 0.01)
        assertEquals(20000.0, summary.currentDue, 0.01)
    }

    /**
     * Audit Test 2: Purchase-Level Due vs Supplier-Level Balance Verification
     * Purchase 1: Grand Total = 30,000, Paid = 20,000, Due = 10,000
     * Purchase 2: Grand Total = 50,000, Paid = 0, Due = 50,000
     * Supplier total payable must be 60,000.
     */
    @Test
    fun testPurchaseLevelDueVsSupplierLevelBalance() = runBlocking {
        val supplierId = supplierRepository.saveSupplier(
            SupplierEntity(
                name = "Global Phone Wholesaler",
                phone = "01555667788",
                openingDue = 0.0
            )
        ).getOrThrow()

        val productA = database.productDao().insertProduct(
            ProductEntity(name = "Smartphone Model X", purchasePrice = 10000.0, currentStock = 0)
        )
        val productB = database.productDao().insertProduct(
            ProductEntity(name = "Tablet Pro", purchasePrice = 25000.0, currentStock = 0)
        )

        // Purchase 1: Grand Total = 30,000, Paid = 20,000, Due = 10,000
        val p1Draft = PurchaseEntity(
            supplierId = supplierId,
            paidAmount = 20000.0,
            paymentMethod = "CASH"
        )
        val p1Items = listOf(
            PurchaseItemDraft(
                productId = productA,
                productName = "Smartphone Model X",
                quantity = 3,
                unitCost = 10000.0 // 3 * 10,000 = 30,000
            )
        )
        val p1Id = purchaseRepository.createPurchase(p1Draft, p1Items).getOrThrow()
        val purchase1 = purchaseRepository.getPurchaseById(p1Id)!!
        assertEquals(30000.0, purchase1.totalAmount, 0.01)
        assertEquals(20000.0, purchase1.paidAmount, 0.01)
        assertEquals(10000.0, purchase1.dueAmount, 0.01)
        assertEquals("PARTIAL", purchase1.paymentStatus)

        // At this point, supplier-level balance is 10,000
        var supplier = supplierRepository.getSupplierById(supplierId)!!
        assertEquals(10000.0, supplier.currentDue, 0.01)

        // Purchase 2: Grand Total = 50,000, Paid = 0, Due = 50,000
        val p2Draft = PurchaseEntity(
            supplierId = supplierId,
            paidAmount = 0.0
        )
        val p2Items = listOf(
            PurchaseItemDraft(
                productId = productB,
                productName = "Tablet Pro",
                quantity = 2,
                unitCost = 25000.0 // 2 * 25,000 = 50,000
            )
        )
        val p2Id = purchaseRepository.createPurchase(p2Draft, p2Items).getOrThrow()
        val purchase2 = purchaseRepository.getPurchaseById(p2Id)!!
        assertEquals(50000.0, purchase2.totalAmount, 0.01)
        assertEquals(0.0, purchase2.paidAmount, 0.01)
        assertEquals(50000.0, purchase2.dueAmount, 0.01)
        assertEquals("DUE", purchase2.paymentStatus)

        // Verify: Supplier total payable must be 60,000 (10,000 + 50,000)
        supplier = supplierRepository.getSupplierById(supplierId)!!
        assertEquals(60000.0, supplier.currentDue, 0.01)

        val authoritativeBalance = database.supplierLedgerDao().calculateAuthoritativeBalance(supplierId)
        assertEquals(60000.0, authoritativeBalance, 0.01)

        // Now pay remaining 10,000 specifically for Purchase 1
        supplierPaymentRepository.recordSupplierPayment(
            supplierId = supplierId,
            amount = 10000.0,
            paymentMethod = "BKASH",
            purchaseId = p1Id
        ).getOrThrow()

        // Purchase 1 must now be PAID with due 0
        val purchase1AfterPay = purchaseRepository.getPurchaseById(p1Id)!!
        assertEquals(30000.0, purchase1AfterPay.paidAmount, 0.01)
        assertEquals(0.0, purchase1AfterPay.dueAmount, 0.01)
        assertEquals("PAID", purchase1AfterPay.paymentStatus)

        // Purchase 2 remains DUE with 50,000
        val purchase2AfterPay = purchaseRepository.getPurchaseById(p2Id)!!
        assertEquals(50000.0, purchase2AfterPay.dueAmount, 0.01)

        // Supplier total payable must now be 50,000
        supplier = supplierRepository.getSupplierById(supplierId)!!
        assertEquals(50000.0, supplier.currentDue, 0.01)
    }

    /**
     * Audit Test 3: Debit and Credit Adjustments and Supplier Refunds
     * Required signs:
     * DEBIT_ADJUSTMENT  = positive (+)
     * CREDIT_ADJUSTMENT = negative (-)
     * SUPPLIER_REFUND   = positive (+): cash the supplier hands back uses up credit they owed the shop
     */
    @Test
    fun testDebitCreditAdjustmentsAndSupplierRefund() = runBlocking {
        val supplierId = supplierRepository.saveSupplier(
            SupplierEntity(
                name = "Adjustment Test Vendor",
                phone = "01333445566",
                openingDue = 5000.0
            )
        ).getOrThrow()

        // Balance = 5,000
        var bal = supplierRepository.getSupplierById(supplierId)!!.currentDue
        assertEquals(5000.0, bal, 0.01)

        // 1. Debit adjustment (+2,000) -> increases payable
        supplierRepository.recordAdjustment(
            supplierId = supplierId,
            amount = 2000.0,
            isDebit = true,
            reason = "Late fee charges / Price correction"
        ).getOrThrow()

        bal = supplierRepository.getSupplierById(supplierId)!!.currentDue
        assertEquals(7000.0, bal, 0.01) // 5,000 + 2,000 = 7,000

        // 2. Credit adjustment (-1,500) -> decreases payable
        supplierRepository.recordAdjustment(
            supplierId = supplierId,
            amount = 1500.0,
            isDebit = false,
            reason = "Vendor annual rebate discount"
        ).getOrThrow()

        bal = supplierRepository.getSupplierById(supplierId)!!.currentDue
        assertEquals(5500.0, bal, 0.01) // 7,000 - 1,500 = 5,500

        // 3. Supplier Refund (+500) -> vendor hands cash back to the shop
        supplierRepository.recordSupplierRefund(
            supplierId = supplierId,
            amount = 500.0,
            referenceNumber = "REF-001",
            reason = "Overpayment reimbursement"
        ).getOrThrow()

        bal = supplierRepository.getSupplierById(supplierId)!!.currentDue
        assertEquals(6000.0, bal, 0.01) // 5,500 + 500 = 6,000

        val authoritative = database.supplierLedgerDao().calculateAuthoritativeBalance(supplierId)
        assertEquals(6000.0, authoritative, 0.01)
    }

    /**
     * TEST 1 — Credit Purchase
     * Product: Samsung Galaxy A15
     * Quantity: 2
     * Unit cost: 18,000
     * Grand total = 36,000
     * Paid = 0
     * Supplier payable increase = 36,000
     * Stock increase = 2
     */
    @Test
    fun testFinancialAudit_Test1_CreditPurchase() = runBlocking {
        val supplierId = supplierRepository.saveSupplier(
            SupplierEntity(
                name = "Apex Mobile Supplier",
                phone = "01700112233",
                openingDue = 0.0
            )
        ).getOrThrow()

        val productId = database.productDao().insertProduct(
            ProductEntity(
                name = "Samsung Galaxy A15",
                sku = "SAM-A15-CREDIT",
                trackingType = "IMEI",
                purchasePrice = 18000.0,
                retailPrice = 21000.0,
                currentStock = 0,
                supplierId = supplierId
            )
        )

        val purchaseDraft = PurchaseEntity(
            supplierId = supplierId,
            paidAmount = 0.0 // Credit purchase
        )
        val items = listOf(
            PurchaseItemDraft(
                productId = productId,
                productName = "Samsung Galaxy A15",
                quantity = 2,
                unitCost = 18000.0,
                trackingType = "IMEI",
                serialsOrImeis = listOf(
                    SerialImeiInput(imei1 = "354111111111111"),
                    SerialImeiInput(imei1 = "354222222222222")
                )
            )
        )

        val purchaseId = purchaseRepository.createPurchase(purchaseDraft, items).getOrThrow()
        val purchase = purchaseRepository.getPurchaseById(purchaseId)!!

        assertEquals(36000.0, purchase.totalAmount, 0.01)
        assertEquals(0.0, purchase.paidAmount, 0.01)
        assertEquals(36000.0, purchase.dueAmount, 0.01)
        assertEquals("DUE", purchase.paymentStatus)

        // Stock increase = 2
        val product = database.productDao().getProductById(productId)!!
        assertEquals(2, product.currentStock)

        // Supplier payable increase = 36,000
        val supplier = supplierRepository.getSupplierById(supplierId)!!
        assertEquals(36000.0, supplier.currentDue, 0.01)
        assertEquals(36000.0, database.supplierLedgerDao().calculateAuthoritativeBalance(supplierId), 0.01)
    }

    /**
     * TEST 2 — Partial Payment
     * Purchase total = 36,000
     * Paid = 20,000
     * Supplier payable increase = 16,000
     */
    @Test
    fun testFinancialAudit_Test2_PartialPayment() = runBlocking {
        val supplierId = supplierRepository.saveSupplier(
            SupplierEntity(
                name = "Apex Mobile Supplier 2",
                phone = "01700112234",
                openingDue = 0.0
            )
        ).getOrThrow()

        val productId = database.productDao().insertProduct(
            ProductEntity(
                name = "Samsung Galaxy A15",
                sku = "SAM-A15-PARTIAL",
                trackingType = "IMEI",
                purchasePrice = 18000.0,
                retailPrice = 21000.0,
                currentStock = 0,
                supplierId = supplierId
            )
        )

        val purchaseDraft = PurchaseEntity(
            supplierId = supplierId,
            paidAmount = 20000.0, // Partial payment
            paymentMethod = "CASH"
        )
        val items = listOf(
            PurchaseItemDraft(
                productId = productId,
                productName = "Samsung Galaxy A15",
                quantity = 2,
                unitCost = 18000.0,
                trackingType = "IMEI",
                serialsOrImeis = listOf(
                    SerialImeiInput(imei1 = "354333333333333"),
                    SerialImeiInput(imei1 = "354444444444444")
                )
            )
        )

        val purchaseId = purchaseRepository.createPurchase(purchaseDraft, items).getOrThrow()
        val purchase = purchaseRepository.getPurchaseById(purchaseId)!!

        assertEquals(36000.0, purchase.totalAmount, 0.01)
        assertEquals(20000.0, purchase.paidAmount, 0.01)
        assertEquals(16000.0, purchase.dueAmount, 0.01)
        assertEquals("PARTIAL", purchase.paymentStatus)

        // Supplier payable increase = 16,000
        val supplier = supplierRepository.getSupplierById(supplierId)!!
        assertEquals(16000.0, supplier.currentDue, 0.01)
        assertEquals(16000.0, database.supplierLedgerDao().calculateAuthoritativeBalance(supplierId), 0.01)
    }

    /**
     * TEST 3 — Full Payment
     * Purchase total = 36,000
     * Paid = 36,000
     * Supplier payable increase = 0
     */
    @Test
    fun testFinancialAudit_Test3_FullPayment() = runBlocking {
        val supplierId = supplierRepository.saveSupplier(
            SupplierEntity(
                name = "Apex Mobile Supplier 3",
                phone = "01700112235",
                openingDue = 0.0
            )
        ).getOrThrow()

        val productId = database.productDao().insertProduct(
            ProductEntity(
                name = "Samsung Galaxy A15",
                sku = "SAM-A15-PAID",
                trackingType = "IMEI",
                purchasePrice = 18000.0,
                retailPrice = 21000.0,
                currentStock = 0,
                supplierId = supplierId
            )
        )

        val purchaseDraft = PurchaseEntity(
            supplierId = supplierId,
            paidAmount = 36000.0, // Full payment
            paymentMethod = "BANK"
        )
        val items = listOf(
            PurchaseItemDraft(
                productId = productId,
                productName = "Samsung Galaxy A15",
                quantity = 2,
                unitCost = 18000.0,
                trackingType = "IMEI",
                serialsOrImeis = listOf(
                    SerialImeiInput(imei1 = "354555555555555"),
                    SerialImeiInput(imei1 = "354666666666666")
                )
            )
        )

        val purchaseId = purchaseRepository.createPurchase(purchaseDraft, items).getOrThrow()
        val purchase = purchaseRepository.getPurchaseById(purchaseId)!!

        assertEquals(36000.0, purchase.totalAmount, 0.01)
        assertEquals(36000.0, purchase.paidAmount, 0.01)
        assertEquals(0.0, purchase.dueAmount, 0.01)
        assertEquals("PAID", purchase.paymentStatus)

        // Supplier payable increase = 0
        val supplier = supplierRepository.getSupplierById(supplierId)!!
        assertEquals(0.0, supplier.currentDue, 0.01)
        assertEquals(0.0, database.supplierLedgerDao().calculateAuthoritativeBalance(supplierId), 0.01)
    }

    /**
     * TEST 4 — Purchase Return
     * Original purchase: Quantity = 2, Total = 36,000
     * Return one serialized unit.
     * Expected:
     * Stock = 1
     * Supplier payable decreases by 18,000
     * Returned serialized unit cannot be sold again.
     */
    @Test
    fun testFinancialAudit_Test4_PurchaseReturnSerialized() = runBlocking {
        val supplierId = supplierRepository.saveSupplier(
            SupplierEntity(
                name = "Apex Mobile Supplier 4",
                phone = "01700112236",
                openingDue = 0.0
            )
        ).getOrThrow()

        val productId = database.productDao().insertProduct(
            ProductEntity(
                name = "Samsung Galaxy A15",
                sku = "SAM-A15-RET",
                trackingType = "IMEI",
                purchasePrice = 18000.0,
                retailPrice = 21000.0,
                currentStock = 0,
                supplierId = supplierId
            )
        )

        // Purchase 2 units
        val purchaseDraft = PurchaseEntity(supplierId = supplierId, paidAmount = 0.0)
        val items = listOf(
            PurchaseItemDraft(
                productId = productId,
                productName = "Samsung Galaxy A15",
                quantity = 2,
                unitCost = 18000.0,
                trackingType = "IMEI",
                serialsOrImeis = listOf(
                    SerialImeiInput(imei1 = "354777777777777"),
                    SerialImeiInput(imei1 = "354888888888888")
                )
            )
        )
        val purchaseId = purchaseRepository.createPurchase(purchaseDraft, items).getOrThrow()
        val returnedUnit = database.productDao().getSerialByImei("354777777777777")!!
        val remainingUnit = database.productDao().getSerialByImei("354888888888888")!!

        // Before return: stock = 2, supplier payable = 36,000
        assertEquals(2, database.productDao().getProductById(productId)!!.currentStock)
        assertEquals(36000.0, supplierRepository.getSupplierById(supplierId)!!.currentDue, 0.01)

        // Return one serialized unit (354777777777777)
        val pItem = purchaseRepository.getPurchaseItems(purchaseId).first()
        val returnDraft = PurchaseReturnEntity(
            purchaseId = purchaseId,
            supplierId = supplierId,
            reason = "Damaged packaging upon delivery"
        )
        val returnItems = listOf(
            PurchaseReturnItemDraft(
                purchaseItemId = pItem.id,
                productId = productId,
                productName = "Samsung Galaxy A15",
                returnQuantity = 1,
                unitCost = 18000.0,
                totalAmount = 18000.0,
                serialUnitId = returnedUnit.id,
                serialOrImei = "354777777777777"
            )
        )
        purchaseReturnRepository.createPurchaseReturn(returnDraft, returnItems).getOrThrow()

        // Stock = 1
        val productAfterReturn = database.productDao().getProductById(productId)!!
        assertEquals(1, productAfterReturn.currentStock)

        // Supplier payable decreases by 18,000 -> 36,000 - 18,000 = 18,000
        val supplierAfterReturn = supplierRepository.getSupplierById(supplierId)!!
        assertEquals(18000.0, supplierAfterReturn.currentDue, 0.01)
        assertEquals(18000.0, database.supplierLedgerDao().calculateAuthoritativeBalance(supplierId), 0.01)

        // Returned serialized unit cannot be sold again
        val availableSerials = database.productDao().getAvailableSerialsForProductSync(productId)
        assertEquals(1, availableSerials.size)
        assertEquals("354888888888888", availableSerials.first().imei1)

        val returnedUnitCheck = database.productDao().getSerialByImei("354777777777777")!!
        assertEquals(InventoryStatus.RETURNED, returnedUnitCheck.status)
        assertFalse(availableSerials.any { it.imei1 == "354777777777777" })
    }

    /**
     * TEST 5 — Quantity Product
     * Existing stock = 50
     * Purchase quantity = 100
     * Expected stock = 150.
     * Return quantity = 20.
     * Expected stock = 130.
     * Supplier payable must decrease by the correct return value.
     */
    @Test
    fun testFinancialAudit_Test5_QuantityProductPurchaseAndReturn() = runBlocking {
        val supplierId = supplierRepository.saveSupplier(
            SupplierEntity(
                name = "Cable Supply Hub",
                phone = "01700112237",
                openingDue = 0.0
            )
        ).getOrThrow()

        val productId = database.productDao().insertProduct(
            ProductEntity(
                name = "Fast Charge Type-C Cable",
                sku = "CABLE-TC-50",
                trackingType = "QUANTITY",
                purchasePrice = 120.0,
                retailPrice = 250.0,
                currentStock = 50,
                supplierId = supplierId
            )
        )

        // Purchase quantity = 100 @ ৳120
        val purchaseDraft = PurchaseEntity(supplierId = supplierId, paidAmount = 0.0)
        val items = listOf(
            PurchaseItemDraft(
                productId = productId,
                productName = "Fast Charge Type-C Cable",
                quantity = 100,
                unitCost = 120.0,
                trackingType = "QUANTITY"
            )
        )
        val purchaseId = purchaseRepository.createPurchase(purchaseDraft, items).getOrThrow()

        // Expected stock = 150
        val productAfterPurchase = database.productDao().getProductById(productId)!!
        assertEquals(150, productAfterPurchase.currentStock)
        assertEquals(12000.0, supplierRepository.getSupplierById(supplierId)!!.currentDue, 0.01)

        // Return quantity = 20 @ ৳120 -> ৳2,400 return value
        val pItem = purchaseRepository.getPurchaseItems(purchaseId).first()
        val returnDraft = PurchaseReturnEntity(
            purchaseId = purchaseId,
            supplierId = supplierId,
            reason = "Overstock adjustment"
        )
        val returnItems = listOf(
            PurchaseReturnItemDraft(
                purchaseItemId = pItem.id,
                productId = productId,
                productName = "Fast Charge Type-C Cable",
                returnQuantity = 20,
                unitCost = 120.0,
                totalAmount = 2400.0
            )
        )
        purchaseReturnRepository.createPurchaseReturn(returnDraft, returnItems).getOrThrow()

        // Expected stock = 130
        val productAfterReturn = database.productDao().getProductById(productId)!!
        assertEquals(130, productAfterReturn.currentStock)

        // Supplier payable must decrease by 2,400 (12,000 - 2,400 = 9,600)
        val supplierAfterReturn = supplierRepository.getSupplierById(supplierId)!!
        assertEquals(9600.0, supplierAfterReturn.currentDue, 0.01)
        assertEquals(9600.0, database.supplierLedgerDao().calculateAuthoritativeBalance(supplierId), 0.01)
    }

    /**
     * TEST 6 — Weighted Average Cost
     * Existing: 10 units @ 100
     * New purchase: 20 units @ 130
     * Expected:
     * Total quantity = 30
     * Total cost = 3,600
     * Weighted average cost = 120.
     */
    @Test
    fun testFinancialAudit_Test6_WeightedAverageCost() = runBlocking {
        val supplierId = supplierRepository.saveSupplier(
            SupplierEntity(
                name = "Power Bank Warehouse",
                phone = "01700112238",
                openingDue = 0.0
            )
        ).getOrThrow()

        val productId = database.productDao().insertProduct(
            ProductEntity(
                name = "Portable PowerBank 10000mAh",
                sku = "PB-10K",
                trackingType = "QUANTITY",
                purchasePrice = 100.0, // Existing 10 units @ 100
                currentStock = 10,
                supplierId = supplierId
            )
        )

        // New purchase: 20 units @ 130
        val purchaseDraft = PurchaseEntity(
            supplierId = supplierId,
            paidAmount = 0.0
        )
        val items = listOf(
            PurchaseItemDraft(
                productId = productId,
                productName = "Portable PowerBank 10000mAh",
                quantity = 20,
                unitCost = 130.0,
                trackingType = "QUANTITY"
            )
        )

        purchaseRepository.createPurchase(purchaseDraft, items).getOrThrow()

        val productAfterPurchase = database.productDao().getProductById(productId)!!

        // Total quantity = 30
        assertEquals(30, productAfterPurchase.currentStock)

        // Weighted average cost: (10 * 100 + 20 * 130) / 30 = (1000 + 2600) / 30 = 3600 / 30 = 120.0
        assertEquals(120.0, productAfterPurchase.purchasePrice, 0.001)

        // Total cost = 30 * 120 = 3,600
        val totalCost = productAfterPurchase.currentStock * productAfterPurchase.purchasePrice
        assertEquals(3600.0, totalCost, 0.001)
    }

    /**
     * TEST 7 — Atomicity & Rollback Verification
     * If an operation fails during purchase creation (e.g. duplicate serial/IMEI in the payload),
     * the entire transaction rolls back cleanly:
     * - 0 Purchase records created
     * - 0 Purchase items created
     * - Product stock remains untouched
     * - 0 Serial/IMEI records inserted
     * - 0 Stock transactions created
     * - 0 Supplier ledger entries created
     * - Supplier payable balance unchanged
     */
    @Test
    fun testFinancialAudit_Test7_AtomicityAndRollbackOnFailure() = runBlocking {
        val supplierId = supplierRepository.saveSupplier(
            SupplierEntity(
                name = "Rollback Audit Vendor",
                phone = "01700112239",
                openingDue = 5000.0
            )
        ).getOrThrow()

        val productId = database.productDao().insertProduct(
            ProductEntity(
                name = "Galaxy S24 Ultra",
                sku = "S24-ULTRA",
                trackingType = "IMEI",
                purchasePrice = 90000.0,
                currentStock = 5,
                supplierId = supplierId
            )
        )

        // Pre-existing serial
        database.productDao().insertSerialImei(
            ProductSerialImeiEntity(
                productId = productId,
                imei1 = "EXISTING_IMEI_12345",
                status = "IN_STOCK",
                supplierId = supplierId
            )
        )

        val initialPurchases = database.purchaseDao().getPurchaseCount()
        val initialStock = database.productDao().getProductById(productId)!!.currentStock
        val initialDue = supplierRepository.getSupplierById(supplierId)!!.currentDue
        val initialStockTxCount = database.stockDao().getAllStockTransactions().first().size
        val initialLedgerCount = database.supplierLedgerDao().getLedgerEntriesForSupplierSync(supplierId).size

        // Attempt purchase containing an IMEI that conflicts with existing inventory
        val purchaseDraft = PurchaseEntity(supplierId = supplierId, paidAmount = 0.0)
        val items = listOf(
            PurchaseItemDraft(
                productId = productId,
                productName = "Galaxy S24 Ultra",
                quantity = 2,
                unitCost = 90000.0,
                trackingType = "IMEI",
                serialsOrImeis = listOf(
                    SerialImeiInput(imei1 = "NEW_IMEI_99999"),
                    SerialImeiInput(imei1 = "EXISTING_IMEI_12345") // Duplicate! Must trigger failure
                )
            )
        )

        val result = purchaseRepository.createPurchase(purchaseDraft, items)
        assertTrue(result.isFailure)

        // Verify EVERYTHING rolled back:
        assertEquals(initialPurchases, database.purchaseDao().getPurchaseCount())
        assertEquals(initialStock, database.productDao().getProductById(productId)!!.currentStock)
        assertEquals(initialDue, supplierRepository.getSupplierById(supplierId)!!.currentDue, 0.01)
        assertEquals(initialStockTxCount, database.stockDao().getAllStockTransactions().first().size)
        assertEquals(initialLedgerCount, database.supplierLedgerDao().getLedgerEntriesForSupplierSync(supplierId).size)

        // The "NEW_IMEI_99999" must NOT exist in the database
        assertNull(database.productDao().getSerialByImei("NEW_IMEI_99999"))
    }
}

