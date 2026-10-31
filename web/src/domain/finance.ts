// FinanceRepository: expenses and their categories, the cash book and money moves.
import type { CashTx, Expense } from '../data/schema'
import { audit, check, fail } from './common'
import { formatMoney, methodLabel, normalizeMethod, PAYMENT_METHODS, roundMoney } from './money'
import type { ShopState, Tx } from './state'

export const CashEntryType = {
  OPENING_CASH: 'OPENING_CASH',
  SALE_CASH: 'SALE_CASH',
  CUSTOMER_COLLECTION: 'CUSTOMER_COLLECTION',
  SUPPLIER_PAYMENT: 'SUPPLIER_PAYMENT',
  EXPENSE: 'EXPENSE',
  REFUND: 'REFUND',
  MANUAL_ADJUSTMENT: 'MANUAL_ADJUSTMENT',
  TRANSFER: 'TRANSFER',
}

export function cashLabel(type: string, flow: string): string {
  switch (type) {
    case 'OPENING_CASH': return 'Opening cash'
    case 'SALE_CASH': return 'Sale'
    case 'CUSTOMER_COLLECTION': return 'Customer payment'
    case 'SUPPLIER_PAYMENT': return 'Supplier payment'
    case 'EXPENSE': return 'Expense'
    case 'REFUND': return flow === 'IN' ? 'Refund received' : 'Refund'
    case 'MANUAL_ADJUSTMENT': return flow === 'IN' ? 'Cash in' : 'Cash out'
    case 'TRANSFER': return 'Money moved'
    default: {
      const t = type.toLowerCase().replace(/_/g, ' ')
      return t.charAt(0).toUpperCase() + t.slice(1)
    }
  }
}

export const isManualCash = (type: string) => type === 'OPENING_CASH' || type === 'MANUAL_ADJUSTMENT' || type === 'TRANSFER'

const money = (v: number) => formatMoney(v, '৳')

function cleanAmount(amount: number): number {
  check(Number.isFinite(amount) && amount > 0, 'Amount must be more than 0.')
  const r = roundMoney(amount)
  check(r > 0, 'Amount must be more than 0.')
  check(r < 1_000_000_000, 'That amount is too large.')
  return r
}

function financeAudit(tx: Tx, action: string, recordId: number, description: string) {
  audit(tx, tx.ctx.actor, action, 'FINANCE', String(recordId), description)
}

export interface ExpenseInput {
  categoryName: string
  title: string
  amount: number
  paymentMethod?: string
  expenseDate?: number
  referenceNo?: string
  notes?: string
}

function cleanExpense(tx: Tx, input: ExpenseInput): Required<ExpenseInput> {
  const category = input.categoryName.trim()
  check(category !== '', 'Choose a category.')
  const amount = cleanAmount(input.amount)
  return {
    categoryName: category,
    title: input.title.trim() || category,
    amount,
    paymentMethod: normalizeMethod(input.paymentMethod),
    referenceNo: (input.referenceNo ?? '').trim(),
    notes: (input.notes ?? '').trim(),
    expenseDate: input.expenseDate && input.expenseDate > 0 ? input.expenseDate : tx.now,
  }
}

const sameName = (a: string, b: string) => a.toLowerCase() === b.toLowerCase()

function findCategory(tx: Tx, name: string) {
  return tx.all('expense_categories').sort((a, b) => a.id - b.id).find(c => sameName(c.name, name))
}

/** Category as stored (existing spelling wins); a new name is added to the list. */
function ensureCategory(tx: Tx, name: string): string {
  const found = findCategory(tx, name)
  if (found) return found.name
  tx.insert('expense_categories', { name, isDefault: false })
  return name
}

function expenseCashRow(tx: Tx, expense: Expense, existing: CashTx | undefined): Partial<CashTx> {
  return {
    type: CashEntryType.EXPENSE, amount: expense.amount, flowDirection: 'OUT', paymentMethod: expense.paymentMethod,
    referenceId: expense.id, referenceText: expense.title,
    notes: [expense.categoryName, expense.notes].filter(s => s.trim() !== '').join(' - '), date: expense.expenseDate,
    createdAt: existing?.createdAt ?? tx.now,
  }
}

