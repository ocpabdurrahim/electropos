// The contract details the web owns: document shape, numbers, id blocks and cache recomputation.
import { describe, expect, it } from 'vitest'
import { blank, fromDoc, toFields } from '../data/schema'
import { replayCost } from './caches'
import { kotlinDouble } from './money'
import { formatNumber, nextNumber } from './numbers'
import { cancelPurchase } from './purchases'
import { act, finalRows, ShopState } from './state'
import { buy, product, sell, supplier, TestShop } from './testkit'
import { saveVariant } from './products'
import { addMonths, localDate } from './dates'
import { warrantyEndDate } from './common'

describe('documents', () => {
  it('local-only columns are never written and missing fields take the entity defaults', () => {
    const fields = toFields('shop_profile', blank('shop_profile', { id: 1, shopName: 'Masum Mobile', themeMode: 'DARK', invoiceNextNumber: 77 }))
    expect(fields.shopName).toBe('Masum Mobile')
    for (const k of ['themeMode', 'rememberLogin', 'lastActiveUserId', 'logoUri', 'invoiceNextNumber', 'customerNextNumber']) expect(k in fields).toBe(false)
    expect('imageUri' in toFields('products', blank('products', { id: 3, name: 'x' }))).toBe(false)
    expect('lastLoginAt' in toFields('users', blank('users', { id: 3 }))).toBe(false)

    const p = fromDoc('products', '4294967297', { id: 4294967297, name: 'Cable', retailPrice: 500, isActive: 1, minStock: 3.0, imageUri: 'x' })
    expect(p.id).toBe(4294967297)
    expect(p.retailPrice).toBe(500)
    expect(p.isActive).toBe(true) // 0/1 from an older writer still reads as a boolean
    expect(p.minStock).toBe(3)
    expect(p.maxStock).toBe(1000) // missing → default
    expect(p.unit).toBe('Pcs')
    expect(p.imageUri).toBe('') // local-only: ignored when received
    const s = fromDoc('sales', '9', { customerId: null, invoiceNumber: 'INV-1' })
    expect(s.customerId).toBeNull()
    expect(s.customerName).toBe('Walk-in Customer')
  })
})

describe('numbers', () => {
  const profile = blank('shop_profile', { id: 1 })
  it('use the web till W and the contract formats', () => {
    expect(formatNumber('inv', 42, profile, 2026)).toBe('INV-2026-W00042')
    expect(formatNumber('pur', 1, profile, 2026)).toBe('PUR-2026-W00001')
    expect(formatNumber('prt', 1, profile, 2026)).toBe('PRT-2026-W00001')
    expect(formatNumber('srt', 3, profile, 2026)).toBe('SRT-2026-W00003')
    expect(formatNumber('spay', 1, profile, 2026)).toBe('SPAY-2026-W00001')
    expect(formatNumber('cpay', 9, profile, 2026)).toBe('CPAY-W00009')
    expect(formatNumber('cus', 7, profile, 2026)).toBe('CUS-W0007')
    expect(formatNumber('sup', 7, profile, 2026)).toBe('SUP-W0007')
    expect(formatNumber('inv', 1, { ...profile, invoicePrefix: '' }, 2026)).toBe('INV-2026-W00001')
    expect(formatNumber('inv', 1, { ...profile, invoicePrefix: 'MM-' }, 2026)).toBe('MM-2026-W00001')
  })

  it('skip numbers already used', () => {
    const state = ShopState.of({ sales: [blank('sales', { id: 1, invoiceNumber: 'INV-2026-W00001' }), blank('sales', { id: 2, invoiceNumber: 'INV-2026-W00002' })] })
    expect(nextNumber(state, 'inv', 0, 2026)).toEqual({ n: 3, text: 'INV-2026-W00003' })
  })
})

