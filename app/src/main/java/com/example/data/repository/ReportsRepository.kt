package com.example.data.repository

import androidx.room.withTransaction
import com.example.core.utils.DateTimeUtils
import com.example.data.local.database.AppDatabase
import com.example.data.local.dao.DatedAmountRow
import com.example.data.local.dao.TopProductRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs

enum class ReportPeriod(val label: String) {
    TODAY("Today"),
    YESTERDAY("Yesterday"),
    THIS_WEEK("This week"),
    THIS_MONTH("This month"),
    LAST_MONTH("Last month"),
    THIS_YEAR("This year"),
    CUSTOM("Custom range")
}

/** How the chart groups the period: no chart for a single day, bars per day, or bars per month. */
enum class ChartGrouping { NONE, DAY, MONTH }

/** A report period in local time; [end] is the last millisecond and is included (BETWEEN). */
data class ReportRange(
    val period: ReportPeriod,
    val start: Long,
    val end: Long,
    val grouping: ChartGrouping
) {
    val isSingleDay: Boolean get() = DateTimeUtils.getStartOfDay(start) == DateTimeUtils.getStartOfDay(end)

    /** "31 Oct 2026", "01 Oct - 31 Oct 2026" or "15 Dec 2025 - 10 Jan 2026". */
    val dateLabel: String
        get() {
            if (isSingleDay) return DateTimeUtils.formatDate(start, "dd MMM yyyy")
            val sameYear = DateTimeUtils.formatDate(start, "yyyy") == DateTimeUtils.formatDate(end, "yyyy")
            val from = DateTimeUtils.formatDate(start, if (sameYear) "dd MMM" else "dd MMM yyyy")
            return "$from - ${DateTimeUtils.formatDate(end, "dd MMM yyyy")}"
        }

    val label: String get() = "${period.label} · $dateLabel"
}

data class AmountByLabel(val label: String, val amount: Double)

data class SalesSummary(
    val invoiceCount: Int = 0,
    /** Invoice totals including tax. */
    val grossSales: Double = 0.0,
    val returnCount: Int = 0,
    /** Value refunded or credited on returns made in the period (tax included). */
    val returns: Double = 0.0,
    val discounts: Double = 0.0,
    val taxOnSales: Double = 0.0,
    val taxReturned: Double = 0.0,
    /** Payments at the counter plus due collections, per payment method. */
    val receivedByMethod: List<AmountByLabel> = emptyList(),
    val receivedAtSale: Double = 0.0,
    val dueCollected: Double = 0.0,
    /** Money handed back to customers (returns and cancelled sales). */
    val refundsToCustomers: Double = 0.0,
    /** Part of the period's invoices that was not paid at the counter. */
    val dueCreated: Double = 0.0,
    /** Part of the period's invoices that is still unpaid now. */
    val stillUnpaid: Double = 0.0
) {
    val netSales: Double get() = grossSales - returns
    val taxCollected: Double get() = taxOnSales - taxReturned
    val moneyReceived: Double get() = receivedByMethod.sumOf { it.amount }
    val netMoneyIn: Double get() = moneyReceived - refundsToCustomers
}

data class ProfitSummary(
    /** Profit on the period's sales: total - tax - cost of goods. */
    val saleProfit: Double = 0.0,
    /** Profit given back by the period's returns. */
    val returnedProfit: Double = 0.0,
    val expenses: Double = 0.0,
    val expensesByCategory: List<AmountByLabel> = emptyList()
) {
    val grossProfit: Double get() = saleProfit - returnedProfit
    val netProfit: Double get() = grossProfit - expenses
}

data class PurchaseSummary(
    val purchaseCount: Int = 0,
    val purchased: Double = 0.0,
    val returns: Double = 0.0,
    val returnRefunds: Double = 0.0,
    /** Paid so far on the period's purchases. */
    val paid: Double = 0.0,
    /** Still owed on the period's purchases. */
    val owed: Double = 0.0
) {
    val netPurchased: Double get() = purchased - returns
}