const expenseRows = (tx: Tx, expenseId: number) =>
  tx.where('cash_register_transactions', 'referenceId', expenseId).filter(r => r.type === CashEntryType.EXPENSE).sort((a, b) => a.id - b.id)

/** Makes the expense's cash row match it: updates the first, removes duplicates, adds one if missing. */
function syncExpenseCashRow(tx: Tx, expense: Expense, createIfMissing: boolean) {
  const rows = expenseRows(tx, expense.id)
  if (rows.length === 0) {
    if (createIfMissing) tx.insert('cash_register_transactions', expenseCashRow(tx, expense, undefined))
    return
  }
  tx.put('cash_register_transactions', { ...rows[0], ...expenseCashRow(tx, expense, rows[0]) })
  for (const r of rows.slice(1)) tx.remove('cash_register_transactions', r.id)
}

export function addExpense(tx: Tx, input: ExpenseInput): number {
  const c = cleanExpense(tx, input)
  const categoryName = ensureCategory(tx, c.categoryName)
  const expense = tx.insert('expenses', {
    categoryName, title: c.title, amount: c.amount, paymentMethod: c.paymentMethod, referenceNo: c.referenceNo,
    expenseDate: c.expenseDate, notes: c.notes, createdAt: tx.now,
  })
  tx.insert('cash_register_transactions', expenseCashRow(tx, expense, undefined))
  financeAudit(tx, 'EXPENSE_RECORDED', expense.id,
    `Recorded expense '${c.title}' (${categoryName}) for ${money(c.amount)} via ${methodLabel(c.paymentMethod)}`)
  return expense.id
}

export function updateExpense(tx: Tx, id: number, input: ExpenseInput) {
  const c = cleanExpense(tx, input)
  const old = tx.get('expenses', id) ?? fail('This expense no longer exists.')
  const categoryName = ensureCategory(tx, c.categoryName)
  const updated = tx.put('expenses', {
    ...old, categoryName, title: c.title, amount: c.amount, paymentMethod: c.paymentMethod, referenceNo: c.referenceNo,
    expenseDate: c.expenseDate, notes: c.notes,
  })
  syncExpenseCashRow(tx, updated, true)
  financeAudit(tx, 'EXPENSE_UPDATED', id,
    `Changed expense '${old.title}': ${money(old.amount)} via ${methodLabel(old.paymentMethod)} ` +
    `to '${updated.title}' ${money(updated.amount)} via ${methodLabel(updated.paymentMethod)}`)
}

/** Deletes the expense and its cash register row, so the money is back in the balance. */
export function deleteExpense(tx: Tx, id: number) {
  const old = tx.get('expenses', id) ?? fail('This expense no longer exists.')
  for (const r of expenseRows(tx, id)) tx.remove('cash_register_transactions', r.id)
  tx.remove('expenses', id)
  financeAudit(tx, 'EXPENSE_DELETED', id,
    `Deleted expense '${old.title}' (${old.categoryName}) of ${money(old.amount)} via ${methodLabel(old.paymentMethod)}`)
}

function cleanCategoryName(name: string): string {
  const clean = name.trim().replace(/\s+/g, ' ')
  check(clean !== '', 'Type a category name.')
  check(clean.length <= 40, 'Keep the name under 40 letters.')
  return clean
}

export function addExpenseCategory(tx: Tx, name: string): number {
  const clean = cleanCategoryName(name)
  check(!findCategory(tx, clean), `"${clean}" is already in the list.`)
  const c = tx.insert('expense_categories', { name: clean, isDefault: false })
  financeAudit(tx, 'EXPENSE_CATEGORY_ADDED', c.id, `Added expense category '${clean}'`)
  return c.id
}

/** Renames the category and every expense filed under it. */
export function renameExpenseCategory(tx: Tx, id: number, newName: string) {
  const clean = cleanCategoryName(newName)
  const category = tx.get('expense_categories', id) ?? fail('This category no longer exists.')
  if (category.name === clean) return
  const clash = findCategory(tx, clean)
  check(!clash || clash.id === id, `"${clean}" is already in the list.`)
  tx.put('expense_categories', { ...category, name: clean })
  for (const e of tx.where('expenses', 'categoryName', category.name)) tx.put('expenses', { ...e, categoryName: clean })
  for (const e of tx.where('expenses', 'categoryName', clean)) syncExpenseCashRow(tx, e, false)
  financeAudit(tx, 'EXPENSE_CATEGORY_RENAMED', id, `Renamed expense category '${category.name}' to '${clean}'`)
}

