// An in-memory shop for the domain tests: runs actions the way the web app does
// (working copy → rows → new state with recomputed caches).
import { blank, type Row, type TableName } from '../data/schema'
import { nextNumber } from './numbers'
import { act, ShopState, type Ctx, type Series, type Tx } from './state'

export class TestShop {
  state = ShopState.empty()
  private nextId = 1
  private counters: Partial<Record<Series, number>> = {}
  actor = 'Admin'
  private last = 0
  /** The clock for actions that do not pass their own time: real time, but each action at least 10 ms after the last. */
  clock: () => number = () => {
    this.last = Math.max(Date.now(), this.last + 10)
    return this.last
  }

  /** Runs one action; on a refused action nothing changes and the error is thrown. */
  run<R>(fn: (tx: Tx) => R, now?: number): R {
    const time = now ?? this.clock()
    const year = new Date(time).getFullYear()
    const ctx: Ctx = {
      now: time,
      actor: this.actor,
      year,
      newId: () => this.nextId++,
      number: (series: Series) => {
        const { n, text } = nextNumber(this.state, series, this.counters[series] ?? 0, year)
        this.counters[series] = n
        return text
      },
    }
    const { result, changes } = act(this.state, ctx, fn)
    this.state = this.state.with(changes)
    return result
  }

  /** Inserts a row directly, like a DAO insert in the Kotlin tests. */
  insert<T extends TableName>(table: T, fields: Partial<Row<T>>): number {
    const id = this.nextId++
    const now = Date.now()
    const row = blank(table, { createdAt: now, updatedAt: now, ...fields, id } as Partial<Row<T>>)
    this.state = this.state.with([{ table, id, row: row as never }])
    return id
  }

  get<T extends TableName>(table: T, id: number): Row<T> {
    const r = this.state.get(table, id)
    if (!r) throw new Error(`${table} ${id} missing`)
    return r
  }

  rows<T extends TableName>(table: T): Row<T>[] {
    return this.state.list(table)
  }
}

// ---- The Kotlin tests' helpers ------------------------------------------------------

import { TrackingType } from './common'
import { saveCustomer } from './customers'
import { createPurchase } from './purchases'
import { createSale } from './sales'
import { saveSupplier } from './suppliers'

export function supplier(shop: TestShop, name = 'Vendor', phone = '01700000001'): number {
  return shop.run(tx => saveSupplier(tx, { name, phone }))
}

/** productDao.insertProduct: no price history and no stock movement. [openingStock] adds one, like a stock on hand. */
export function product(shop: TestShop, name: string, opts: { tracking?: string; cost?: number; price?: number; openingStock?: number } = {}): number {
  const id = shop.insert('products', {
    name, trackingType: opts.tracking ?? TrackingType.QUANTITY, purchasePrice: opts.cost ?? 0, retailPrice: opts.price ?? 0,
  })
  if (opts.openingStock) {
    shop.insert('stock_transactions', {
      productId: id, productName: name, transactionType: 'OPENING_STOCK', quantityChange: opts.openingStock, previousStock: 0,
      newStock: opts.openingStock, unitCost: opts.cost ?? 0, referenceId: id, referenceType: 'PRODUCT',
    })
  }
  return id
}

export function buy(shop: TestShop, supplierId: number, productId: number, qty: number, unitCost: number, opts: {
  lineDiscount?: number; invoiceDiscount?: number; paid?: number; date?: number; imeis?: string[]; transport?: number; variantId?: number
} = {}): number {
  const p = shop.get('products', productId)
  return shop.run(tx => createPurchase(tx, {
    supplierId, invoiceDiscount: opts.invoiceDiscount ?? 0, paidAmount: opts.paid ?? 0, purchaseDate: opts.date, transportCost: opts.transport,
  }, [{
    productId, productName: p.name, quantity: qty, unitCost, discount: opts.lineDiscount ?? 0, trackingType: p.trackingType,
    variantId: opts.variantId, variantName: opts.variantId ? 'v' : '',
    serialsOrImeis: (opts.imeis ?? []).map(imei1 => ({ imei1 })),
  }]))
}

export function sell(shop: TestShop, productId: number, qty: number, unitPrice: number, opts: {
  customerId?: number; lineDiscount?: number; lineTax?: number; invoiceDiscount?: number; paid?: number; unitIds?: number[];
  date?: number; method?: string; variantId?: number
} = {}): number {
  const p = shop.get('products', productId)
  const lineDiscount = opts.lineDiscount ?? 0
  const lineTax = opts.lineTax ?? 0
  const invoiceDiscount = opts.invoiceDiscount ?? 0
  const lineTotal = qty * unitPrice - lineDiscount + lineTax
  const total = lineTotal - invoiceDiscount
  const paid = opts.paid ?? total
  const customer = opts.customerId ? shop.get('customers', opts.customerId) : undefined
  const unitIds = opts.unitIds ?? []
  return shop.run(tx => createSale(tx, {
    customerId: opts.customerId ?? null, customerName: customer?.name ?? 'Walk-in Customer', customerPhone: '', saleDate: opts.date ?? Date.now(),
    subTotal: qty * unitPrice, itemDiscount: lineDiscount, invoiceDiscount, discountAmount: 0, taxAmount: lineTax, totalAmount: total,
    paidAmount: paid, dueAmount: total - paid, paymentMethod: opts.method ?? 'CASH', notes: '', salespersonName: 'Admin',
  }, [{
    productId, productName: p.name, quantity: qty, unitPrice, discount: lineDiscount, tax: lineTax, trackingType: p.trackingType,
    variantId: opts.variantId, selectedSerialImeiIds: unitIds, selectedSerialsOrImeis: unitIds.map(id => `unit-${id}`),
  }]))
}

export function customer(shop: TestShop, name: string, phone: string, openingDue = 0): number {
  return shop.run(tx => saveCustomer(tx, { name, phone, openingDue }))
}
