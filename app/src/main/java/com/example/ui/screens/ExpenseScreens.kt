package com.example.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.core.utils.CurrencyUtils
import com.example.core.utils.DateTimeUtils
import com.example.data.local.entities.ExpenseCategoryEntity
import com.example.data.local.entities.ExpenseEntity
import com.example.data.repository.ExpenseInput
import com.example.data.repository.PaymentMethods
import com.example.ui.components.AppTopBar
import com.example.ui.components.EmptyStateCard
import com.example.ui.theme.AlertRose
import com.example.ui.viewmodels.CategoryTotal
import com.example.ui.viewmodels.DateSpan
import com.example.ui.viewmodels.ExpensesUiState
import com.example.ui.viewmodels.ExpensesViewModel
import com.example.ui.viewmodels.FinanceDates
import com.example.ui.viewmodels.FinanceMessage
import com.example.ui.viewmodels.FinancePeriod
import com.example.ui.viewmodels.parseAmountInput
import java.util.Calendar

/**
 * Expenses for a period (today / week / month / picked dates), the period total and totals per category
 * (tap one to filter). [canEdit] false hides add / edit / delete and category management.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExpensesScreen(
    viewModel: ExpensesViewModel,
    onNavigateBack: () -> Unit,
    canEdit: Boolean = true
) {
    val state by viewModel.uiState.collectAsState()
    val message by viewModel.message.collectAsState()
    val snackbarHost = remember { SnackbarHostState() }
    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<ExpenseEntity?>(null) }
    var pickingRange by remember { mutableStateOf(false) }
    var managingCategories by remember { mutableStateOf(false) }

    FinanceMessageEffect(message, snackbarHost) { viewModel.dismissMessage() }

    Scaffold(
        topBar = {
            AppTopBar(
                title = "Expenses",
                subtitle = FinanceDates.describe(state.span),
                onBackClick = onNavigateBack,
                actions = {
                    if (canEdit) {
                        IconButton(
                            onClick = { managingCategories = true },
                            modifier = Modifier.testTag("expense_categories_button")
                        ) {
                            Icon(Icons.Default.Category, contentDescription = "Expense categories")
                        }
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHost) },
        floatingActionButton = {
            if (canEdit) {
                ExtendedFloatingActionButton(
                    onClick = { adding = true },
                    icon = { Icon(Icons.Default.Add, contentDescription = null) },
                    text = { Text("Add expense") },
                    modifier = Modifier.testTag("add_expense_fab")
                )
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                FinancePeriodChips(
                    selected = state.period,
                    onSelect = viewModel::selectPeriod,
                    onPickDates = { pickingRange = true }
                )
            }
            item { ExpenseTotalCard(state) }
            if (state.categoryTotals.isNotEmpty() || state.selectedCategory != null) {
                item {
                    ExpenseCategoryFilter(
                        totals = state.categoryTotals,
                        selected = state.selectedCategory,
                        onSelect = viewModel::selectCategory
                    )
                }
            }
            if (state.expenses.isEmpty() && !state.isLoading) {
                item {
                    EmptyStateCard(
                        title = if (state.selectedCategory != null) "No ${state.selectedCategory} expenses here" else "No expenses in this period",
                        description = if (canEdit) "Rent, bills, salary, tea, transport: add them so profit and cash stay right." else null,
                        icon = Icons.AutoMirrored.Filled.ReceiptLong,
                        actionText = if (canEdit) "Add expense" else null,
                        onActionClick = if (canEdit) ({ adding = true }) else null
                    )
                }
            }
            items(state.expenses, key = { it.id }) { expense ->
                ExpenseRow(
                    expense = expense,
                    showDate = !state.span.isSingleDay,
                    onClick = if (canEdit) ({ editing = expense }) else null
                )
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

    if (adding) {
        ExpenseFormDialog(
            initial = null,
            categories = state.categories,
            defaultCategory = state.selectedCategory,
            onDismiss = { adding = false },
            onSave = { input, setError ->
                viewModel.saveExpense(null, input) { err -> if (err == null) adding = false else setError(err) }
            },
            onDelete = null
        )
    }

    editing?.let { expense ->
        ExpenseFormDialog(
            initial = expense,
            categories = state.categories,
            defaultCategory = null,
            onDismiss = { editing = null },
            onSave = { input, setError ->
                viewModel.saveExpense(expense.id, input) { err -> if (err == null) editing = null else setError(err) }
            },
            onDelete = {
                editing = null
                viewModel.deleteExpense(expense)
            }
        )
    }

    if (managingCategories) {
        ExpenseCategoriesDialog(
            categories = state.categories,
            usage = state.categoryUsage,
            onDismiss = { managingCategories = false },
            onAdd = viewModel::addCategory,
            onRename = viewModel::renameCategory,
            onDelete = viewModel::deleteCategory
        )
    }
}

@Composable
private fun ExpenseTotalCard(state: ExpensesUiState) {
    Card(
        modifier = Modifier.fillMaxWidth().testTag("expense_total_card"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = AlertRose.copy(alpha = 0.10f))
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                "Total spent · ${FinanceDates.describe(state.span)}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                CurrencyUtils.formatBDT(state.periodTotal),
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.ExtraBold),
                color = AlertRose
            )
            Text(
                "${state.periodCount} expense${if (state.periodCount == 1) "" else "s"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (state.selectedCategory != null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "${state.selectedCategory}: ${CurrencyUtils.formatBDT(state.shownTotal)} (${state.expenses.size})",
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExpenseCategoryFilter(totals: List<CategoryTotal>, selected: String?, onSelect: (String?) -> Unit) {
    val shown = if (selected != null && totals.none { it.categoryName == selected }) {
        totals + CategoryTotal(selected, 0.0, 0)
    } else totals
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            "By category",
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.primary
        )
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(selected = selected == null, onClick = { onSelect(null) }, label = { Text("All") })
            shown.forEach { t ->
                FilterChip(
                    selected = selected == t.categoryName,
                    onClick = { onSelect(t.categoryName) },
                    label = { Text("${t.categoryName} · ${CurrencyUtils.formatBDT(t.total)}") }
                )
            }
        }
    }
}

@Composable
private fun ExpenseRow(expense: ExpenseEntity, showDate: Boolean, onClick: (() -> Unit)?) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .testTag("expense_row_${expense.id}"),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.AutoMirrored.Filled.ReceiptLong,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(expense.title, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${expense.categoryName} · ${PaymentMethods.label(expense.paymentMethod)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                val time = DateTimeUtils.formatDate(expense.expenseDate, if (showDate) "dd MMM, hh:mm a" else "hh:mm a")
                val extra = listOf(expense.referenceNo, expense.notes).filter { it.isNotBlank() }.joinToString(" · ")
                Text(
                    if (extra.isBlank()) time else "$time · $extra",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                CurrencyUtils.formatBDT(expense.amount),
                fontWeight = FontWeight.Bold,
                color = AlertRose
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExpenseFormDialog(
    initial: ExpenseEntity?,
    categories: List<ExpenseCategoryEntity>,
    defaultCategory: String?,
    onDismiss: () -> Unit,
    onSave: (input: ExpenseInput, setError: (String) -> Unit) -> Unit,
    onDelete: (() -> Unit)?
) {
    var category by remember { mutableStateOf(initial?.categoryName ?: defaultCategory ?: categories.firstOrNull()?.name.orEmpty()) }
    var typingNewCategory by remember { mutableStateOf(initial == null && categories.isEmpty()) }
    var newCategory by remember { mutableStateOf("") }
    var title by remember { mutableStateOf(initial?.title.orEmpty()) }
    var amount by remember { mutableStateOf(initial?.let { CurrencyUtils.toInput(it.amount) }.orEmpty()) }
    var method by remember { mutableStateOf(PaymentMethods.normalize(initial?.paymentMethod)) }
    var date by remember { mutableStateOf(initial?.expenseDate ?: System.currentTimeMillis()) }
    var reference by remember { mutableStateOf(initial?.referenceNo.orEmpty()) }
    var notes by remember { mutableStateOf(initial?.notes.orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var categoryMenu by remember { mutableStateOf(false) }
    var pickingDate by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    LaunchedEffect(categories) {
        if (category.isBlank() && !typingNewCategory) category = categories.firstOrNull()?.name.orEmpty()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Add expense" else "Edit expense", fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (typingNewCategory) {
                    OutlinedTextField(
                        value = newCategory,
                        onValueChange = { newCategory = it; error = null },
                        label = { Text("New category *") },
                        singleLine = true,
                        trailingIcon = if (categories.isNotEmpty()) {
                            {
                                IconButton(onClick = { typingNewCategory = false }) {
                                    Icon(Icons.Default.Close, contentDescription = "Pick from list")
                                }
                            }
                        } else null,
                        modifier = Modifier.fillMaxWidth().testTag("expense_new_category_input")
                    )
                } else {
                    ExposedDropdownMenuBox(expanded = categoryMenu, onExpandedChange = { categoryMenu = it }) {
                        OutlinedTextField(
                            value = category,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Category *") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = categoryMenu) },
                            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth().testTag("expense_category_input")
                        )
                        ExposedDropdownMenu(expanded = categoryMenu, onDismissRequest = { categoryMenu = false }) {
                            categories.forEach { c ->
                                DropdownMenuItem(
                                    text = { Text(c.name) },
                                    onClick = { category = c.name; categoryMenu = false; error = null }
                                )
                            }
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("New category…") },
                                leadingIcon = { Icon(Icons.Default.Add, contentDescription = null) },
                                onClick = { typingNewCategory = true; categoryMenu = false }
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("What for") },
                    placeholder = { Text("e.g. October rent") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("expense_title_input")
                )
                FinanceAmountField(
                    value = amount,
                    onValueChange = { amount = it; error = null },
                    modifier = Modifier.testTag("expense_amount_input")
                )
                Text("Paid with", style = MaterialTheme.typography.labelMedium)
                PaymentMethodChips(selected = method, onSelect = { method = it })
                OutlinedButton(
                    onClick = { pickingDate = true },
                    modifier = Modifier.fillMaxWidth().testTag("expense_date_button")
                ) {
                    Icon(Icons.Default.CalendarMonth, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Date: ${DateTimeUtils.formatDate(date, "dd MMM yyyy")}")
                }
                OutlinedTextField(
                    value = reference,
                    onValueChange = { reference = it },
                    label = { Text("Bill no. / TrxID") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text("Notes") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth()
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            Button(
                enabled = !saving,
                onClick = {
                    val value = parseAmountInput(amount)
                    val chosenCategory = if (typingNewCategory) newCategory.trim() else category
                    when {
                        chosenCategory.isBlank() -> error = "Choose a category."
                        value == null || value <= 0.0 -> error = "Type an amount more than 0."
                        else -> {
                            saving = true
                            onSave(
                                ExpenseInput(
                                    categoryName = chosenCategory,
                                    title = title,
                                    amount = value,
                                    paymentMethod = method,
                                    expenseDate = date,
                                    referenceNo = reference,
                                    notes = notes
                                )
                            ) { saving = false; error = it }
                        }
                    }
                },
                modifier = Modifier.testTag("expense_save_button")
            ) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (onDelete != null) {
                    TextButton(
                        onClick = { confirmDelete = true },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) { Text("Delete") }
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        }
    )

    if (pickingDate) {
        FinanceDatePickerDialog(
            initialLocal = date,
            onDismiss = { pickingDate = false },
            onConfirm = { dayStart ->
                pickingDate = false
                if (DateTimeUtils.getStartOfDay(date) != dayStart) date = FinanceDates.onDayWithTimeOf(dayStart, date)
            }
        )
    }

    if (confirmDelete && initial != null && onDelete != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this expense?", fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "${initial.title}: ${CurrencyUtils.formatBDT(initial.amount)} goes back into " +
                        "${PaymentMethods.balanceLabel(initial.paymentMethod)}."
                )
            },
            confirmButton = {
                Button(
                    onClick = { confirmDelete = false; onDelete() },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.testTag("expense_confirm_delete_button")
                ) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Keep") } }
        )
    }
}

@Composable
private fun ExpenseCategoriesDialog(
    categories: List<ExpenseCategoryEntity>,
    usage: Map<String, Int>,
    onDismiss: () -> Unit,
    onAdd: (name: String, onDone: (String?) -> Unit) -> Unit,
    onRename: (id: Long, name: String, onDone: (String?) -> Unit) -> Unit,
    onDelete: (id: Long, onDone: (String?) -> Unit) -> Unit
) {
    var newName by remember { mutableStateOf("") }
    var renamingId by remember { mutableStateOf<Long?>(null) }
    var renameText by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Expense categories", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it; error = null },
                        label = { Text("New category") },
                        singleLine = true,
                        modifier = Modifier.weight(1f).testTag("new_expense_category_input")
                    )
                    IconButton(
                        onClick = {
                            onAdd(newName) { err -> if (err == null) newName = "" else error = err }
                        },
                        enabled = newName.isNotBlank(),
                        modifier = Modifier.testTag("add_expense_category_button")
                    ) { Icon(Icons.Default.AddCircle, contentDescription = "Add category") }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                Text(
                    "A category used by expenses can't be deleted, but you can rename it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Column(
                    modifier = Modifier.heightIn(max = 340.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    categories.forEach { c ->
                        val used = usage[c.name] ?: 0
                        if (renamingId == c.id) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                OutlinedTextField(
                                    value = renameText,
                                    onValueChange = { renameText = it; error = null },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(onClick = {
                                    onRename(c.id, renameText) { err -> if (err == null) renamingId = null else error = err }
                                }) { Icon(Icons.Default.Check, contentDescription = "Save name") }
                                IconButton(onClick = { renamingId = null }) {
                                    Icon(Icons.Default.Close, contentDescription = "Cancel rename")
                                }
                            }
                        } else {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(c.name, fontWeight = FontWeight.SemiBold)
                                    Text(
                                        if (used == 0) "Not used yet" else "$used expense${if (used == 1) "" else "s"}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                IconButton(onClick = { renamingId = c.id; renameText = c.name; error = null }) {
                                    Icon(Icons.Default.Edit, contentDescription = "Rename ${c.name}")
                                }
                                IconButton(
                                    onClick = { onDelete(c.id) { err -> error = err } },
                                    enabled = used == 0
                                ) {
                                    Icon(Icons.Default.Delete, contentDescription = "Delete ${c.name}")
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } }
    )
}

// -------------------------------------------------------------------------------------------------
// Pieces shared with the Cash book screen
// -------------------------------------------------------------------------------------------------

/** Shows each [FinanceMessage] once as a snackbar, then clears it. */
@Composable
internal fun FinanceMessageEffect(message: FinanceMessage?, host: SnackbarHostState, onShown: () -> Unit) {
    LaunchedEffect(message) {
        if (message != null) {
            host.showSnackbar(
                message = message.text,
                withDismissAction = message.isError,
                duration = if (message.isError) SnackbarDuration.Long else SnackbarDuration.Short
            )
            onShown()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FinancePeriodChips(
    selected: FinancePeriod,
    onSelect: (FinancePeriod) -> Unit,
    onPickDates: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        FinancePeriod.values().forEach { period ->
            FilterChip(
                selected = selected == period,
                onClick = { if (period == FinancePeriod.CUSTOM) onPickDates() else onSelect(period) },
                label = { Text(period.label) },
                leadingIcon = if (period == FinancePeriod.CUSTOM) {
                    { Icon(Icons.Default.DateRange, contentDescription = null, modifier = Modifier.size(16.dp)) }
                } else null,
                modifier = Modifier.testTag("period_chip_${period.name.lowercase()}")
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PaymentMethodChips(
    selected: String?,
    onSelect: (String) -> Unit,
    methods: List<String> = PaymentMethods.ALL,
    isEnabled: (String) -> Boolean = { true }
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        methods.chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { m ->
                    FilterChip(
                        selected = selected == m,
                        onClick = { onSelect(m) },
                        enabled = isEnabled(m),
                        label = { Text(PaymentMethods.label(m), maxLines = 1) },
                        modifier = Modifier.weight(1f)
                    )
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
internal fun FinanceAmountField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String = "Amount (৳) *"
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier.fillMaxWidth()
    )
}

/** Today and earlier only: expenses and cash entries are not entered for the future. */
@OptIn(ExperimentalMaterial3Api::class)
internal object PastOrTodayDates : SelectableDates {
    override fun isSelectableDate(utcTimeMillis: Long): Boolean =
        utcTimeMillis <= FinanceDates.localDayToPickerUtc(System.currentTimeMillis())

    override fun isSelectableYear(year: Int): Boolean = year <= Calendar.getInstance().get(Calendar.YEAR)
}

/** Material3 DateRangePicker; returns local start-of-day times (end = start when one day is picked). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FinanceDateRangeDialog(
    initial: DateSpan,
    onDismiss: () -> Unit,
    onConfirm: (startLocal: Long, endLocal: Long) -> Unit
) {
    val state = rememberDateRangePickerState(
        initialSelectedStartDateMillis = FinanceDates.localDayToPickerUtc(initial.start),
        initialSelectedEndDateMillis = FinanceDates.localDayToPickerUtc(initial.end),
        selectableDates = PastOrTodayDates
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    val start = state.selectedStartDateMillis ?: return@TextButton
                    val end = state.selectedEndDateMillis ?: start
                    onConfirm(FinanceDates.pickerUtcToLocalDay(start), FinanceDates.pickerUtcToLocalDay(end))
                },
                enabled = state.selectedStartDateMillis != null,
                modifier = Modifier.testTag("date_range_confirm_button")
            ) { Text("Show") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    ) {
        DateRangePicker(
            state = state,
            modifier = Modifier.weight(1f),
            title = {
                Text(
                    "Choose a day or a date range",
                    modifier = Modifier.padding(start = 24.dp, end = 12.dp, top = 16.dp)
                )
            },
            showModeToggle = false
        )
    }
}

/** Material3 DatePicker; returns the local start of the picked day. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FinanceDatePickerDialog(
    initialLocal: Long,
    onDismiss: () -> Unit,
    onConfirm: (localDayStart: Long) -> Unit
) {
    val state = rememberDatePickerState(
        initialSelectedDateMillis = FinanceDates.localDayToPickerUtc(initialLocal),
        selectableDates = PastOrTodayDates
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = { state.selectedDateMillis?.let { onConfirm(FinanceDates.pickerUtcToLocalDay(it)) } },
                enabled = state.selectedDateMillis != null
            ) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    ) {
        DatePicker(state = state)
    }
}
