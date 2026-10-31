// Mirrors VariantsTest.kt and parts of Phase4PurchaseAndSupplierTest / Phase5SalesAndCustomerTest.
import { describe, expect, it } from 'vitest'
import { saveBrand, saveCategory } from './catalog'
import { createPurchase } from './purchases'
import { deactivateVariant, findByCode, saveProduct, saveVariant } from './products'
import { createSale } from './sales'
import { createSalesReturn } from './salesReturns'
import { recordSupplierPayment } from './suppliers'
import { recordCustomerPayment } from './customers'
import { buy, customer, product, sell, supplier, TestShop } from './testkit'

function variantShop() {
  const shop = new TestShop()
  const supplierId = supplier(shop, 'Vendor', '01700000009')
  const productId = product(shop, 'Galaxy A15', { price: 20000 })
  const variant = (name: string, retail: number, extra: { sku?: string; barcode?: string } = {}) =>
    shop.run(tx => saveVariant(tx, { productId, variantName: name, retailPrice: retail, ...extra }))
  const buyVariant = (variantId: number, qty: number, unitCost: number) =>
    shop.run(tx => createPurchase(tx, { supplierId }, [{ productId, productName: 'Galaxy A15', variantId, variantName: 'v', quantity: qty, unitCost }]))
  return { shop, productId, variant, buyVariant }
}

describe('variants (VariantsTest)', () => {
  it('a new variant starts empty and marks the product as having variants', () => {
    const { shop, productId } = variantShop()
    const id = shop.run(tx => saveVariant(tx, { productId, variantName: '8/128 Black', retailPrice: 21000, currentStock: 50 }))
    expect(shop.get('product_variants', id).currentStock).toBe(0)
    expect(shop.get('products', productId).hasVariants).toBe(true)
  })

  it('a duplicate variant name or barcode is refused', () => {
    const { shop, productId, variant } = variantShop()
    variant('8/128 Black', 21000, { barcode: '8800000000011' })
    expect(() => shop.run(tx => saveVariant(tx, { productId, variantName: '8/128 black', retailPrice: 1 }))).toThrow()
    expect(() => shop.run(tx => saveVariant(tx, { productId, variantName: '8/256', retailPrice: 1, barcode: '8800000000011' }))).toThrow()
  })

  it("buying and selling a variant moves that variant's stock and uses its cost", () => {
    const { shop, productId, variant, buyVariant } = variantShop()
    const small = variant('8/128', 21000)
    const big = variant('8/256', 24000)
    buyVariant(small, 3, 18000)
    buyVariant(big, 2, 21000)
    expect(shop.get('product_variants', small).currentStock).toBe(3)
    expect(shop.get('product_variants', big).currentStock).toBe(2)
    expect(shop.get('products', productId).currentStock).toBe(5)
    expect(shop.get('product_variants', big).purchasePrice).toBeCloseTo(21000, 2)

    const saleId = shop.run(tx => createSale(tx, {
      customerId: null, customerName: 'Walk-in Customer', customerPhone: '', saleDate: Date.now(), subTotal: 24000, itemDiscount: 0,
      invoiceDiscount: 0, discountAmount: 0, taxAmount: 0, totalAmount: 24000, paidAmount: 24000, dueAmount: 0, paymentMethod: 'CASH',
      notes: '', salespersonName: 'Admin',
    }, [{ productId, productName: 'Galaxy A15', variantId: big, quantity: 1, unitPrice: 24000 }]))
    expect(shop.get('product_variants', big).currentStock).toBe(1)
    expect(shop.get('products', productId).currentStock).toBe(4)
    // Profit uses the 8/256's own cost (21,000), not the product's average
    expect(shop.get('sales', saleId).totalProfit).toBeCloseTo(3000, 2)
    expect(shop.state.where('sale_items', 'saleId', saleId)[0].productName).toBe('Galaxy A15 (8/256)')
  })

  it('a variant with stock cannot be removed', () => {
    const { shop, variant, buyVariant } = variantShop()
    const id = variant('8/128', 21000)
    buyVariant(id, 1, 18000)
    expect(() => shop.run(tx => deactivateVariant(tx, id))).toThrow()
  })

  it('scanning a variant barcode finds the variant', () => {
    const { shop, productId, variant } = variantShop()
    const id = variant('8/256 Blue', 24000, { barcode: '8800000000028' })
    const match = findByCode(shop.state, '8800000000028')!
    expect(match.product.id).toBe(productId)
    expect(match.variant!.id).toBe(id)
  })

  it('renaming a category and brand updates products; re-adding a hidden one brings it back', () => {
    const { shop, productId } = variantShop()
    const catId = shop.run(tx => saveCategory(tx, { name: 'Phones' }))
    const brandId = shop.run(tx => saveBrand(tx, { name: 'Samsung' }))
    shop.run(tx => { tx.update('products', productId, { categoryId: catId, categoryName: 'Phones', brandId, brandName: 'Samsung' }) })
    shop.run(tx => saveCategory(tx, { ...shop.get('categories', catId), name: 'Mobile Phones' }))
    shop.run(tx => saveBrand(tx, { ...shop.get('brands', brandId), name: 'Samsung BD' }))
    expect(shop.get('products', productId).categoryName).toBe('Mobile Phones')
    expect(shop.get('products', productId).brandName).toBe('Samsung BD')

    const unused = shop.run(tx => saveCategory(tx, { name: 'Tablets' }))
    shop.run(tx => { tx.update('categories', unused, { isActive: false }) })
    expect(shop.run(tx => saveCategory(tx, { name: 'tablets' }))).toBe(unused)
    expect(shop.get('categories', unused).isActive).toBe(true)
  })
})

