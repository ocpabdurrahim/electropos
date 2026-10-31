package com.example.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.core.utils.DateTimeUtils
import com.example.data.local.dao.PaymentMethodBalance
import com.example.data.local.entities.CashRegisterTransactionEntity
import com.example.data.local.entities.ExpenseCategoryEntity
import com.example.data.local.entities.ExpenseEntity
import com.example.data.repository.CashBookPage
import com.example.data.repository.CashEntryType
import com.example.data.repository.ExpenseInput
import com.example.data.repository.FinanceRepository
import com.example.data.repository.PaymentMethods
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.TimeZone

// -------------------------------------------------------------------------------------------------
// Periods and dates shared by the Expenses and Cash book screens
// -------------------------------------------------------------------------------------------------

enum class FinancePeriod(val label: String) {
    TODAY("Today"),
    YESTERDAY("Yesterday"),
    THIS_WEEK("This week"),
    THIS_MONTH("This month"),
    CUSTOM("Pick dates")
}

/** Inclusive local time span, start of the first day to end of the last day. */
data class DateSpan(val start: Long, val end: Long) {
    val isSingleDay: Boolean get() = DateTimeUtils.getStartOfDay(start) == DateTimeUtils.getStartOfDay(end)
}

object FinanceDates {
    private const val DAY_MS = 24L * 60 * 60 * 1000

    fun day(time: Long): DateSpan = DateSpan(DateTimeUtils.getStartOfDay(time), DateTimeUtils.getEndOfDay(time))

    fun spanFor(period: FinancePeriod, now: Long = System.currentTimeMillis()): DateSpan = when (period) {
        FinancePeriod.TODAY, FinancePeriod.CUSTOM -> day(now)
        FinancePeriod.YESTERDAY -> day(shiftDays(now, -1))
        FinancePeriod.THIS_WEEK -> {
            val cal = Calendar.getInstance().apply { timeInMillis = DateTimeUtils.getStartOfDay(now) }
            while (cal.get(Calendar.DAY_OF_WEEK) != cal.firstDayOfWeek) cal.add(Calendar.DAY_OF_MONTH, -1)
            DateSpan(cal.timeInMillis, DateTimeUtils.getEndOfDay(now))
        }
        FinancePeriod.THIS_MONTH -> {
            val cal = Calendar.getInstance().apply {
                timeInMillis = DateTimeUtils.getStartOfDay(now)
                set(Calendar.DAY_OF_MONTH, 1)
            }
            DateSpan(cal.timeInMillis, DateTimeUtils.getEndOfDay(now))
        }
    }

    /** Same clock time, [days] calendar days later (safe across daylight-saving changes). */
    fun shiftDays(time: Long, days: Int): Long =
        Calendar.getInstance().apply { timeInMillis = time; add(Calendar.DAY_OF_MONTH, days) }.timeInMillis

    /** Material3 date pickers work in UTC midnight; this returns the start of that calendar day in local time. */
    fun pickerUtcToLocalDay(utcMillis: Long): Long {
        val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = utcMillis }
        return Calendar.getInstance().apply {
            clear()
            set(utc.get(Calendar.YEAR), utc.get(Calendar.MONTH), utc.get(Calendar.DAY_OF_MONTH), 0, 0, 0)
        }.timeInMillis
    }

    /** The local calendar day of [localMillis] as the UTC midnight a Material3 date picker expects. */
    fun localDayToPickerUtc(localMillis: Long): Long {
        val local = Calendar.getInstance().apply { timeInMillis = localMillis }
        return Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(local.get(Calendar.YEAR), local.get(Calendar.MONTH), local.get(Calendar.DAY_OF_MONTH), 0, 0, 0)
        }.timeInMillis
    }

    /** Picked day (local start of day) with the clock time of [timeSource], so entries on one day keep their order. */
    fun onDayWithTimeOf(localDayStart: Long, timeSource: Long = System.currentTimeMillis()): Long =
        localDayStart + (timeSource - DateTimeUtils.getStartOfDay(timeSource)).coerceIn(0L, DAY_MS - 1)

    fun describe(span: DateSpan): String {
        if (span.isSingleDay) {
            val today = DateTimeUtils.getStartOfDay(System.currentTimeMillis())
            val start = DateTimeUtils.getStartOfDay(span.start)
            val prefix = when (start) {
                today -> "Today, "
                DateTimeUtils.getStartOfDay(shiftDays(today, -1)) -> "Yesterday, "
                else -> ""
            }
            return prefix + DateTimeUtils.formatDate(span.start, "dd MMM yyyy")
        }
        val sameYear = DateTimeUtils.formatDate(span.start, "yyyy") == DateTimeUtils.formatDate(span.end, "yyyy")
        val first = DateTimeUtils.formatDate(span.start, if (sameYear) "dd MMM" else "dd MMM yyyy")
        return "$first – ${DateTimeUtils.formatDate(span.end, "dd MMM yyyy")}"
    }
}

