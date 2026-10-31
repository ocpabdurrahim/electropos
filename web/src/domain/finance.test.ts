// Mirrors ExpensesAndCashBookTest.kt.
import { describe, expect, it } from 'vitest'
import { DAY_MS, endOfDay, localDate, startOfDay } from './dates'
import {
  addExpense, addExpenseCategory, addManualCashEntry, balancesByMethod, cashBook, deleteExpense, deleteExpenseCategory,
  deleteManualCashEntry, methodBalance, methodsWithOpening, moveMoney, renameExpenseCategory, setOpeningBalance, updateExpense,
} from './finance'
import { parseAmount } from './money'
import { TestShop } from './testkit'

const HOUR = 60 * 60 * 1000

function setup() {
  const shop = new TestShop()
  const balance = (m: string) => methodBalance(shop.state, m)
  const cashRows = (expenseId: number) =>
    shop.rows('cash_register_transactions').filter(r => r.type === 'EXPENSE' && r.referenceId === expenseId)
  const insertRow = (type: string, amount: number, flowDirection: string, paymentMethod: string, date: number) =>
    shop.insert('cash_register_transactions', { type, amount, flowDirection, paymentMethod, date })
  return { shop, balance, cashRows, insertRow }
}

describe('expenses', () => {
  it('an expense writes one matching cash-out row and lowers the balance', () => {
    const { shop, balance, cashRows } = setup()
    shop.run(tx => setOpeningBalance(tx, 10_000, 'CASH'))
    const date = Date.now() - DAY_MS
    const id = shop.run(tx => addExpense(tx, { categoryName: 'Shop Rent', title: 'October rent', amount: 3000, paymentMethod: 'CASH', expenseDate: date, notes: 'paid landlord' }))
    const e = shop.get('expenses', id)
    expect(e.amount).toBe(3000)
    expect(e.expenseDate).toBe(date)
    const rows = cashRows(id)
    expect(rows).toHaveLength(1)
    expect(rows[0]).toMatchObject({ type: 'EXPENSE', flowDirection: 'OUT', amount: 3000, paymentMethod: 'CASH', date, referenceText: 'October rent', notes: 'Shop Rent - paid landlord' })
    expect(balance('CASH')).toBe(7000)
  })

  it('bad input writes nothing; title falls back to the category; methods are upper-cased', () => {
    const { shop, cashRows } = setup()
    expect(() => shop.run(tx => addExpense(tx, { categoryName: ' ', title: 'x', amount: 10 }))).toThrow()
    expect(() => shop.run(tx => addExpense(tx, { categoryName: 'Rent', title: 'x', amount: 0 }))).toThrow()
    expect(shop.rows('expenses')).toHaveLength(0)
    const id = shop.run(tx => addExpense(tx, { categoryName: 'Shop Rent', title: '  ', amount: 50, paymentMethod: 'bkash' }))
    expect(shop.get('expenses', id).title).toBe('Shop Rent')
    expect(shop.get('expenses', id).paymentMethod).toBe('BKASH')
    expect(cashRows(id)[0].paymentMethod).toBe('BKASH')
  })

  it('the existing category spelling is kept and new categories are added', () => {
    const { shop } = setup()
    shop.run(tx => addExpense(tx, { categoryName: 'Shop Rent', title: 'a', amount: 1 }))
    const b = shop.run(tx => addExpense(tx, { categoryName: 'shop rent', title: 'b', amount: 1 }))
    expect(shop.get('expenses', b).categoryName).toBe('Shop Rent')
    expect(shop.rows('expense_categories').filter(c => c.name.toLowerCase() === 'shop rent')).toHaveLength(1)
  })

  it('editing updates the same cash row and moves money between methods', () => {
    const { shop, balance, cashRows } = setup()
    shop.run(tx => setOpeningBalance(tx, 10_000, 'CASH'))
    shop.run(tx => setOpeningBalance(tx, 5_000, 'BKASH'))
    const id = shop.run(tx => addExpense(tx, { categoryName: 'Electricity Bill', title: 'DESCO', amount: 1000, paymentMethod: 'CASH' }))
    const rowId = cashRows(id)[0].id
    expect(balance('CASH')).toBe(9000)
    const newDate = Date.now() - DAY_MS
    shop.run(tx => updateExpense(tx, id, { categoryName: 'Electricity Bill', title: 'DESCO October', amount: 1500, paymentMethod: 'BKASH', expenseDate: newDate, notes: 'late fee' }))
    const rows = cashRows(id)
    expect(rows).toHaveLength(1)
    expect(rows[0]).toMatchObject({ id: rowId, amount: 1500, paymentMethod: 'BKASH', date: newDate, referenceText: 'DESCO October' })
    expect(balance('CASH')).toBe(10_000)
    expect(balance('BKASH')).toBe(3500)
    expect(() => shop.run(tx => updateExpense(tx, id, { categoryName: 'Electricity Bill', title: 'x', amount: 0 }))).toThrow()
    expect(shop.get('expenses', id).amount).toBe(1500)
    expect(() => shop.run(tx => updateExpense(tx, 999, { categoryName: 'Electricity Bill', title: 'x', amount: 10 }))).toThrow()
  })

  it('an edit adds a missing cash row and removes duplicates', () => {
    const { shop, balance, cashRows, insertRow } = setup()
    const legacy = shop.insert('expenses', { categoryName: 'Shop Rent', title: 'Old', amount: 200 })
    shop.run(tx => updateExpense(tx, legacy, { categoryName: 'Shop Rent', title: 'Old', amount: 250 }))
    expect(cashRows(legacy)).toHaveLength(1)
    expect(balance('CASH')).toBe(-250)
    const id = shop.run(tx => addExpense(tx, { categoryName: 'Shop Rent', title: 'Twice', amount: 100 }))
    const dup = insertRow('EXPENSE', 100, 'OUT', 'CASH', Date.now())
    shop.run(tx => { tx.update('cash_register_transactions', dup, { referenceId: id }) })
    expect(cashRows(id)).toHaveLength(2)
    shop.run(tx => updateExpense(tx, id, { categoryName: 'Shop Rent', title: 'Twice', amount: 100 }))
    expect(cashRows(id)).toHaveLength(1)
    expect(balance('CASH')).toBe(-350)
  })

  it('deleting an expense removes its cash row (tombstones) and restores the balance', () => {
    const { shop, balance, cashRows } = setup()
    shop.run(tx => setOpeningBalance(tx, 2000))
    const keep = shop.run(tx => addExpense(tx, { categoryName: 'Shop Rent', title: 'Keep', amount: 300 }))
    const gone = shop.run(tx => addExpense(tx, { categoryName: 'Shop Rent', title: 'Gone', amount: 500 }))
    expect(balance('CASH')).toBe(1200)
    shop.run(tx => deleteExpense(tx, gone))
    expect(shop.state.get('expenses', gone)).toBeUndefined()
    expect(cashRows(gone)).toHaveLength(0)
    expect(cashRows(keep)).toHaveLength(1)
    expect(balance('CASH')).toBe(1700)
    expect(() => shop.run(tx => deleteExpense(tx, gone))).toThrow()
  })

  it('categories: add, rename (expenses follow) and delete rules', () => {
    const { shop, cashRows } = setup()
    const rentId = shop.run(tx => addExpenseCategory(tx, '  Shop   Rent '))
    expect(shop.get('expense_categories', rentId).name).toBe('Shop Rent')
    expect(() => shop.run(tx => addExpenseCategory(tx, 'shop rent'))).toThrow()
    const expenseId = shop.run(tx => addExpense(tx, { categoryName: 'Shop Rent', title: 'Rent', amount: 100, notes: 'cash' }))
    shop.run(tx => renameExpenseCategory(tx, rentId, 'Rent'))
    expect(shop.get('expenses', expenseId).categoryName).toBe('Rent')
    expect(cashRows(expenseId)[0].notes).toBe('Rent - cash')
    expect(() => shop.run(tx => deleteExpenseCategory(tx, rentId))).toThrow(/used by 1 expense/)
    const spare = shop.run(tx => addExpenseCategory(tx, 'Spare'))
    shop.run(tx => deleteExpenseCategory(tx, spare))
    expect(shop.state.get('expense_categories', spare)).toBeUndefined()
  })
})