/** Only a category no expense uses can be deleted. */
export function deleteExpenseCategory(tx: Tx, id: number) {
  const category = tx.get('expense_categories', id) ?? fail('This category no longer exists.')
  const used = tx.where('expenses', 'categoryName', category.name).length
  check(used === 0, `"${category.name}" is used by ${used} expense${used === 1 ? '' : 's'}, so it can't be deleted. You can rename it.`)
  tx.remove('expense_categories', id)
  financeAudit(tx, 'EXPENSE_CATEGORY_DELETED', id, `Deleted expense category '${category.name}'`)
}

/** Owner adds change money (IN) or takes money out (OUT); type MANUAL_ADJUSTMENT. */
export function addManualCashEntry(tx: Tx, flowDirection: string, paymentMethod: string, amount: number, note: string, date?: number): number {
  const direction = flowDirection.trim().toUpperCase()
  check(direction === 'IN' || direction === 'OUT', 'Choose money in or money out.')
  const value = cleanAmount(amount)
  const method = normalizeMethod(paymentMethod)
  const label = cashLabel(CashEntryType.MANUAL_ADJUSTMENT, direction)
  const row = tx.insert('cash_register_transactions', {
    type: CashEntryType.MANUAL_ADJUSTMENT, amount: value, flowDirection: direction, paymentMethod: method, referenceText: label,
    notes: note.trim(), date: date ?? tx.now, createdAt: tx.now,
  })
  financeAudit(tx, 'CASH_MANUAL_ENTRY', row.id, `${label} ${money(value)} (${methodLabel(method)}) ${note.trim()}`.trim())
  return row.id
}

const methodOf = (r: CashTx) => normalizeMethod(r.paymentMethod)

/** Money a method held when the shop started (OPENING_CASH), once per method, dated just before its first entry. */
export function setOpeningBalance(tx: Tx, amount: number, paymentMethod = 'CASH', note = ''): number {
  const value = cleanAmount(amount)
  const method = normalizeMethod(paymentMethod)
  const rows = tx.all('cash_register_transactions').filter(r => methodOf(r) === method)
  check(!rows.some(r => r.type === CashEntryType.OPENING_CASH), `${methodLabel(method)} already has an opening balance.`)
  const earliest = rows.length ? Math.min(...rows.map(r => r.date)) : null
  const date = earliest !== null && earliest <= tx.now ? earliest - 1 : tx.now
  const row = tx.insert('cash_register_transactions', {
    type: CashEntryType.OPENING_CASH, amount: value, flowDirection: 'IN', paymentMethod: method, referenceText: 'Opening balance',
    notes: note.trim(), date, createdAt: tx.now,
  })
  financeAudit(tx, 'CASH_OPENING_SET', row.id, `Set opening balance of ${methodLabel(method)} to ${money(value)}`)
  return row.id
}

/** Moves money between methods: an OUT and an IN row of type TRANSFER pointing at each other. Returns the OUT id. */
export function moveMoney(tx: Tx, fromMethod: string, toMethod: string, amount: number, note: string, date?: number): number {
  const from = normalizeMethod(fromMethod)
  const to = normalizeMethod(toMethod)
  check(from !== to, 'Choose two different places to move the money between.')
  const value = cleanAmount(amount)
  const text = `${methodLabel(from)} → ${methodLabel(to)}`
  const when = date ?? tx.now
  const outId = tx.ctx.newId('cash_register_transactions')
  const inRow = tx.insert('cash_register_transactions', {
    type: CashEntryType.TRANSFER, amount: value, flowDirection: 'IN', paymentMethod: to, referenceId: outId, referenceText: text,
    notes: note.trim(), date: when, createdAt: tx.now,
  })
  tx.put('cash_register_transactions', {
    ...inRow, id: outId, flowDirection: 'OUT', paymentMethod: from, referenceId: inRow.id,
  })
  financeAudit(tx, 'CASH_MOVED', outId, `Moved ${money(value)} ${text} ${note.trim()}`.trim())
  return outId
}

