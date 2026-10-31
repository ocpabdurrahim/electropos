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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import com.example.ui.screens.ReportsScreen
import com.example.ui.viewmodels.ReportsViewModel
import java.util.Calendar
import java.util.TimeZone

/** Numbers on the Reports screen: sales, returns, tax, discounts, expenses, net profit, periods and the CSV. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReportsTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var database: AppDatabase
    private lateinit var supplierRepository: SupplierRepository
    private lateinit var purchaseRepository: PurchaseRepository
    private lateinit var customerRepository: CustomerRepository
    private lateinit var saleRepository: SaleRepository
    private lateinit var salesReturnRepository: SalesReturnRepository
    private lateinit var reports: ReportsRepository

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        supplierRepository = SupplierRepository(database)
        purchaseRepository = PurchaseRepository(database)
        customerRepository = CustomerRepository(database)
        saleRepository = SaleRepository(database)
        salesReturnRepository = SalesReturnRepository(database)
        reports = ReportsRepository(database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    // ---------------------------------------------------------------- helpers

    /** Local time; month is 1-12. */
    private fun at(year: Int, month: Int, day: Int, hour: Int = 0, minute: Int = 0, second: Int = 0, ms: Int = 0): Long =
        Calendar.getInstance().apply {
            clear()
            set(year, month - 1, day, hour, minute, second)
            set(Calendar.MILLISECOND, ms)
        }.timeInMillis

    private suspend fun supplier(name: String = "Vendor", phone: String = "01700000001"): Long =
        supplierRepository.saveSupplier(SupplierEntity(name = name, phone = phone)).getOrThrow()

    private suspend fun customer(name: String, phone: String): Long =
        customerRepository.saveCustomer(CustomerEntity(name = name, phone = phone)).getOrThrow()

    private suspend fun product(name: String, price: Double = 0.0): Long =
        database.productDao().insertProduct(ProductEntity(name = name, retailPrice = price))

    private suspend fun buy(supplierId: Long, productId: Long, qty: Int, unitCost: Double, paid: Double = 0.0, date: Long = System.currentTimeMillis()): Long {
        val p = database.productDao().getProductById(productId)!!
        return purchaseRepository.createPurchase(
            PurchaseEntity(supplierId = supplierId, paidAmount = paid, purchaseDate = date),
            listOf(PurchaseItemDraft(productId = productId, productName = p.name, quantity = qty, unitCost = unitCost, trackingType = p.trackingType))
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
        method: String = "CASH",
        date: Long = System.currentTimeMillis()
    ): Long {
        val p = database.productDao().getProductById(productId)!!
        val total = qty * unitPrice - lineDiscount + lineTax - invoiceDiscount
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
                discountAmount = lineDiscount + invoiceDiscount,
                taxAmount = lineTax,
                totalAmount = total,
                paidAmount = paidAmount,
                dueAmount = total - paidAmount,
                paymentMethod = method
            ),
            listOf(
                SaleItemInput(
                    productId = productId,
                    productName = p.name,
                    quantity = qty,
                    unitPrice = unitPrice,
                    discount = lineDiscount,
                    tax = lineTax,
                    trackingType = p.trackingType
                )
            )
        ).getOrThrow()
    }

    private suspend fun returnOne(saleId: Long, productId: Long, date: Long, restock: String = "RESTOCK_SELLABLE"): Long {
        val item = saleRepository.getSaleItems(saleId).single()
        return salesReturnRepository.createSalesReturn(
            SalesReturnDraft(saleId = saleId, returnDate = date),
            listOf(SalesReturnItemDraft(item.id, productId, item.productName, returnQuantity = 1, unitPrice = 0.0, totalAmount = 0.0, restockOption = restock))
        ).getOrThrow()
    }

    private suspend fun expense(category: String, amount: Double, date: Long) {
        database.financeDao().insertExpense(ExpenseEntity(categoryName = category, title = category, amount = amount, expenseDate = date))
    }

    private fun day(year: Int, month: Int, dayOfMonth: Int) =
        ReportRanges.forPeriod(ReportPeriod.TODAY, now = at(year, month, dayOfMonth, 13))

    // ---------------------------------------------------------------- numbers

    @Test
    fun netProfit_coversTaxDiscountReturnAndExpense() = runBlocking {
        val s = supplier()
        val speaker = product("BT speaker", price = 1000.0)
        val cable = product("USB cable", price = 500.0)
        buy(s, speaker, 10, 600.0, paid = 6000.0, date = at(2026, 10, 15, 9))
        buy(s, cable, 10, 200.0, paid = 0.0, date = at(2026, 10, 15, 9))
        val rahim = customer("Rahim", "01811000001")

        // 2 x 1,000 - 100 line discount + 150 tax - 50 invoice discount = 2,000; profit 2,000 - 150 - 1,200 = 650
        val first = sell(speaker, 2, 1000.0, lineDiscount = 100.0, lineTax = 150.0, invoiceDiscount = 50.0, date = at(2026, 10, 15, 11))
        // 3 x 500 = 1,500, 500 paid now; profit 1,500 - 600 = 900
        sell(cable, 3, 500.0, customerId = rahim, paid = 500.0, date = at(2026, 10, 15, 12))
        // One speaker back on the shelf: 1,000 refunded (73.17 of it tax), profit given back 1,000 - 73.17 - 600 = 326.83
        returnOne(first, speaker, date = at(2026, 10, 15, 15))
        expense("Rent", 300.0, at(2026, 10, 15, 18))
        expense("Electricity", 100.0, at(2026, 10, 15, 19))

        val report = reports.loadReport(day(2026, 10, 15))

        val sales = report.sales
        assertEquals(2, sales.invoiceCount)
        assertEquals(3500.0, sales.grossSales, 0.01)
        assertEquals(1, sales.returnCount)
        assertEquals(1000.0, sales.returns, 0.01)
        assertEquals(2500.0, sales.netSales, 0.01)
        assertEquals(150.0, sales.discounts, 0.01)
        assertEquals(150.0, sales.taxOnSales, 0.01)
        assertEquals(73.17, sales.taxReturned, 0.01)
        assertEquals(76.83, sales.taxCollected, 0.01)
        assertEquals(1000.0, sales.dueCreated, 0.01)
        assertEquals(1000.0, sales.stillUnpaid, 0.01)
        assertEquals(1000.0, sales.refundsToCustomers, 0.01)

        val profit = report.profit
        assertEquals(1550.0, profit.saleProfit, 0.01)
        assertEquals(326.83, profit.returnedProfit, 0.01)
        assertEquals(1223.17, profit.grossProfit, 0.01)
        assertEquals(400.0, profit.expenses, 0.01)
        assertEquals(823.17, profit.netProfit, 0.01)
        assertEquals(listOf("Rent", "Electricity"), profit.expensesByCategory.map { it.label })

        // Same rule as the dashboard
        val dashboardProfit = (database.saleDao().getTotalProfitBetween(report.range.start, report.range.end).first() ?: 0.0) -
            database.salesReturnDao().getReturnedProfitBetween(report.range.start, report.range.end).first()
        assertEquals(dashboardProfit, profit.grossProfit, 0.01)

        val purchases = report.purchases
        assertEquals(2, purchases.purchaseCount)
        assertEquals(8000.0, purchases.purchased, 0.01)
        assertEquals(6000.0, purchases.paid, 0.01)
        assertEquals(2000.0, purchases.owed, 0.01)

        // The day row adds up to the period totals
        val row = report.daily.single()
        assertEquals(2, row.invoices)
        assertEquals(2500.0, row.netSales, 0.01)
        assertEquals(76.83, row.tax, 0.01)
        assertEquals(1223.17, row.grossProfit, 0.01)
        assertEquals(823.17, row.netProfit, 0.01)
        assertTrue(report.chart.isEmpty()) // single day: no chart

        // Cable 3 sold; speaker 2 sold - 1 returned
        assertEquals(listOf("USB cable" to 3, "BT speaker" to 1), report.topByQuantity.map { it.name to it.quantity })
        assertEquals(listOf("USB cable", "BT speaker"), report.topByProfit.map { it.name })
        val speakerRow = report.topByProfit.first { it.productId == speaker }
        assertEquals(1000.0, speakerRow.netSales, 0.01)
        assertEquals(650.0 - 326.83, speakerRow.profit, 0.01)
        assertEquals(900.0, report.topByProfit.first { it.productId == cable }.profit, 0.01)
    }

    @Test
    fun damagedReturn_givesBackTheWholeValueExclTax() = runBlocking {
        val s = supplier()
        val charger = product("Charger", price = 1000.0)
        buy(s, charger, 5, 600.0, paid = 3000.0, date = at(2026, 5, 2, 9))
        val saleId = sell(charger, 1, 1000.0, date = at(2026, 5, 2, 10))
        returnOne(saleId, charger, date = at(2026, 5, 2, 11), restock = "DEFECTIVE_DAMAGED")

        val report = reports.loadReport(day(2026, 5, 2))
        // Sold for 1,000 (profit 400), all 1,000 refunded and the unit is lost: -600
        assertEquals(1000.0, report.profit.returnedProfit, 0.01)
        assertEquals(-600.0, report.profit.grossProfit, 0.01)
        assertEquals(0.0, report.sales.netSales, 0.01)
        assertEquals(-600.0, report.topByProfit.single().profit, 0.01)
        assertTrue(report.topByQuantity.isEmpty()) // nothing left sold
    }

    @Test
    fun cancelledSale_isLeftOut() = runBlocking {
        val s = supplier()
        val ssd = product("SSD 512GB", price = 1000.0)
        buy(s, ssd, 5, 600.0, paid = 3000.0, date = at(2026, 7, 1, 9))
        sell(ssd, 1, 1000.0, date = at(2026, 7, 1, 10))
        val cancelled = sell(ssd, 2, 1000.0, date = at(2026, 7, 1, 11))
        saleRepository.cancelSale(cancelled, "Entered by mistake").getOrThrow()

        val report = reports.loadReport(day(2026, 7, 1))
        assertEquals(1, report.sales.invoiceCount)
        assertEquals(1000.0, report.sales.grossSales, 0.01)
        assertEquals(400.0, report.profit.grossProfit, 0.01)
        assertEquals(1, report.topByQuantity.single().quantity)
        assertEquals(1, report.daily.single().invoices)
    }

    @Test
    fun dateBoundaries_areInclusiveWholeDays() = runBlocking {
        val s = supplier()
        val cover = product("Phone cover", price = 100.0)
        buy(s, cover, 10, 50.0, paid = 500.0, date = at(2026, 3, 1, 9))
        sell(cover, 1, 100.0, date = at(2026, 3, 9, 23, 59, 59, 999)) // day before: out
        sell(cover, 1, 100.0, date = at(2026, 3, 10, 0, 0, 0, 0))     // first ms: in
        sell(cover, 1, 100.0, date = at(2026, 3, 10, 23, 59, 59, 999)) // last ms: in
        sell(cover, 1, 100.0, date = at(2026, 3, 11, 0, 0, 0, 0))     // next day: out
        expense("Tea", 10.0, at(2026, 3, 10, 0, 0, 0, 0))
        expense("Tea", 20.0, at(2026, 3, 11, 0, 0, 0, 0))

        val yesterday = ReportRanges.forPeriod(ReportPeriod.YESTERDAY, now = at(2026, 3, 11, 8))
        assertEquals(at(2026, 3, 10), yesterday.start)
        assertEquals(at(2026, 3, 10, 23, 59, 59, 999), yesterday.end)
        assertEquals(yesterday.start to yesterday.end, day(2026, 3, 10).let { it.start to it.end })
        assertEquals(yesterday.start to yesterday.end, ReportRanges.custom(at(2026, 3, 10, 5), at(2026, 3, 10, 6)).let { it.start to it.end })

        val oneDay = reports.loadReport(yesterday)
        assertEquals(2, oneDay.sales.invoiceCount)
        assertEquals(200.0, oneDay.sales.grossSales, 0.01)
        assertEquals(10.0, oneDay.profit.expenses, 0.01)
        assertEquals(90.0, oneDay.profit.netProfit, 0.01)

        // Three days, picked backwards: one row per day
        val threeDays = reports.loadReport(ReportRanges.custom(at(2026, 3, 11, 12), at(2026, 3, 9, 12)))
        assertEquals(listOf(1, 2, 1), threeDays.daily.map { it.invoices })
        assertEquals(4, threeDays.sales.invoiceCount)
        assertEquals(ChartGrouping.DAY, threeDays.range.grouping)
        assertEquals(3, threeDays.chart.size)

        // The whole month: 31 rows and bars that add up to the totals
        val month = reports.loadReport(ReportRanges.forPeriod(ReportPeriod.THIS_MONTH, now = at(2026, 3, 20, 10)))
        assertEquals(31, month.daily.size)
        assertEquals(31, month.chart.size)
        assertEquals(4, month.sales.invoiceCount)
        assertEquals(30.0, month.profit.expenses, 0.01)
        assertEquals(month.sales.netSales, month.chart.sumOf { it.sales }, 0.01)
        assertEquals(month.profit.grossProfit, month.chart.sumOf { it.profit }, 0.01)
        assertEquals(month.profit.netProfit, month.daily.sumOf { it.netProfit }, 0.01)
        // The purchase was on 1 March
        assertEquals(500.0, month.purchases.purchased, 0.01)
        assertEquals(0.0, oneDay.purchases.purchased, 0.01)
    }

    @Test
    fun periods_coverWholeWeeksMonthsAndYears() {
        val now = at(2026, 10, 31, 14)
        for (firstDay in listOf(Calendar.SATURDAY, Calendar.SUNDAY, Calendar.MONDAY)) {
            val week = ReportRanges.forPeriod(ReportPeriod.THIS_WEEK, now = now, firstDayOfWeek = firstDay)
            val startCal = Calendar.getInstance().apply { timeInMillis = week.start }
            assertEquals(firstDay, startCal.get(Calendar.DAY_OF_WEEK))
            assertTrue(now in week.start..week.end)
            assertEquals(7, ReportRanges.dayStarts(week.start, week.end).size)
        }

        val lastMonth = ReportRanges.forPeriod(ReportPeriod.LAST_MONTH, now = at(2026, 3, 31, 10))
        assertEquals(at(2026, 2, 1), lastMonth.start)
        assertEquals(at(2026, 2, 28, 23, 59, 59, 999), lastMonth.end)

        val december = ReportRanges.forPeriod(ReportPeriod.LAST_MONTH, now = at(2026, 1, 15, 10))
        assertEquals(at(2025, 12, 1), december.start)
        assertEquals(at(2025, 12, 31, 23, 59, 59, 999), december.end)

        val year = ReportRanges.forPeriod(ReportPeriod.THIS_YEAR, now = now)
        assertEquals(at(2026, 1, 1), year.start)
        assertEquals(at(2026, 12, 31, 23, 59, 59, 999), year.end)
        assertEquals(ChartGrouping.MONTH, year.grouping)
        val daily = ReportsRepository.buildDailyRows(year, emptyList(), emptyList(), emptyList())
        assertEquals(365, daily.size)
        assertEquals(12, ReportsRepository.buildChart(year, daily).size)

        assertEquals(ChartGrouping.NONE, ReportRanges.forPeriod(ReportPeriod.TODAY, now = now).grouping)
        assertEquals(ChartGrouping.NONE, ReportRanges.custom(now, now).grouping)
        assertEquals(ChartGrouping.MONTH, ReportRanges.custom(at(2026, 1, 1), at(2026, 6, 30)).grouping)

        // DateRangePicker dates are UTC midnight
        val utcMidnight = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { clear(); set(2026, 9, 15) }.timeInMillis
        assertEquals(utcMidnight, ReportRanges.toPickerUtc(at(2026, 10, 15, 23, 30)))
        assertEquals(at(2026, 10, 15), ReportRanges.fromPickerUtc(utcMidnight))
    }

    // ---------------------------------------------------------------- money in, dues, stock

    @Test
    fun moneyReceived_dues_andStock() = runBlocking {
        val s = supplier("Vendor A", "01700000001")
        val s2 = supplier("Vendor B", "01700000002")
        val phone = product("Phone", price = 1000.0)
        val cable = product("Cable", price = 200.0)
        product("Earphone") // never bought: out of stock
        buy(s, phone, 10, 600.0, paid = 6000.0)
        buy(s2, cable, 5, 100.0, paid = 0.0)
        val karim = customer("Karim", "01811000002")

        sell(phone, 1, 1000.0)                                             // cash 1,000
        sell(phone, 2, 1000.0, customerId = karim, paid = 500.0, method = "BKASH") // bKash 500, due 1,500
        customerRepository.recordCustomerPayment(karim, 300.0, "NAGAD").getOrThrow()

        val report = reports.loadReport(ReportRanges.forPeriod(ReportPeriod.TODAY))
        val received = report.sales.receivedByMethod.associate { it.label to it.amount }
        assertEquals(1000.0, received["Cash"]!!, 0.01)
        assertEquals(500.0, received["bKash"]!!, 0.01)
        assertEquals(300.0, received["Nagad"]!!, 0.01)
        assertEquals(1800.0, report.sales.moneyReceived, 0.01)
        assertEquals(1500.0, report.sales.receivedAtSale, 0.01)
        assertEquals(300.0, report.sales.dueCollected, 0.01)
        assertEquals(1500.0, report.sales.dueCreated, 0.01)
        assertEquals(1200.0, report.sales.stillUnpaid, 0.01)

        assertEquals(1200.0, report.dues.customersOwe, 0.01)
        assertEquals(1, report.dues.customersOwingCount)
        assertEquals("Karim", report.dues.topCustomers.single().name)
        assertEquals(500.0, report.dues.youOweSuppliers, 0.01)
        assertEquals("Vendor B", report.dues.topSuppliers.single().name)

        // 7 phones x 600 + 5 cables x 100
        assertEquals(4700.0, report.stock.stockValue, 0.01)
        assertEquals(1, report.stock.lowStockCount)   // cables: 5 left, minimum 5
        assertEquals(1, report.stock.outOfStockCount) // earphone
        assertEquals(3, report.stock.productCount)
    }

    // ---------------------------------------------------------------- CSV

    @Test
    fun csv_hasBomSummaryAndDailyRows() = runBlocking {
        val s = supplier()
        val cover = product("Cover", price = 100.0)
        buy(s, cover, 10, 50.0, paid = 500.0, date = at(2026, 4, 1, 9))
        sell(cover, 2, 100.0, date = at(2026, 4, 2, 10))
        expense("ভাড়া", 30.0, at(2026, 4, 2, 11))           // Bangla category
        expense("Tea, snacks", 5.0, at(2026, 4, 3, 11))      // needs quoting
        expense("=HYPERLINK(1)", 1.0, at(2026, 4, 3, 12))   // must not become a formula

        val report = reports.loadReport(ReportRanges.custom(at(2026, 4, 1), at(2026, 4, 3)))
        val bytes = ReportsRepository.csvBytes(ReportsRepository.buildCsv(report))
        assertEquals(0xEF.toByte(), bytes[0])
        assertEquals(0xBB.toByte(), bytes[1])
        assertEquals(0xBF.toByte(), bytes[2])

        val csv = String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        val lines = csv.split("\r\n")
        assertTrue(csv.contains("Expenses,ভাড়া,30.00"))
        assertTrue(csv.contains("Expenses,\"Tea, snacks\",5.00"))
        assertTrue(csv.contains("Expenses,'=HYPERLINK(1),1.00"))
        assertTrue(csv.contains("Profit,Net profit,64.00")) // 200 - 100 cost - 36 expenses
        assertTrue(csv.contains("Sales,Net sales,200.00"))

        val header = lines.indexOfFirst { it.startsWith("Date,Invoices,") }
        assertTrue(header > 0)
        assertEquals("2026-04-01,0,0.00,0.00,0.00,0.00,0.00,0.00,0.00,0.00", lines[header + 1])
        assertEquals("2026-04-02,1,200.00,0.00,200.00,0.00,0.00,100.00,30.00,70.00", lines[header + 2])
        assertEquals("2026-04-03,0,0.00,0.00,0.00,0.00,0.00,0.00,6.00,-6.00", lines[header + 3])
        assertTrue(lines[header + 4].startsWith("Total,1,200.00,"))
        assertTrue(lines[header + 4].endsWith(",36.00,64.00"))
        assertEquals("report_20260401_20260403.csv", ReportsRepository.suggestedFileName(report.range))
    }

    // ---------------------------------------------------------------- screen

    /** The progress bar animates forever while loading, so the main looper is run by hand until the load lands. */
    private fun waitForReport(period: ReportPeriod, viewModel: ReportsViewModel) {
        compose.mainClock.autoAdvance = false
        compose.waitUntil(10_000) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            val state = viewModel.uiState.value
            state.report?.range?.period == period && !state.isLoading
        }
        compose.mainClock.autoAdvance = true
    }

    @Test
    fun screen_showsEverySection_andSwitchesPeriods() {
        runBlocking {
            val s = supplier()
            val cover = product("Phone cover", price = 500.0)
            buy(s, cover, 10, 200.0, paid = 2000.0)
            sell(cover, 2, 500.0)
            expense("Rent", 100.0, System.currentTimeMillis())
        }
        val viewModel = ReportsViewModel(reports)
        compose.setContent { MaterialTheme { ReportsScreen(viewModel = viewModel, onNavigateBack = {}) } }
        compose.waitUntil(10_000) { viewModel.uiState.value.report != null && !viewModel.uiState.value.isLoading }

        assertEquals(ReportPeriod.THIS_MONTH, viewModel.uiState.value.period)
        assertEquals(500.0, viewModel.uiState.value.report!!.profit.netProfit, 0.01) // 1,000 - 400 cost - 100 rent
        val list = compose.onNodeWithTag("reports_list")
        for (text in listOf("Sales summary", "NET PROFIT", "Purchases", "Sales & profit", "Top products", "Receivables & payables", "Stock value at cost")) {
            list.performScrollToNode(hasText(text))
            compose.onNodeWithText(text).assertExists()
        }
        list.performScrollToNode(hasTestTag("reports_chart"))

        list.performScrollToNode(hasText("By profit"))
        compose.onNodeWithText("By profit").performClick()
        list.performScrollToNode(hasText("Phone cover"))
        compose.onNodeWithText("Phone cover").assertExists()

        list.performScrollToNode(hasTestTag("reports_period_this_year"))
        compose.onNodeWithTag("reports_period_this_year").performScrollTo().performClick()
        waitForReport(ReportPeriod.THIS_YEAR, viewModel)
        assertEquals(12, viewModel.uiState.value.report!!.chart.size)

        compose.onNodeWithTag("reports_period_today").performScrollTo().performClick()
        waitForReport(ReportPeriod.TODAY, viewModel)
        list.performScrollToNode(hasText("Pick a week, month or year", substring = true))

        list.performScrollToNode(hasTestTag("reports_period_custom"))
        compose.onNodeWithTag("reports_period_custom").performScrollTo().performClick()
        compose.onNodeWithText("Apply").assertExists()
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(ReportPeriod.TODAY, viewModel.uiState.value.period)
    }
}
