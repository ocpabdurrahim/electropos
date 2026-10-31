package com.example.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.automirrored.filled.TrendingDown
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.example.core.utils.CurrencyUtils
import com.example.core.utils.DateTimeUtils
import com.example.data.repository.ChartGrouping
import com.example.data.repository.ChartPoint
import com.example.data.repository.PartyDue
import com.example.data.repository.ReportData
import com.example.data.repository.ReportPeriod
import com.example.data.repository.ReportRanges
import com.example.data.repository.TopProduct
import com.example.ui.components.AppTopBar
import com.example.ui.components.EmptyStateCard
import com.example.ui.components.StatCard
import com.example.ui.theme.*
import com.example.ui.viewmodels.ReportsViewModel
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportsScreen(
    viewModel: ReportsViewModel,
    onNavigateBack: () -> Unit
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    var showRangePicker by rememberSaveable { mutableStateOf(false) }

    // Loads on first show and again whenever the user comes back (a sale or expense may have been added)
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri -> if (uri != null) viewModel.exportCsv(context.contentResolver, uri) }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            AppTopBar(
                title = "Reports",
                subtitle = state.range.label,
                onBackClick = onNavigateBack,
                actions = {
                    IconButton(onClick = { viewModel.refresh() }, modifier = Modifier.testTag("reports_refresh")) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                }
            )
        }
    ) { padding ->
        val report = state.report
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .testTag("reports_list"),
            contentPadding = PaddingValues(bottom = 32.dp)
        ) {
            item {
                PeriodChips(
                    selected = state.period,
                    onSelect = { period ->
                        if (period == ReportPeriod.CUSTOM) showRangePicker = true else viewModel.selectPeriod(period)
                    }
                )
            }

            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = report?.let { "Updated ${DateTimeUtils.formatDate(it.generatedAt, "hh:mm a")}" } ?: "Loading…",
                        style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                        modifier = Modifier.weight(1f)
                    )
                    FilledTonalButton(
                        onClick = { exportLauncher.launch(viewModel.suggestedFileName()) },
                        enabled = report != null && !state.isLoading && !state.isExporting,
                        modifier = Modifier.testTag("reports_export_csv")
                    ) {
                        Icon(Icons.Default.FileDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (state.isExporting) "Saving…" else "Export CSV")
                    }
                }
                if (state.isLoading || state.isExporting) {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                    )
                }
            }

            if (state.errorMessage != null) {
                item {
                    EmptyStateCard(
                        title = "Could not load the report",
                        message = state.errorMessage ?: "",
                        icon = Icons.Default.ErrorOutline,
                        tint = AlertRose,
                        actionText = "Try again",
                        onActionClick = { viewModel.refresh() },
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }

            if (report == null) {
                if (state.errorMessage == null) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(48.dp),
                            contentAlignment = Alignment.Center
                        ) { CircularProgressIndicator() }
                    }
                }
            } else {
                val sym = report.currencySymbol
                item { KpiGrid(report, sym) }
                item { SalesSummaryCard(report, sym) }
                item { ProfitCard(report, sym) }
                item { PurchasesCard(report, sym) }
                item { ChartCard(report, sym) }
                item { TopProductsCard(report, sym) }
                item { DuesCard(report, sym) }
                item { StockCard(report, sym) }
            }
        }
    }

    if (showRangePicker) {
        CustomRangeDialog(
            initialStartUtc = if (state.period == ReportPeriod.CUSTOM) ReportRanges.toPickerUtc(state.range.start) else null,
            initialEndUtc = if (state.period == ReportPeriod.CUSTOM) ReportRanges.toPickerUtc(state.range.end) else null,
            onDismiss = { showRangePicker = false },
            onConfirm = { start, end ->
                showRangePicker = false
                viewModel.selectCustomRange(start, end)
            }
        )
    }
}