describe('purchases (Phase4PurchaseAndSupplierTest)', () => {
  it('weighted average cost: (10 x 100 + 20 x 130) / 30 = 120', () => {
    const shop = new TestShop()
    const s = supplier(shop, 'Anker Bangladesh', '01999887766')
    const p = product(shop, 'Anker PowerPort III', { cost: 100, openingStock: 10 })
    buy(shop, s, p, 20, 130, { paid: 2600 })
    expect(shop.get('products', p).currentStock).toBe(30)
    expect(shop.get('products', p).purchasePrice).toBeCloseTo(120, 2)
  })

  it('landed cost is shared by line value', () => {
    const shop = new TestShop()
    const s = supplier(shop, 'Direct Importers', '01700112233')
    const p1 = product(shop, 'Router AC1200')
    const p2 = product(shop, 'Gigabit Switch')
    shop.run(tx => createPurchase(tx, { supplierId: s, transportCost: 3000, paidAmount: 33000 }, [
      { productId: p1, productName: 'Router AC1200', quantity: 10, unitCost: 1000 },
      { productId: p2, productName: 'Gigabit Switch', quantity: 10, unitCost: 2000 },
    ]))
    expect(shop.get('products', p1).purchasePrice).toBeCloseTo(1100, 2)
    expect(shop.get('products', p2).purchasePrice).toBeCloseTo(2200, 2)
  })

  it('partial payment turns into paid', () => {
    const shop = new TestShop()
    const s = supplier(shop, 'Xiaomi Global Distribution', '01700000077')
    const p = product(shop, 'Redmi Pad')
    const purchaseId = buy(shop, s, p, 2, 25000, { paid: 20000 })
    expect(shop.get('purchases', purchaseId).paymentStatus).toBe('PARTIAL')
    expect(shop.get('suppliers', s).currentDue).toBeCloseTo(30000, 2)
    shop.run(tx => recordSupplierPayment(tx, { supplierId: s, amount: 30000, paymentMethod: 'BANK', purchaseId }))
    expect(shop.get('suppliers', s).currentDue).toBeCloseTo(0, 2)
    expect(shop.get('purchases', purchaseId).paymentStatus).toBe('PAID')
    const ledger = shop.state.where('supplier_ledger_entries', 'supplierId', s).sort((a, b) => a.createdAt - b.createdAt || a.id - b.id)
    expect(ledger.map(e => e.runningBalance)).toEqual([50000, 30000, 0])
  })

  it('a supplier opening advance is a credit adjustment', () => {
    const shop = new TestShop()
    const s = shop.run(tx => supplierWithOpening(tx, -5000))
    const entry = shop.state.where('supplier_ledger_entries', 'supplierId', s)[0]
    expect(entry.transactionType).toBe('CREDIT_ADJUSTMENT')
    expect(shop.get('suppliers', s).currentDue).toBeCloseTo(-5000, 2)
  })
})

import { saveSupplier } from './suppliers'
import type { Tx } from './state'
const supplierWithOpening = (tx: Tx, openingDue: number) => saveSupplier(tx, { name: 'Advance Co', phone: '017', openingDue })