data class DailyRow(
    val dayStart: Long,
    val invoices: Int = 0,
    val grossSales: Double = 0.0,
    val returns: Double = 0.0,
    val discounts: Double = 0.0,
    /** Tax on sales less tax returned. */
    val tax: Double = 0.0,
    val grossProfit: Double = 0.0,
    val expenses: Double = 0.0
) {
    val netSales: Double get() = grossSales - returns
    val netProfit: Double get() = grossProfit - expenses
}

/** One bar pair: net sales and gross profit (both after returns). */
data class ChartPoint(
    val label: String,
    val fullLabel: String,
    val start: Long,
    val sales: Double,
    val profit: Double
)

data class TopProduct(
    val productId: Long,
    val name: String,
    val quantity: Int,
    val netSales: Double,
    val profit: Double
)

data class PartyDue(val id: Long, val name: String, val phone: String, val due: Double)

data class DuesSummary(
    val customersOwe: Double = 0.0,
    val customerAdvance: Double = 0.0,
    val customersOwingCount: Int = 0,
    val topCustomers: List<PartyDue> = emptyList(),
    val youOweSuppliers: Double = 0.0,
    val supplierAdvance: Double = 0.0,
    val suppliersOwedCount: Int = 0,
    val topSuppliers: List<PartyDue> = emptyList()
)

data class StockSummary(
    val stockValue: Double = 0.0,
    val lowStockCount: Int = 0,
    val outOfStockCount: Int = 0,
    val productCount: Int = 0
)

data class ReportData(
    val range: ReportRange,
    val shopName: String = "",
    val currencySymbol: String = "৳",
    val generatedAt: Long = System.currentTimeMillis(),
    val sales: SalesSummary = SalesSummary(),
    val profit: ProfitSummary = ProfitSummary(),
    val purchases: PurchaseSummary = PurchaseSummary(),
    /** Every day of the period, including days without business. */
    val daily: List<DailyRow> = emptyList(),
    /** Bars for the chart; empty when [ReportRange.grouping] is NONE. */
    val chart: List<ChartPoint> = emptyList(),
    val topByQuantity: List<TopProduct> = emptyList(),
    val topByProfit: List<TopProduct> = emptyList(),
    val dues: DuesSummary = DuesSummary(),
    val stock: StockSummary = StockSummary()
)

/** Builds report periods in the device's local time. */
object ReportRanges {
    /** Custom ranges up to this many days get one bar per day, longer ones one bar per month. */
    const val MAX_DAILY_BARS = 62

    fun forPeriod(
        period: ReportPeriod,
        now: Long = System.currentTimeMillis(),
        customStart: Long? = null,
        customEnd: Long? = null,
        firstDayOfWeek: Int = Calendar.getInstance().firstDayOfWeek
    ): ReportRange {
        val cal = Calendar.getInstance().apply { timeInMillis = DateTimeUtils.getStartOfDay(now) }
        return when (period) {
            ReportPeriod.TODAY ->
                ReportRange(period, cal.timeInMillis, DateTimeUtils.getEndOfDay(now), ChartGrouping.NONE)
            ReportPeriod.YESTERDAY -> {
                cal.add(Calendar.DAY_OF_MONTH, -1)
                ReportRange(period, cal.timeInMillis, DateTimeUtils.getEndOfDay(cal.timeInMillis), ChartGrouping.NONE)
            }
            ReportPeriod.THIS_WEEK -> {
                while (cal.get(Calendar.DAY_OF_WEEK) != firstDayOfWeek) cal.add(Calendar.DAY_OF_MONTH, -1)
                val start = cal.timeInMillis
                cal.add(Calendar.DAY_OF_MONTH, 6)
                ReportRange(period, start, DateTimeUtils.getEndOfDay(cal.timeInMillis), ChartGrouping.DAY)
            }
            ReportPeriod.THIS_MONTH -> monthRange(period, cal)
            ReportPeriod.LAST_MONTH -> {
                cal.set(Calendar.DAY_OF_MONTH, 1)
                cal.add(Calendar.MONTH, -1)
                monthRange(period, cal)
            }
            ReportPeriod.THIS_YEAR -> {
                cal.set(Calendar.DAY_OF_YEAR, 1)
                val start = cal.timeInMillis
                cal.add(Calendar.YEAR, 1)
                cal.add(Calendar.DAY_OF_MONTH, -1)
                ReportRange(period, start, DateTimeUtils.getEndOfDay(cal.timeInMillis), ChartGrouping.MONTH)
            }
            ReportPeriod.CUSTOM -> custom(customStart ?: now, customEnd ?: customStart ?: now)
        }
    }