// ---------------------------------------------------------------- period selection

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PeriodChips(selected: ReportPeriod, onSelect: (ReportPeriod) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        ReportPeriod.entries.forEach { period ->
            FilterChip(
                selected = period == selected,
                onClick = { onSelect(period) },
                label = { Text(period.label) },
                leadingIcon = if (period == ReportPeriod.CUSTOM) {
                    { Icon(Icons.Default.DateRange, contentDescription = null, modifier = Modifier.size(18.dp)) }
                } else null,
                modifier = Modifier.testTag("reports_period_${period.name.lowercase()}")
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CustomRangeDialog(
    initialStartUtc: Long?,
    initialEndUtc: Long?,
    onDismiss: () -> Unit,
    onConfirm: (Long, Long) -> Unit
) {
    val pickerState = rememberDateRangePickerState(
        initialSelectedStartDateMillis = initialStartUtc,
        initialSelectedEndDateMillis = initialEndUtc
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                enabled = pickerState.selectedStartDateMillis != null,
                onClick = {
                    val start = pickerState.selectedStartDateMillis ?: return@TextButton
                    onConfirm(start, pickerState.selectedEndDateMillis ?: start)
                }
            ) { Text("Apply") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    ) {
        DateRangePicker(
            state = pickerState,
            modifier = Modifier.weight(1f),
            title = {
                Text(
                    "Pick the first and last day",
                    modifier = Modifier.padding(start = 24.dp, end = 12.dp, top = 16.dp)
                )
            }
        )
    }
}

// ---------------------------------------------------------------- building blocks

@Composable
private fun ReportCard(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector, tint: Color, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(tint.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.width(10.dp))
                Text(title, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
            }
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

@Composable
private fun AmountRow(
    label: String,
    value: String,
    bold: Boolean = false,
    indent: Boolean = false,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
    note: String? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = if (indent) 14.dp else 0.dp, top = 3.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
                    color = if (indent) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
                )
            )
            if (note != null) {
                Text(
                    text = note,
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp
                    )
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontWeight = if (bold) FontWeight.Bold else FontWeight.Medium,
                color = valueColor
            )
        )
    }
}

@Composable
private fun SubHeading(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge.copy(
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary
        ),
        modifier = Modifier.padding(top = 10.dp, bottom = 2.dp)
    )
}

@Composable
private fun Explanation(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall.copy(
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 11.sp
        ),
        modifier = Modifier.padding(top = 8.dp)
    )
}

private fun money(amount: Double, sym: String): String = CurrencyUtils.format(if (abs(amount) < 0.005) 0.0 else amount, sym)

private fun minus(amount: Double, sym: String): String = if (abs(amount) < 0.005) money(0.0, sym) else "− ${money(amount, sym)}"

@Composable
private fun profitColor(value: Double): Color = when {
    value > 0.004 -> StockGreen
    value < -0.004 -> AlertRose
    else -> MaterialTheme.colorScheme.onSurface
}

// ---------------------------------------------------------------- sections

