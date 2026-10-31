// Fills the local Firestore emulator with a shop the way a phone puts one online: the shop
// document, its owner member, meta/counters, device 0 (till A, id block 0), then every row
// built with the same domain rules the apps use (so stock, ledgers and caches are consistent).
//
//   npm --prefix web run seed:emulator        (emulators running: auth 9099, firestore 8085)
//
// Writes go through the emulator REST API with "Authorization: Bearer owner" (rules bypassed).
// Test sign-in (web, emulator mode): owner@electropos.test, manager@electropos.test, sales@electropos.test.
import { blank, TABLES, toFields, type Row, type TableName } from '../src/data/schema'
import { addExpense, setOpeningBalance } from '../src/domain/finance'
import { formatNumber } from '../src/domain/numbers'
import { createPurchase } from '../src/domain/purchases'
import { saveBrand, saveCategory, saveUnit } from '../src/domain/catalog'
import { recordCustomerPayment, saveCustomer } from '../src/domain/customers'
import { saveProduct, saveVariant } from '../src/domain/products'
import { createSale } from '../src/domain/sales'
import { createSalesReturn } from '../src/domain/salesReturns'
import { act, ShopState, type Ctx, type Series, type Tx } from '../src/domain/state'
import { saveSupplier } from '../src/domain/suppliers'
import { generateSalt, hashPin } from '../src/domain/users'

const PROJECT = 'demo-electropos'
const HOST = process.env.FIRESTORE_EMULATOR_HOST ?? '127.0.0.1:8085'
const BASE = `http://${HOST}/v1/projects/${PROJECT}/databases/(default)/documents`
const SHOP = process.env.SEED_SHOP_ID ?? 'demo-shop'
const OWNER = 'owner@electropos.test'
const DAY = 24 * 60 * 60 * 1000

type Value = Record<string, unknown>

function encode(v: unknown): Value {
  if (v === null || v === undefined) return { nullValue: null }
  if (typeof v === 'boolean') return { booleanValue: v }
  if (typeof v === 'number') return Number.isSafeInteger(v) ? { integerValue: String(v) } : { doubleValue: v }
  if (typeof v === 'string') return { stringValue: v }
  if (v instanceof Date) return { timestampValue: v.toISOString() }
  throw new Error(`Cannot encode ${String(v)}`)
}

const fields = (o: Record<string, unknown>) => Object.fromEntries(Object.entries(o).map(([k, v]) => [k, encode(v)]))

async function commit(writes: { path: string; data: Record<string, unknown> }[]) {
  for (let i = 0; i < writes.length; i += 400) {
    const body = {
      writes: writes.slice(i, i + 400).map(w => ({
        update: { name: `projects/${PROJECT}/databases/(default)/documents/${w.path}`, fields: fields(w.data) },
      })),
    }
    const res = await fetch(`${BASE}:commit`, {
      method: 'POST', headers: { 'Content-Type': 'application/json', Authorization: 'Bearer owner' }, body: JSON.stringify(body),
    })
    if (!res.ok) throw new Error(`commit failed: ${res.status} ${await res.text()}`)
  }
}

// ---- Build the shop with the domain rules, as the phone (block 0, till A) would ----------

let state = ShopState.empty()
let nextId = 1
const counters: Partial<Record<Series, number>> = {}
let clock = Date.now() - 20 * DAY

function run<R>(fn: (tx: Tx) => R, at?: number, actor = 'Masum'): R {
  clock = Math.max(clock + 60_000, at ?? clock + 60_000)
  const year = new Date(clock).getFullYear()
  const ctx: Ctx = {
    now: clock, actor, year, newId: () => nextId++,
    number: s => {
      const n = (counters[s] ?? 0) + 1
      counters[s] = n
      return formatNumber(s, n, state.profile, year, 'A')
    },
  }
  const { result, changes } = act(state, ctx, fn)
  state = state.with(changes)
  return result
}

const daysAgo = (d: number, hour = 11) => {
  const t = new Date(Date.now() - d * DAY)
  t.setHours(hour, 0, 0, 0)
  return t.getTime()
}