describe('cash book', () => {
  it('manual cash in/out and the balance per method', () => {
    const { shop } = setup()
    shop.run(tx => addManualCashEntry(tx, 'IN', 'CASH', 2000, 'change money'))
    shop.run(tx => addManualCashEntry(tx, 'out', 'cash', 400, 'owner'))
    shop.run(tx => addManualCashEntry(tx, 'IN', 'NAGAD', 800, ''))
    expect(() => shop.run(tx => addManualCashEntry(tx, 'SIDEWAYS', 'CASH', 1, ''))).toThrow()
    const balances = balancesByMethod(shop.state)
    expect(balances.map(b => b.method)).toEqual(['CASH', 'BKASH', 'NAGAD', 'ROCKET', 'CARD', 'BANK'])
    const by = Object.fromEntries(balances.map(b => [b.method, b.balance]))
    expect(by.CASH).toBe(1600)
    expect(by.NAGAD).toBe(800)
    expect(by.BKASH).toBe(0)
    shop.insert('cash_register_transactions', { type: 'SALE_CASH', amount: 5, flowDirection: 'IN', paymentMethod: 'OTHER', date: Date.now() })
    expect(balancesByMethod(shop.state).at(-1)!.method).toBe('OTHER')
  })

  it('an opening balance is set once per method and dated before the first entry', () => {
    const { shop, balance, insertRow } = setup()
    const firstSale = Date.now() - 3 * DAY_MS
    insertRow('SALE_CASH', 500, 'IN', 'CASH', firstSale)
    const id = shop.run(tx => setOpeningBalance(tx, 1000, 'CASH', 'counted drawer'))
    const opening = shop.get('cash_register_transactions', id)
    expect(opening.type).toBe('OPENING_CASH')
    expect(opening.flowDirection).toBe('IN')
    expect(opening.date).toBeLessThan(firstSale)
    expect(() => shop.run(tx => setOpeningBalance(tx, 200, 'CASH'))).toThrow()
    expect(() => shop.run(tx => setOpeningBalance(tx, 0, 'BANK'))).toThrow()
    shop.run(tx => setOpeningBalance(tx, 300, 'BKASH'))
    expect(methodsWithOpening(shop.state)).toEqual(new Set(['CASH', 'BKASH']))
    expect(balance('CASH')).toBe(1500)
    expect(balance('BKASH')).toBe(300)
    expect(cashBook(shop.state, 'CASH', startOfDay(firstSale) + DAY_MS, Date.now()).openingBalance).toBe(1500)
  })

  it('moving money writes an OUT/IN pair, keeps the total and deletes as a pair', () => {
    const { shop, balance } = setup()
    shop.run(tx => setOpeningBalance(tx, 5000))
    const outId = shop.run(tx => moveMoney(tx, 'CASH', 'BANK', 2000, 'deposit slip 44'))
    expect(balance('CASH')).toBe(3000)
    expect(balance('BANK')).toBe(2000)
    expect(balancesByMethod(shop.state).reduce((a, b) => a + b.balance, 0)).toBe(5000)
    const outRow = shop.get('cash_register_transactions', outId)
    const inRow = shop.get('cash_register_transactions', outRow.referenceId)
    expect(outRow).toMatchObject({ type: 'TRANSFER', flowDirection: 'OUT', paymentMethod: 'CASH' })
    expect(inRow).toMatchObject({ type: 'TRANSFER', flowDirection: 'IN', paymentMethod: 'BANK', referenceId: outId })
    expect(() => shop.run(tx => moveMoney(tx, 'CASH', 'cash', 10, ''))).toThrow()
    expect(() => shop.run(tx => moveMoney(tx, 'CASH', 'BKASH', -1, ''))).toThrow()
    shop.run(tx => deleteManualCashEntry(tx, inRow.id))
    expect(shop.state.get('cash_register_transactions', outId)).toBeUndefined()
    expect(shop.state.get('cash_register_transactions', inRow.id)).toBeUndefined()
    expect(balance('CASH')).toBe(5000)
    expect(balance('BANK')).toBe(0)
  })

  it('rows that belong to something else cannot be deleted from the cash book', () => {
    const { shop, cashRows, insertRow } = setup()
    const expenseId = shop.run(tx => addExpense(tx, { categoryName: 'Shop Rent', title: 'Rent', amount: 100 }))
    const expenseRow = cashRows(expenseId)[0]
    expect(() => shop.run(tx => deleteManualCashEntry(tx, expenseRow.id))).toThrow(/Expenses/)
    const saleRow = insertRow('SALE_CASH', 300, 'IN', 'CASH', Date.now())
    expect(() => shop.run(tx => deleteManualCashEntry(tx, saleRow))).toThrow()
    const manual = shop.run(tx => addManualCashEntry(tx, 'OUT', 'CASH', 50, 'tea'))
    shop.run(tx => deleteManualCashEntry(tx, manual))
    expect(shop.state.get('cash_register_transactions', manual)).toBeUndefined()
  })

  it('running balance for a method and for all money', () => {
    const { shop, insertRow } = setup()
    const day0 = localDate(2026, 10, 1)
    insertRow('OPENING_CASH', 1000, 'IN', 'CASH', day0 - DAY_MS)
    insertRow('SALE_CASH', 500, 'IN', 'CASH', day0 + HOUR)
    shop.run(tx => addExpense(tx, { categoryName: 'Shop Rent', title: 'Rent', amount: 200, paymentMethod: 'CASH', expenseDate: day0 + 2 * HOUR }))
    insertRow('SALE_CASH', 700, 'IN', 'BKASH', day0 + 3 * HOUR)
    shop.run(tx => addManualCashEntry(tx, 'OUT', 'CASH', 100, 'owner', day0 + 4 * HOUR))
    insertRow('SALE_CASH', 50, 'IN', 'CASH', day0 + DAY_MS + HOUR)

    const cash = cashBook(shop.state, 'CASH', day0, endOfDay(day0))
    expect(cash.method).toBe('CASH')
    expect(cash.openingBalance).toBe(1000)
    expect(cash.lines.map(l => l.label)).toEqual(['Sale', 'Expense', 'Cash out'])
    expect(cash.lines.map(l => l.runningBalance)).toEqual([1500, 1300, 1200])
    expect(cash.lines.map(l => l.signedAmount)).toEqual([500, -200, -100])
    expect(cash.totalIn).toBe(500)
    expect(cash.totalOut).toBe(300)
    expect(cash.closingBalance).toBe(1200)

    const all = cashBook(shop.state, null, day0, endOfDay(day0))
    expect(all.method).toBeNull()
    expect(all.openingBalance).toBe(1000)
    expect(all.lines).toHaveLength(4)
    expect(all.closingBalance).toBe(1900)

    const bkash = cashBook(shop.state, 'bkash', day0, endOfDay(day0))
    expect(bkash.openingBalance).toBe(0)
    expect(bkash.closingBalance).toBe(700)

    const next = cashBook(shop.state, 'CASH', day0 + DAY_MS, endOfDay(day0 + DAY_MS))
    expect(next.openingBalance).toBe(1200)
    expect(next.closingBalance).toBe(1250)
  })

  it('amount input accepts grouping and Bangla digits', () => {
    expect(parseAmount('1,500')).toBe(1500)
    expect(parseAmount('২৫০.৫')).toBe(250.5)
    expect(parseAmount('abc')).toBeNull()
  })
})