/** Removes an entry typed in the cash book (both halves of a "move money"). */
export function deleteManualCashEntry(tx: Tx, id: number) {
  const row = tx.get('cash_register_transactions', id) ?? fail('This entry no longer exists.')
  if (!isManualCash(row.type)) {
    const where = row.type === 'EXPENSE' ? 'Expenses' : row.type === 'SUPPLIER_PAYMENT' ? "the supplier's page"
      : row.type === 'CUSTOMER_COLLECTION' ? "the customer's page" : 'the sale or purchase it came from'
    fail(`This ${cashLabel(row.type, row.flowDirection).toLowerCase()} entry can only be changed from ${where}.`)
  }
  tx.remove('cash_register_transactions', row.id)
  if (row.type === CashEntryType.TRANSFER && row.referenceId > 0) {
    const other = tx.get('cash_register_transactions', row.referenceId)
    if (other && other.type === CashEntryType.TRANSFER && other.referenceId === row.id) tx.remove('cash_register_transactions', other.id)
  }
  financeAudit(tx, 'CASH_ENTRY_DELETED', row.id,
    `Deleted ${cashLabel(row.type, row.flowDirection)} ${money(row.amount)} (${methodLabel(row.paymentMethod)})`)
}

// ---- Reading the cash book ----------------------------------------------------------

export interface CashBookLine {
  entry: CashTx
  label: string
  signedAmount: number
  runningBalance: number
}

export interface CashBookPage {
  method: string | null
  openingBalance: number
  totalIn: number
  totalOut: number
  closingBalance: number
  lines: CashBookLine[]
}

const signed = (r: CashTx) => (r.flowDirection === 'IN' ? r.amount : -r.amount)

/** Cash book for start..end inclusive; [method] null means every method together. */
export function cashBook(state: ShopState, method: string | null, start: number, end: number): CashBookPage {
  const m = method === null ? null : normalizeMethod(method)
  const rows = state.list('cash_register_transactions').filter(r => m === null || methodOf(r) === m)
  let opening = 0
  for (const r of rows) if (r.date < start) opening += signed(r)
  const inRange = rows.filter(r => r.date >= start && r.date <= end).sort((a, b) => a.date - b.date || a.id - b.id)
  let running = opening
  let totalIn = 0
  let totalOut = 0
  const lines = inRange.map(r => {
    if (r.flowDirection === 'IN') totalIn += r.amount
    else totalOut += r.amount
    running += signed(r)
    return { entry: r, label: cashLabel(r.type, r.flowDirection), signedAmount: signed(r), runningBalance: roundMoney(running) }
  })
  return {
    method: m, openingBalance: roundMoney(opening), totalIn: roundMoney(totalIn), totalOut: roundMoney(totalOut),
    closingBalance: roundMoney(running), lines,
  }
}

/** Balance per method: Cash first, then the usual methods (0 when unused), then anything else found. */
export function balancesByMethod(state: ShopState): { method: string; balance: number }[] {
  const by = new Map<string, number>()
  for (const r of state.list('cash_register_transactions')) by.set(methodOf(r), (by.get(methodOf(r)) ?? 0) + signed(r))
  const standard = PAYMENT_METHODS.map(m => ({ method: m as string, balance: roundMoney(by.get(m) ?? 0) }))
  const others = [...by.keys()].filter(k => !(PAYMENT_METHODS as readonly string[]).includes(k)).sort()
    .map(k => ({ method: k, balance: roundMoney(by.get(k)!) }))
  return [...standard, ...others]
}

export const methodBalance = (state: ShopState, method: string) =>
  roundMoney(state.list('cash_register_transactions').filter(r => methodOf(r) === normalizeMethod(method)).reduce((a, r) => a + signed(r), 0))

export const methodsWithOpening = (state: ShopState) =>
  new Set(state.list('cash_register_transactions').filter(r => r.type === 'OPENING_CASH').map(methodOf))
