// Cache columns, recomputed from the synced rows exactly as docs/data-model.md
// ("Cache columns") says. Every client computes the same values, so caches never
// need to travel on their own.
import type {
  Customer, CustomerLedgerEntry, CustomerPayment, Product, Purchase, PurchaseReturn, Row, Sale, SalesReturn, StockTx,
  Supplier, SupplierLedgerEntry, SupplierPayment, TableName, Variant,
} from '../data/schema'
import { roundMoney } from './money'

export type Tables = { [T in TableName]: Map<number, Row<T>> }

export interface Settlement {
  paid: number
  due: number
  status: string
}

export interface Derived {
  productStock: Map<number, number>
  variantStock: Map<number, number>
  /** Weighted average cost of products that had a purchase; the others keep their entered cost. */
  productCost: Map<number, number>
  variantCost: Map<number, number>
  customerDue: Map<number, number>
  supplierDue: Map<number, number>
  customerRunning: Map<number, number>
  supplierRunning: Map<number, number>
  sales: Map<number, Settlement>
  purchases: Map<number, Settlement>
}

export const byTime = (a: { createdAt: number; id: number }, b: { createdAt: number; id: number }) =>
  a.createdAt - b.createdAt || a.id - b.id

function group<T, K>(rows: Iterable<T>, key: (r: T) => K): Map<K, T[]> {
  const out = new Map<K, T[]>()
  for (const r of rows) {
    const k = key(r)
    const list = out.get(k)
    if (list) list.push(r)
    else out.set(k, [r])
  }
  return out
}

/**
 * Weighted average cost from a product's (or variant's) stock movements, oldest first; the same
 * arithmetic as PurchaseRepository and the phone's sync/CacheRecompute.kt WeightedCost.replay:
 * - no PURCHASE movement: null (the entered cost stands);
 * - the average starts at the first movement's unitCost (the entered cost the opening stock recorded);
 * - PURCHASE q at c: avg = (max(stock,0)·avg + q·c) / (max(stock,0) + q);
 * - PURCHASE_CANCELLED (products only, [restoreOnCancel]): while stock stays above 0 the cancelled
 *   line's cost comes back out, the cost being that of its PURCHASE movement (same purchase and
 *   variant, same quantity first, each matched once); the phone never did this for variants;
 * - every other movement only changes the stock.
 */
export function replayCost(txs: readonly StockTx[], restoreOnCancel = true): number | null {
  const sorted = [...txs].sort(byTime)
  const purchases = sorted.filter(t => t.transactionType === 'PURCHASE')
  if (purchases.length === 0) return null
  const used = new Set<number>()
  let stock = 0
  let avg = sorted[0].unitCost
  for (const t of sorted) {
    if (t.transactionType === 'PURCHASE') {
      const base = Math.max(stock, 0)
      const q = t.quantityChange
      avg = base + q > 0 ? (base * avg + q * t.unitCost) / (base + q) : t.unitCost
      stock += q
    } else if (t.transactionType === 'PURCHASE_CANCELLED') {
      const q = -t.quantityChange
      const next = stock - q
      if (restoreOnCancel && next > 0) {
        const candidates = purchases.filter(p => p.referenceId === t.referenceId && p.variantId === t.variantId && !used.has(p.id))
        const match = candidates.find(p => p.quantityChange === q) ?? candidates[0]
        if (match) {
          used.add(match.id)
          const restored: number = (stock * avg - q * match.unitCost) / next
          if (restored > 0) avg = restored
        }
      }
      stock = next
    } else {
      stock += t.quantityChange
    }
  }
  return avg
}

const statusOf = (due: number, paid: number) => (due <= 0.005 ? 'PAID' : paid > 0.005 ? 'PARTIAL' : 'DUE')