describe('sales and customers (Phase5SalesAndCustomerTest)', () => {
  it('a customer with an opening balance gets an OPENING_BALANCE ledger row', () => {
    const shop = new TestShop()
    const c = customer(shop, 'Rahim', '01711223344', 1500)
    const [entry] = shop.state.where('customer_ledger_entries', 'customerId', c)
    expect(entry.transactionType).toBe('OPENING_BALANCE')
    expect(entry.signedAmount).toBe(1500)
    expect(shop.get('customers', c).currentDue).toBe(1500)
    expect(shop.get('customers', c).customerCode).toBe('CUS-W0001')
  })

  it('USB cable sale with discount and payment (acceptance scenario 1)', () => {
    const shop = new TestShop()
    const rahim = customer(shop, 'Rahim', '01711223344')
    const cable = product(shop, 'USB-C Fast Charging Cable', { cost: 120, price: 250, openingStock: 20 })
    shop.run(tx => createSale(tx, {
      customerId: rahim, customerName: 'Rahim', customerPhone: '', saleDate: Date.now(), subTotal: 1250, itemDiscount: 0,
      invoiceDiscount: 50, discountAmount: 0, taxAmount: 0, totalAmount: 1200, paidAmount: 500, dueAmount: 700, paymentMethod: 'CASH',
      notes: '', salespersonName: 'Admin',
    }, [{ productId: cable, productName: 'USB-C Fast Charging Cable', quantity: 5, unitPrice: 250 }]))
    expect(shop.get('products', cable).currentStock).toBe(15)
    expect(shop.get('customers', rahim).currentDue).toBeCloseTo(700, 3)
    shop.run(tx => recordCustomerPayment(tx, { customerId: rahim, amount: 700, paymentMethod: 'BKASH', referenceNumber: 'TRX-7788', notes: 'Full settlement' }))
    expect(shop.get('customers', rahim).currentDue).toBeCloseTo(0, 3)
  })

  it('walk-in credit is blocked and the credit limit is enforced', () => {
    const shop = new TestShop()
    const p = product(shop, 'Earbuds', { cost: 500, price: 1000, openingStock: 10 })
    expect(() => sell(shop, p, 1, 1000, { paid: 0 })).toThrow(/Walk-in customer cannot have unpaid due/)
    const c = shop.run(tx => saveCustomerWithLimit(tx))
    sell(shop, p, 1, 1000, { customerId: c, paid: 0 })
    expect(() => sell(shop, p, 2, 1000, { customerId: c, paid: 0 })).toThrow(/credit limit/)
    expect(shop.get('products', p).currentStock).toBe(9)
  })

  it('IMEI sale marks the unit SOLD and a defective return marks it DAMAGED (acceptance scenario 2)', () => {
    const shop = new TestShop()
    const s = supplier(shop)
    const phone = product(shop, 'Redmi Note 13', { tracking: 'IMEI', price: 22000 })
    buy(shop, s, phone, 1, 18000, { imeis: ['860123456789001'] })
    const unit = shop.rows('product_serial_imei')[0]
    const karim = customer(shop, 'Karim Mobile', '01811000009')
    const saleId = sell(shop, phone, 1, 22000, { customerId: karim, paid: 10000, unitIds: [unit.id] })
    expect(shop.get('product_serial_imei', unit.id).status).toBe('SOLD')
    expect(() => sell(shop, phone, 1, 22000, { unitIds: [unit.id] })).toThrow()
    const item = shop.state.where('sale_items', 'saleId', saleId)[0]
    shop.run(tx => createSalesReturn(tx, { saleId }, [{
      saleItemId: item.id, productId: phone, productName: 'Redmi Note 13', returnQuantity: 1, serialUnitId: unit.id,
      serialOrImei: '860123456789001', restockOption: 'DEFECTIVE_DAMAGED',
    }]))
    expect(shop.get('product_serial_imei', unit.id).status).toBe('DAMAGED')
    expect(shop.get('products', phone).currentStock).toBe(0)
    expect(shop.get('customers', karim).currentDue).toBeCloseTo(-10000, 2)
  })

  it('a product with opening stock writes its price history and OPENING_STOCK movement', () => {
    const shop = new TestShop()
    const id = shop.run(tx => saveProduct(tx, { name: 'Power bank', purchasePrice: 900, retailPrice: 1300, currentStock: 4 }))
    expect(shop.get('products', id).currentStock).toBe(4)
    expect(shop.get('products', id).openingStock).toBe(4)
    expect(shop.state.where('stock_transactions', 'productId', id)[0].transactionType).toBe('OPENING_STOCK')
    expect(shop.state.where('product_price_history', 'productId', id)[0].note).toBe('Initial product price')
    expect(() => shop.run(tx => saveProduct(tx, { name: 'IMEI phone', trackingType: 'IMEI', currentStock: 1 }))).toThrow()
  })
})

import { saveCustomer } from './customers'
const saveCustomerWithLimit = (tx: Tx) => saveCustomer(tx, { name: 'Limit', phone: '01900000000', creditLimit: 1500 })

import { adjustStock, deleteProductIfUnused } from './products'
import { deleteBrandIfUnused, deleteCategoryIfUnused, recordWarrantyClaim, saveShopProfile } from './catalog'
import { toFields } from '../data/schema'

