// ReportsRepository / ReportsDao / ReportRanges: the Reports screen and its CSV.
// Net sales = sales - returns (tax included); gross profit = profit on sales (excl. tax) -
// profit given back by returns; net profit = gross profit - expenses. Cancelled/void sales are left out.
import { addDays, endOfDay, formatDate, startOfDay } from './dates'
import { methodLabel } from './money'
import type { ShopState } from './state'

export type ReportPeriod = 'TODAY' | 'YESTERDAY' | 'THIS_WEEK' | 'THIS_MONTH' | 'LAST_MONTH' | 'THIS_YEAR' | 'CUSTOM'
export const PERIOD_LABELS: Record<ReportPeriod, string> = {
  TODAY: 'Today', YESTERDAY: 'Yesterday', THIS_WEEK: 'This week', THIS_MONTH: 'This month', LAST_MONTH: 'Last month',
  THIS_YEAR: 'This year', CUSTOM: 'Custom range',
}
export type ChartGrouping = 'NONE' | 'DAY' | 'MONTH'

export interface ReportRange {
  period: ReportPeriod
  start: number
  end: number
  grouping: ChartGrouping
}

export const MAX_DAILY_BARS = 62

/** First day of the week for this browser's language (0 = Sunday … 6 = Saturday). */
export function localeFirstDay(): number {
  try {
    const loc = new Intl.Locale(navigator.language) as Intl.Locale & { weekInfo?: { firstDay: number }; getWeekInfo?: () => { firstDay: number } }
    const info = loc.getWeekInfo?.() ?? loc.weekInfo
    if (info) return info.firstDay % 7
  } catch {
    // older browsers
  }
  return 0
}

export function dayStarts(start: number, end: number): number[] {
  const out: number[] = []
  for (let d = startOfDay(start); d <= end; d = addDays(d, 1)) out.push(d)
  return out
}

/** Whole days from the day of [from] to the day of [to] (swapped if given backwards). */
export function customRange(from: number, to: number): ReportRange {
  const start = startOfDay(Math.min(from, to))
  const end = endOfDay(Math.max(from, to))
  const days = dayStarts(start, end).length
  return { period: 'CUSTOM', start, end, grouping: days <= 1 ? 'NONE' : days <= MAX_DAILY_BARS ? 'DAY' : 'MONTH' }
}

export function forPeriod(period: ReportPeriod, now = Date.now(), customStart?: number, customEnd?: number, firstDayOfWeek = 0): ReportRange {
  const today = startOfDay(now)
  switch (period) {
    case 'TODAY': return { period, start: today, end: endOfDay(now), grouping: 'NONE' }
    case 'YESTERDAY': {
      const y = addDays(today, -1)
      return { period, start: y, end: endOfDay(y), grouping: 'NONE' }
    }
    case 'THIS_WEEK': {
      let d = today
      while (new Date(d).getDay() !== firstDayOfWeek) d = addDays(d, -1)
      return { period, start: d, end: endOfDay(addDays(d, 6)), grouping: 'DAY' }
    }
    case 'THIS_MONTH': case 'LAST_MONTH': {
      const t = new Date(today)
      const first = new Date(t.getFullYear(), t.getMonth() - (period === 'LAST_MONTH' ? 1 : 0), 1).getTime()
      const last = new Date(new Date(first).getFullYear(), new Date(first).getMonth() + 1, 0).getTime()
      return { period, start: first, end: endOfDay(last), grouping: 'DAY' }
    }
    case 'THIS_YEAR': {
      const y = new Date(today).getFullYear()
      return { period, start: new Date(y, 0, 1).getTime(), end: endOfDay(new Date(y, 11, 31).getTime()), grouping: 'MONTH' }
    }
    case 'CUSTOM': return customRange(customStart ?? now, customEnd ?? customStart ?? now)
  }
}

export function isSingleDay(r: ReportRange) {
  return startOfDay(r.start) === startOfDay(r.end)
}

