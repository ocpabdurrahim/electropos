package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.core.utils.DateTimeUtils
import com.example.data.local.database.AppDatabase
import com.example.data.local.entities.CashRegisterTransactionEntity
import com.example.data.local.entities.ExpenseEntity
import com.example.data.repository.CashEntryType
import com.example.data.repository.ExpenseInput
import com.example.data.repository.FinanceRepository
import com.example.data.repository.PaymentMethods
import com.example.ui.viewmodels.FinanceDates
import com.example.ui.viewmodels.parseAmountInput
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Calendar

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExpensesAndCashBookTest {

    private lateinit var database: AppDatabase
    private lateinit var repo: FinanceRepository

    private val financeDao get() = database.financeDao()
    private val hour = 60L * 60 * 1000
    private val day = 24 * hour

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repo = FinanceRepository(database)
        runBlocking {
            repo.addExpenseCategory("Shop Rent").getOrThrow()
            repo.addExpenseCategory("Electricity Bill").getOrThrow()
        }
    }

    @After
    fun tearDown() {
        database.close()
    }

    private suspend fun expenseCashRows(expenseId: Long) =
        financeDao.getCashTransactionsForReference(CashEntryType.EXPENSE, expenseId)

    private suspend fun balance(method: String) = repo.getMethodBalance(method)

    private fun localDay(year: Int, month: Int, dayOfMonth: Int): Long =
        Calendar.getInstance().apply { clear(); set(year, month - 1, dayOfMonth, 0, 0, 0) }.timeInMillis

    private suspend fun insertRow(type: String, amount: Double, direction: String, method: String, date: Long): Long =
        financeDao.insertCashTransaction(
            CashRegisterTransactionEntity(type = type, amount = amount, flowDirection = direction, paymentMethod = method, date = date)
        )

    // ---------------------------------------------------------------------------------------------
    // Expenses
    // ---------------------------------------------------------------------------------------------

    @Test
    fun addExpense_writesOneMatchingCashOutRow_andLowersTheBalance() = runBlocking {
        repo.setOpeningBalance(10_000.0).getOrThrow()
        val date = System.currentTimeMillis() - 2 * hour

        val id = repo.addExpense(
            ExpenseInput("Shop Rent", "October rent", 3_000.0, "CASH", date, referenceNo = "R-1", notes = "paid landlord")
        ).getOrThrow()

        val expense = repo.getExpenseById(id)!!
        assertEquals(3_000.0, expense.amount, 0.001)
        assertEquals(date, expense.expenseDate)

        val rows = expenseCashRows(id)
        assertEquals(1, rows.size)
        val row = rows.single()
        assertEquals(CashEntryType.EXPENSE, row.type)
        assertEquals(CashEntryType.OUT, row.flowDirection)
        assertEquals(3_000.0, row.amount, 0.001)
        assertEquals("CASH", row.paymentMethod)
        assertEquals(date, row.date)
        assertEquals("October rent", row.referenceText)
        assertEquals("Shop Rent - paid landlord", row.notes)

        assertEquals(7_000.0, balance("CASH"), 0.001)
        assertEquals(7_000.0, financeDao.getCurrentCashBalance().first(), 0.001)
        val dayTotal = financeDao.getTotalExpensesBetween(DateTimeUtils.getStartOfDay(date), DateTimeUtils.getEndOfDay(date)).first()
        assertEquals(3_000.0, dayTotal ?: 0.0, 0.001)
    }

    @Test
    fun addExpense_rejectsBadInput_andWritesNothing() = runBlocking {
        for (bad in listOf(0.0, -5.0, Double.NaN, Double.POSITIVE_INFINITY, 0.001)) {
            val result = repo.addExpense(ExpenseInput("Shop Rent", "x", bad))
            assertTrue("amount $bad should be refused", result.isFailure)
        }
        assertTrue(repo.addExpense(ExpenseInput("  ", "x", 100.0)).isFailure)
        assertTrue(financeDao.getAllExpenses().first().isEmpty())
        assertTrue(financeDao.getAllCashTransactions().first().isEmpty())

        // A blank title falls back to the category; methods are stored upper-case
        val id = repo.addExpense(ExpenseInput("Shop Rent", "  ", 50.0, paymentMethod = "bkash")).getOrThrow()
        val saved = repo.getExpenseById(id)!!
        assertEquals("Shop Rent", saved.title)
        assertEquals("BKASH", saved.paymentMethod)
        assertEquals("BKASH", expenseCashRows(id).single().paymentMethod)
    }

    @Test
    fun addExpense_usesExistingCategorySpelling_andAddsNewCategories() = runBlocking {
        val a = repo.addExpense(ExpenseInput("shop rent", "Rent", 100.0)).getOrThrow()
        assertEquals("Shop Rent", repo.getExpenseById(a)!!.categoryName)

        repo.addExpense(ExpenseInput("Generator Fuel", "Diesel", 400.0)).getOrThrow()
        val names = repo.expenseCategories.first().map { it.name }
        assertTrue("Generator Fuel" in names)
        assertEquals(1, names.count { it.equals("shop rent", ignoreCase = true) })
    }

    @Test
    fun editExpense_updatesTheSameCashRow_andMovesMoneyBetweenMethods() = runBlocking {
        repo.setOpeningBalance(10_000.0, "CASH").getOrThrow()
        repo.setOpeningBalance(5_000.0, "BKASH").getOrThrow()
        val id = repo.addExpense(ExpenseInput("Electricity Bill", "DESCO", 1_000.0, "CASH")).getOrThrow()
        val rowId = expenseCashRows(id).single().id
        assertEquals(9_000.0, balance("CASH"), 0.001)

        val newDate = System.currentTimeMillis() - day
        repo.updateExpense(id, ExpenseInput("Electricity Bill", "DESCO October", 1_500.0, "BKASH", newDate, notes = "late fee")).getOrThrow()

        val expense = repo.getExpenseById(id)!!
        assertEquals(1_500.0, expense.amount, 0.001)
        assertEquals("BKASH", expense.paymentMethod)
        assertEquals(newDate, expense.expenseDate)

        val rows = expenseCashRows(id)
        assertEquals(1, rows.size)
        val row = rows.single()
        assertEquals(rowId, row.id)
        assertEquals(1_500.0, row.amount, 0.001)
        assertEquals("BKASH", row.paymentMethod)
        assertEquals(newDate, row.date)
        assertEquals("DESCO October", row.referenceText)

        assertEquals(10_000.0, balance("CASH"), 0.001)
        assertEquals(3_500.0, balance("BKASH"), 0.001)

        // Invalid edit changes nothing
        assertTrue(repo.updateExpense(id, ExpenseInput("Electricity Bill", "x", 0.0)).isFailure)
        assertEquals(1_500.0, repo.getExpenseById(id)!!.amount, 0.001)
        assertTrue(repo.updateExpense(999L, ExpenseInput("Electricity Bill", "x", 10.0)).isFailure)
    }

    @Test
    fun editExpense_withoutACashRow_addsOne_andDuplicatesAreRemoved() = runBlocking {
        // An old expense saved without its cash row
        val legacyId = financeDao.insertExpense(ExpenseEntity(categoryName = "Shop Rent", title = "Old", amount = 200.0))
        repo.updateExpense(legacyId, ExpenseInput("Shop Rent", "Old", 250.0)).getOrThrow()
        assertEquals(1, expenseCashRows(legacyId).size)
        assertEquals(-250.0, balance("CASH"), 0.001)

        // An expense whose cash row was written twice
        val id = repo.addExpense(ExpenseInput("Shop Rent", "Twice", 100.0)).getOrThrow()
        insertRow(CashEntryType.EXPENSE, 100.0, CashEntryType.OUT, "CASH", System.currentTimeMillis())
            .also { financeDao.updateCashTransaction(financeDao.getCashTransactionById(it)!!.copy(referenceId = id)) }
        assertEquals(2, expenseCashRows(id).size)
        repo.updateExpense(id, ExpenseInput("Shop Rent", "Twice", 100.0)).getOrThrow()
        assertEquals(1, expenseCashRows(id).size)
        assertEquals(-350.0, balance("CASH"), 0.001)
    }

    @Test
    fun deleteExpense_removesItsCashRow_andRestoresTheBalance() = runBlocking {
        repo.setOpeningBalance(2_000.0).getOrThrow()
        val keep = repo.addExpense(ExpenseInput("Shop Rent", "Keep", 300.0)).getOrThrow()
        val gone = repo.addExpense(ExpenseInput("Shop Rent", "Gone", 500.0)).getOrThrow()
        assertEquals(1_200.0, balance("CASH"), 0.001)

        repo.deleteExpense(gone).getOrThrow()

        assertNull(repo.getExpenseById(gone))
        assertTrue(expenseCashRows(gone).isEmpty())
        assertEquals(1, expenseCashRows(keep).size)
        assertEquals(1_700.0, balance("CASH"), 0.001)
        assertTrue(repo.deleteExpense(gone).isFailure)
    }

    // ---------------------------------------------------------------------------------------------
    // Categories
    // ---------------------------------------------------------------------------------------------

    @Test
    fun categories_add_rename_andDeleteRules() = runBlocking {
        assertTrue(repo.addExpenseCategory("shop rent").isFailure) // same name, other case
        assertTrue(repo.addExpenseCategory("   ").isFailure)

        val rentId = repo.expenseCategories.first().first { it.name == "Shop Rent" }.id
        val billId = repo.expenseCategories.first().first { it.name == "Electricity Bill" }.id
        val expenseId = repo.addExpense(ExpenseInput("Shop Rent", "October", 8_000.0, notes = "cash")).getOrThrow()

        assertTrue(repo.renameExpenseCategory(rentId, "electricity bill").isFailure) // clashes
        repo.renameExpenseCategory(rentId, "Rent").getOrThrow()

        assertEquals("Rent", repo.getExpenseById(expenseId)!!.categoryName)
        assertEquals("Rent - cash", expenseCashRows(expenseId).single().notes)
        assertEquals(1, repo.expenseCategoryUsage.first()["Rent"])

        val inUse = repo.deleteExpenseCategory(rentId)
        assertTrue(inUse.isFailure)
        assertTrue(inUse.exceptionOrNull()!!.message!!.contains("1 expense"))
        assertTrue(repo.expenseCategories.first().any { it.id == rentId })

        repo.deleteExpenseCategory(billId).getOrThrow()
        assertTrue(repo.expenseCategories.first().none { it.id == billId })
    }

    // ---------------------------------------------------------------------------------------------
    // Cash book
    // ---------------------------------------------------------------------------------------------

    @Test
    fun manualCashInOut_andBalancePerMethod() = runBlocking {
        repo.addManualCashEntry("IN", "CASH", 2_000.0, "change money").getOrThrow()
        repo.addManualCashEntry("OUT", "CASH", 500.0, "owner took home").getOrThrow()
        repo.addManualCashEntry("in", "nagad", 800.0, "").getOrThrow()
        assertTrue(repo.addManualCashEntry("SIDEWAYS", "CASH", 10.0, "").isFailure)
        assertTrue(repo.addManualCashEntry("IN", "CASH", 0.0, "").isFailure)

        val rows = financeDao.getAllCashTransactions().first()
        assertEquals(3, rows.size)
        assertTrue(rows.all { it.type == CashEntryType.MANUAL_ADJUSTMENT })

        // Old rows saved with a blank method count as cash
        insertRow(CashEntryType.SALE_CASH, 100.0, CashEntryType.IN, "", System.currentTimeMillis())

        val balances = repo.balancesByMethod.first()
        assertEquals(PaymentMethods.ALL, balances.map { it.paymentMethod })
        val byMethod = balances.associate { it.paymentMethod to it.balance }
        assertEquals(1_600.0, byMethod.getValue("CASH"), 0.001)
        assertEquals(800.0, byMethod.getValue("NAGAD"), 0.001)
        assertEquals(0.0, byMethod.getValue("BKASH"), 0.001)
        assertEquals(1_600.0, balance("CASH"), 0.001)
        assertEquals(1_600.0, financeDao.getCurrentCashBalance().first(), 0.001)

        // An unusual method still shows, after the usual ones
        insertRow(CashEntryType.SALE_CASH, 40.0, CashEntryType.IN, "OTHER", System.currentTimeMillis())
        assertEquals("OTHER", repo.balancesByMethod.first().last().paymentMethod)
    }

    @Test
    fun openingBalance_oncePerMethod_datedBeforeTheFirstEntry() = runBlocking {
        val firstSale = System.currentTimeMillis() - 3 * day
        insertRow(CashEntryType.SALE_CASH, 500.0, CashEntryType.IN, "CASH", firstSale)

        val id = repo.setOpeningBalance(1_000.0, "CASH", "counted drawer").getOrThrow()
        val opening = financeDao.getCashTransactionById(id)!!
        assertEquals(CashEntryType.OPENING_CASH, opening.type)
        assertEquals(CashEntryType.IN, opening.flowDirection)
        assertTrue(opening.date < firstSale)

        assertTrue(repo.setOpeningBalance(200.0, "CASH").isFailure)
        assertTrue(repo.setOpeningBalance(0.0, "BANK").isFailure)
        repo.setOpeningBalance(300.0, "BKASH").getOrThrow()

        assertEquals(setOf("CASH", "BKASH"), repo.methodsWithOpeningEntry.first())
        assertEquals(1_500.0, balance("CASH"), 0.001)
        assertEquals(300.0, balance("BKASH"), 0.001)

        // Every day after the first sale already includes the opening cash
        val page = repo.cashBook("CASH", DateTimeUtils.getStartOfDay(firstSale) + day, System.currentTimeMillis()).first()
        assertEquals(1_500.0, page.openingBalance, 0.001)
    }

    @Test
    fun moveMoney_writesAnOutInPair_keepsTheTotal_andDeletesAsAPair() = runBlocking {
        repo.setOpeningBalance(5_000.0).getOrThrow()
        val outId = repo.moveMoney("CASH", "BANK", 2_000.0, "deposit slip 44").getOrThrow()

        assertEquals(3_000.0, balance("CASH"), 0.001)
        assertEquals(2_000.0, balance("BANK"), 0.001)
        assertEquals(5_000.0, repo.balancesByMethod.first().sumOf { it.balance }, 0.001)

        val outRow = financeDao.getCashTransactionById(outId)!!
        val inRow = financeDao.getCashTransactionById(outRow.referenceId)!!
        assertEquals(CashEntryType.TRANSFER, outRow.type)
        assertEquals(CashEntryType.TRANSFER, inRow.type)
        assertEquals(CashEntryType.OUT, outRow.flowDirection)
        assertEquals(CashEntryType.IN, inRow.flowDirection)
        assertEquals("BANK", inRow.paymentMethod)
        assertEquals(outId, inRow.referenceId)

        assertTrue(repo.moveMoney("CASH", "cash", 10.0, "").isFailure)
        assertTrue(repo.moveMoney("CASH", "BKASH", -1.0, "").isFailure)

        // Deleting either half removes both
        repo.deleteManualCashEntry(inRow.id).getOrThrow()
        assertNull(financeDao.getCashTransactionById(outId))
        assertNull(financeDao.getCashTransactionById(inRow.id))
        assertEquals(5_000.0, balance("CASH"), 0.001)
        assertEquals(0.0, balance("BANK"), 0.001)
    }

    @Test
    fun deleteManualCashEntry_refusesRowsThatBelongToSomethingElse() = runBlocking {
        val expenseId = repo.addExpense(ExpenseInput("Shop Rent", "Rent", 100.0)).getOrThrow()
        val expenseRow = expenseCashRows(expenseId).single()
        val result = repo.deleteManualCashEntry(expenseRow.id)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("Expenses"))
        assertNotNull(financeDao.getCashTransactionById(expenseRow.id))

        val saleRow = insertRow(CashEntryType.SALE_CASH, 300.0, CashEntryType.IN, "CASH", System.currentTimeMillis())
        assertTrue(repo.deleteManualCashEntry(saleRow).isFailure)

        val manual = repo.addManualCashEntry("OUT", "CASH", 50.0, "tea").getOrThrow()
        repo.deleteManualCashEntry(manual).getOrThrow()
        assertNull(financeDao.getCashTransactionById(manual))
    }

    @Test
    fun cashBook_runningBalanceForAMethodAndForAllMoney() = runBlocking {
        val day0 = localDay(2026, 10, 1)
        insertRow(CashEntryType.OPENING_CASH, 1_000.0, CashEntryType.IN, "CASH", day0 - day)          // before the period
        insertRow(CashEntryType.SALE_CASH, 500.0, CashEntryType.IN, "CASH", day0 + 1 * hour)
        repo.addExpense(ExpenseInput("Shop Rent", "Rent", 200.0, "CASH", day0 + 2 * hour)).getOrThrow()
        insertRow(CashEntryType.SALE_CASH, 700.0, CashEntryType.IN, "BKASH", day0 + 3 * hour)          // other method
        repo.addManualCashEntry("OUT", "CASH", 100.0, "owner", date = day0 + 4 * hour).getOrThrow()
        insertRow(CashEntryType.SALE_CASH, 50.0, CashEntryType.IN, "CASH", day0 + day + hour)          // after the period

        val cash = repo.cashBook("CASH", day0, DateTimeUtils.getEndOfDay(day0)).first()
        assertEquals("CASH", cash.method)
        assertEquals(1_000.0, cash.openingBalance, 0.001)
        assertEquals(listOf("Sale", "Expense", "Cash out"), cash.lines.map { it.label })
        assertEquals(listOf(1_500.0, 1_300.0, 1_200.0), cash.lines.map { it.runningBalance })
        assertEquals(listOf(500.0, -200.0, -100.0), cash.lines.map { it.signedAmount })
        assertEquals(500.0, cash.totalIn, 0.001)
        assertEquals(300.0, cash.totalOut, 0.001)
        assertEquals(1_200.0, cash.closingBalance, 0.001)

        val all = repo.cashBook(null, day0, DateTimeUtils.getEndOfDay(day0)).first()
        assertNull(all.method)
        assertEquals(1_000.0, all.openingBalance, 0.001)
        assertEquals(4, all.lines.size)
        assertEquals(1_900.0, all.closingBalance, 0.001)

        val bkash = repo.cashBook("bkash", day0, DateTimeUtils.getEndOfDay(day0)).first()
        assertEquals(0.0, bkash.openingBalance, 0.001)
        assertEquals(700.0, bkash.closingBalance, 0.001)

        // The next day opens with the previous day's closing balance
        val next = repo.cashBook("CASH", day0 + day, DateTimeUtils.getEndOfDay(day0 + day)).first()
        assertEquals(1_200.0, next.openingBalance, 0.001)
        assertEquals(1_250.0, next.closingBalance, 0.001)
    }

    // ---------------------------------------------------------------------------------------------
    // Small helpers used by the screens
    // ---------------------------------------------------------------------------------------------

    @Test
    fun pickerDates_andAmountInput() {
        val local = localDay(2026, 10, 31)
        val utc = FinanceDates.localDayToPickerUtc(local + 15 * hour)
        assertEquals(local, FinanceDates.pickerUtcToLocalDay(utc))
        assertEquals(local + 9 * hour, FinanceDates.onDayWithTimeOf(local, localDay(2026, 10, 5) + 9 * hour))

        assertEquals(1500.0, parseAmountInput("1,500")!!, 0.001)
        assertEquals(250.5, parseAmountInput("২৫০.৫")!!, 0.001)
        assertNull(parseAmountInput("abc"))
    }
}