    /** Whole days from the day of [from] to the day of [to] (swapped if given backwards). */
    fun custom(from: Long, to: Long): ReportRange {
        val start = DateTimeUtils.getStartOfDay(minOf(from, to))
        val end = DateTimeUtils.getEndOfDay(maxOf(from, to))
        val days = dayStarts(start, end).size
        val grouping = when {
            days <= 1 -> ChartGrouping.NONE
            days <= MAX_DAILY_BARS -> ChartGrouping.DAY
            else -> ChartGrouping.MONTH
        }
        return ReportRange(ReportPeriod.CUSTOM, start, end, grouping)
    }

    private fun monthRange(period: ReportPeriod, cal: Calendar): ReportRange {
        cal.set(Calendar.DAY_OF_MONTH, 1)
        val start = cal.timeInMillis
        cal.set(Calendar.DAY_OF_MONTH, cal.getActualMaximum(Calendar.DAY_OF_MONTH))
        return ReportRange(period, start, DateTimeUtils.getEndOfDay(cal.timeInMillis), ChartGrouping.DAY)
    }

    /** Local start of every day from [start] to [end]. */
    fun dayStarts(start: Long, end: Long): List<Long> {
        val result = ArrayList<Long>()
        val cal = Calendar.getInstance().apply { timeInMillis = DateTimeUtils.getStartOfDay(start) }
        while (cal.timeInMillis <= end) {
            result.add(cal.timeInMillis)
            cal.add(Calendar.DAY_OF_MONTH, 1)
        }
        return result
    }

    /** The Material date pickers give UTC midnight of the picked date; this is local midnight of that same date. */
    fun fromPickerUtc(utcMillis: Long): Long {
        val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = utcMillis }
        return Calendar.getInstance().apply {
            clear()
            set(utc.get(Calendar.YEAR), utc.get(Calendar.MONTH), utc.get(Calendar.DAY_OF_MONTH), 0, 0, 0)
        }.timeInMillis
    }

    /** Local time to UTC midnight of the same date, for preselecting a date in the Material pickers. */
    fun toPickerUtc(localMillis: Long): Long {
        val local = Calendar.getInstance().apply { timeInMillis = localMillis }
        return Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(local.get(Calendar.YEAR), local.get(Calendar.MONTH), local.get(Calendar.DAY_OF_MONTH), 0, 0, 0)
        }.timeInMillis
    }
}

/**
 * Numbers for the Reports screen. Business rules (same as the dashboard):
 * net sales = sales - returns (tax included); gross profit = profit on sales (excl. tax) - profit given back
 * by returns; net profit = gross profit - expenses. Cancelled/void sales are left out.
 */
class ReportsRepository(private val database: AppDatabase) {
    private val dao = database.reportsDao()