@Composable
private fun KpiGrid(report: ReportData, sym: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatCard(
                title = "Net Sales",
                value = money(report.sales.netSales, sym),
                icon = Icons.Default.PointOfSale,
                iconTint = PrimaryCyan,
                subtitle = "${report.sales.invoiceCount} invoice${if (report.sales.invoiceCount == 1) "" else "s"}, after returns",
                modifier = Modifier.weight(1f)
            )
            StatCard(
                title = "Gross Profit",
                value = money(report.profit.grossProfit, sym),
                icon = Icons.AutoMirrored.Filled.TrendingUp,
                iconTint = StockGreen,
                subtitle = "After returns, excl. tax",
                modifier = Modifier.weight(1f)
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatCard(
                title = "Expenses",
                value = money(report.profit.expenses, sym),
                icon = Icons.AutoMirrored.Filled.ReceiptLong,
                iconTint = WarningAmber,
                subtitle = report.profit.expensesByCategory.size.let { n -> "$n categor${if (n == 1) "y" else "ies"}" },
                modifier = Modifier.weight(1f)
            )
            val net = report.profit.netProfit
            StatCard(
                title = if (net < -0.004) "Net Loss" else "Net Profit",
                value = money(net, sym),
                icon = if (net < -0.004) Icons.AutoMirrored.Filled.TrendingDown else Icons.Default.Savings,
                iconTint = if (net < -0.004) AlertRose else StockGreen,
                subtitle = "Gross profit − expenses",
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun SalesSummaryCard(report: ReportData, sym: String) {
    val s = report.sales
    ReportCard("Sales summary", Icons.Default.PointOfSale, PrimaryCyan) {
        AmountRow("Invoices", s.invoiceCount.toString())
        AmountRow("Gross sales", money(s.grossSales, sym), note = "Invoice totals, tax included")
        AmountRow("Returns (${s.returnCount})", minus(s.returns, sym))
        AmountRow("Net sales", money(s.netSales, sym), bold = true)
        HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
        AmountRow("Discounts given", money(s.discounts, sym))
        AmountRow("Tax collected", money(s.taxCollected, sym), note = "Tax on sales less tax on returns; not the shop's profit")
        AmountRow(
            "Due created",
            money(s.dueCreated, sym),
            valueColor = if (s.dueCreated > 0.004) WarningAmber else MaterialTheme.colorScheme.onSurface,
            note = "Not paid at the counter · still unpaid now: ${money(s.stillUnpaid, sym)}"
        )

        SubHeading("Money received")
        if (s.receivedByMethod.isEmpty()) {
            AmountRow("No payments received", money(0.0, sym), indent = true)
        } else {
            s.receivedByMethod.forEach { AmountRow(it.label, money(it.amount, sym), indent = true) }
        }
        AmountRow(
            "Total received",
            money(s.moneyReceived, sym),
            bold = true,
            note = "At sale ${money(s.receivedAtSale, sym)} · due collected ${money(s.dueCollected, sym)}"
        )
        if (s.refundsToCustomers > 0.004) {
            AmountRow("Refunded to customers", minus(s.refundsToCustomers, sym))
            AmountRow("Net money in", money(s.netMoneyIn, sym), bold = true)
        }
    }
}

@Composable
private fun ProfitCard(report: ReportData, sym: String) {
    val p = report.profit
    ReportCard("Profit", Icons.AutoMirrored.Filled.TrendingUp, StockGreen) {
        AmountRow("Profit on sales", money(p.saleProfit, sym), note = "Sales excl. tax − cost of goods")
        AmountRow("Given back by returns", minus(p.returnedProfit, sym))
        AmountRow("Gross profit", money(p.grossProfit, sym), bold = true)
        HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
        AmountRow("Expenses", minus(p.expenses, sym))
        p.expensesByCategory.forEach { AmountRow(it.label, money(it.amount, sym), indent = true) }
        HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (p.netProfit < -0.004) "NET LOSS" else "NET PROFIT",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.ExtraBold),
                modifier = Modifier.weight(1f)
            )
            Text(
                text = money(p.netProfit, sym),
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.ExtraBold,
                    color = profitColor(p.netProfit)
                )
            )
        }
        Explanation(
            "Net profit = gross profit − expenses. Gross profit is what you sold for (after returns, " +
                "without tax) minus what those goods cost you."
        )
    }
}