/** Amount typed by the user: allows commas, spaces and Bangla digits; null when it is not a number. */
fun parseAmountInput(text: String): Double? {
    val bangla = "০১২৩৪৫৬৭৮৯"
    val ascii = text.map { c -> bangla.indexOf(c).let { i -> if (i >= 0) '0' + i else c } }.joinToString("")
    return ascii.replace(",", "").replace(" ", "").trim().toDoubleOrNull()
}

data class FinanceMessage(val text: String, val isError: Boolean = false)

// -------------------------------------------------------------------------------------------------
// Expenses
// -------------------------------------------------------------------------------------------------

data class CategoryTotal(val categoryName: String, val total: Double, val count: Int)

data class ExpensesUiState(
    val period: FinancePeriod = FinancePeriod.TODAY,
    val span: DateSpan = FinanceDates.spanFor(FinancePeriod.TODAY),
    /** Expenses of the period, after the category filter; newest first. */
    val expenses: List<ExpenseEntity> = emptyList(),
    /** Every expense of the period, whatever the category filter. */
    val periodTotal: Double = 0.0,
    val periodCount: Int = 0,
    /** Total of [expenses] (equals [periodTotal] without a category filter). */
    val shownTotal: Double = 0.0,
    /** Totals per category in the period, biggest first. */
    val categoryTotals: List<CategoryTotal> = emptyList(),
    val selectedCategory: String? = null,
    val categories: List<ExpenseCategoryEntity> = emptyList(),
    /** Category name to number of expenses (all time); used to block deleting a category in use. */
    val categoryUsage: Map<String, Int> = emptyMap(),
    val isLoading: Boolean = true
)

private data class ExpenseFilter(val period: FinancePeriod, val span: DateSpan, val category: String?)