    suspend fun loadReport(range: ReportRange, topLimit: Int = 10, dueLimit: Int = 5): ReportData = withContext(Dispatchers.IO) {
        // One read transaction so every section comes from the same moment
        database.withTransaction {
            val start = range.start
            val end = range.end

            val saleTotals = dao.getSalesTotals(start, end)
            val returnTotals = dao.getReturnTotals(start, end)
            val sales = SalesSummary(
                invoiceCount = saleTotals.invoiceCount,
                grossSales = saleTotals.grossSales,
                returnCount = returnTotals.returnCount,
                returns = returnTotals.returnsValue,
                discounts = saleTotals.discounts,
                taxOnSales = saleTotals.taxOnSales,
                taxReturned = returnTotals.taxReturned,
                receivedByMethod = dao.getMoneyReceivedByMethod(start, end)
                    .filter { abs(it.amount) > 0.004 }
                    .map { AmountByLabel(methodLabel(it.label), it.amount) },
                receivedAtSale = dao.getMoneyInOfType("SALE_CASH", start, end),
                dueCollected = dao.getMoneyInOfType("CUSTOMER_COLLECTION", start, end),
                refundsToCustomers = dao.getRefundsToCustomers(start, end),
                dueCreated = saleTotals.dueCreated,
                stillUnpaid = saleTotals.stillUnpaid
            )

            val profit = ProfitSummary(
                saleProfit = saleTotals.saleProfit,
                returnedProfit = returnTotals.returnedProfit,
                expenses = dao.getExpenseTotal(start, end),
                expensesByCategory = dao.getExpensesByCategory(start, end).map { AmountByLabel(it.label, it.amount) }
            )

            val purchaseTotals = dao.getPurchaseTotals(start, end)
            val purchases = PurchaseSummary(
                purchaseCount = purchaseTotals.purchaseCount,
                purchased = purchaseTotals.purchased,
                returns = purchaseTotals.returnsValue,
                returnRefunds = purchaseTotals.returnRefunds,
                paid = purchaseTotals.paid,
                owed = purchaseTotals.owed
            )

            val daily = buildDailyRows(
                range,
                dao.getSaleRows(start, end),
                dao.getReturnRows(start, end),
                dao.getExpenseRows(start, end)
            )

            val products = dao.getProductSales(start, end).map { it.toTopProduct() }
            val topByQuantity = products.filter { it.quantity > 0 }
                .sortedWith(compareByDescending<TopProduct> { it.quantity }.thenByDescending { it.profit }.thenBy { it.name })
                .take(topLimit)
            val topByProfit = products.filter { it.quantity > 0 || abs(it.profit) > 0.004 }
                .sortedWith(compareByDescending<TopProduct> { it.profit }.thenByDescending { it.quantity }.thenBy { it.name })
                .take(topLimit)

            val customerTotals = dao.getCustomerDueTotals()
            val supplierTotals = dao.getSupplierDueTotals()
            val dues = DuesSummary(
                customersOwe = customerTotals.owed,
                customerAdvance = customerTotals.advance,
                customersOwingCount = customerTotals.partyCount,
                topCustomers = dao.getTopCustomerDues(dueLimit).map { PartyDue(it.id, it.name, it.phone, it.due) },
                youOweSuppliers = supplierTotals.owed,
                supplierAdvance = supplierTotals.advance,
                suppliersOwedCount = supplierTotals.partyCount,
                topSuppliers = dao.getTopSupplierDues(dueLimit).map { PartyDue(it.id, it.name, it.phone, it.due) }
            )

            val stockRow = dao.getStockTotals()
            val profile = database.settingsAndAuditDao().getShopProfileSync()

            ReportData(
                range = range,
                shopName = profile?.shopName.orEmpty(),
                currencySymbol = profile?.currencySymbol?.ifBlank { null } ?: "৳",
                generatedAt = System.currentTimeMillis(),
                sales = sales,
                profit = profit,
                purchases = purchases,
                daily = daily,
                chart = buildChart(range, daily),
                topByQuantity = topByQuantity,
                topByProfit = topByProfit,
                dues = dues,
                stock = StockSummary(stockRow.stockValue, stockRow.lowStockCount, stockRow.outOfStockCount, stockRow.productCount)
            )
        }
    }

    private fun TopProductRow.toTopProduct() = TopProduct(productId, productName, quantity, netSales, profit)