@Composable
private fun PurchasesCard(report: ReportData, sym: String) {
    val p = report.purchases
    ReportCard("Purchases", Icons.Default.ShoppingBag, SecondaryBlue) {
        AmountRow("Total purchased", money(p.purchased, sym), note = "${p.purchaseCount} purchase invoices")
        AmountRow(
            "Purchase returns",
            minus(p.returns, sym),
            note = if (p.returnRefunds > 0.004) "Cash back from suppliers: ${money(p.returnRefunds, sym)}" else null
        )
        AmountRow("Paid", money(p.paid, sym), note = "Paid so far on this period's purchases")
        AmountRow(
            "Still owed",
            money(p.owed, sym),
            bold = true,
            valueColor = if (p.owed > 0.004) AlertRose else MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun ChartCard(report: ReportData, sym: String) {
    ReportCard("Sales & profit", Icons.Default.BarChart, AccentPurple) {
        if (report.range.grouping == ChartGrouping.NONE || report.chart.isEmpty()) {
            Text(
                "Pick a week, month or year to see sales and profit day by day.",
                style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
            )
        } else {
            SalesProfitChart(points = report.chart, sym = sym)
            Explanation(
                if (report.range.grouping == ChartGrouping.MONTH) "One pair of bars per month. Tap a bar to see its numbers."
                else "One pair of bars per day. Tap a bar to see its numbers."
            )
        }
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(color)
        )
        Spacer(Modifier.width(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun SalesProfitChart(points: List<ChartPoint>, sym: String) {
    var selected by remember(points) { mutableStateOf<Int?>(null) }
    val salesColor = PrimaryCyan
    val profitBarColor = StockGreen
    val lossColor = AlertRose
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val highlight = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
    val labelStyle = MaterialTheme.typography.labelSmall.copy(
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 9.sp
    )
    val textMeasurer = rememberTextMeasurer()

    val maxValue = max(points.maxOf { max(it.sales, it.profit) }, 0.0)
    val minValue = min(points.minOf { min(it.sales, it.profit) }, 0.0)

    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
        LegendDot(salesColor, "Net sales")
        LegendDot(profitBarColor, "Gross profit")
        if (minValue < 0) LegendDot(lossColor, "Loss")
    }
    Spacer(Modifier.height(6.dp))
    val info = selected?.let { points.getOrNull(it) }
    Text(
        text = if (info != null) "${info.fullLabel}: sales ${money(info.sales, sym)}, profit ${money(info.profit, sym)}"
        else "Highest: ${money(maxValue, sym)}",
        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
        maxLines = 2,
        overflow = TextOverflow.Ellipsis
    )
    Spacer(Modifier.height(6.dp))

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(190.dp)
            .testTag("reports_chart")
            .pointerInput(points) {
                detectTapGestures { offset ->
                    val index = (offset.x / (size.width.toFloat() / points.size)).toInt().coerceIn(0, points.lastIndex)
                    selected = if (selected == index) null else index
                }
            }
    ) {
        val labelHeight = 16.dp.toPx()
        val chartHeight = size.height - labelHeight
        val span = (maxValue - minValue).takeIf { it > 0.0 } ?: 1.0
        val zeroY = (chartHeight * (maxValue / span)).toFloat()
        val groupWidth = size.width / points.size
        val barWidth = min(groupWidth * 0.38f, 14.dp.toPx())
        val gap = groupWidth * 0.05f

        // Top line and zero line
        drawLine(gridColor, Offset(0f, 0f), Offset(size.width, 0f), strokeWidth = 0.5.dp.toPx())
        drawLine(gridColor, Offset(0f, zeroY), Offset(size.width, zeroY), strokeWidth = 1.dp.toPx())

        fun bar(value: Double, left: Float, color: Color) {
            if (abs(value) < 0.005) return
            val height = max((abs(value) / span * chartHeight).toFloat(), 1.dp.toPx())
            val top = if (value >= 0) zeroY - height else zeroY
            drawRect(color, topLeft = Offset(left, top), size = Size(barWidth, height))
        }

        points.forEachIndexed { i, point ->
            val center = groupWidth * i + groupWidth / 2f
            if (selected == i) {
                drawRect(highlight, topLeft = Offset(groupWidth * i, 0f), size = Size(groupWidth, chartHeight))
            }
            bar(point.sales, center - gap / 2f - barWidth, salesColor)
            bar(point.profit, center + gap / 2f, if (point.profit >= 0) profitBarColor else lossColor)
        }

        val step = if (points.size <= 12) 1 else ceil(points.size / 7.0).toInt()
        points.forEachIndexed { i, point ->
            if (i % step == 0) {
                val layout = textMeasurer.measure(point.label, labelStyle)
                val x = (groupWidth * i + groupWidth / 2f - layout.size.width / 2f)
                    .coerceIn(0f, max(0f, size.width - layout.size.width))
                drawText(layout, topLeft = Offset(x, chartHeight + 3.dp.toPx()))
            }
        }
    }
}

@Composable
private fun TopProductsCard(report: ReportData, sym: String) {
    var byProfit by rememberSaveable { mutableStateOf(false) }
    ReportCard("Top products", Icons.Default.Star, WarningAmber) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = !byProfit, onClick = { byProfit = false }, label = { Text("By quantity") })
            FilterChip(selected = byProfit, onClick = { byProfit = true }, label = { Text("By profit") })
        }
        val list = if (byProfit) report.topByProfit else report.topByQuantity
        if (list.isEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(
                "No products sold in this period.",
                style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
            )
        } else {
            Spacer(Modifier.height(4.dp))
            list.forEachIndexed { index, product -> TopProductRow(index + 1, product, byProfit, sym) }
            Explanation("Sold in this period, less units customers returned. Cancelled sales are left out.")
        }
    }
}

@Composable
private fun TopProductRow(rank: Int, product: TopProduct, byProfit: Boolean, sym: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(26.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Text(
                "$rank",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                product.name,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                "Sales ${money(product.netSales, sym)}",
                style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
            )
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            val qtyText = "${product.quantity} sold"
            val profitText = money(product.profit, sym)
            Text(
                if (byProfit) profitText else qtyText,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = FontWeight.Bold,
                    color = if (byProfit) profitColor(product.profit) else MaterialTheme.colorScheme.onSurface
                )
            )
            Text(
                if (byProfit) qtyText else "Profit $profitText",
                style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
            )
        }
    }
}

