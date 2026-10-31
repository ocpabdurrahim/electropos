// The shop as plain data (every synced table in memory) and the working copy one
// action builds its rows in. Domain functions read and write a Tx; the caller
// sends Tx.changes() in one batch.
import { blank, TABLES, type Row, type ShopProfile, type StockTx, type TableName } from '../data/schema'
import { byTime, CACHE_TABLES, derive, replayCost, withCaches, type Derived, type Tables } from './caches'

export type AnyRow = { id: number } & Record<string, unknown>

export type Change =
  | { table: TableName; id: number; row: AnyRow; del?: false }
  | { table: TableName; id: number; del: true }

const emptyTables = (): Tables => Object.fromEntries(TABLES.map(t => [t, new Map()])) as unknown as Tables

/** An immutable copy of the shop. Rows read through it carry recomputed cache columns. */
export class ShopState {
  private derivedCache: Derived | null = null
  private effCache = new Map<TableName, Map<number, AnyRow>>()
  private listCache = new Map<TableName, AnyRow[]>()
  private groupCache = new Map<string, Map<unknown, AnyRow[]>>()

  private constructor(readonly raw: Tables) {}

  static empty(): ShopState {
    return new ShopState(emptyTables())
  }

  static of(rows: { [T in TableName]?: Row<T>[] }): ShopState {
    const t = emptyTables() as unknown as Record<TableName, Map<number, AnyRow>>
    for (const [name, list] of Object.entries(rows) as [TableName, AnyRow[]][]) {
      for (const r of list) t[name].set(r.id, r)
    }
    return new ShopState(t as unknown as Tables)
  }

  /** A new state with [changes] applied (rows set or removed). */
  with(changes: readonly Change[]): ShopState {
    if (changes.length === 0) return this
    const t = { ...this.raw } as unknown as Record<TableName, Map<number, AnyRow>>
    const copied = new Set<TableName>()
    for (const c of changes) {
      if (!copied.has(c.table)) {
        t[c.table] = new Map(t[c.table])
        copied.add(c.table)
      }
      if (c.del) t[c.table].delete(c.id)
      else t[c.table].set(c.id, c.row)
    }
    return new ShopState(t as unknown as Tables)
  }

  get derived(): Derived {
    this.derivedCache ??= derive(this.raw)
    return this.derivedCache
  }

  private eff(table: TableName): Map<number, AnyRow> {
    const rawMap = this.raw[table] as unknown as Map<number, AnyRow>
    if (!CACHE_TABLES.includes(table)) return rawMap
    let m = this.effCache.get(table)
    if (!m) {
      const d = this.derived
      m = new Map()
      for (const [id, r] of rawMap) m.set(id, withCaches(table, r as never, d) as AnyRow)
      this.effCache.set(table, m)
    }
    return m
  }

  get<T extends TableName>(table: T, id: number | null | undefined): Row<T> | undefined {
    if (id === null || id === undefined) return undefined
    return this.eff(table).get(id) as Row<T> | undefined
  }

  list<T extends TableName>(table: T): Row<T>[] {
    let l = this.listCache.get(table)
    if (!l) {
      l = [...this.eff(table).values()]
      this.listCache.set(table, l)
    }
    return l as Row<T>[]
  }

  size(table: TableName): number {
    return this.raw[table].size
  }

  /** Rows grouped by one field (cached). */
  by<T extends TableName>(table: T, field: keyof Row<T> & string): Map<unknown, Row<T>[]> {
    const key = `${table}.${field}`
    let g = this.groupCache.get(key)
    if (!g) {
      g = new Map()
      for (const r of this.list(table) as AnyRow[]) {
        const k = r[field]
        const list = g.get(k)
        if (list) list.push(r)
        else g.set(k, [r])
      }
      this.groupCache.set(key, g)
    }
    return g as Map<unknown, Row<T>[]>
  }

  where<T extends TableName>(table: T, field: keyof Row<T> & string, value: unknown): Row<T>[] {
    return this.by(table, field).get(value) ?? []
  }

  get profile(): ShopProfile {
    return this.get('shop_profile', 1) ?? blank('shop_profile', { id: 1 })
  }
}

/** Anything that can look rows up (the shop, or an action's working copy). */
export interface Reader {
  where<T extends TableName>(table: T, field: keyof Row<T> & string, value: unknown): Row<T>[]
}

/** Who acts, when, and where new ids and document numbers come from. */
export interface Ctx {
  now: number
  /** Name written to createdBy / salespersonName (the phone writes the PIN user's name). */
  actor: string
  year: number
  newId: (table: TableName) => number
  /** A number of the given series (taken from meta/counters before the action runs). */
  number: (series: Series) => string
}

export type Series = 'inv' | 'pur' | 'prt' | 'srt' | 'spay' | 'cpay' | 'cus' | 'sup'

/** One action's working copy: reads see the action's own writes, like Room inside withTransaction. */
export class Tx {
  private over = new Map<TableName, Map<number, AnyRow | null>>()
  private touched: { table: TableName; id: number }[] = []

  constructor(readonly base: ShopState, readonly ctx: Ctx) {}

  get now() {
    return this.ctx.now
  }

  private layer(table: TableName) {
    let m = this.over.get(table)
    if (!m) {
      m = new Map()
      this.over.set(table, m)
    }
    return m
  }

  private touch(table: TableName, id: number) {
    const m = this.layer(table)
    if (!m.has(id)) this.touched.push({ table, id })
  }

  get<T extends TableName>(table: T, id: number | null | undefined): Row<T> | undefined {
    if (id === null || id === undefined) return undefined
    const m = this.over.get(table)
    if (m?.has(id)) return (m.get(id) ?? undefined) as Row<T> | undefined
    return this.base.get(table, id)
  }

