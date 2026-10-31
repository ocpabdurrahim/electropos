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
class Phase5SalesAndCustomerTest {

    private lateinit var database: AppDatabase
    private lateinit var customerRepository: CustomerRepository
    private lateinit var productRepository: ProductRepository
    private lateinit var purchaseRepository: PurchaseRepository
    private lateinit var saleRepository: SaleRepository
    private lateinit var salesReturnRepository: SalesReturnRepository
    private lateinit var supplierRepository: SupplierRepository

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        customerRepository = CustomerRepository(database)
        productRepository = ProductRepository(
            database.productDao(),
            database.stockDao(),
            database.productVariantDao(),
            database.productPriceHistoryDao(),
            database.settingsAndAuditDao()
        )
        purchaseRepository = PurchaseRepository(database)
        saleRepository = SaleRepository(database)
        salesReturnRepository = SalesReturnRepository(database)
        supplierRepository = SupplierRepository(database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    // =========================================================================
    // 1. CUSTOMER CREATION & OPENING BALANCE LEDGER
    // =========================================================================

    @Test
    fun testCustomerCreationWithOpeningBalance() = runBlocking {
        val customer = CustomerEntity(
            name = "Rahim Telecom",
            phone = "01711000000",
            address = "Dhanmondi, Dhaka",
            openingDue = 5000.0,
            creditLimit = 20000.0
        )

        val customerId = customerRepository.saveCustomer(customer).getOrThrow()
        assertTrue(customerId > 0)

        val retrieved = customerRepository.getCustomerById(customerId)
        assertNotNull(retrieved)
        assertEquals("Rahim Telecom", retrieved?.name)
        assertTrue(retrieved?.customerCode?.startsWith("CUS-") == true)
        assertEquals(5000.0, retrieved?.openingDue ?: 0.0, 0.001)

        // Verify authoritative ledger entry created
        val ledger = database.customerLedgerDao().getLedgerEntriesForCustomer(customerId).first()
        assertEquals(1, ledger.size)
        val entry = ledger[0]
        assertEquals("OPENING_BALANCE", entry.transactionType)
        assertEquals(5000.0, entry.amount, 0.001)
        assertEquals(5000.0, entry.signedAmount, 0.001)

        // Verify authoritative balance calculation
        val calculatedBalance = customerRepository.getAuthoritativeCustomerBalance(customerId)
        assertEquals(5000.0, calculatedBalance, 0.001)
        assertEquals(5000.0, retrieved?.currentDue ?: 0.0, 0.001)
    }

    @Test
    fun testCustomerPaymentReducesBalanceAndCashIn() = runBlocking {
        val customerId = customerRepository.saveCustomer(
            CustomerEntity(
                name = "Karim Electronics",
                phone = "01811000000",
                openingDue = 10000.0
            )
        ).getOrThrow()

        assertEquals(10000.0, customerRepository.getAuthoritativeCustomerBalance(customerId), 0.001)

        // Customer pays 4,000 cash
        val paymentResult = customerRepository.recordCustomerPayment(
            customerId = customerId,
            amount = 4000.0,
            paymentMethod = "CASH",
            referenceNumber = "RECEIPT-101",
            notes = "Partial settlement"
        )
        assertTrue(paymentResult.isSuccess)

        // Authoritative balance must now be 6,000
        val balanceAfterPayment = customerRepository.getAuthoritativeCustomerBalance(customerId)
        assertEquals(6000.0, balanceAfterPayment, 0.001)

        val updatedCustomer = customerRepository.getCustomerById(customerId)
        assertEquals(6000.0, updatedCustomer?.currentDue ?: 0.0, 0.001)

        // Ledger must have 2 entries
        val ledger = database.customerLedgerDao().getLedgerEntriesForCustomer(customerId).first()
        assertEquals(2, ledger.size)
        val paymentEntry = ledger.find { it.transactionType == "CUSTOMER_PAYMENT" }
        assertNotNull(paymentEntry)
        assertEquals(4000.0, paymentEntry?.amount ?: 0.0, 0.001)
        assertEquals(-4000.0, paymentEntry?.signedAmount ?: 0.0, 0.001)
    }

    // =========================================================================
    // 2. QUANTITY-BASED SALE, STOCK DECREMENT & CUSTOMER LEDGER
    // =========================================================================

    @Test
    fun testQuantityProductSaleSuccess() = runBlocking {
        // 1. Create product with initial stock
        val productId = database.productDao().insertProduct(
            ProductEntity(
                name = "Fast Charger 20W",
                sku = "ANKER-20W-001",
                categoryName = "Accessories",
                brandName = "Anker",
                barcode = "ANKER-20W-001",
                purchasePrice = 300.0,
                retailPrice = 600.0,
                currentStock = 10,
                trackingType = "QUANTITY"
            )
        )

        // 2. Create customer
        val customerId = customerRepository.saveCustomer(
            CustomerEntity(
                name = "Tanvir Ahmed",
                phone = "01911000000",
                openingDue = 0.0
            )
        ).getOrThrow()

        // 3. Make sale of 3 units @ 600 = 1,800. Paid: 1,000, Due: 800
        val saleDraft = SaleEntity(
            invoiceNumber = "INV-TEST-001",
            customerId = customerId,
            customerName = "Tanvir Ahmed",
            subTotal = 1800.0,
            totalAmount = 1800.0,
            paidAmount = 1000.0,
            dueAmount = 800.0,
            paymentMethod = "CASH",
            paymentStatus = "PARTIAL",
            status = "COMPLETED"
        )
        val saleItems = listOf(
            SaleItemInput(
                productId = productId,
                productName = "Fast Charger 20W",
                quantity = 3,
                unitPrice = 600.0,
                trackingType = "QUANTITY"
            )
        )

        val saleId = saleRepository.createSale(saleDraft, saleItems).getOrThrow()
        assertTrue(saleId > 0)

        // Assert stock decremented by 3 (10 - 3 = 7)
        val updatedProduct = database.productDao().getProductById(productId)
        assertEquals(7, updatedProduct?.currentStock)

        // Assert customer due is 800
        val customerBalance = customerRepository.getAuthoritativeCustomerBalance(customerId)
        assertEquals(800.0, customerBalance, 0.001)

        // Assert stock transaction logged
        val stockTxs = database.stockDao().getTransactionsForProductSync(productId)
        val saleStockTx = stockTxs.find { it.transactionType == "SALE" }
        assertNotNull(saleStockTx)
        assertEquals(-3, saleStockTx?.quantityChange)
    }

    @Test
    fun testInsufficientStockBlocksSale() = runBlocking {
        val productId = database.productDao().insertProduct(
            ProductEntity(
                name = "USB-C Cable",
                sku = "BAS-USB-01",
                categoryName = "Cables",
                brandName = "Baseus",
                purchasePrice = 50.0,
                retailPrice = 120.0,
                currentStock = 2,
                trackingType = "QUANTITY"
            )
        )

        val saleDraft = SaleEntity(
            invoiceNumber = "INV-TEST-002",
            customerId = 0L,
            customerName = "Walk-in Customer",
            subTotal = 360.0,
            totalAmount = 360.0,
            paidAmount = 360.0,
            dueAmount = 0.0,
            paymentMethod = "CASH",
            status = "COMPLETED"
        )
        val saleItems = listOf(
            SaleItemInput(
                productId = productId,
                productName = "USB-C Cable",
                quantity = 3, // Requesting 3, but only 2 available
                unitPrice = 120.0,
                trackingType = "QUANTITY"
            )
        )

        val result = saleRepository.createSale(saleDraft, saleItems)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("Insufficient stock") == true)

        // Verify stock remains untouched at 2
        val product = database.productDao().getProductById(productId)
        assertEquals(2, product?.currentStock)
    }