describe('caches', () => {
  it('weighted average replay matches the purchase rule and takes a cancelled purchase back out', () => {
    const shop = new TestShop()
    const s = supplier(shop)
    const p = product(shop, 'Charger', { cost: 100, openingStock: 10 })
    buy(shop, s, p, 20, 130)
    expect(shop.get('products', p).purchasePrice).toBeCloseTo(120, 6)
    const second = buy(shop, s, p, 10, 150)
    expect(shop.get('products', p).purchasePrice).toBeCloseTo((30 * 120 + 10 * 150) / 40, 6)
    shop.run(tx => cancelPurchase(tx, second, 'wrong'))
    expect(shop.get('products', p).purchasePrice).toBeCloseTo(120, 6)
    expect(shop.get('products', p).currentStock).toBe(30)
    expect(replayCost(shop.state.where('stock_transactions', 'productId', p))).toBeCloseTo(120, 6)
  })

  it('like the phone, a cancelled purchase comes out of the product cost but not of the variant cost', () => {
    const shop = new TestShop()
    const s = supplier(shop)
    const p = product(shop, 'Phone')
    const v = shop.run(tx => saveVariant(tx, { productId: p, variantName: '8/128' }))
    buy(shop, s, p, 2, 100, { variantId: v })
    const second = buy(shop, s, p, 2, 200, { variantId: v })
    expect(shop.get('products', p).purchasePrice).toBeCloseTo(150, 6)
    expect(shop.get('product_variants', v).purchasePrice).toBeCloseTo(150, 6)
    shop.run(tx => cancelPurchase(tx, second, 'wrong'))
    expect(shop.get('products', p).purchasePrice).toBeCloseTo(100, 6)
    expect(shop.get('product_variants', v).purchasePrice).toBeCloseTo(150, 6)
    expect(shop.get('product_variants', v).currentStock).toBe(2)
  })

  it('a product without purchases keeps its entered cost', () => {
    const shop = new TestShop()
    const p = product(shop, 'Service', { cost: 250 })
    expect(shop.get('products', p).purchasePrice).toBe(250)
  })

  it('written rows carry the recomputed caches, never stale ones', () => {
    const shop = new TestShop()
    const s = supplier(shop)
    const p = product(shop, 'Mouse', { price: 500 })
    buy(shop, s, p, 4, 300)
    const ctx = { now: Date.now(), actor: 'Web', year: 2026, newId: (() => { let n = 4294967297; return () => n++ })(), number: () => 'INV-2026-W00001' }
    const before = shop.state
    const { changes } = act(before, ctx, tx => {
      tx.put('products', { ...tx.get('products', p)!, name: 'Mouse X', currentStock: 999 })
    })
    const after = before.with(changes)
    const [row] = finalRows(changes, after)
    expect(row.del).toBeFalsy()
    if (!row.del) {
      expect(row.row.currentStock).toBe(4)
      expect(row.row.purchasePrice).toBe(300)
      expect(row.row.name).toBe('Mouse X')
    }
  })

  it('a walk-in sale keeps its own paid/due; a customer sale follows the ledger', () => {
    const shop = new TestShop()
    const s = supplier(shop)
    const p = product(shop, 'Cover', { price: 100 })
    buy(shop, s, p, 10, 50)
    const walkIn = sell(shop, p, 1, 100)
    expect(shop.get('sales', walkIn)).toMatchObject({ paidAmount: 100, dueAmount: 0, paymentStatus: 'PAID' })
  })
})

describe('Kotlin text and dates', () => {
  it('Double.toString', () => {
    expect(kotlinDouble(500)).toBe('500.0')
    expect(kotlinDouble(1363.95)).toBe('1363.95')
    expect(kotlinDouble(1e7)).toBe('1.0E7')
    expect(kotlinDouble(12345678)).toBe('1.2345678E7')
    expect(kotlinDouble(-0.5)).toBe('-0.5')
    expect(kotlinDouble(0)).toBe('0.0')
  })

  it('Calendar.add(MONTH) clamps the day', () => {
    expect(addMonths(localDate(2026, 1, 31), 1)).toBe(localDate(2026, 2, 28))
    const p = blank('products', { id: 1, warrantyPeriodMonths: 12 })
    expect(warrantyEndDate(p, localDate(2024, 2, 29))).toBe(localDate(2025, 2, 28))
    expect(warrantyEndDate({ ...p, warrantyPeriod: 7, warrantyUnit: 'DAYS' }, localDate(2026, 10, 28))).toBe(localDate(2026, 11, 4))
  })
})