    companion object {
        fun methodLabel(method: String): String = when (method.uppercase(Locale.US)) {
            "", "CASH" -> "Cash"
            "BKASH" -> "bKash"
            "NAGAD" -> "Nagad"
            "ROCKET" -> "Rocket"
            "CARD" -> "Card"
            "BANK" -> "Bank"
            "MULTIPLE" -> "Multiple"
            "OTHER" -> "Other"
            else -> method
        }

        /** Every day of the range, with that day's sales, returns and expenses. */
        fun buildDailyRows(
            range: ReportRange,
            saleRows: List<DatedAmountRow>,
            returnRows: List<DatedAmountRow>,
            expenseRows: List<DatedAmountRow>
        ): List<DailyRow> {
            val days = ReportRanges.dayStarts(range.start, range.end)
            val index = HashMap<Long, Int>(days.size * 2)
            days.forEachIndexed { i, day -> index[day] = i }

            val invoices = IntArray(days.size)
            val gross = DoubleArray(days.size)
            val returns = DoubleArray(days.size)
            val discounts = DoubleArray(days.size)
            val tax = DoubleArray(days.size)
            val profit = DoubleArray(days.size)
            val expenses = DoubleArray(days.size)

            fun slot(date: Long): Int? = index[DateTimeUtils.getStartOfDay(date)]

            for (row in saleRows) {
                val i = slot(row.date) ?: continue
                invoices[i]++
                gross[i] += row.amount
                discounts[i] += row.discount
                tax[i] += row.tax
                profit[i] += row.profit
            }
            for (row in returnRows) {
                val i = slot(row.date) ?: continue
                returns[i] += row.amount
                tax[i] -= row.tax
                profit[i] -= row.profit
            }
            for (row in expenseRows) {
                val i = slot(row.date) ?: continue
                expenses[i] += row.amount
            }

            return days.mapIndexed { i, day ->
                DailyRow(day, invoices[i], gross[i], returns[i], discounts[i], tax[i], profit[i], expenses[i])
            }
        }

        /** Bars per day (week, month, short custom range) or per month (year, long custom range). */
        fun buildChart(range: ReportRange, daily: List<DailyRow>): List<ChartPoint> = when (range.grouping) {
            ChartGrouping.NONE -> emptyList()
            ChartGrouping.DAY -> {
                val pattern = when (range.period) {
                    ReportPeriod.THIS_WEEK -> "EEE"
                    ReportPeriod.CUSTOM -> "d/M"
                    else -> "d"
                }
                daily.map {
                    ChartPoint(
                        label = DateTimeUtils.formatDate(it.dayStart, pattern),
                        fullLabel = DateTimeUtils.formatDate(it.dayStart, "EEE, dd MMM yyyy"),
                        start = it.dayStart,
                        sales = it.netSales,
                        profit = it.grossProfit
                    )
                }
            }
            ChartGrouping.MONTH -> {
                val multiYear = DateTimeUtils.formatDate(range.start, "yyyy") != DateTimeUtils.formatDate(range.end, "yyyy")
                daily.groupBy { DateTimeUtils.formatDate(it.dayStart, "yyyy-MM") }
                    .values
                    .map { days ->
                        val first = days.first().dayStart
                        ChartPoint(
                            label = DateTimeUtils.formatDate(first, if (multiYear) "MMM yy" else "MMM"),
                            fullLabel = DateTimeUtils.formatDate(first, "MMMM yyyy"),
                            start = first,
                            sales = days.sumOf { it.netSales },
                            profit = days.sumOf { it.grossProfit }
                        )
                    }
            }
        }

        // ------------------------------------------------------------ CSV

        private val UTF8_BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())

        /** UTF-8 with a byte order mark, so Excel shows Bangla names correctly. */
        fun csvBytes(csv: String): ByteArray = UTF8_BOM + csv.toByteArray(Charsets.UTF_8)

        fun suggestedFileName(range: ReportRange): String {
            val from = csvDate(range.start, "yyyyMMdd")
            val to = csvDate(range.end, "yyyyMMdd")
            return if (from == to) "report_$from.csv" else "report_${from}_$to.csv"
        }