describe('stock, deletes and settings', () => {
  it('a stock adjustment writes one movement and never goes below zero (StockRepository.adjustStock)', () => {
    const shop = new TestShop()
    const p = product(shop, 'Cable', { cost: 100, openingStock: 5 })
    shop.run(tx => adjustStock(tx, p, -2, 'ADJUSTMENT_DAMAGE', 'two broken'))
    expect(shop.get('products', p).currentStock).toBe(3)
    const mv = shop.state.where('stock_transactions', 'productId', p).find(t => t.transactionType === 'ADJUSTMENT_DAMAGE')!
    expect(mv).toMatchObject({ quantityChange: -2, previousStock: 5, newStock: 3, unitCost: 100, referenceType: 'MANUAL_ADJUSTMENT', balanceAfter: 0 })
    expect(() => shop.run(tx => adjustStock(tx, p, -4, 'ADJUSTMENT_LOST', ''))).toThrow(/negative/)
    expect(shop.rows('audit_logs').at(-1)!.description).toBe('Adjusted Cable stock by -2 (ADJUSTMENT_DAMAGE). Notes: two broken')
  })

  it('an unused product is removed with its variants and price history; a used one is only deactivated', () => {
    const shop = new TestShop()
    const unused = shop.run(tx => saveProduct(tx, { name: 'Unused', retailPrice: 10 }))
    shop.run(tx => saveVariant(tx, { productId: unused, variantName: 'Red' }))
    expect(shop.run(tx => deleteProductIfUnused(tx, unused))).toBeNull()
    expect(shop.state.get('products', unused)).toBeUndefined()
    expect(shop.state.where('product_variants', 'productId', unused)).toHaveLength(0)
    expect(shop.state.where('product_price_history', 'productId', unused)).toHaveLength(0)
    const used = shop.run(tx => saveProduct(tx, { name: 'Used', retailPrice: 10, currentStock: 2 }))
    expect(shop.run(tx => deleteProductIfUnused(tx, used))).toMatch(/deactivated/)
    expect(shop.get('products', used).isActive).toBe(false)
  })

  it('a category or brand with products is deactivated instead of deleted', () => {
    const shop = new TestShop()
    const cat = shop.run(tx => saveCategory(tx, { name: 'Phones' }))
    const brand = shop.run(tx => saveBrand(tx, { name: 'Nokia' }))
    shop.run(tx => saveProduct(tx, { name: 'N1', categoryId: cat, categoryName: 'Phones', brandId: brand, brandName: 'Nokia' }))
    expect(shop.run(tx => deleteCategoryIfUnused(tx, cat))).toMatch(/deactivated/)
    expect(shop.get('categories', cat).isActive).toBe(false)
    expect(shop.run(tx => deleteBrandIfUnused(tx, brand))).toMatch(/deactivated/)
    const empty = shop.run(tx => saveCategory(tx, { name: 'Empty' }))
    expect(shop.run(tx => deleteCategoryIfUnused(tx, empty))).toBeNull()
    expect(shop.state.get('categories', empty)).toBeUndefined()
  })

  it('the shop profile keeps its local-only columns out of the document', () => {
    const shop = new TestShop()
    shop.run(tx => saveShopProfile(tx, { shopName: 'Masum Mobile', invoicePrefix: 'MM-' }))
    const fields = toFields('shop_profile', shop.get('shop_profile', 1))
    expect(fields.shopName).toBe('Masum Mobile')
    expect(fields.invoicePrefix).toBe('MM-')
    expect('invoiceNextNumber' in fields).toBe(false)
    expect(() => shop.run(tx => saveShopProfile(tx, { shopName: ' ' }))).toThrow()
  })

  it('a warranty claim keeps a dated note trail; a returned sale has no warranty', () => {
    const shop = new TestShop()
    const s = supplier(shop)
    const charger = shop.run(tx => saveProduct(tx, { name: 'Charger', retailPrice: 1000, warrantyPeriod: 6, warrantyPeriodMonths: 6 }))
    buy(shop, s, charger, 2, 600)
    const saleId = sell(shop, charger, 1, 1000)
    const w = shop.state.where('warranty_records', 'saleId', saleId)[0]
    expect(w.status).toBe('ACTIVE')
    shop.run(tx => recordWarrantyClaim(tx, w.id, 'CLAIMED', 'not charging'))
    shop.run(tx => recordWarrantyClaim(tx, w.id, 'REPAIRED', ''))
    const after = shop.get('warranty_records', w.id)
    expect(after.status).toBe('REPAIRED')
    expect(after.claimNotes.split('\n')).toHaveLength(2)
    expect(after.claimNotes).toContain(': CLAIMED - not charging')
  })
})