async function build() {
  run(tx => {
    tx.put('shop_profile', blank('shop_profile', {
      id: 1, shopName: 'Masum Mobile & Electronics', slogan: 'Mobile, Computer & Accessories', address: 'Shop 12, Bashundhara City, Dhaka',
      phone: '01711000000', email: 'shop@electropos.test', setupCompleted: true, updatedAt: tx.now,
    }))
  }, daysAgo(20, 9))
  const salt = generateSalt()
  const pinHash = await hashPin('1234', salt)
  run(tx => { tx.insert('users', { name: 'Masum', username: 'owner', pinHash, salt, role: 'ADMIN', createdAt: tx.now, updatedAt: tx.now }) })
  for (const [name, code] of [['Piece', 'Pcs'], ['Box', 'Box'], ['Pack', 'Pk'], ['Meter', 'Mtr'], ['Set', 'Set']]) run(tx => saveUnit(tx, { name, code }))
  const phones = run(tx => saveCategory(tx, { name: 'Mobile Phones' }))
  const accessories = run(tx => saveCategory(tx, { name: 'Accessories' }))
  const computer = run(tx => saveCategory(tx, { name: 'Computer' }))
  const samsung = run(tx => saveBrand(tx, { name: 'Samsung', originCountry: 'South Korea' }))
  const xiaomi = run(tx => saveBrand(tx, { name: 'Xiaomi', originCountry: 'China' }))
  const baseus = run(tx => saveBrand(tx, { name: 'Baseus', originCountry: 'China' }))
  const anker = run(tx => saveBrand(tx, { name: 'Anker', originCountry: 'USA' }))
  const cat = (id: number) => state.get('categories', id)!.name
  const brand = (id: number) => state.get('brands', id)!.name

  const galaxy = run(tx => saveProduct(tx, {
    name: 'Samsung Galaxy A15', categoryId: phones, categoryName: cat(phones), brandId: samsung, brandName: brand(samsung), model: 'SM-A155F',
    trackingType: 'IMEI', isImeiRequired: true, retailPrice: 21000, minSellingPrice: 19500, purchasePrice: 18000, sku: 'MOB-SAM-A15',
    warrantyPeriod: 12, warrantyUnit: 'MONTHS', warrantyPeriodMonths: 12, hasWarranty: true, warrantyType: 'SERVICE', minStock: 2,
  }))
  const v128 = run(tx => saveVariant(tx, { productId: galaxy, variantName: '8/128 Black', ram: '8GB', storage: '128GB', color: 'Black', retailPrice: 21000 }))
  const v256 = run(tx => saveVariant(tx, { productId: galaxy, variantName: '8/256 Blue', ram: '8GB', storage: '256GB', color: 'Blue', retailPrice: 24000 }))
  const redmi = run(tx => saveProduct(tx, {
    name: 'Redmi Note 13', categoryId: phones, categoryName: cat(phones), brandId: xiaomi, brandName: brand(xiaomi), trackingType: 'IMEI',
    isImeiRequired: true, retailPrice: 23500, purchasePrice: 20000, warrantyPeriod: 12, warrantyPeriodMonths: 12, hasWarranty: true, minStock: 2,
  }))
  const cable = run(tx => saveProduct(tx, {
    name: 'Baseus USB-C Cable 1m', categoryId: accessories, categoryName: cat(accessories), brandId: baseus, brandName: brand(baseus),
    retailPrice: 350, purchasePrice: 180, currentStock: 40, barcode: '2000000000017', sku: 'ACC-BAS-USBC', minStock: 10,
  }))
  const charger = run(tx => saveProduct(tx, {
    name: 'Anker 25W Charger', categoryId: accessories, categoryName: cat(accessories), brandId: anker, brandName: brand(anker),
    retailPrice: 1200, purchasePrice: 800, currentStock: 15, barcode: '2000000000024', warrantyPeriod: 6, warrantyPeriodMonths: 6, hasWarranty: true,
  }))
  run(tx => saveProduct(tx, { name: 'Phone Cover (assorted)', categoryId: accessories, categoryName: cat(accessories), retailPrice: 250, purchasePrice: 90, currentStock: 4, minStock: 5 }))
  run(tx => saveProduct(tx, { name: 'Wireless Earbuds', categoryId: accessories, categoryName: cat(accessories), retailPrice: 1800, purchasePrice: 1100 }))
  const ssd = run(tx => saveProduct(tx, {
    name: 'Kingston SSD 512GB', categoryId: computer, categoryName: cat(computer), trackingType: 'SERIAL', isSerialRequired: true,
    retailPrice: 5200, purchasePrice: 4300, warrantyPeriod: 3, warrantyUnit: 'YEARS', warrantyPeriodMonths: 36, hasWarranty: true,
  }))

  const dist = run(tx => saveSupplier(tx, { name: 'Smart Distribution', company: 'Smart Technologies BD', phone: '01811000001', city: 'Dhaka' }))
  const gadget = run(tx => saveSupplier(tx, { name: 'Gadget Wholesale', phone: '01811000002', openingDue: 5000 }))

  run(tx => createPurchase(tx, { supplierId: dist, purchaseDate: daysAgo(15), paidAmount: 50000, paymentMethod: 'BANK', supplierInvoiceNumber: 'SD-7781' }, [
    { productId: galaxy, productName: 'Samsung Galaxy A15', variantId: v128, variantName: '8/128 Black', quantity: 3, unitCost: 18000, trackingType: 'IMEI',
      serialsOrImeis: [{ imei1: '350000000000101' }, { imei1: '350000000000102' }, { imei1: '350000000000103' }] },
    { productId: galaxy, productName: 'Samsung Galaxy A15', variantId: v256, variantName: '8/256 Blue', quantity: 2, unitCost: 21000, trackingType: 'IMEI',
      serialsOrImeis: [{ imei1: '350000000000201', imei2: '350000000000202' }, { imei1: '350000000000203', imei2: '350000000000204' }] },
    { productId: redmi, productName: 'Redmi Note 13', quantity: 2, unitCost: 20000, trackingType: 'IMEI',
      serialsOrImeis: [{ imei1: '860000000000301' }, { imei1: '860000000000302' }] },
  ]), daysAgo(15, 10))
  run(tx => createPurchase(tx, { supplierId: gadget, purchaseDate: daysAgo(10), paidAmount: 3000, transportCost: 200 }, [
    { productId: cable, productName: 'Baseus USB-C Cable 1m', quantity: 20, unitCost: 170 },
    { productId: charger, productName: 'Anker 25W Charger', quantity: 5, unitCost: 780 },
    { productId: ssd, productName: 'Kingston SSD 512GB', quantity: 2, unitCost: 4300, trackingType: 'SERIAL',
      serialsOrImeis: [{ serialNumber: 'KS512-0001' }, { serialNumber: 'KS512-0002' }] },
  ]), daysAgo(10, 10))

  const rahim = run(tx => saveCustomer(tx, { name: 'Rahim Uddin', phone: '01711223344', address: 'Mirpur 10, Dhaka' }))
  const karim = run(tx => saveCustomer(tx, { name: 'Karim Mobile Point', phone: '01911556677', customerType: 'CREDIT', creditLimit: 100000, openingDue: 2500 }))
  run(tx => saveCustomer(tx, { name: 'Nasrin Akter', phone: '01611889900' }))

  const sale = (at: number, customerId: number | null, items: { productId: number; variantId?: number; quantity: number; unitPrice: number; discount?: number; units?: string[] }[], paid: number | null, method = 'CASH', invoiceDiscount = 0) =>
    run(tx => {
      const customer = customerId ? tx.get('customers', customerId) : undefined
      const lines = items.map(i => {
        const p = tx.get('products', i.productId)!
        const units = (i.units ?? []).map(code => tx.all('product_serial_imei').find(u => u.imei1 === code || u.serialNumber === code)!)
        return {
          productId: i.productId, productName: p.name, variantId: i.variantId ?? 0, quantity: i.quantity, unitPrice: i.unitPrice, discount: i.discount ?? 0,
          tax: 0, trackingType: p.trackingType, selectedSerialImeiIds: units.map(u => u.id), selectedSerialsOrImeis: units.map(u => u.imei1 ?? u.serialNumber ?? ''),
        }
      })
      const sub = lines.reduce((a, l) => a + l.quantity * l.unitPrice, 0)
      const itemDiscount = lines.reduce((a, l) => a + l.discount, 0)
      const total = sub - itemDiscount - invoiceDiscount
      const paidAmount = paid ?? total
      return createSale(tx, {
        customerId, customerName: customer?.name ?? 'Walk-in Customer', customerPhone: customer?.phone ?? '', saleDate: at, subTotal: sub, itemDiscount,
        invoiceDiscount, discountAmount: itemDiscount + invoiceDiscount, taxAmount: 0, totalAmount: total, paidAmount, dueAmount: total - paidAmount,
        paymentMethod: method, notes: '', salespersonName: 'Masum',
      }, lines)
    }, at)

  run(tx => setOpeningBalance(tx, 20000, 'CASH', 'Drawer when we started'), daysAgo(20, 9))
  run(tx => setOpeningBalance(tx, 100000, 'BANK', 'Shop account'), daysAgo(20, 9))
  sale(daysAgo(9, 12), null, [{ productId: cable, quantity: 2, unitPrice: 350 }, { productId: charger, quantity: 1, unitPrice: 1200 }], null)
  sale(daysAgo(7, 15), rahim, [{ productId: galaxy, variantId: v128, quantity: 1, unitPrice: 21000, units: ['350000000000101'] }], 15000, 'BKASH')
  run(tx => addExpense(tx, { categoryName: 'Shop Rent', title: 'October rent', amount: 15000, paymentMethod: 'BANK', expenseDate: daysAgo(6, 10) }), daysAgo(6, 10))
  const karimSale = sale(daysAgo(5, 13), karim, [{ productId: redmi, quantity: 1, unitPrice: 23500, units: ['860000000000301'] }, { productId: cable, quantity: 3, unitPrice: 330 }], 10000)
  run(tx => {
    const item = tx.where('sale_items', 'saleId', karimSale).find(i => i.productName.startsWith('Baseus'))!
    return createSalesReturn(tx, { saleId: karimSale, reason: 'Wrong colour', refundAmount: 0, returnDate: tx.now }, [{
      saleItemId: item.id, productId: item.productId, productName: item.productName, returnQuantity: 1,
    }])
  }, daysAgo(4, 12))
  sale(daysAgo(3, 17), null, [{ productId: ssd, quantity: 1, unitPrice: 5200, units: ['KS512-0001'] }], null, 'CARD')
  run(tx => recordCustomerPayment(tx, { customerId: karim, amount: 5000, paymentMethod: 'NAGAD', notes: 'Part payment' }), daysAgo(2, 16))
  run(tx => addExpense(tx, { categoryName: 'Electricity Bill', title: 'DESCO', amount: 2200, expenseDate: daysAgo(1, 10) }), daysAgo(1, 10))
  sale(daysAgo(0, 9), null, [{ productId: cable, quantity: 1, unitPrice: 350 }, { productId: charger, quantity: 1, unitPrice: 1200, discount: 100 }], null)
  sale(daysAgo(0, 9) + 30 * 60_000, rahim, [{ productId: charger, quantity: 1, unitPrice: 1200 }], 0)
}

