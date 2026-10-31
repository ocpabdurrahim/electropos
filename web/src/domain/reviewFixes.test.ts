// Mirrors app/src/test/java/com/example/ReviewFixesTest.kt (same numbers).
import { pbkdf2Sync } from 'node:crypto'
import { describe, expect, it } from 'vitest'
import { InventoryStatus, TrackingType } from './common'
import { recordCustomerPayment } from './customers'
import { createPurchaseReturn, returnableItems } from './purchaseReturns'
import { cancelSale } from './sales'
import { createSalesReturn, refundUnitValue } from './salesReturns'
import { recordSupplierPayment } from './suppliers'
import { buy, customer, product, sell, supplier, TestShop } from './testkit'
import { generateSalt, hashPin, verifyPin } from './users'

const unitByImei = (shop: TestShop, imei: string) => shop.rows('product_serial_imei').find(u => u.imei1 === imei)!

describe('purchases (ReviewFixesTest)', () => {
  it('IMEI purchase return credits what was owed after line and invoice discount', () => {
    const shop = new TestShop()
    const s = supplier(shop)
    const phone = product(shop, 'Galaxy A15', { tracking: TrackingType.IMEI })
    // 2 x 20,000 - 1,000 line discount = 39,000; - 1,000 invoice discount = 38,000 owed
    const purchaseId = buy(shop, s, phone, 2, 20000, { lineDiscount: 1000, invoiceDiscount: 1000, imeis: ['350000000000001', '350000000000002'] })
    expect(shop.get('suppliers', s).currentDue).toBeCloseTo(38000, 2)
    // Unit cost in stock includes the invoice discount share
    expect(shop.get('products', phone).purchasePrice).toBeCloseTo(19000, 2)

    const [line] = returnableItems(shop.state, purchaseId)
    expect(line.returnUnitValue).toBeCloseTo(19000, 2)
    const unit = line.serialUnits[0]
    shop.run(tx => createPurchaseReturn(tx, { purchaseId }, [{
      purchaseItemId: line.purchaseItem.id, productId: phone, productName: 'Galaxy A15', returnQuantity: 1, serialUnitId: unit.id,
      serialOrImei: unit.imei1 ?? '',
    }]))
    expect(shop.get('suppliers', s).currentDue).toBeCloseTo(19000, 2)
    const purchase = shop.get('purchases', purchaseId)
    expect(purchase.dueAmount).toBeCloseTo(19000, 2)
    expect(purchase.paymentStatus).toBe('DUE')
  })

  it('IMEI purchase requires the picked unit on return', () => {
    const shop = new TestShop()
    const s = supplier(shop)
    const phone = product(shop, 'Redmi 13', { tracking: TrackingType.IMEI })
    const purchaseId = buy(shop, s, phone, 1, 15000, { imeis: ['350000000000011'] })
    const [line] = returnableItems(shop.state, purchaseId)
    expect(() => shop.run(tx => createPurchaseReturn(tx, { purchaseId }, [{
      purchaseItemId: line.purchaseItem.id, productId: phone, productName: 'Redmi 13', returnQuantity: 1,
    }]))).toThrow()
    expect(shop.get('products', phone).currentStock).toBe(1)
  })

  it('a purchase return with a cash refund is not credited twice', () => {
    const shop = new TestShop()
    const s = supplier(shop)
    const cable = product(shop, 'USB-C cable')
    const purchaseId = buy(shop, s, cable, 10, 1000, { paid: 10000 })
    expect(shop.get('suppliers', s).currentDue).toBeCloseTo(0, 2)
    const [line] = returnableItems(shop.state, purchaseId)
    shop.run(tx => createPurchaseReturn(tx, { purchaseId, refundAmount: 2000 }, [{
      purchaseItemId: line.purchaseItem.id, productId: cable, productName: 'USB-C cable', returnQuantity: 2,
    }]))
    // Supplier took 2 cables back and paid 2,000 cash: nobody owes anybody
    expect(shop.get('suppliers', s).currentDue).toBeCloseTo(0, 2)
    expect(shop.get('products', cable).currentStock).toBe(8)
    expect(shop.get('purchases', purchaseId).paymentStatus).toBe('PAID')
  })

  it('a supplier payment settles the oldest invoice first', () => {
    const shop = new TestShop()
    const s = supplier(shop)
    const mouse = product(shop, 'Mouse')
    const older = buy(shop, s, mouse, 5, 1000, { date: 1_000_000 })
    const newer = buy(shop, s, mouse, 3, 1000, { date: 2_000_000 })
    shop.run(tx => recordSupplierPayment(tx, { supplierId: s, amount: 6000, paymentMethod: 'CASH' }))
    const a = shop.get('purchases', older)
    const b = shop.get('purchases', newer)
    expect(a.paymentStatus).toBe('PAID')
    expect(a.dueAmount).toBeCloseTo(0, 2)
    expect(b.paymentStatus).toBe('PARTIAL')
    expect(b.dueAmount).toBeCloseTo(2000, 2)
    expect(shop.get('suppliers', s).currentDue).toBeCloseTo(2000, 2)
  })

  it('an IMEI returned to the supplier can be bought again', () => {
    const shop = new TestShop()
    const s = supplier(shop)
    const phone = product(shop, 'Nokia G21', { tracking: TrackingType.IMEI })
    const purchaseId = buy(shop, s, phone, 1, 12000, { imeis: ['350000000000021'] })
    const [line] = returnableItems(shop.state, purchaseId)
    const unit = line.serialUnits[0]
    shop.run(tx => createPurchaseReturn(tx, { purchaseId }, [{
      purchaseItemId: line.purchaseItem.id, productId: phone, productName: 'Nokia G21', returnQuantity: 1, serialUnitId: unit.id,
      serialOrImei: '350000000000021',
    }]))
    expect(shop.get('products', phone).currentStock).toBe(0)
    buy(shop, s, phone, 1, 11500, { imeis: ['350000000000021'] })
    const again = unitByImei(shop, '350000000000021')
    expect(again.status).toBe(InventoryStatus.IN_STOCK)
    expect(again.id).toBe(unit.id) // the row is reused, the IMEI stays unique
    expect(shop.get('products', phone).currentStock).toBe(1)
  })
})