export function rangeDateLabel(r: ReportRange): string {
  if (isSingleDay(r)) return formatDate(r.start, 'dd MMM yyyy')
  const sameYear = formatDate(r.start, 'yyyy') === formatDate(r.end, 'yyyy')
  return `${formatDate(r.start, sameYear ? 'dd MMM' : 'dd MMM yyyy')} - ${formatDate(r.end, 'dd MMM yyyy')}`
}

export interface AmountByLabel { label: string; amount: number }

export interface SalesSummary {
  invoiceCount: number
  grossSales: number
  returnCount: number
  returns: number
  discounts: number
  taxOnSales: number
  taxReturned: number
  receivedByMethod: AmountByLabel[]
  receivedAtSale: number
  dueCollected: number
  refundsToCustomers: number
  dueCreated: number
  stillUnpaid: number
  netSales: number
  taxCollected: number
  moneyReceived: number
  netMoneyIn: number
}

export interface ProfitSummary {
  saleProfit: number
  returnedProfit: number
  expenses: number
  expensesByCategory: AmountByLabel[]
  grossProfit: number
  netProfit: number
}

export interface PurchaseSummary {
  purchaseCount: number
  purchased: number
  returns: number
  returnRefunds: number
  paid: number
  owed: number
  netPurchased: number
}

export interface DailyRow {
  dayStart: number
  invoices: number
  grossSales: number
  returns: number
  discounts: number
  tax: number
  grossProfit: number
  expenses: number
  netSales: number
  netProfit: number
}

export interface ChartPoint { label: string; fullLabel: string; start: number; sales: number; profit: number }
export interface TopProduct { productId: number; name: string; quantity: number; netSales: number; profit: number }
export interface PartyDue { id: number; name: string; phone: string; due: number }

export interface DuesSummary {
  customersOwe: number
  customerAdvance: number
  customersOwingCount: number
  topCustomers: PartyDue[]
  youOweSuppliers: number
  supplierAdvance: number
  suppliersOwedCount: number
  topSuppliers: PartyDue[]
}

export interface StockSummary { stockValue: number; lowStockCount: number; outOfStockCount: number; productCount: number }

export interface ReportData {
  range: ReportRange
  shopName: string
  currencySymbol: string
  generatedAt: number
  sales: SalesSummary
  profit: ProfitSummary
  purchases: PurchaseSummary
  daily: DailyRow[]
  chart: ChartPoint[]
  topByQuantity: TopProduct[]
  topByProfit: TopProduct[]
  dues: DuesSummary
  stock: StockSummary
}

interface DatedAmount { date: number; amount: number; tax: number; discount: number; profit: number }

const within = (t: number, r: ReportRange) => t >= r.start && t <= r.end
const activeSale = (status: string) => status !== 'CANCELLED' && status !== 'VOID'

export function buildDailyRows(range: ReportRange, sales: DatedAmount[], returns: DatedAmount[], expenses: DatedAmount[]): DailyRow[] {
  const days = dayStarts(range.start, range.end)
  const index = new Map(days.map((d, i) => [d, i]))
  const rows = days.map(d => ({ dayStart: d, invoices: 0, grossSales: 0, returns: 0, discounts: 0, tax: 0, grossProfit: 0, expenses: 0 }))
  const slot = (t: number) => index.get(startOfDay(t))
  for (const r of sales) {
    const i = slot(r.date)
    if (i === undefined) continue
    rows[i].invoices++
    rows[i].grossSales += r.amount
    rows[i].discounts += r.discount
    rows[i].tax += r.tax
    rows[i].grossProfit += r.profit
  }
  for (const r of returns) {
    const i = slot(r.date)
    if (i === undefined) continue
    rows[i].returns += r.amount
    rows[i].tax -= r.tax
    rows[i].grossProfit -= r.profit
  }
  for (const r of expenses) {
    const i = slot(r.date)
    if (i === undefined) continue
    rows[i].expenses += r.amount
  }
  return rows.map(r => ({ ...r, netSales: r.grossSales - r.returns, netProfit: r.grossProfit - r.expenses }))
}