@OptIn(ExperimentalCoroutinesApi::class)
class ExpensesViewModel(
    private val financeRepository: FinanceRepository,
    /** Name written to the audit log, e.g. `{ app.operatorName() }`. */
    private val operatorName: () -> String = { "Admin" }
) : ViewModel() {

    private val filter = MutableStateFlow(
        ExpenseFilter(FinancePeriod.TODAY, FinanceDates.spanFor(FinancePeriod.TODAY), null)
    )

    private val _message = MutableStateFlow<FinanceMessage?>(null)
    val message: StateFlow<FinanceMessage?> = _message.asStateFlow()

    val uiState: StateFlow<ExpensesUiState> = combine(
        filter.flatMapLatest { f ->
            financeRepository.getExpensesBetween(f.span.start, f.span.end).map { f to it }
        },
        financeRepository.expenseCategories,
        financeRepository.expenseCategoryUsage
    ) { filtered, categories, usage ->
        val (f, periodExpenses) = filtered
        val totals = periodExpenses.groupBy { it.categoryName }
            .map { (name, list) -> CategoryTotal(name, list.sumOf { it.amount }, list.size) }
            .sortedByDescending { it.total }
        val shown = if (f.category == null) periodExpenses else periodExpenses.filter { it.categoryName == f.category }
        ExpensesUiState(
            period = f.period,
            span = f.span,
            expenses = shown,
            periodTotal = periodExpenses.sumOf { it.amount },
            periodCount = periodExpenses.size,
            shownTotal = shown.sumOf { it.amount },
            categoryTotals = totals,
            selectedCategory = f.category,
            categories = categories,
            categoryUsage = usage,
            isLoading = false
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ExpensesUiState())

    fun selectPeriod(period: FinancePeriod) {
        if (period == FinancePeriod.CUSTOM) return // the screen opens the date range picker and calls setCustomRange
        filter.update { it.copy(period = period, span = FinanceDates.spanFor(period)) }
    }

    /** Local times; [endLocal] may be the start of the last day, the whole day is included. */
    fun setCustomRange(startLocal: Long, endLocal: Long) {
        val start = DateTimeUtils.getStartOfDay(minOf(startLocal, endLocal))
        val end = DateTimeUtils.getEndOfDay(maxOf(startLocal, endLocal))
        filter.update { it.copy(period = FinancePeriod.CUSTOM, span = DateSpan(start, end)) }
    }

    fun selectCategory(categoryName: String?) {
        filter.update { it.copy(category = if (it.category == categoryName) null else categoryName) }
    }

    /** [id] null adds a new expense; otherwise the expense and its cash row are updated together. */
    fun saveExpense(id: Long?, input: ExpenseInput, onDone: (error: String?) -> Unit) {
        viewModelScope.launch {
            val result = if (id == null) financeRepository.addExpense(input, operatorName()).map { }
            else financeRepository.updateExpense(id, input, operatorName())
            result.onSuccess { _message.value = FinanceMessage(if (id == null) "Expense saved." else "Expense updated.") }
            onDone(result.exceptionOrNull()?.let { it.message ?: "Could not save the expense." })
        }
    }

    fun deleteExpense(expense: ExpenseEntity, onDone: (error: String?) -> Unit = {}) {
        viewModelScope.launch {
            val result = financeRepository.deleteExpense(expense.id, operatorName())
            _message.value = result.fold(
                onSuccess = { FinanceMessage("Expense deleted; the money is back in ${PaymentMethods.balanceLabel(expense.paymentMethod)}.") },
                onFailure = { FinanceMessage(it.message ?: "Could not delete the expense.", isError = true) }
            )
            onDone(result.exceptionOrNull()?.message)
        }
    }

    fun addCategory(name: String, onDone: (error: String?) -> Unit) {
        viewModelScope.launch {
            onDone(financeRepository.addExpenseCategory(name, operatorName()).exceptionOrNull()?.message)
        }
    }

    fun renameCategory(id: Long, newName: String, onDone: (error: String?) -> Unit) {
        viewModelScope.launch {
            val oldName = uiState.value.categories.firstOrNull { it.id == id }?.name
            val result = financeRepository.renameExpenseCategory(id, newName, operatorName())
            if (result.isSuccess && oldName != null && filter.value.category == oldName) {
                filter.update { it.copy(category = newName.trim().replace(Regex("\\s+"), " ")) }
            }
            onDone(result.exceptionOrNull()?.message)
        }
    }

    fun deleteCategory(id: Long, onDone: (error: String?) -> Unit) {
        viewModelScope.launch {
            onDone(financeRepository.deleteExpenseCategory(id, operatorName()).exceptionOrNull()?.message)
        }
    }

    fun dismissMessage() {
        _message.value = null
    }
}

// -------------------------------------------------------------------------------------------------
// Cash book
// -------------------------------------------------------------------------------------------------

data class CashBookUiState(
    /** Method whose running balance is shown; null = every method together. */
    val method: String? = PaymentMethods.CASH,
    val period: FinancePeriod = FinancePeriod.TODAY,
    val span: DateSpan = FinanceDates.spanFor(FinancePeriod.TODAY),
    val page: CashBookPage = CashBookPage.EMPTY,
    /** Balance right now per method: Cash, bKash, Nagad, Rocket, Card, Bank, then any others. */
    val balances: List<PaymentMethodBalance> = PaymentMethods.ALL.map { PaymentMethodBalance(it, 0.0) },
    val totalMoney: Double = 0.0,
    /** Methods that already have an opening balance. */
    val openingSetFor: Set<String> = emptySet(),
    val isLoading: Boolean = true
) {
    val methodsWithoutOpening: List<String> get() = PaymentMethods.ALL.filter { it !in openingSetFor }
    fun balanceOf(method: String): Double =
        balances.firstOrNull { it.paymentMethod == PaymentMethods.normalize(method) }?.balance ?: 0.0
}

private data class CashBookFilter(val method: String?, val period: FinancePeriod, val span: DateSpan)

@OptIn(ExperimentalCoroutinesApi::class)
class CashBookViewModel(
    private val financeRepository: FinanceRepository,
    /** Name written to the audit log, e.g. `{ app.operatorName() }`. */
    private val operatorName: () -> String = { "Admin" }
) : ViewModel() {

    private val filter = MutableStateFlow(
        CashBookFilter(PaymentMethods.CASH, FinancePeriod.TODAY, FinanceDates.spanFor(FinancePeriod.TODAY))
    )

    private val _message = MutableStateFlow<FinanceMessage?>(null)
    val message: StateFlow<FinanceMessage?> = _message.asStateFlow()

    val uiState: StateFlow<CashBookUiState> = combine(
        filter.flatMapLatest { f -> financeRepository.cashBook(f.method, f.span.start, f.span.end).map { f to it } },
        financeRepository.balancesByMethod,
        financeRepository.methodsWithOpeningEntry
    ) { filtered, balances, openingSet ->
        val (f, page) = filtered
        CashBookUiState(
            method = f.method,
            period = f.period,
            span = f.span,
            page = page,
            balances = balances,
            totalMoney = balances.sumOf { it.balance },
            openingSetFor = openingSet,
            isLoading = false
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), CashBookUiState())

    fun selectMethod(method: String?) {
        filter.update { it.copy(method = method?.let { m -> PaymentMethods.normalize(m) }) }
    }

    fun selectPeriod(period: FinancePeriod) {
        if (period == FinancePeriod.CUSTOM) return // the screen opens the date range picker and calls setCustomRange
        filter.update { it.copy(period = period, span = FinanceDates.spanFor(period)) }
    }

    fun setCustomRange(startLocal: Long, endLocal: Long) {
        val start = DateTimeUtils.getStartOfDay(minOf(startLocal, endLocal))
        val end = DateTimeUtils.getEndOfDay(maxOf(startLocal, endLocal))
        filter.update { it.copy(period = periodForSpan(DateSpan(start, end)), span = DateSpan(start, end)) }
    }

    /** Previous / next day while one day is shown; never past today. */
    fun shiftDay(days: Int) {
        val current = filter.value.span
        if (!current.isSingleDay) return
        val target = FinanceDates.shiftDays(current.start, days)
        if (DateTimeUtils.getStartOfDay(target) > DateTimeUtils.getStartOfDay(System.currentTimeMillis())) return
        val span = FinanceDates.day(target)
        filter.update { it.copy(period = periodForSpan(span), span = span) }
    }

    private fun periodForSpan(span: DateSpan): FinancePeriod = when (span) {
        FinanceDates.spanFor(FinancePeriod.TODAY) -> FinancePeriod.TODAY
        FinanceDates.spanFor(FinancePeriod.YESTERDAY) -> FinancePeriod.YESTERDAY
        else -> FinancePeriod.CUSTOM
    }

    /** Manual "Cash in" ([flowDirection] IN) or "Cash out" (OUT), dated now. */
    fun addManualEntry(flowDirection: String, method: String, amount: Double, note: String, onDone: (error: String?) -> Unit) {
        viewModelScope.launch {
            val result = financeRepository.addManualCashEntry(flowDirection, method, amount, note, createdBy = operatorName())
            result.onSuccess {
                val label = CashEntryType.label(CashEntryType.MANUAL_ADJUSTMENT, flowDirection.uppercase())
                _message.value = FinanceMessage("$label saved for ${PaymentMethods.label(method)}.")
            }
            onDone(result.exceptionOrNull()?.let { it.message ?: "Could not save." })
        }
    }

    fun setOpeningBalance(method: String, amount: Double, note: String, onDone: (error: String?) -> Unit) {
        viewModelScope.launch {
            val result = financeRepository.setOpeningBalance(amount, method, note, operatorName())
            result.onSuccess { _message.value = FinanceMessage("Opening balance set for ${PaymentMethods.label(method)}.") }
            onDone(result.exceptionOrNull()?.let { it.message ?: "Could not save." })
        }
    }

    fun moveMoney(fromMethod: String, toMethod: String, amount: Double, note: String, onDone: (error: String?) -> Unit) {
        viewModelScope.launch {
            val result = financeRepository.moveMoney(fromMethod, toMethod, amount, note, createdBy = operatorName())
            result.onSuccess {
                _message.value = FinanceMessage("Moved from ${PaymentMethods.label(fromMethod)} to ${PaymentMethods.label(toMethod)}.")
            }
            onDone(result.exceptionOrNull()?.let { it.message ?: "Could not save." })
        }
    }

    /** Only entries typed in the cash book (opening, cash in/out, move money) can be deleted here. */
    fun deleteEntry(entry: CashRegisterTransactionEntity, onDone: (error: String?) -> Unit = {}) {
        viewModelScope.launch {
            val result = financeRepository.deleteManualCashEntry(entry.id, operatorName())
            _message.value = result.fold(
                onSuccess = { FinanceMessage("Entry deleted.") },
                onFailure = { FinanceMessage(it.message ?: "Could not delete the entry.", isError = true) }
            )
            onDone(result.exceptionOrNull()?.message)
        }
    }

    fun dismissMessage() {
        _message.value = null
    }
}