describe('sales (ReviewFixesTest)', () => {
  it('a sales return refunds what the customer paid', () => {
    const shop = new TestShop()
    const s = supplier(shop)
    const charger = product(shop, '25W charger', { price: 1000 })
    buy(shop, s, charger, 10, 600, { paid: 6000 })
    // 1,000 - 100 line discount = 900; - 90 invoice discount = 810 paid
    const saleId = sell(shop, charger, 1, 1000, { lineDiscount: 100, invoiceDiscount: 90 })
    const item = shop.state.where('sale_items', 'saleId', saleId)[0]
    expect(refundUnitValue(shop.get('sales', saleId), item)).toBeCloseTo(810, 2)
    const returnId = shop.run(tx => createSalesReturn(tx, { saleId, refundAmount: 0 }, [{
      saleItemId: item.id, productId: charger, productName: '25W charger', returnQuantity: 1,
    }]))
    const ret = shop.get('sales_returns', returnId)
    expect(ret.totalAmount).toBeCloseTo(810, 2)
    expect(ret.refundAmount).toBeCloseTo(810, 2) // walk-in: always refunded in full
    expect(shop.get('products', charger).currentStock).toBe(10)
  })

  it('the same IMEI can be returned again after it was sold again', () => {
    const shop = new TestShop()
    const s = supplier(shop)
    const phone = product(shop, 'iPhone 13', { tracking: TrackingType.IMEI, price: 60000 })
    buy(shop, s, phone, 1, 50000, { paid: 50000, imeis: ['350000000000031'] })
    const unitId = unitByImei(shop, '350000000000031').id
    const first = sell(shop, phone, 1, 60000, { unitIds: [unitId] })
    const firstItem = shop.state.where('sale_items', 'saleId', first)[0]
    shop.run(tx => createSalesReturn(tx, { saleId: first }, [{
      saleItemId: firstItem.id, productId: phone, productName: 'iPhone 13', returnQuantity: 1, serialUnitId: unitId, serialOrImei: '350000000000031',
    }]))
    const second = sell(shop, phone, 1, 58000, { unitIds: [unitId] })
    const secondItem = shop.state.where('sale_items', 'saleId', second)[0]
    shop.run(tx => createSalesReturn(tx, { saleId: second }, [{
      saleItemId: secondItem.id, productId: phone, productName: 'iPhone 13', returnQuantity: 1, serialUnitId: unitId, serialOrImei: '350000000000031',
    }]))
    expect(shop.get('product_serial_imei', unitId).status).toBe(InventoryStatus.IN_STOCK)
  })

  it('an IMEI sales return requires the picked unit', () => {
    const shop = new TestShop()
    const s = supplier(shop)
    const phone = product(shop, 'Vivo Y28', { tracking: TrackingType.IMEI, price: 20000 })
    buy(shop, s, phone, 1, 17000, { imeis: ['350000000000041'] })
    const unitId = unitByImei(shop, '350000000000041').id
    const saleId = sell(shop, phone, 1, 20000, { unitIds: [unitId] })
    const item = shop.state.where('sale_items', 'saleId', saleId)[0]
    expect(() => shop.run(tx => createSalesReturn(tx, { saleId }, [{
      saleItemId: item.id, productId: phone, productName: 'Vivo Y28', returnQuantity: 1,
    }]))).toThrow()
    expect(shop.get('products', phone).currentStock).toBe(0)
  })

  it('a customer payment settles the oldest invoice first', () => {
    const shop = new TestShop()
    const s = supplier(shop)
    const cover = product(shop, 'Phone cover', { price: 500 })
    buy(shop, s, cover, 10, 200)
    const c = customer(shop, 'Rahim', '01811000001')
    const older = sell(shop, cover, 2, 500, { customerId: c, paid: 0, date: 1_000_000 })
    const newer = sell(shop, cover, 1, 500, { customerId: c, paid: 0, date: 2_000_000 })
    shop.run(tx => recordCustomerPayment(tx, { customerId: c, amount: 1200, paymentMethod: 'CASH' }))
    expect(shop.get('sales', older).paymentStatus).toBe('PAID')
    expect(shop.get('sales', newer).paymentStatus).toBe('PARTIAL')
    expect(shop.get('sales', newer).dueAmount).toBeCloseTo(300, 2)
    expect(shop.get('customers', c).currentDue).toBeCloseTo(300, 2)
  })

  it('profit leaves out the tax collected', () => {
    const shop = new TestShop()
    const s = supplier(shop)
    const speaker = product(shop, 'BT speaker', { price: 1000 })
    buy(shop, s, speaker, 1, 600)
    const sale = shop.get('sales', sell(shop, speaker, 1, 1000, { lineTax: 150 }))
    expect(sale.totalAmount).toBeCloseTo(1150, 2)
    expect(sale.totalProfit).toBeCloseTo(400, 2)
  })

  it('cancelling a sale refunds only the counter payment; later payments stay as credit', () => {
    const shop = new TestShop()
    const s = supplier(shop)
    const ssd = product(shop, 'SSD 512GB', { price: 1000 })
    buy(shop, s, ssd, 2, 700)
    const c = customer(shop, 'Karim', '01811000002')
    const saleId = sell(shop, ssd, 1, 1000, { customerId: c, paid: 200 })
    shop.run(tx => recordCustomerPayment(tx, { customerId: c, amount: 300, paymentMethod: 'CASH' }))
    expect(shop.get('customers', c).currentDue).toBeCloseTo(500, 2)
    shop.run(tx => cancelSale(tx, saleId, 'Entered by mistake'))
    // The 300 paid later stays as the customer's credit
    expect(shop.get('customers', c).currentDue).toBeCloseTo(-300, 2)
    expect(shop.get('products', ssd).currentStock).toBe(2)
    const refund = shop.rows('cash_register_transactions').find(r => r.type === 'REFUND')!
    expect(refund.amount).toBeCloseTo(200, 2)
    expect(refund.flowDirection).toBe('OUT')
  })
})

describe('PIN hashing (SecurityUtils)', () => {
  it('uses the slow hash and verifies', async () => {
    const salt = generateSalt()
    const hash = await hashPin('4321', salt)
    expect(hash.startsWith('pbkdf2$')).toBe(true)
    expect(await verifyPin('4321', salt, hash)).toBe(true)
    expect(await verifyPin('1234', salt, hash)).toBe(false)
  })

  it('matches PBKDF2-HMAC-SHA256 with 120 000 rounds over the salt text (the JVM PBEKeySpec)', async () => {
    const salt = '00112233445566778899aabbccddeeff'
    const expected = 'pbkdf2$' + pbkdf2Sync('2468', Buffer.from(salt, 'utf8'), 120_000, 32, 'sha256').toString('hex')
    expect(await hashPin('2468', salt)).toBe(expected)
  })
})