  /** Rows whose [field] equals [value], the action's own changes included. */
  where<T extends TableName>(table: T, field: keyof Row<T> & string, value: unknown): Row<T>[] {
    const m = this.over.get(table)
    const base = this.base.where(table, field, value)
    if (!m || m.size === 0) return [...base]
    const out = base.filter(r => !m.has(r.id as number))
    for (const r of m.values()) if (r && r[field] === value) out.push(r as Row<T>)
    return out
  }

  all<T extends TableName>(table: T): Row<T>[] {
    const m = this.over.get(table)
    const base = this.base.list(table)
    if (!m || m.size === 0) return [...base]
    const out = base.filter(r => !m.has(r.id as number))
    for (const r of m.values()) if (r) out.push(r as Row<T>)
    return out
  }

  insert<T extends TableName>(table: T, fields: Partial<Row<T>>): Row<T> {
    const id = this.ctx.newId(table)
    const row = blank(table, { ...fields, id } as Partial<Row<T>>)
    this.touch(table, id)
    this.layer(table).set(id, row as AnyRow)
    return row
  }

  /** Writes the whole row (an update). */
  put<T extends TableName>(table: T, row: Row<T>): Row<T> {
    const id = (row as AnyRow).id
    this.touch(table, id)
    this.layer(table).set(id, row as AnyRow)
    return row
  }

  /** Shorthand: the stored row with [fields] changed. */
  update<T extends TableName>(table: T, id: number, fields: Partial<Row<T>>): Row<T> {
    const old = this.get(table, id)
    if (!old) throw new Error(`${table} ${id} not found`)
    return this.put(table, { ...old, ...fields })
  }

  remove(table: TableName, id: number) {
    this.touch(table, id)
    this.layer(table).set(id, null)
  }

  changes(): Change[] {
    const out: Change[] = []
    for (const { table, id } of this.touched) {
      const row = this.over.get(table)!.get(id)
      if (row) out.push({ table, id, row })
      else out.push({ table, id, del: true })
    }
    return out
  }

  // ---- Cached values as Room would hold them at this point of the transaction ----

  private stockTxs(productId: number): StockTx[] {
    return this.where('stock_transactions', 'productId', productId)
  }

  stock(productId: number): number {
    let s = this.base.derived.productStock.get(productId) ?? 0
    const m = this.over.get('stock_transactions')
    if (m) for (const r of m.values()) if (r && r.productId === productId) s += r.quantityChange as number
    return s
  }

  variantStock(variantId: number): number {
    let s = this.base.derived.variantStock.get(variantId) ?? 0
    const m = this.over.get('stock_transactions')
    if (m) for (const r of m.values()) if (r && r.variantId === variantId && variantId > 0) s += r.quantityChange as number
    return s
  }

  productCost(productId: number): number {
    const own = this.over.get('stock_transactions')
    const hasNew = own ? [...own.values()].some(r => r && r.productId === productId) : false
    if (!hasNew) return this.base.get('products', productId)?.purchasePrice ?? 0
    const replayed = replayCost(this.stockTxs(productId))
    return replayed ?? this.get('products', productId)?.purchasePrice ?? 0
  }

  variantCost(variantId: number): number {
    const v = this.get('product_variants', variantId)
    if (!v) return 0
    const own = this.over.get('stock_transactions')
    const hasNew = own ? [...own.values()].some(r => r && r.variantId === variantId) : false
    if (!hasNew) return this.base.get('product_variants', variantId)?.purchasePrice ?? v.purchasePrice
    const replayed = replayCost(this.stockTxs(v.productId).filter(t => t.variantId === variantId), false)
    return replayed ?? v.purchasePrice
  }

  customerBalance(customerId: number): number {
    return this.where('customer_ledger_entries', 'customerId', customerId).reduce((a, e) => a + e.signedAmount, 0)
  }

  supplierBalance(supplierId: number): number {
    return this.where('supplier_ledger_entries', 'supplierId', supplierId).reduce((a, e) => a + e.signedAmount, 0)
  }

  /** The product with its stock and cost as they are now in this action. */
  product(id: number) {
    const p = this.get('products', id)
    return p ? { ...p, currentStock: this.stock(id), purchasePrice: this.productCost(id) } : undefined
  }

  variant(id: number) {
    const v = this.get('product_variants', id)
    return v ? { ...v, currentStock: this.variantStock(id), purchasePrice: this.variantCost(id) } : undefined
  }

  customer(id: number | null | undefined) {
    const c = this.get('customers', id)
    return c ? { ...c, currentDue: this.customerBalance(c.id) } : undefined
  }

  supplier(id: number) {
    const s = this.get('suppliers', id)
    return s ? { ...s, currentDue: this.supplierBalance(s.id) } : undefined
  }

  /** Ledger entries of one customer, oldest first. */
  customerLedger(customerId: number) {
    return this.where('customer_ledger_entries', 'customerId', customerId).sort(byTime)
  }
}

/** Runs [fn] on a working copy of [state]; returns its result and the rows to write. */
export function act<R>(state: ShopState, ctx: Ctx, fn: (tx: Tx) => R): { result: R; changes: Change[] } {
  const tx = new Tx(state, ctx)
  const result = fn(tx)
  return { result, changes: tx.changes() }
}

/** The changed rows as they must be written: cache columns filled from [after]. */
export function finalRows(changes: readonly Change[], after: ShopState): Change[] {
  return changes.map(c => {
    if (c.del) return c
    const row = after.get(c.table, c.id) as AnyRow | undefined
    return row ? { ...c, row } : c
  })
}