export function buildChart(range: ReportRange, daily: DailyRow[]): ChartPoint[] {
  if (range.grouping === 'NONE') return []
  if (range.grouping === 'DAY') {
    const pattern = range.period === 'THIS_WEEK' ? 'EEE' : range.period === 'CUSTOM' ? 'd/M' : 'd'
    return daily.map(d => ({
      label: formatDate(d.dayStart, pattern), fullLabel: formatDate(d.dayStart, 'EEE, dd MMM yyyy'), start: d.dayStart,
      sales: d.netSales, profit: d.grossProfit,
    }))
  }
  const multiYear = formatDate(range.start, 'yyyy') !== formatDate(range.end, 'yyyy')
  const months = new Map<string, DailyRow[]>()
  for (const d of daily) {
    const k = formatDate(d.dayStart, 'yyyy-MM')
    months.set(k, [...(months.get(k) ?? []), d])
  }
  return [...months.values()].map(days => ({
    label: formatDate(days[0].dayStart, multiYear ? 'MMM yy' : 'MMM'), fullLabel: formatDate(days[0].dayStart, 'MMMM yyyy'),
    start: days[0].dayStart, sales: days.reduce((a, d) => a + d.netSales, 0), profit: days.reduce((a, d) => a + d.grossProfit, 0),
  }))
}

