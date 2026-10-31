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
class StockIntegrityAuditTest {

    private lateinit var database: AppDatabase
    private lateinit var stockRepository: StockRepository
    private lateinit var purchaseRepository: PurchaseRepository
    private lateinit var purchaseReturnRepository: PurchaseReturnRepository
    private lateinit var supplierRepository: SupplierRepository

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        stockRepository = StockRepository(database)
        purchaseRepository = PurchaseRepository(database)
        purchaseReturnRepository = PurchaseReturnRepository(database)
        supplierRepository = SupplierRepository(database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun testPurchaseIncreasesStockAndCreatesAuditableLedger() = runBlocking {
        // Setup supplier & product
        val supplierId = supplierRepository.saveSupplier(
            SupplierEntity(
                name = "Dhaka Tech Hub",
                phone = "01711000111",
                address = "Motijheel, Dhaka",
                openingDue = 0.0
            )
        ).getOrThrow()

        val productId = database.productDao().insertProduct(
            ProductEntity(
                name = "TP-Link Archer C6 Router",
                categoryName = "Routers",
                trackingType = "QUANTITY",
                purchasePrice = 2500.0,
                retailPrice = 3200.0,
                currentStock = 0
            )
        )

        // 1. Complete purchase of 5 routers
        val purchaseDraft = PurchaseEntity(
            supplierId = supplierId,
            paidAmount = 0.0
        )
        val items = listOf(
            PurchaseItemDraft(
                productId = productId,
                productName = "TP-Link Archer C6 Router",
                quantity = 5,
                unitCost = 2500.0,
                trackingType = "QUANTITY"
            )
        )

        val purchaseId = purchaseRepository.createPurchase(purchaseDraft, items).getOrThrow()
        assertTrue(purchaseId > 0)

        // Verify product stock increased
        val product = database.productDao().getProductById(productId)!!
        assertEquals(5, product.currentStock)

        // Verify authoritative ledger
        val authoritativeStock = stockRepository.getAuthoritativeStock(productId)
        assertEquals(5, authoritativeStock)

        // Verify stock transaction ledger record
        val transactions = database.stockDao().getTransactionsForProductSync(productId)
        assertEquals(1, transactions.size)
        val tx = transactions[0]
        assertEquals(StockMovementType.PURCHASE, tx.transactionType)
        assertEquals(5, tx.quantityChange)
        assertEquals(0, tx.previousStock)
        assertEquals(5, tx.newStock)
        assertEquals(purchaseId, tx.referenceId)
        assertEquals("PURCHASE", tx.referenceType)

        // Verify audit integrity report shows healthy
        val auditReport = stockRepository.auditStockIntegrity()
        assertTrue(auditReport.isHealthy)
        assertTrue(auditReport.negativeStockItems.isEmpty())
        assertTrue(auditReport.cachedVsLedgerDiscrepancies.isEmpty())
    }

    @Test
    fun testPurchaseReturnDecreasesStockAndEnforcesNoNegativeStock() = runBlocking {
        val supplierId = supplierRepository.saveSupplier(
            SupplierEntity(
                name = "Global Accessories",
                phone = "01811223344",
                address = "Gulshan, Dhaka",
                openingDue = 0.0
            )
        ).getOrThrow()

        val productId = database.productDao().insertProduct(
            ProductEntity(
                name = "Anker 20W Fast Charger",
                categoryName = "Accessories",
                trackingType = "QUANTITY",
                purchasePrice = 1200.0,
                retailPrice = 1600.0,
                currentStock = 0
            )
        )

        // Purchase 10 units
        val purchaseDraft = PurchaseEntity(
            supplierId = supplierId,
            paidAmount = 0.0
        )
        val purchaseId = purchaseRepository.createPurchase(
            purchaseDraft,
            listOf(
                PurchaseItemDraft(
                    productId = productId,
                    productName = "Anker 20W Fast Charger",
                    quantity = 10,
                    unitCost = 1200.0,
                    trackingType = "QUANTITY"
                )
            )
        ).getOrThrow()

        val pItem = purchaseRepository.getPurchaseItems(purchaseId).first()

        // 1. Return 3 units to supplier
        val returnDraft = PurchaseReturnEntity(
            purchaseId = purchaseId,
            supplierId = supplierId,
            reason = "Damaged packaging"
        )
        val returnItems = listOf(
            PurchaseReturnItemDraft(
                purchaseItemId = pItem.id,
                productId = productId,
                productName = pItem.productName,
                variantId = 0,
                returnQuantity = 3,
                unitCost = 1200.0,
                totalAmount = 3600.0
            )
        )

        val returnResult = purchaseReturnRepository.createPurchaseReturn(returnDraft, returnItems)
        assertTrue(returnResult.isSuccess)

        // Verify stock decreased to 7
        val productAfterReturn = database.productDao().getProductById(productId)!!
        assertEquals(7, productAfterReturn.currentStock)

        // Verify authoritative ledger equals 7
        assertEquals(7, stockRepository.getAuthoritativeStock(productId))

        // Verify transaction count: 1 PURCHASE (+10) and 1 PURCHASE_RETURN (-3)
        val transactions = database.stockDao().getTransactionsForProductSync(productId)
        assertEquals(2, transactions.size)
        assertEquals(StockMovementType.PURCHASE, transactions[0].transactionType)
        assertEquals(10, transactions[0].quantityChange)
        assertEquals(StockMovementType.PURCHASE_RETURN, transactions[1].transactionType)
        assertEquals(-3, transactions[1].quantityChange)
        assertEquals(7, transactions[1].newStock)

        // 2. Attempt to return 8 units (exceeding remaining 7 units in purchase) -> should fail
        val excessiveReturnDraft = PurchaseReturnEntity(
            purchaseId = purchaseId,
            supplierId = supplierId,
            reason = "Return more than available"
        )
        val excessiveItems = listOf(
            PurchaseReturnItemDraft(
                purchaseItemId = pItem.id,
                productId = productId,
                productName = pItem.productName,
                variantId = 0,
                returnQuantity = 8,
                unitCost = 1200.0,
                totalAmount = 9600.0
            )
        )
        val excessiveResult = purchaseReturnRepository.createPurchaseReturn(excessiveReturnDraft, excessiveItems)
        assertTrue(excessiveResult.isFailure)
        assertTrue(excessiveResult.exceptionOrNull()?.message?.contains("available to return", ignoreCase = true) == true)

        // Verify stock is still 7
        assertEquals(7, database.productDao().getProductById(productId)!!.currentStock)
        assertEquals(7, stockRepository.getAuthoritativeStock(productId))
    }

    @Test
    fun testSerializedStockLifecycleAndIntegrity() = runBlocking {
        val supplierId = supplierRepository.saveSupplier(
            SupplierEntity(
                name = "Samsung Bangladesh Distributor",
                phone = "01999888777",
                address = "Dhaka",
                openingDue = 0.0
            )
        ).getOrThrow()

        val productId = database.productDao().insertProduct(
            ProductEntity(
                name = "Samsung Galaxy A15",
                categoryName = "Smartphones",
                trackingType = "IMEI",
                purchasePrice = 18000.0,
                retailPrice = 22000.0,
                currentStock = 0
            )
        )

        val imei1 = "111111111111111"
        val imei2 = "222222222222222"

        // 1. Purchase 2 IMEI units
        val purchaseDraft = PurchaseEntity(
            supplierId = supplierId,
            paidAmount = 0.0
        )
        val purchaseId = purchaseRepository.createPurchase(
            purchaseDraft,
            listOf(
                PurchaseItemDraft(
                    productId = productId,
                    productName = "Samsung Galaxy A15",
                    quantity = 2,
                    unitCost = 18000.0,
                    trackingType = "IMEI",
                    serialsOrImeis = listOf(
                        SerialImeiInput(imei1 = imei1),
                        SerialImeiInput(imei1 = imei2)
                    )
                )
            )
        ).getOrThrow()

        assertEquals(2, database.productDao().getProductById(productId)!!.currentStock)
        assertEquals(2, stockRepository.getAuthoritativeStock(productId))

        // Check serialized units status
        val serial1 = database.productDao().getSerialByImei(imei1)!!
        val serial2 = database.productDao().getSerialByImei(imei2)!!
        assertEquals("IN_STOCK", serial1.status)
        assertEquals("IN_STOCK", serial2.status)
        assertEquals(2, database.productDao().countAvailableSerialsForProductSync(productId))

        // 2. Return 1 unit (imei1)
        val purchaseItemEntity = purchaseRepository.getPurchaseItems(purchaseId).first()
        val returnResult = purchaseReturnRepository.createPurchaseReturn(
            PurchaseReturnEntity(
                purchaseId = purchaseId,
                supplierId = supplierId,
                reason = "Defective display"
            ),
            listOf(
                PurchaseReturnItemDraft(
                    purchaseItemId = purchaseItemEntity.id,
                    productId = productId,
                    productName = "Samsung Galaxy A15",
                    returnQuantity = 1,
                    unitCost = 18000.0,
                    totalAmount = 18000.0,
                    serialUnitId = serial1.id,
                    serialOrImei = imei1
                )
            )
        )
        assertTrue(returnResult.isSuccess)

        // Verify stock is now 1
        assertEquals(1, database.productDao().getProductById(productId)!!.currentStock)
        assertEquals(1, stockRepository.getAuthoritativeStock(productId))

        // Check serial unit statuses
        val serial1AfterReturn = database.productDao().getSerialById(serial1.id)!!
        val serial2AfterReturn = database.productDao().getSerialById(serial2.id)!!
        assertEquals(InventoryStatus.RETURNED, serial1AfterReturn.status)
        assertEquals("IN_STOCK", serial2AfterReturn.status)

        // Sellable serial count must strictly equal 1
        assertEquals(1, database.productDao().countAvailableSerialsForProductSync(productId))

        // Stock report must be healthy and aligned
        val report = stockRepository.auditStockIntegrity()
        assertTrue(report.isHealthy)
        assertTrue(report.serializedDiscrepancies.isEmpty())
    }

    @Test
    fun testPurchaseCancellationReversesStockAndSerialUnitsExactlyOnce() = runBlocking {
        val supplierId = supplierRepository.saveSupplier(
            SupplierEntity(
                name = "Xiaomi Official",
                phone = "01555444333",
                address = "Banani, Dhaka",
                openingDue = 0.0
            )
        ).getOrThrow()

        val productId = database.productDao().insertProduct(
            ProductEntity(
                name = "Redmi Note 13",
                categoryName = "Smartphones",
                trackingType = "IMEI",
                purchasePrice = 20000.0,
                retailPrice = 25000.0,
                currentStock = 0
            )
        )

        val imei1 = "333333333333333"
        val purchaseDraft = PurchaseEntity(
            supplierId = supplierId,
            paidAmount = 0.0
        )
        val purchaseId = purchaseRepository.createPurchase(
            purchaseDraft,
            listOf(
                PurchaseItemDraft(
                    productId = productId,
                    productName = "Redmi Note 13",
                    quantity = 1,
                    unitCost = 20000.0,
                    trackingType = "IMEI",
                    serialsOrImeis = listOf(
                        SerialImeiInput(imei1 = imei1)
                    )
                )
            )
        ).getOrThrow()

        assertEquals(1, database.productDao().getProductById(productId)!!.currentStock)
        assertEquals(1, stockRepository.getAuthoritativeStock(productId))

        // 1. Cancel the purchase
        val cancelResult = purchaseRepository.cancelPurchase(purchaseId, "Supplier invoice error")
        assertTrue(cancelResult.isSuccess)

        // Verify stock is now 0
        assertEquals(0, database.productDao().getProductById(productId)!!.currentStock)
        assertEquals(0, stockRepository.getAuthoritativeStock(productId))

        // Serial status must be CANCELLED
        val serial = database.productDao().getSerialByImei(imei1)!!
        assertEquals("CANCELLED", serial.status)
        assertEquals(0, database.productDao().countAvailableSerialsForProductSync(productId))

        // Verify ledger has 1 PURCHASE (+1) and 1 PURCHASE_CANCELLED (-1)
        val transactions = database.stockDao().getTransactionsForProductSync(productId)
        assertEquals(2, transactions.size)
        assertEquals(StockMovementType.PURCHASE, transactions[0].transactionType)
        assertEquals(1, transactions[0].quantityChange)
        assertEquals(StockMovementType.PURCHASE_CANCELLED, transactions[1].transactionType)
        assertEquals(-1, transactions[1].quantityChange)
        assertEquals(0, transactions[1].newStock)

        // 2. Attempt to cancel again -> must fail safely
        val secondCancelResult = purchaseRepository.cancelPurchase(purchaseId, "Duplicate cancel attempt")
        assertTrue(secondCancelResult.isFailure)
        assertTrue(secondCancelResult.exceptionOrNull()?.message?.contains("already cancelled", ignoreCase = true) == true)

        // Verify stock remains 0 (not decreased again!)
        assertEquals(0, database.productDao().getProductById(productId)!!.currentStock)
        assertEquals(0, stockRepository.getAuthoritativeStock(productId))
    }

    @Test
    fun testStockResyncAndDiscrepancyDetection() = runBlocking {
        val productId = database.productDao().insertProduct(
            ProductEntity(
                name = "SanDisk 64GB Flash Drive",
                categoryName = "Storage",
                trackingType = "QUANTITY",
                purchasePrice = 500.0,
                retailPrice = 750.0,
                currentStock = 0
            )
        )

        // Normal adjustment of +10 via stockRepository
        val adjustResult = stockRepository.adjustStock(
            productId = productId,
            quantityDelta = 10,
            reason = StockMovementType.ADJUSTMENT_FOUND,
            notes = "Found in inventory count"
        )
        assertTrue(adjustResult.isSuccess)
        assertEquals(10, database.productDao().getProductById(productId)!!.currentStock)
        assertEquals(10, stockRepository.getAuthoritativeStock(productId))

        // Simulate an out-of-band cache divergence (e.g. legacy direct write)
        database.productDao().setStockQuantity(productId, 15)
        assertEquals(15, database.productDao().getProductById(productId)!!.currentStock)
        assertEquals(10, stockRepository.getAuthoritativeStock(productId)) // Authoritative ledger is 10

        // Audit report should detect the discrepancy
        val auditReport = stockRepository.auditStockIntegrity()
        assertFalse(auditReport.isHealthy)
        assertEquals(1, auditReport.cachedVsLedgerDiscrepancies.size)
        val disc = auditReport.cachedVsLedgerDiscrepancies[0]
        assertEquals(productId, disc.productId)
        assertEquals(15, disc.cachedStock)
        assertEquals(10, disc.ledgerStock)
        assertEquals(5, disc.discrepancy)

        // Resync cached stock with authoritative ledger
        val fixed = stockRepository.syncAllProductStocks()
        assertEquals(1, fixed.size)

        // Verify cached stock restored to authoritative 10
        assertEquals(10, database.productDao().getProductById(productId)!!.currentStock)
        val cleanReport = stockRepository.auditStockIntegrity()
        assertTrue(cleanReport.isHealthy)
        assertTrue(cleanReport.cachedVsLedgerDiscrepancies.isEmpty())
    }
}