/** CustomerRepository.reallocateSalePayments for one customer. */
export function settleCustomer(
  balance: number,
  salesOldestFirst: readonly Sale[],
  completedReturns: readonly SalesReturn[],
  ledger: readonly CustomerLedgerEntry[],
  payments: readonly CustomerPayment[],
): Map<number, Settlement> {
  const out = new Map<number, Settlement>()
  if (salesOldestFirst.length === 0) return out
  const returned = new Map<number, number>()
  const refunded = new Map<number, number>()
  for (const r of completedReturns) {
    returned.set(r.saleId, (returned.get(r.saleId) ?? 0) + r.totalAmount)
    refunded.set(r.saleId, (refunded.get(r.saleId) ?? 0) + r.refundAmount)
  }
  const linked = payments.filter(p => p.saleId > 0)
  let outstanding = Math.max(balance, 0)
  for (let i = salesOldestFirst.length - 1; i >= 0; i--) {
    const sale = salesOldestFirst[i]
    const net = roundMoney(Math.max(sale.totalAmount - (returned.get(sale.id) ?? 0), 0))
    const counter = `PAY-${sale.invoiceNumber}`
    let taken = 0
    for (const e of ledger) {
      if (e.transactionType === 'CUSTOMER_PAYMENT' && e.referenceId === sale.id && e.referenceNumber === counter) taken += e.amount
    }
    for (const p of linked) if (p.saleId === sale.id) taken += p.amount
    const settled = Math.max(taken - (refunded.get(sale.id) ?? 0), 0)
    const unpaid = Math.max(net - settled, 0)
    const due = roundMoney(Math.min(unpaid, outstanding))
    outstanding = Math.max(outstanding - due, 0)
    const paid = roundMoney(Math.max(net - due, 0))
    out.set(sale.id, { paid, due, status: statusOf(due, paid) })
  }
  return out
}

/** SupplierRepository.reallocatePurchasePayments for one supplier. */
export function settleSupplier(
  balance: number,
  purchasesOldestFirst: readonly Purchase[],
  returns: readonly PurchaseReturn[],
  payments: readonly SupplierPayment[],
): Map<number, Settlement> {
  const out = new Map<number, Settlement>()
  if (purchasesOldestFirst.length === 0) return out
  const returned = new Map<number, number>()
  const refunded = new Map<number, number>()
  for (const r of returns) {
    returned.set(r.purchaseId, (returned.get(r.purchaseId) ?? 0) + r.totalAmount)
    refunded.set(r.purchaseId, (refunded.get(r.purchaseId) ?? 0) + r.refundAmount)
  }
  const linkedPaid = new Map<number, number>()
  for (const p of payments) if (p.purchaseId > 0) linkedPaid.set(p.purchaseId, (linkedPaid.get(p.purchaseId) ?? 0) + p.amount)
  let outstanding = Math.max(balance, 0)
  for (let i = purchasesOldestFirst.length - 1; i >= 0; i--) {
    const p = purchasesOldestFirst[i]
    const net = roundMoney(Math.max(p.totalAmount - (returned.get(p.id) ?? 0), 0))
    const linked = Math.max((linkedPaid.get(p.id) ?? 0) - (refunded.get(p.id) ?? 0), 0)
    const unpaid = Math.max(net - linked, 0)
    const due = roundMoney(Math.min(unpaid, outstanding))
    outstanding = Math.max(outstanding - due, 0)
    const paid = roundMoney(Math.max(net - due, 0))
    out.set(p.id, { paid, due, status: statusOf(due, paid) })
  }
  return out
}

const isActiveSale = (s: Sale) => s.status !== 'CANCELLED' && s.status !== 'VOID'

