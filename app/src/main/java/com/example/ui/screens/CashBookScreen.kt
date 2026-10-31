package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.core.utils.CurrencyUtils
import com.example.core.utils.DateTimeUtils
import com.example.data.repository.CashBookLine
import com.example.data.repository.CashBookPage
import com.example.data.repository.CashEntryType
import com.example.data.repository.PaymentMethods
import com.example.ui.components.AppTopBar
import com.example.ui.components.EmptyStateCard
import com.example.ui.theme.AlertRose
import com.example.ui.theme.StockGreen
import com.example.ui.theme.WarningAmber
import com.example.ui.viewmodels.CashBookUiState
import com.example.ui.viewmodels.CashBookViewModel
import com.example.ui.viewmodels.FinanceDates
import com.example.ui.viewmodels.parseAmountInput
import kotlin.math.abs

/**
 * Money now per method (tap one to see its book), then the entries of a day or period with a running
 * balance. [canEdit] false hides Cash in / Cash out / Move money / Opening balance and deleting.
 * [onOpenExpenses] adds an "Expenses" item to the menu when given.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CashBookScreen(
    viewModel: CashBookViewModel,
    onNavigateBack: () -> Unit,
    canEdit: Boolean = true,
    onOpenExpenses: (() -> Unit)? = null
) {
    val state by viewModel.uiState.collectAsState()
    val message by viewModel.message.collectAsState()
    val snackbarHost = remember { SnackbarHostState() }
    var manualDirection by remember { mutableStateOf<String?>(null) }
    var moving by remember { mutableStateOf(false) }
    var settingOpening by remember { mutableStateOf(false) }
    var pickingRange by remember { mutableStateOf(false) }
    var selectedLine by remember { mutableStateOf<CashBookLine?>(null) }
    var menuOpen by remember { mutableStateOf(false) }

    FinanceMessageEffect(message, snackbarHost) { viewModel.dismissMessage() }

    val methodTitle = state.method?.let { PaymentMethods.balanceLabel(it) } ?: "All money"
    val todayStart = DateTimeUtils.getStartOfDay(System.currentTimeMillis())

    Scaffold(
        topBar = {
            AppTopBar(
                title = "Cash book",
                subtitle = "$methodTitle · ${FinanceDates.describe(state.span)}",
                onBackClick = onNavigateBack,
                actions = {
                    val showSetOpening = canEdit && state.methodsWithoutOpening.isNotEmpty()
                    if (showSetOpening || onOpenExpenses != null) {
                        Box {
                            IconButton(onClick = { menuOpen = true }, modifier = Modifier.testTag("cash_book_menu_button")) {
                                Icon(Icons.Default.MoreVert, contentDescription = "More")
                            }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                if (showSetOpening) {
                                    DropdownMenuItem(
                                        text = { Text("Set opening balance") },
                                        leadingIcon = { Icon(Icons.Default.Savings, contentDescription = null) },
                                        onClick = { menuOpen = false; settingOpening = true }
                                    )
                                }
                                if (onOpenExpenses != null) {
                                    DropdownMenuItem(
                                        text = { Text("Expenses") },
                                        leadingIcon = { Icon(Icons.AutoMirrored.Filled.ReceiptLong, contentDescription = null) },
                                        onClick = { menuOpen = false; onOpenExpenses() }
                                    )
                                }
                            }
                        }
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHost) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 32.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                CashBalancesSection(state = state, onSelect = viewModel::selectMethod)
            }
            if (canEdit) {
                item {
                    CashBookActions(
                        onCashIn = { manualDirection = CashEntryType.IN },
                        onCashOut = { manualDirection = CashEntryType.OUT },
                        onMove = { moving = true }
                    )
                }
                if (!state.isLoading && PaymentMethods.CASH !in state.openingSetFor) {
                    item { OpeningCashCard(onSet = { settingOpening = true }) }
                }
            }
            item {
                FinancePeriodChips(
                    selected = state.period,
                    onSelect = viewModel::selectPeriod,
                    onPickDates = { pickingRange = true }
                )
            }
            if (state.span.isSingleDay) {
                item {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { viewModel.shiftDay(-1) }, modifier = Modifier.testTag("cash_book_prev_day")) {
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Day before")
                        }
                        Text(
                            FinanceDates.describe(state.span),
                            modifier = Modifier.weight(1f),
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold)
                        )
                        IconButton(
                            onClick = { viewModel.shiftDay(1) },
                            enabled = DateTimeUtils.getStartOfDay(state.span.start) < todayStart,
                            modifier = Modifier.testTag("cash_book_next_day")
                        ) {
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Next day")
                        }
                    }
                }
            }
            item { CashBookSummaryCard(page = state.page, title = methodTitle) }
            if (state.page.lines.isEmpty() && !state.isLoading) {
                item {
                    EmptyStateCard(
                        title = "No entries in this period",
                        description = "Sales, payments, expenses and refunds show here as they happen.",
                        icon = Icons.Default.AccountBalanceWallet
                    )
                }
            } else {
                items(state.page.lines, key = { it.entry.id }) { line ->
                    CashBookLineRow(
                        line = line,
                        showMethod = state.method == null,
                        showDate = !state.span.isSingleDay,
                        onClick = { selectedLine = line }
                    )
                }
            }
        }
    }

    if (pickingRange) {
        FinanceDateRangeDialog(
            initial = state.span,
            onDismiss = { pickingRange = false },
            onConfirm = { start, end ->
                pickingRange = false
                viewModel.setCustomRange(start, end)
            }
        )
    }

    manualDirection?.let { direction ->
        ManualCashDialog(
            direction = direction,
            initialMethod = state.method ?: PaymentMethods.CASH,
            balanceOf = state::balanceOf,
            onDismiss = { manualDirection = null },
            onSave = { method, amount, note, setError ->
                viewModel.addManualEntry(direction, method, amount, note) { err ->
                    if (err == null) manualDirection = null else setError(err)
                }
            }
        )
    }

    if (moving) {
        MoveMoneyDialog(
            balanceOf = state::balanceOf,
            onDismiss = { moving = false },
            onSave = { from, to, amount, note, setError ->
                viewModel.moveMoney(from, to, amount, note) { err -> if (err == null) moving = false else setError(err) }
            }
        )
    }

    if (settingOpening) {
        OpeningBalanceDialog(
            methods = state.methodsWithoutOpening,
            onDismiss = { settingOpening = false },
            onSave = { method, amount, note, setError ->
                viewModel.setOpeningBalance(method, amount, note) { err -> if (err == null) settingOpening = false else setError(err) }
            }
        )
    }

    selectedLine?.let { line ->
        CashEntryDetailsDialog(
            line = line,
            canDelete = canEdit && CashEntryType.isManual(line.entry.type),
            onDismiss = { selectedLine = null },
            onDelete = {
                selectedLine = null
                viewModel.deleteEntry(line.entry)
            }
        )
    }
}

@Composable
private fun CashBalancesSection(state: CashBookUiState, onSelect: (String?) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "MONEY NOW",
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.primary
        )
        BalanceTile(
            label = "All money (every method)",
            amount = state.totalMoney,
            selected = state.method == null,
            onClick = { onSelect(null) },
            modifier = Modifier.fillMaxWidth().testTag("balance_tile_all")
        )
        state.balances.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { b ->
                    BalanceTile(
                        label = PaymentMethods.balanceLabel(b.paymentMethod),
                        amount = b.balance,
                        selected = state.method == b.paymentMethod,
                        onClick = { onSelect(b.paymentMethod) },
                        modifier = Modifier.weight(1f).testTag("balance_tile_${b.paymentMethod.lowercase()}")
                    )
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BalanceTile(label: String, amount: Double, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
        ),
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                CurrencyUtils.formatBDT(amount),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = if (amount < -0.004) AlertRose else MaterialTheme.colorScheme.onSurface,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun CashBookActions(onCashIn: () -> Unit, onCashOut: () -> Unit, onMove: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = onCashIn,
                colors = ButtonDefaults.buttonColors(containerColor = StockGreen),
                modifier = Modifier.weight(1f).testTag("cash_in_button")
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Cash in")
            }
            Button(
                onClick = onCashOut,
                colors = ButtonDefaults.buttonColors(containerColor = AlertRose),
                modifier = Modifier.weight(1f).testTag("cash_out_button")
            ) {
                Icon(Icons.Default.Remove, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Cash out")
            }
        }
        OutlinedButton(onClick = onMove, modifier = Modifier.fillMaxWidth().testTag("move_money_button")) {
            Icon(Icons.Default.SwapHoriz, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Move money (e.g. Cash → Bank deposit)")
        }
    }
}

@Composable
private fun OpeningCashCard(onSet: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().testTag("opening_cash_card"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = WarningAmber.copy(alpha = 0.15f))
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Savings, contentDescription = null, tint = WarningAmber)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("No opening cash yet", fontWeight = FontWeight.Bold)
                Text(
                    "Count the money in the drawer and set it once, so the balance matches.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            TextButton(onClick = onSet, modifier = Modifier.testTag("set_opening_cash_button")) { Text("Set") }
        }
    }
}

@Composable
private fun CashBookSummaryCard(page: CashBookPage, title: String) {
    Card(
        modifier = Modifier.fillMaxWidth().testTag("cash_book_summary"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
            SummaryLine("Opening balance", CurrencyUtils.formatBDT(page.openingBalance))
            SummaryLine("Money in", "+ ${CurrencyUtils.formatBDT(page.totalIn)}", StockGreen)
            SummaryLine("Money out", "− ${CurrencyUtils.formatBDT(page.totalOut)}", AlertRose)
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
            SummaryLine("Closing balance", CurrencyUtils.formatBDT(page.closingBalance), bold = true)
        }
    }
}

@Composable
private fun SummaryLine(label: String, value: String, color: Color = Color.Unspecified, bold: Boolean = false) {
    Row(Modifier.fillMaxWidth()) {
        Text(
            label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal)
        )
        Text(
            value,
            color = color,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = if (bold) FontWeight.ExtraBold else FontWeight.SemiBold)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CashBookLineRow(line: CashBookLine, showMethod: Boolean, showDate: Boolean, onClick: () -> Unit) {
    val entry = line.entry
    val isIn = entry.flowDirection == CashEntryType.IN
    val accent = if (isIn) StockGreen else AlertRose
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().testTag("cash_line_${entry.id}"),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (isIn) Icons.Default.ArrowDownward else Icons.Default.ArrowUpward,
                contentDescription = if (isIn) "Money in" else "Money out",
                tint = accent,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    line.label + if (showMethod) " · ${PaymentMethods.label(entry.paymentMethod)}" else "",
                    fontWeight = FontWeight.Bold
                )
                val detail = entryDetail(line)
                if (detail.isNotBlank()) {
                    Text(
                        detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    DateTimeUtils.formatDate(entry.date, if (showDate) "dd MMM, hh:mm a" else "hh:mm a"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    (if (isIn) "+ " else "− ") + CurrencyUtils.formatBDT(abs(entry.amount)),
                    color = accent,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "Bal ${CurrencyUtils.formatBDT(line.runningBalance)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun entryDetail(line: CashBookLine): String =
    listOf(line.entry.referenceText, line.entry.notes)
        .filter { it.isNotBlank() && !it.equals(line.label, ignoreCase = true) }
        .distinct()
        .joinToString(" · ")

@Composable
private fun ManualCashDialog(
    direction: String,
    initialMethod: String,
    balanceOf: (String) -> Double,
    onDismiss: () -> Unit,
    onSave: (method: String, amount: Double, note: String, setError: (String) -> Unit) -> Unit
) {
    val isIn = direction == CashEntryType.IN
    var method by remember { mutableStateOf(PaymentMethods.normalize(initialMethod)) }
    var amount by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val value = parseAmountInput(amount)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isIn) "Cash in" else "Cash out", fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (isIn) "Money put in by hand, e.g. change money brought from home."
                    else "Money taken out that is not a shop expense, e.g. the owner took money home.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                PaymentMethodChips(selected = method, onSelect = { method = it; error = null })
                Text(
                    "${PaymentMethods.balanceLabel(method)} now: ${CurrencyUtils.formatBDT(balanceOf(method))}",
                    style = MaterialTheme.typography.bodySmall
                )
                FinanceAmountField(
                    value = amount,
                    onValueChange = { amount = it; error = null },
                    modifier = Modifier.testTag("manual_cash_amount_input")
                )
                if (!isIn && value != null && value > balanceOf(method) + 0.004) {
                    Text(
                        "This is more than the ${PaymentMethods.balanceLabel(method)} balance.",
                        color = WarningAmber,
                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold)
                    )
                }
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("Note (who / why)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("manual_cash_note_input")
                )
                Text(
                    "Bank deposit or bKash cash-out? Use Move money. Rent, bills or salary? Add them in Expenses.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            Button(
                enabled = !saving,
                onClick = {
                    if (value == null || value <= 0.0) {
                        error = "Type an amount more than 0."
                    } else {
                        saving = true
                        onSave(method, value, note) { saving = false; error = it }
                    }
                },
                modifier = Modifier.testTag("manual_cash_save_button")
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun MoveMoneyDialog(
    balanceOf: (String) -> Double,
    onDismiss: () -> Unit,
    onSave: (from: String, to: String, amount: Double, note: String, setError: (String) -> Unit) -> Unit
) {
    var from by remember { mutableStateOf(PaymentMethods.CASH) }
    var to by remember { mutableStateOf(PaymentMethods.BANK) }
    var amount by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val value = parseAmountInput(amount)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Move money", fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "e.g. Cash → Bank (deposit), bKash → Cash (cash-out). Total money stays the same.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text("From (${CurrencyUtils.formatBDT(balanceOf(from))})", style = MaterialTheme.typography.labelMedium)
                PaymentMethodChips(selected = from, onSelect = { from = it; if (to == it) to = PaymentMethods.ALL.first { m -> m != it }; error = null })
                Text("To", style = MaterialTheme.typography.labelMedium)
                PaymentMethodChips(selected = to, onSelect = { to = it; error = null }, isEnabled = { it != from })
                FinanceAmountField(
                    value = amount,
                    onValueChange = { amount = it; error = null },
                    modifier = Modifier.testTag("move_money_amount_input")
                )
                if (value != null && value > balanceOf(from) + 0.004) {
                    Text(
                        "This is more than the ${PaymentMethods.balanceLabel(from)} balance.",
                        color = WarningAmber,
                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold)
                    )
                }
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("Note (e.g. deposit slip no.)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            Button(
                enabled = !saving,
                onClick = {
                    when {
                        from == to -> error = "Choose two different places."
                        value == null || value <= 0.0 -> error = "Type an amount more than 0."
                        else -> {
                            saving = true
                            onSave(from, to, value, note) { saving = false; error = it }
                        }
                    }
                },
                modifier = Modifier.testTag("move_money_save_button")
            ) { Text("Move") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun OpeningBalanceDialog(
    methods: List<String>,
    onDismiss: () -> Unit,
    onSave: (method: String, amount: Double, note: String, setError: (String) -> Unit) -> Unit
) {
    var method by remember { mutableStateOf(if (PaymentMethods.CASH in methods) PaymentMethods.CASH else methods.firstOrNull() ?: PaymentMethods.CASH) }
    var amount by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Set opening balance", fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "The money you had when you started using the app. It counts before your first entry, " +
                        "so every balance after it includes it. It can be set once for each place.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                PaymentMethodChips(selected = method, onSelect = { method = it; error = null }, isEnabled = { it in methods })
                FinanceAmountField(
                    value = amount,
                    onValueChange = { amount = it; error = null },
                    label = "${PaymentMethods.balanceLabel(method)} at the start (৳) *",
                    modifier = Modifier.testTag("opening_amount_input")
                )
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("Note") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            Button(
                enabled = !saving && methods.isNotEmpty(),
                onClick = {
                    val value = parseAmountInput(amount)
                    if (value == null || value <= 0.0) {
                        error = "Type an amount more than 0."
                    } else {
                        saving = true
                        onSave(method, value, note) { saving = false; error = it }
                    }
                },
                modifier = Modifier.testTag("opening_save_button")
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun CashEntryDetailsDialog(
    line: CashBookLine,
    canDelete: Boolean,
    onDismiss: () -> Unit,
    onDelete: () -> Unit
) {
    val entry = line.entry
    val isIn = entry.flowDirection == CashEntryType.IN
    var confirming by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(line.label, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    (if (isIn) "Money in: + " else "Money out: − ") + CurrencyUtils.formatBDT(entry.amount),
                    color = if (isIn) StockGreen else AlertRose,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                )
                Text("Method: ${PaymentMethods.label(entry.paymentMethod)}", style = MaterialTheme.typography.bodyMedium)
                Text("When: ${DateTimeUtils.formatDateTime(entry.date)}", style = MaterialTheme.typography.bodyMedium)
                if (entry.referenceText.isNotBlank()) Text("For: ${entry.referenceText}", style = MaterialTheme.typography.bodyMedium)
                if (entry.notes.isNotBlank()) Text("Note: ${entry.notes}", style = MaterialTheme.typography.bodyMedium)
                Text("Balance after: ${CurrencyUtils.formatBDT(line.runningBalance)}", style = MaterialTheme.typography.bodySmall)
                if (!CashEntryType.isManual(entry.type)) {
                    Text(
                        when (entry.type) {
                            CashEntryType.EXPENSE -> "Made from Expenses; change or delete it there."
                            CashEntryType.SALE_CASH -> "Made by a sale; it changes when the sale is returned or cancelled."
                            else -> "Made by the ${line.label.lowercase()} itself; change it there."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else if (entry.type == CashEntryType.TRANSFER && canDelete) {
                    Text(
                        "Deleting removes both sides of this move.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        dismissButton = if (canDelete) {
            {
                TextButton(
                    onClick = { confirming = true },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.testTag("cash_entry_delete_button")
                ) { Text("Delete") }
            }
        } else null
    )

    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("Delete this entry?", fontWeight = FontWeight.Bold) },
            text = { Text("${line.label} of ${CurrencyUtils.formatBDT(entry.amount)} will be removed from the cash book.") },
            confirmButton = {
                Button(
                    onClick = { confirming = false; onDelete() },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.testTag("cash_entry_confirm_delete_button")
                ) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text("Keep") } }
        )
    }
}