async function main() {
  const clear = await fetch(`http://${HOST}/emulator/v1/projects/${PROJECT}/databases/(default)/documents`, { method: 'DELETE' })
  if (!clear.ok) throw new Error(`Is the Firestore emulator running on ${HOST}? (${clear.status})`)
  await build()
  const now = new Date()
  const writes: { path: string; data: Record<string, unknown> }[] = [
    { path: `shops/${SHOP}`, data: { name: state.profile.shopName, ownerUid: `test-${OWNER}`, ownerEmail: OWNER, createdAt: Date.now() - 20 * DAY, _ts: now } },
    ...[[OWNER, 'Masum', 'OWNER'], ['manager@electropos.test', 'Mina Manager', 'MANAGER'], ['sales@electropos.test', 'Sabbir Sales', 'SALESPERSON']].map(([email, name, role]) => ({
      path: `shops/${SHOP}/members/${email}`,
      data: { email, name, role, active: true, addedBy: OWNER, createdAt: Date.now() - 20 * DAY, _del: false, _ts: now },
    })),
    { path: `shops/${SHOP}/meta/counters`, data: { nextBlock: 1, nextPhone: 1, _ts: now } },
    { path: `shops/${SHOP}/devices/0`, data: { block: 0, kind: 'PHONE', tillCode: 'A', email: OWNER, label: 'Shop phone', createdAt: Date.now() - 20 * DAY, _ts: now } },
  ]
  let rows = 0
  for (const table of TABLES) {
    for (const row of state.list(table) as Row<TableName>[]) {
      writes.push({ path: `shops/${SHOP}/${table}/${row.id}`, data: { ...toFields(table, row), id: row.id, _del: false, _ts: now } })
      rows++
    }
  }
  await commit(writes)
  console.log(`Seeded shop "${state.profile.shopName}" (${SHOP}) with ${rows} rows. Sign in (emulator) as ${OWNER}, manager@electropos.test or sales@electropos.test.`)
}

main().catch(e => {
  console.error(e)
  process.exit(1)
})