/** Every section of the report, from one state of the shop. */
export function loadReport(state: ShopState, range: ReportRange, topLimit = 10, dueLimit = 5, now = Date.now()): ReportData {
  const sales = state.list('sales').filter(s => within(s.saleDate, range) && activeSale(s.status))
  const cash = state.list('cash_register_transactions')
  const saleCashIn = new Map<number, number>()
  for (const c of cash) {
    if (c.type === 'SALE_CASH' && c.flowDirection === 'IN') saleCashIn.set(c.referenceId, (saleCashIn.get(c.referenceId) ?? 0) + c.amount)
  }
  const discountOf = (s: { discountAmount: number; itemDiscount: number; invoiceDiscount: number }) =>
    Math.max(s.discountAmount, s.itemDiscount + s.invoiceDiscount)

  const returns = state.list('sales_returns').filter(r => r.status === 'COMPLETED' && within(r.returnDate, range))
  const returnProfit = (rid: number, tax: number) => {
    let p = 0
    for (const i of state.where('sales_return_items', 'salesReturnId', rid)) {
      p += i.totalPrice - (i.restockOption === 'RESTOCK_SELLABLE' ? i.unitCostAtSale * i.quantity : 0)
    }
    return p - tax
  }

  const inRangeCash = cash.filter(c => within(c.date, range))
  const received = new Map<string, number>()
  for (const c of inRangeCash) {
    if (c.flowDirection === 'IN' && (c.type === 'SALE_CASH' || c.type === 'CUSTOMER_COLLECTION')) {
      const k = c.paymentMethod === '' ? 'CASH' : c.paymentMethod
      received.set(k, (received.get(k) ?? 0) + c.amount)
    }
  }
  const moneyIn = (type: string) => inRangeCash.filter(c => c.flowDirection === 'IN' && c.type === type).reduce((a, c) => a + c.amount, 0)
  const receivedByMethod = [...received.entries()].sort((a, b) => b[1] - a[1]).filter(([, v]) => Math.abs(v) > 0.004)
    .map(([k, v]) => ({ label: methodLabel(k), amount: v }))

  const grossSales = sales.reduce((a, s) => a + s.totalAmount, 0)
  const returnsValue = returns.reduce((a, r) => a + r.totalAmount, 0)
  const taxOnSales = sales.reduce((a, s) => a + s.taxAmount, 0)
  const taxReturned = returns.reduce((a, r) => a + r.taxAmount, 0)
  const refundsToCustomers = inRangeCash.filter(c => c.flowDirection === 'OUT' && c.type === 'REFUND').reduce((a, c) => a + c.amount, 0)
  const moneyReceived = receivedByMethod.reduce((a, r) => a + r.amount, 0)
  const salesSummary: SalesSummary = {
    invoiceCount: sales.length,
    grossSales,
    returnCount: returns.length,
    returns: returnsValue,
    discounts: sales.reduce((a, s) => a + discountOf(s), 0),
    taxOnSales,
    taxReturned,
    receivedByMethod,
    receivedAtSale: moneyIn('SALE_CASH'),
    dueCollected: moneyIn('CUSTOMER_COLLECTION'),
    refundsToCustomers,
    dueCreated: sales.reduce((a, s) => a + Math.max(0, s.totalAmount - (saleCashIn.get(s.id) ?? 0)), 0),
    stillUnpaid: sales.reduce((a, s) => a + s.dueAmount, 0),
    netSales: grossSales - returnsValue,
    taxCollected: taxOnSales - taxReturned,
    moneyReceived,
    netMoneyIn: moneyReceived - refundsToCustomers,
  }

  const expenses = state.list('expenses').filter(e => within(e.expenseDate, range))
  const byCategory = new Map<string, number>()
  for (const e of expenses) {
    const k = e.categoryName.trim() === '' ? 'Other' : e.categoryName
    byCategory.set(k, (byCategory.get(k) ?? 0) + e.amount)
  }
  const saleProfit = sales.reduce((a, s) => a + s.totalProfit, 0)
  const returnedProfit = returns.reduce((a, r) => a + returnProfit(r.id, r.taxAmount), 0)
  const expenseTotal = expenses.reduce((a, e) => a + e.amount, 0)
  const profit: ProfitSummary = {
    saleProfit, returnedProfit, expenses: expenseTotal,
    expensesByCategory: [...byCategory.entries()].sort((a, b) => b[1] - a[1]).map(([label, amount]) => ({ label, amount })),
    grossProfit: saleProfit - returnedProfit,
    netProfit: saleProfit - returnedProfit - expenseTotal,
  }

  const purchases = state.list('purchases').filter(p => p.status !== 'CANCELLED' && within(p.purchaseDate, range))
  const pReturns = state.list('purchase_returns').filter(r => r.status !== 'CANCELLED' && within(r.returnDate, range))
  const purchased = purchases.reduce((a, p) => a + p.totalAmount, 0)
  const pReturned = pReturns.reduce((a, r) => a + r.totalAmount, 0)
  const purchaseSummary: PurchaseSummary = {
    purchaseCount: purchases.length, purchased, returns: pReturned, returnRefunds: pReturns.reduce((a, r) => a + r.refundAmount, 0),
    paid: purchases.reduce((a, p) => a + p.paidAmount, 0), owed: purchases.reduce((a, p) => a + p.dueAmount, 0),
    netPurchased: purchased - pReturned,
  }

  const daily = buildDailyRows(
    range,
    sales.map(s => ({ date: s.saleDate, amount: s.totalAmount, tax: s.taxAmount, discount: discountOf(s), profit: s.totalProfit })),
    returns.map(r => ({ date: r.returnDate, amount: r.totalAmount, tax: r.taxAmount, discount: 0, profit: returnProfit(r.id, r.taxAmount) })),
    expenses.map(e => ({ date: e.expenseDate, amount: e.amount, tax: 0, discount: 0, profit: 0 })),
  )

  // Products sold in the period, less what came back from those same lines
  const completed = state.list('sales_returns').filter(r => r.status === 'COMPLETED')
  const retByItem = new Map<number, { qty: number; value: number; profit: number }>()
  for (const r of completed) {
    for (const i of state.where('sales_return_items', 'salesReturnId', r.id)) {
      if (i.saleItemId <= 0) continue
      const cur = retByItem.get(i.saleItemId) ?? { qty: 0, value: 0, profit: 0 }
      cur.qty += i.quantity
      cur.value += i.totalPrice
      cur.profit += i.totalPrice - (r.totalAmount > 0 ? (r.taxAmount * i.totalPrice) / r.totalAmount : 0) -
        (i.restockOption === 'RESTOCK_SELLABLE' ? i.unitCostAtSale * i.quantity : 0)
      retByItem.set(i.saleItemId, cur)
    }
  }
  const perProduct = new Map<number, TopProduct>()
  for (const s of sales) {
    const factor = s.totalAmount + s.invoiceDiscount > 0 ? s.totalAmount / (s.totalAmount + s.invoiceDiscount) : 0
    for (const si of state.where('sale_items', 'saleId', s.id)) {
      const cur = perProduct.get(si.productId) ?? { productId: si.productId, name: si.productName, quantity: 0, netSales: 0, profit: 0 }
      if (si.productName > cur.name) cur.name = si.productName
      const ret = retByItem.get(si.id)
      cur.quantity += si.quantity - (ret?.qty ?? 0)
      cur.netSales += si.totalPrice * factor - (ret?.value ?? 0)
      cur.profit += si.totalPrice * factor - si.tax - si.unitCostAtSale * si.quantity - (ret?.profit ?? 0)
      perProduct.set(si.productId, cur)
    }
  }
  const products = [...perProduct.values()]
  const byName = (a: TopProduct, b: TopProduct) => (a.name < b.name ? -1 : a.name > b.name ? 1 : 0)
  const topByQuantity = products.filter(p => p.quantity > 0)
    .sort((a, b) => b.quantity - a.quantity || b.profit - a.profit || byName(a, b)).slice(0, topLimit)
  const topByProfit = products.filter(p => p.quantity > 0 || Math.abs(p.profit) > 0.004)
    .sort((a, b) => b.profit - a.profit || b.quantity - a.quantity || byName(a, b)).slice(0, topLimit)

  const partyTotals = (rows: { id: number; name: string; phone: string; currentDue: number }[]) => ({
    owed: rows.reduce((a, r) => a + (r.currentDue > 0 ? r.currentDue : 0), 0),
    advance: rows.reduce((a, r) => a + (r.currentDue < 0 ? -r.currentDue : 0), 0),
    count: rows.filter(r => r.currentDue > 0.005).length,
    top: rows.filter(r => r.currentDue > 0.005).sort((a, b) => b.currentDue - a.currentDue || (a.name < b.name ? -1 : a.name > b.name ? 1 : 0))
      .slice(0, dueLimit).map(r => ({ id: r.id, name: r.name, phone: r.phone, due: r.currentDue })),
  })
  const c = partyTotals(state.list('customers'))
  const sp = partyTotals(state.list('suppliers'))
  const dues: DuesSummary = {
    customersOwe: c.owed, customerAdvance: c.advance, customersOwingCount: c.count, topCustomers: c.top,
    youOweSuppliers: sp.owed, supplierAdvance: sp.advance, suppliersOwedCount: sp.count, topSuppliers: sp.top,
  }

  const profile = state.profile
  return {
    range, shopName: profile.shopName, currencySymbol: profile.currencySymbol.trim() || '৳', generatedAt: now,
    sales: salesSummary, profit, purchases: purchaseSummary, daily, chart: buildChart(range, daily), topByQuantity, topByProfit,
    dues, stock: stockSummary(state),
  }
}