        /** Period summary, then one row per day. Amounts are plain numbers (2 decimals) so Excel can add them up. */
        fun buildCsv(report: ReportData): String {
            val lines = ArrayList<String>()
            fun row(vararg cells: String) = lines.add(cells.joinToString(","))
            fun item(section: String, name: String, amount: Double) = row(text(section), text(name), num(amount))
            fun count(section: String, name: String, value: Int) = row(text(section), text(name), value.toString())

            val r = report.range
            row(text("Report"), text(report.shopName.ifBlank { "ElectroPOS" }))
            row(text("Period"), text(r.period.label), csvDate(r.start), csvDate(r.end))
            row(text("Generated"), csvDate(report.generatedAt, "yyyy-MM-dd HH:mm"))
            row(text("Currency"), text(report.currencySymbol))
            lines.add("")

            row(text("Section"), text("Item"), text("Amount"))
            val s = report.sales
            count("Sales", "Invoices", s.invoiceCount)
            item("Sales", "Gross sales (incl. tax)", s.grossSales)
            item("Sales", "Returns (${s.returnCount})", s.returns)
            item("Sales", "Net sales", s.netSales)
            item("Sales", "Discounts given", s.discounts)
            item("Sales", "Tax collected (after returns)", s.taxCollected)
            item("Sales", "Due created", s.dueCreated)
            item("Sales", "Still unpaid on these invoices", s.stillUnpaid)
            s.receivedByMethod.forEach { item("Money received", it.label, it.amount) }
            item("Money received", "Total received", s.moneyReceived)
            item("Money received", "Refunded to customers", s.refundsToCustomers)
            item("Money received", "Net money in", s.netMoneyIn)

            val p = report.profit
            item("Profit", "Profit on sales (excl. tax)", p.saleProfit)
            item("Profit", "Given back by returns", p.returnedProfit)
            item("Profit", "Gross profit", p.grossProfit)
            p.expensesByCategory.forEach { item("Expenses", it.label, it.amount) }
            item("Expenses", "Total expenses", p.expenses)
            item("Profit", "Net profit", p.netProfit)

            val pu = report.purchases
            count("Purchases", "Purchase invoices", pu.purchaseCount)
            item("Purchases", "Total purchased", pu.purchased)
            item("Purchases", "Purchase returns", pu.returns)
            item("Purchases", "Paid", pu.paid)
            item("Purchases", "Still owed", pu.owed)

            val d = report.dues
            item("Dues (now)", "Customers owe the shop", d.customersOwe)
            item("Dues (now)", "Shop owes suppliers", d.youOweSuppliers)
            item("Stock (now)", "Stock value at cost", report.stock.stockValue)
            count("Stock (now)", "Low stock products", report.stock.lowStockCount)
            count("Stock (now)", "Out of stock products", report.stock.outOfStockCount)
            lines.add("")

            row(
                text("Date"), text("Invoices"), text("Gross sales"), text("Returns"), text("Net sales"), text("Discounts"),
                text("Tax"), text("Gross profit"), text("Expenses"), text("Net profit")
            )
            for (day in report.daily) {
                row(
                    csvDate(day.dayStart), day.invoices.toString(), num(day.grossSales), num(day.returns),
                    num(day.netSales), num(day.discounts), num(day.tax), num(day.grossProfit), num(day.expenses), num(day.netProfit)
                )
            }
            val days = report.daily
            row(
                text("Total"), days.sumOf { it.invoices }.toString(), num(days.sumOf { it.grossSales }), num(days.sumOf { it.returns }),
                num(days.sumOf { it.netSales }), num(days.sumOf { it.discounts }), num(days.sumOf { it.tax }),
                num(days.sumOf { it.grossProfit }), num(days.sumOf { it.expenses }), num(days.sumOf { it.netProfit })
            )
            return lines.joinToString("\r\n", postfix = "\r\n")
        }

        /** Western digits whatever the phone language, so Excel reads it as a date. */
        private fun csvDate(millis: Long, pattern: String = "yyyy-MM-dd"): String =
            java.text.SimpleDateFormat(pattern, Locale.US).format(java.util.Date(millis))

        private fun num(value: Double): String {
            val v = if (abs(value) < 0.005) 0.0 else value
            return String.format(Locale.US, "%.2f", v)
        }

        /** A text cell: quoted when needed, and never read by Excel as a formula. */
        private fun text(value: String): String {
            var v = value
            if (v.isNotEmpty() && v[0] in "=+-@\t\r") v = "'$v"
            return if (v.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"${v.replace("\"", "\"\"")}\"" else v
        }
    }
}