@Composable
private fun DuesCard(report: ReportData, sym: String) {
    val d = report.dues
    ReportCard("Receivables & payables", Icons.Default.AccountBalanceWallet, WarningAmber) {
        Text(
            "Balances right now, whatever period is picked.",
            style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
        )
        SubHeading("Customers owe you")
        AmountRow(
            "${d.customersOwingCount} customers",
            money(d.customersOwe, sym),
            bold = true,
            valueColor = if (d.customersOwe > 0.004) WarningAmber else MaterialTheme.colorScheme.onSurface,
            note = if (d.customerAdvance > 0.004) "Advance held for customers: ${money(d.customerAdvance, sym)}" else null
        )
        PartyList(d.topCustomers, sym, emptyText = "No customer owes you money.")

        SubHeading("You owe suppliers")
        AmountRow(
            "${d.suppliersOwedCount} suppliers",
            money(d.youOweSuppliers, sym),
            bold = true,
            valueColor = if (d.youOweSuppliers > 0.004) AlertRose else MaterialTheme.colorScheme.onSurface,
            note = if (d.supplierAdvance > 0.004) "Advance paid to suppliers: ${money(d.supplierAdvance, sym)}" else null
        )
        PartyList(d.topSuppliers, sym, emptyText = "You owe no supplier.")
    }
}

@Composable
private fun PartyList(parties: List<PartyDue>, sym: String, emptyText: String) {
    if (parties.isEmpty()) {
        Text(
            emptyText,
            style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
            modifier = Modifier.padding(start = 14.dp, top = 2.dp)
        )
    } else {
        parties.forEach { party ->
            AmountRow(
                label = party.name,
                value = money(party.due, sym),
                indent = true,
                note = party.phone.ifBlank { null }
            )
        }
    }
}

@Composable
private fun StockCard(report: ReportData, sym: String) {
    val st = report.stock
    ReportCard("Stock", Icons.Default.Inventory2, PrimaryCyan) {
        AmountRow("Stock value at cost", money(st.stockValue, sym), bold = true, note = "${st.productCount} active products · right now")
        AmountRow(
            "Low stock",
            "${st.lowStockCount} products",
            valueColor = if (st.lowStockCount > 0) WarningAmber else MaterialTheme.colorScheme.onSurface
        )
        AmountRow(
            "Out of stock",
            "${st.outOfStockCount} products",
            valueColor = if (st.outOfStockCount > 0) AlertRose else MaterialTheme.colorScheme.onSurface
        )
    }
}