    @Test
    fun testWalkInCustomerCreditIsBlocked() = runBlocking {
        val productId = database.productDao().insertProduct(
            ProductEntity(
                name = "Earphones",
                sku = "EAR-GEN-01",
                categoryName = "Audio",
                brandName = "Generic",
                purchasePrice = 100.0,
                retailPrice = 200.0,
                currentStock = 5,
                trackingType = "QUANTITY"
            )
        )

        // Walk-in customer (customerId = 0L), total 400, paid 200 (credit 200 requested)
        val saleDraft = SaleEntity(
            invoiceNumber = "INV-TEST-003",
            customerId = 0L,
            customerName = "Walk-in Customer",
            subTotal = 400.0,
            totalAmount = 400.0,
            paidAmount = 200.0,
            dueAmount = 200.0,
            paymentMethod = "CASH",
            status = "COMPLETED"
        )
        val saleItems = listOf(
            SaleItemInput(
                productId = productId,
                productName = "Earphones",
                quantity = 2,
                unitPrice = 200.0,
                trackingType = "QUANTITY"
            )
        )

        val result = saleRepository.createSale(saleDraft, saleItems)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("Walk-in customer cannot have unpaid due") == true)

        // Verify product stock still 5
        assertEquals(5, database.productDao().getProductById(productId)?.currentStock)
    }

    @Test
    fun testCustomerCreditLimitEnforcement() = runBlocking {
        val customerId = customerRepository.saveCustomer(
            CustomerEntity(
                name = "Limited Credit Buyer",
                phone = "01511000000",
                openingDue = 4000.0,
                creditLimit = 5000.0 // Only 1,000 credit remaining!
            )
        ).getOrThrow()

        val productId = database.productDao().insertProduct(
            ProductEntity(
                name = "Smart Watch",
                sku = "ORA-SW-01",
                categoryName = "Wearables",
                brandName = "Oraimo",
                purchasePrice = 1500.0,
                retailPrice = 2500.0,
                currentStock = 5,
                trackingType = "QUANTITY"
            )
        )

        // Attempting to buy with 2,000 due (exceeds credit limit: 4,000 + 2,000 = 6,000 > 5,000)
        val saleDraft = SaleEntity(
            invoiceNumber = "INV-TEST-004",
            customerId = customerId,
            customerName = "Limited Credit Buyer",
            subTotal = 2500.0,
            totalAmount = 2500.0,
            paidAmount = 500.0,
            dueAmount = 2000.0,
            paymentMethod = "CASH",
            status = "COMPLETED"
        )
        val saleItems = listOf(
            SaleItemInput(
                productId = productId,
                productName = "Smart Watch",
                quantity = 1,
                unitPrice = 2500.0,
                trackingType = "QUANTITY"
            )
        )

        val result = saleRepository.createSale(saleDraft, saleItems)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("credit limit") == true)
    }

    // =========================================================================
    // 3. SERIALIZED / IMEI PRODUCT SALE & PREVENTING DUPLICATE SALES
    // =========================================================================

    @Test
    fun testSerializedAndImeiSaleUpdatesStatusToSold() = runBlocking {
        // Create supplier
        val supplierId = supplierRepository.saveSupplier(
            SupplierEntity(name = "Mobile World", phone = "01711111111")
        ).getOrThrow()

        // Create IMEI phone
        val phoneId = database.productDao().insertProduct(
            ProductEntity(
                name = "Redmi 12",
                sku = "XIA-R12-BLK",
                categoryName = "Smartphones",
                brandName = "Xiaomi",
                purchasePrice = 12000.0,
                retailPrice = 15000.0,
                currentStock = 0,
                trackingType = "IMEI"
            )
        )

        // Inbound via purchase of 2 units
        val purchase = PurchaseEntity(
            supplierId = supplierId,
            paidAmount = 24000.0
        )
        val purchaseItems = listOf(
            PurchaseItemDraft(
                productId = phoneId,
                productName = "Redmi 12",
                quantity = 2,
                unitCost = 12000.0,
                trackingType = "IMEI",
                serialsOrImeis = listOf(
                    SerialImeiInput(imei1 = "860111111111111", imei2 = "860222222222222"),
                    SerialImeiInput(imei1 = "860333333333333", imei2 = "860444444444444")
                )
            )
        )
        purchaseRepository.createPurchase(purchase, purchaseItems).getOrThrow()

        // Stock is now 2
        assertEquals(2, database.productDao().getProductById(phoneId)?.currentStock)

        val inStockSerials = productRepository.getAvailableSerialsForProductSync(phoneId)
        assertEquals(2, inStockSerials.size)
        val unitToSell = inStockSerials.find { it.imei1 == "860111111111111" }
        assertNotNull(unitToSell)

        // Sell unit 1 to walk-in customer (paid in full)
        val saleDraft = SaleEntity(
            invoiceNumber = "INV-REDMI-001",
            customerId = null,
            customerName = "Walk-in Buyer",
            subTotal = 15000.0,
            totalAmount = 15000.0,
            paidAmount = 15000.0,
            dueAmount = 0.0,
            paymentMethod = "CASH",
            status = "COMPLETED"
        )
        val saleItems = listOf(
            SaleItemInput(
                productId = phoneId,
                productName = "Redmi 12",
                quantity = 1,
                unitPrice = 15000.0,
                trackingType = "IMEI",
                selectedSerialImeiIds = listOf(unitToSell!!.id),
                selectedSerialsOrImeis = listOf("860111111111111")
            )
        )

        val saleId = saleRepository.createSale(saleDraft, saleItems).getOrThrow()
        assertTrue(saleId > 0)

        // Product stock is now 1
        assertEquals(1, database.productDao().getProductById(phoneId)?.currentStock)

        // Verify serial unit status is SOLD and linked to sale
        val soldUnit = database.productDao().getSerialById(unitToSell.id)
        assertEquals("SOLD", soldUnit?.status)
        assertEquals(saleId, soldUnit?.saleId)

        // Attempting to sell the SAME serial unit again must FAIL
        val duplicateAttempt = saleRepository.createSale(
            sale = saleDraft.copy(invoiceNumber = "INV-REDMI-DUP"),
            items = saleItems
        )
        assertTrue(duplicateAttempt.isFailure)
        assertTrue(duplicateAttempt.exceptionOrNull()?.message?.contains("not in stock") == true)
    }

    // =========================================================================
    // 4. SALE CANCELLATION RESTORES STOCK, SERIALS & REVERSES CUSTOMER DUE
    // =========================================================================

    @Test
    fun testSaleCancellationRestoresStockAndReversesCustomerDue() = runBlocking {
        // Create customer with 0 due
        val customerId = customerRepository.saveCustomer(
            CustomerEntity(name = "Salam Brothers", phone = "01611000000")
        ).getOrThrow()

        // Create product with 5 stock
        val productId = database.productDao().insertProduct(
            ProductEntity(
                name = "Bluetooth Speaker",
                sku = "JBL-BT-01",
                categoryName = "Audio",
                brandName = "JBL",
                purchasePrice = 2000.0,
                retailPrice = 3000.0,
                currentStock = 5,
                trackingType = "QUANTITY"
            )
        )

        // Sell 2 units on credit (Due: 6,000)
        val saleDraft = SaleEntity(
            invoiceNumber = "INV-JBL-001",
            customerId = customerId,
            customerName = "Salam Brothers",
            subTotal = 6000.0,
            totalAmount = 6000.0,
            paidAmount = 0.0,
            dueAmount = 6000.0,
            status = "COMPLETED"
        )
        val saleItems = listOf(
            SaleItemInput(
                productId = productId,
                productName = "Bluetooth Speaker",
                quantity = 2,
                unitPrice = 3000.0,
                trackingType = "QUANTITY"
            )
        )

        val saleId = saleRepository.createSale(saleDraft, saleItems).getOrThrow()

        // Pre-cancellation checks:
        assertEquals(3, database.productDao().getProductById(productId)?.currentStock)
        assertEquals(6000.0, customerRepository.getAuthoritativeCustomerBalance(customerId), 0.001)

        // Cancel sale
        val cancelResult = saleRepository.cancelSale(saleId, "Customer order cancelled before delivery")
        assertTrue(cancelResult.isSuccess)

        // Post-cancellation checks:
        // Stock restored to 5
        assertEquals(5, database.productDao().getProductById(productId)?.currentStock)

        // Customer due reversed back to 0
        assertEquals(0.0, customerRepository.getAuthoritativeCustomerBalance(customerId), 0.001)

        // Sale marked CANCELLED
        val cancelledSale = saleRepository.getSaleById(saleId)
        assertEquals("CANCELLED", cancelledSale?.status)

        // Verify reversal ledger entry
        val ledger = database.customerLedgerDao().getLedgerEntriesForCustomer(customerId).first()
        val cancelEntry = ledger.find { it.transactionType == "SALE_CANCEL" }
        assertNotNull(cancelEntry)
        assertEquals(-6000.0, cancelEntry?.signedAmount ?: 0.0, 0.001)
    }

    // =========================================================================
    // 5. SALES RETURN: RESTOCK SELLABLE VS DEFECTIVE, BALANCE ADJUSTMENT
    // =========================================================================

    @Test
    fun testSalesReturnWithRestockAndLedgerReduction() = runBlocking {
        val customerId = customerRepository.saveCustomer(
            CustomerEntity(name = "Digital Plaza", phone = "01311000000")
        ).getOrThrow()

        val productId = database.productDao().insertProduct(
            ProductEntity(
                name = "Power Bank 10000mAh",
                sku = "BAS-PB-01",
                categoryName = "Accessories",
                brandName = "Baseus",
                purchasePrice = 800.0,
                retailPrice = 1500.0,
                currentStock = 10,
                trackingType = "QUANTITY"
            )
        )

        // Sale: 4 units @ 1,500 = 6,000. Paid 2,000, Due 4,000
        val saleDraft = SaleEntity(
            invoiceNumber = "INV-PB-001",
            customerId = customerId,
            customerName = "Digital Plaza",
            subTotal = 6000.0,
            totalAmount = 6000.0,
            paidAmount = 2000.0,
            dueAmount = 4000.0,
            status = "COMPLETED"
        )
        val saleItems = listOf(
            SaleItemInput(
                productId = productId,
                productName = "Power Bank 10000mAh",
                quantity = 4,
                unitPrice = 1500.0,
                trackingType = "QUANTITY"
            )
        )

        val saleId = saleRepository.createSale(saleDraft, saleItems).getOrThrow()
        val createdSaleItems = database.saleDao().getSaleItems(saleId)
        val saleItemId = createdSaleItems[0].id

        assertEquals(6, database.productDao().getProductById(productId)?.currentStock)
        assertEquals(4000.0, customerRepository.getAuthoritativeCustomerBalance(customerId), 0.001)

        // Return 1 unit (sellable restock, credit to customer account)
        val returnDraft = SalesReturnDraft(
            saleId = saleId,
            reason = "Customer changed mind",
            refundAmount = 0.0,
            customerCredit = 1500.0
        )
        val returnItems = listOf(
            SalesReturnItemDraft(
                saleItemId = saleItemId,
                productId = productId,
                productName = "Power Bank 10000mAh",
                returnQuantity = 1,
                unitPrice = 1500.0,
                totalAmount = 1500.0,
                restockOption = "RESTOCK_SELLABLE"
            )
        )

        val returnResult = salesReturnRepository.createSalesReturn(returnDraft, returnItems)
        assertTrue(returnResult.isSuccess)

        // 1. Stock restored by 1 (6 + 1 = 7)
        assertEquals(7, database.productDao().getProductById(productId)?.currentStock)

        // 2. Customer balance reduced from 4,000 to 2,500 (4,000 - 1,500)
        assertEquals(2500.0, customerRepository.getAuthoritativeCustomerBalance(customerId), 0.001)

        // 3. Prevent returning more than remaining purchased quantity (originally 4, returned 1 -> max 3 remaining)
        val invalidReturnItems = listOf(
            SalesReturnItemDraft(
                saleItemId = saleItemId,
                productId = productId,
                productName = "Power Bank 10000mAh",
                returnQuantity = 4, // Only 3 returnable!
                unitPrice = 1500.0,
                totalAmount = 6000.0,
                restockOption = "RESTOCK_SELLABLE"
            )
        )
        val excessReturnResult = salesReturnRepository.createSalesReturn(
            returnDraft.copy(customerCredit = 6000.0),
            invalidReturnItems
        )
        assertTrue(excessReturnResult.isFailure)
        assertTrue(excessReturnResult.exceptionOrNull()?.message?.contains("Remaining returnable") == true)
    }

    // =========================================================================
    // 6. ACCEPTANCE SCENARIOS FROM BRIEF
    // =========================================================================

    /**
     * Acceptance Scenario 1:
     * Product: USB-C Fast Charging Cable
     * Cost = 120, Selling price = 250
     * Customer: Rahim (Opening balance = 0)
     * Sell 5 cables:
     * - Subtotal = 5 * 250 = 1,250
     * - Discount = 50 -> Grand Total = 1,200
     * - Paid = 500, Due = 700
     * Assertions:
     * - Stock decremented by 5
     * - Rahim balance = 700
     * - Rahim pays 700 -> Balance becomes 0
     */
    @Test
    fun testAcceptanceScenario1_UsbCableSaleDiscountAndPayment() = runBlocking {
        val rahimId = customerRepository.saveCustomer(
            CustomerEntity(name = "Rahim", phone = "01711223344", openingDue = 0.0)
        ).getOrThrow()

        val cableId = database.productDao().insertProduct(
            ProductEntity(
                name = "USB-C Fast Charging Cable",
                sku = "BAS-USBC-01",
                categoryName = "Cables",
                brandName = "Baseus",
                purchasePrice = 120.0,
                retailPrice = 250.0,
                currentStock = 20,
                trackingType = "QUANTITY"
            )
        )

        // Sale: 5 cables @ 250 = 1,250. Discount = 50. Total = 1,200. Paid = 500. Due = 700.
        val saleDraft = SaleEntity(
            invoiceNumber = "INV-ACCEPT-01",
            customerId = rahimId,
            customerName = "Rahim",
            subTotal = 1250.0,
            invoiceDiscount = 50.0,
            totalAmount = 1200.0,
            paidAmount = 500.0,
            dueAmount = 700.0,
            paymentMethod = "CASH",
            paymentStatus = "PARTIAL",
            status = "COMPLETED"
        )
        val saleItems = listOf(
            SaleItemInput(
                productId = cableId,
                productName = "USB-C Fast Charging Cable",
                quantity = 5,
                unitPrice = 250.0,
                trackingType = "QUANTITY"
            )
        )

        val saleId = saleRepository.createSale(saleDraft, saleItems).getOrThrow()
        assertTrue(saleId > 0)

        // Check stock: 20 - 5 = 15
        assertEquals(15, database.productDao().getProductById(cableId)?.currentStock)

        // Check Rahim balance: 700
        assertEquals(700.0, customerRepository.getAuthoritativeCustomerBalance(rahimId), 0.001)

        // Rahim settles due with 700 payment
        customerRepository.recordCustomerPayment(
            customerId = rahimId,
            amount = 700.0,
            paymentMethod = "BKASH",
            referenceNumber = "TRX-7788",
            notes = "Full settlement"
        ).getOrThrow()

        // Rahim balance is now 0
        assertEquals(0.0, customerRepository.getAuthoritativeCustomerBalance(rahimId), 0.001)
    }

    /**
     * Acceptance Scenario 2:
     * Phone with IMEI: Redmi Note 13
     * 1. Purchase phone (IMEI: 860123456789001) -> IN_STOCK
     * 2. Sell to Karim Mobile:
     *    - Price = 22,000, Paid = 10,000, Due = 12,000
     *    - Status becomes SOLD
     * 3. Attempt duplicate sale of same IMEI -> BLOCKED
     * 4. Return unit with DEFECTIVE_DAMAGED restock option:
     *    - Status becomes DAMAGED (not available for resale)
     *    - Karim balance reduced by full unit price (22,000) -> Karim has advance credit of -10,000
     */
    @Test
    fun testAcceptanceScenario2_ImeiSaleDuplicateBlockedAndDefectiveReturn() = runBlocking {
        val supplierId = supplierRepository.saveSupplier(
            SupplierEntity(name = "National Distributor", phone = "01811998877")
        ).getOrThrow()

        val karimId = customerRepository.saveCustomer(
            CustomerEntity(name = "Karim Mobile", phone = "01999887766", openingDue = 0.0)
        ).getOrThrow()

        val redmiId = database.productDao().insertProduct(
            ProductEntity(
                name = "Redmi Note 13",
                sku = "XIA-RN13-01",
                categoryName = "Smartphones",
                brandName = "Xiaomi",
                purchasePrice = 18000.0,
                retailPrice = 22000.0,
                currentStock = 0,
                trackingType = "IMEI"
            )
        )

        // 1. Purchase phone
        val purchase = PurchaseEntity(
            supplierId = supplierId,
            paidAmount = 18000.0
        )
        val purchaseItems = listOf(
            PurchaseItemDraft(
                productId = redmiId,
                productName = "Redmi Note 13",
                quantity = 1,
                unitCost = 18000.0,
                trackingType = "IMEI",
                serialsOrImeis = listOf(
                    SerialImeiInput(imei1 = "860123456789001")
                )
            )
        )
        purchaseRepository.createPurchase(purchase, purchaseItems).getOrThrow()

        val inStockUnit = productRepository.getAvailableSerialsForProductSync(redmiId).first()
        assertEquals("860123456789001", inStockUnit.imei1)
        assertEquals("IN_STOCK", inStockUnit.status)

        // 2. Sell to Karim Mobile: Price 22,000, Paid 10,000, Due 12,000
        val saleDraft = SaleEntity(
            invoiceNumber = "INV-REDMI-K01",
            customerId = karimId,
            customerName = "Karim Mobile",
            subTotal = 22000.0,
            totalAmount = 22000.0,
            paidAmount = 10000.0,
            dueAmount = 12000.0,
            status = "COMPLETED"
        )
        val saleItems = listOf(
            SaleItemInput(
                productId = redmiId,
                productName = "Redmi Note 13",
                quantity = 1,
                unitPrice = 22000.0,
                trackingType = "IMEI",
                selectedSerialImeiIds = listOf(inStockUnit.id),
                selectedSerialsOrImeis = listOf("860123456789001")
            )
        )

        val saleId = saleRepository.createSale(saleDraft, saleItems).getOrThrow()

        // Verify status is SOLD and Karim balance = 12,000
        val soldUnit = database.productDao().getSerialById(inStockUnit.id)
        assertEquals("SOLD", soldUnit?.status)
        assertEquals(12000.0, customerRepository.getAuthoritativeCustomerBalance(karimId), 0.001)

        // 3. Attempt duplicate sale of same IMEI -> BLOCKED
        val duplicateAttempt = saleRepository.createSale(
            sale = saleDraft.copy(invoiceNumber = "INV-REDMI-DUP"),
            items = saleItems
        )
        assertTrue(duplicateAttempt.isFailure)

        // 4. Return defective unit
        val saleItemId = database.saleDao().getSaleItems(saleId).first().id
        val returnDraft = SalesReturnDraft(
            saleId = saleId,
            reason = "Defective screen",
            refundAmount = 0.0,
            customerCredit = 22000.0
        )
        val returnItems = listOf(
            SalesReturnItemDraft(
                saleItemId = saleItemId,
                productId = redmiId,
                productName = "Redmi Note 13",
                returnQuantity = 1,
                unitPrice = 22000.0,
                totalAmount = 22000.0,
                serialUnitId = inStockUnit.id,
                serialOrImei = "860123456789001",
                restockOption = "DEFECTIVE_DAMAGED"
            )
        )

        salesReturnRepository.createSalesReturn(returnDraft, returnItems).getOrThrow()

        // Unit status must be DAMAGED, not IN_STOCK
        val returnedUnit = database.productDao().getSerialById(inStockUnit.id)
        assertEquals("DAMAGED", returnedUnit?.status)

        // Sellable stock for Redmi Note 13 must still be 0 (since it was damaged, not restocked to sellable)
        assertEquals(0, database.productDao().getProductById(redmiId)?.currentStock)

        // Karim's balance: was 12,000 due, returned 22,000 credit -> Karim now has -10,000 advance
        assertEquals(-10000.0, customerRepository.getAuthoritativeCustomerBalance(karimId), 0.001)
    }
}