/** Stock value at cost and low / out-of-stock counts (active products; same rules as the dashboard). */
export function stockSummary(state: ShopState): StockSummary {
  const active = state.list('products').filter(p => p.isActive)
  return {
    stockValue: active.reduce((a, p) => a + p.currentStock * p.purchasePrice, 0),
    lowStockCount: active.filter(p => p.currentStock <= p.minStock && p.currentStock > 0).length,
    outOfStockCount: active.filter(p => p.currentStock <= 0).length,
    productCount: active.length,
  }
}

// ---- CSV ------------------------------------------------------------------------------

export function suggestedFileName(range: ReportRange): string {
  const from = formatDate(range.start, 'yyyyMMdd')
  const to = formatDate(range.end, 'yyyyMMdd')
  return from === to ? `report_${from}.csv` : `report_${from}_${to}.csv`
}

const num = (v: number) => (Math.abs(v) < 0.005 ? 0 : v).toFixed(2)

/** A text cell: quoted when needed, and never read by Excel as a formula. */
function text(value: string): string {
  let v = value
  if (v.length > 0 && '=+-@\t\r'.includes(v[0])) v = `'${v}`
  return /[,"\n\r]/.test(v) ? `"${v.replace(/"/g, '""')}"` : v
}

/** Period summary, then one row per day; amounts are plain numbers (2 decimals). */
export function buildCsv(report: ReportData): string {
  const lines: string[] = []
  const row = (...cells: string[]) => lines.push(cells.join(','))
  const item = (section: string, name: string, amount: number) => row(text(section), text(name), num(amount))
  const count = (section: string, name: string, value: number) => row(text(section), text(name), String(value))
  const r = report.range
  row(text('Report'), text(report.shopName.trim() || 'ElectroPOS'))
  row(text('Period'), text(PERIOD_LABELS[r.period]), formatDate(r.start, 'yyyy-MM-dd'), formatDate(r.end, 'yyyy-MM-dd'))
  row(text('Generated'), formatDate(report.generatedAt, 'yyyy-MM-dd HH:mm'))
  row(text('Currency'), text(report.currencySymbol))
  lines.push('')
  row(text('Section'), text('Item'), text('Amount'))
  const s = report.sales
  count('Sales', 'Invoices', s.invoiceCount)
  item('Sales', 'Gross sales (incl. tax)', s.grossSales)
  item('Sales', `Returns (${s.returnCount})`, s.returns)
  item('Sales', 'Net sales', s.netSales)
  item('Sales', 'Discounts given', s.discounts)
  item('Sales', 'Tax collected (after returns)', s.taxCollected)
  item('Sales', 'Due created', s.dueCreated)
  item('Sales', 'Still unpaid on these invoices', s.stillUnpaid)
  for (const m of s.receivedByMethod) item('Money received', m.label, m.amount)
  item('Money received', 'Total received', s.moneyReceived)
  item('Money received', 'Refunded to customers', s.refundsToCustomers)
  item('Money received', 'Net money in', s.netMoneyIn)
  const p = report.profit
  item('Profit', 'Profit on sales (excl. tax)', p.saleProfit)
  item('Profit', 'Given back by returns', p.returnedProfit)
  item('Profit', 'Gross profit', p.grossProfit)
  for (const e of p.expensesByCategory) item('Expenses', e.label, e.amount)
  item('Expenses', 'Total expenses', p.expenses)
  item('Profit', 'Net profit', p.netProfit)
  const pu = report.purchases
  count('Purchases', 'Purchase invoices', pu.purchaseCount)
  item('Purchases', 'Total purchased', pu.purchased)
  item('Purchases', 'Purchase returns', pu.returns)
  item('Purchases', 'Paid', pu.paid)
  item('Purchases', 'Still owed', pu.owed)
  item('Dues (now)', 'Customers owe the shop', report.dues.customersOwe)
  item('Dues (now)', 'Shop owes suppliers', report.dues.youOweSuppliers)
  item('Stock (now)', 'Stock value at cost', report.stock.stockValue)
  count('Stock (now)', 'Low stock products', report.stock.lowStockCount)
  count('Stock (now)', 'Out of stock products', report.stock.outOfStockCount)
  lines.push('')
  row(text('Date'), text('Invoices'), text('Gross sales'), text('Returns'), text('Net sales'), text('Discounts'), text('Tax'),
    text('Gross profit'), text('Expenses'), text('Net profit'))
  for (const d of report.daily) {
    row(formatDate(d.dayStart, 'yyyy-MM-dd'), String(d.invoices), num(d.grossSales), num(d.returns), num(d.netSales), num(d.discounts),
      num(d.tax), num(d.grossProfit), num(d.expenses), num(d.netProfit))
  }
  const days = report.daily
  const t = (f: (d: DailyRow) => number) => days.reduce((a, d) => a + f(d), 0)
  row(text('Total'), String(t(d => d.invoices)), num(t(d => d.grossSales)), num(t(d => d.returns)), num(t(d => d.netSales)),
    num(t(d => d.discounts)), num(t(d => d.tax)), num(t(d => d.grossProfit)), num(t(d => d.expenses)), num(t(d => d.netProfit)))
  return lines.join('\r\n') + '\r\n'
}

/** UTF-8 with a byte order mark, so Excel shows Bangla names correctly. */
export function csvBlob(csv: string): Blob {
  return new Blob([new Uint8Array([0xef, 0xbb, 0xbf]), csv], { type: 'text/csv;charset=utf-8' })
}