export function derive(t: Tables): Derived {
  const productStock = new Map<number, number>()
  const variantStock = new Map<number, number>()
  const txByProduct = group(t.stock_transactions.values(), x => x.productId)
  const productCost = new Map<number, number>()
  const variantCost = new Map<number, number>()
  for (const [productId, txs] of txByProduct) {
    let stock = 0
    for (const x of txs) stock += x.quantityChange
    productStock.set(productId, stock)
    const cost = replayCost(txs)
    if (cost !== null) productCost.set(productId, cost)
    const byVariant = group(txs.filter(x => x.variantId > 0), x => x.variantId)
    for (const [variantId, vtx] of byVariant) {
      let vs = 0
      for (const x of vtx) vs += x.quantityChange
      variantStock.set(variantId, vs)
      const vc = replayCost(vtx, false)
      if (vc !== null) variantCost.set(variantId, vc)
    }
  }

  const customerDue = new Map<number, number>()
  const customerRunning = new Map<number, number>()
  const ledgerByCustomer = group(t.customer_ledger_entries.values(), e => e.customerId)
  for (const [customerId, entries] of ledgerByCustomer) {
    entries.sort(byTime)
    let running = 0
    for (const e of entries) {
      running += e.signedAmount
      customerRunning.set(e.id, running)
    }
    customerDue.set(customerId, running)
  }

  const supplierDue = new Map<number, number>()
  const supplierRunning = new Map<number, number>()
  const ledgerBySupplier = group(t.supplier_ledger_entries.values(), e => e.supplierId)
  for (const [supplierId, entries] of ledgerBySupplier) {
    entries.sort(byTime)
    let running = 0
    for (const e of entries) {
      running += e.signedAmount
      supplierRunning.set(e.id, running)
    }
    supplierDue.set(supplierId, running)
  }

  const sales = new Map<number, Settlement>()
  const salesByCustomer = group([...t.sales.values()].filter(s => s.customerId !== null && s.customerId > 0 && isActiveSale(s)), s => s.customerId!)
  if (salesByCustomer.size > 0) {
    const returnsByCustomer = group([...t.sales_returns.values()].filter(r => r.status === 'COMPLETED'), r => r.customerId)
    const paymentsByCustomer = group(t.customer_payments.values(), p => p.customerId)
    for (const [customerId, list] of salesByCustomer) {
      list.sort((a, b) => a.saleDate - b.saleDate || a.id - b.id)
      const settled = settleCustomer(customerDue.get(customerId) ?? 0, list, returnsByCustomer.get(customerId) ?? [],
        ledgerByCustomer.get(customerId) ?? [], paymentsByCustomer.get(customerId) ?? [])
      for (const [id, s] of settled) sales.set(id, s)
    }
  }

  const purchases = new Map<number, Settlement>()
  const purchasesBySupplier = group([...t.purchases.values()].filter(p => p.status !== 'CANCELLED'), p => p.supplierId)
  if (purchasesBySupplier.size > 0) {
    const returnsBySupplier = group([...t.purchase_returns.values()].filter(r => r.status !== 'CANCELLED'), r => r.supplierId)
    const paymentsBySupplier = group(t.supplier_payments.values(), p => p.supplierId)
    for (const [supplierId, list] of purchasesBySupplier) {
      list.sort((a, b) => a.purchaseDate - b.purchaseDate || a.id - b.id)
      const settled = settleSupplier(supplierDue.get(supplierId) ?? 0, list, returnsBySupplier.get(supplierId) ?? [],
        paymentsBySupplier.get(supplierId) ?? [])
      for (const [id, s] of settled) purchases.set(id, s)
    }
  }

  return { productStock, variantStock, productCost, variantCost, customerDue, supplierDue, customerRunning, supplierRunning, sales, purchases }
}

/** Tables whose rows carry cache columns. */
export const CACHE_TABLES: TableName[] = [
  'products', 'product_variants', 'customers', 'suppliers', 'customer_ledger_entries', 'supplier_ledger_entries', 'sales', 'purchases',
]

/** The row with its cache columns replaced by the recomputed values. */
export function withCaches<T extends TableName>(table: T, row: Row<T>, d: Derived): Row<T> {
  switch (table) {
    case 'products': {
      const p = row as Product
      return { ...p, currentStock: d.productStock.get(p.id) ?? 0, purchasePrice: d.productCost.get(p.id) ?? p.purchasePrice } as Row<T>
    }
    case 'product_variants': {
      const v = row as Variant
      return { ...v, currentStock: d.variantStock.get(v.id) ?? 0, purchasePrice: d.variantCost.get(v.id) ?? v.purchasePrice } as Row<T>
    }
    case 'customers': {
      const c = row as Customer
      return { ...c, currentDue: d.customerDue.get(c.id) ?? 0 } as Row<T>
    }
    case 'suppliers': {
      const s = row as Supplier
      return { ...s, currentDue: d.supplierDue.get(s.id) ?? 0 } as Row<T>
    }
    case 'customer_ledger_entries': {
      const e = row as CustomerLedgerEntry
      return { ...e, runningBalance: d.customerRunning.get(e.id) ?? e.signedAmount } as Row<T>
    }
    case 'supplier_ledger_entries': {
      const e = row as SupplierLedgerEntry
      return { ...e, runningBalance: d.supplierRunning.get(e.id) ?? e.signedAmount } as Row<T>
    }
    case 'sales': {
      const s = d.sales.get(row.id as number)
      return s ? ({ ...(row as Sale), paidAmount: s.paid, dueAmount: s.due, paymentStatus: s.status } as Row<T>) : row
    }
    case 'purchases': {
      const s = d.purchases.get(row.id as number)
      return s ? ({ ...(row as Purchase), paidAmount: s.paid, dueAmount: s.due, paymentStatus: s.status } as Row<T>) : row
    }
    default:
      return row
  }
}
