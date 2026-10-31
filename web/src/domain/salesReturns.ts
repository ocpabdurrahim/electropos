// SalesReturnRepository: a customer brings items back.
import type { Sale, SaleItem, SerialUnit } from '../data/schema'
import { audit, check, fail, InventoryStatus, StockMovementType, TrackingType, unitLabel } from './common'
import { coerceIn, kotlinDouble, roundMoney } from './money'
import type { Reader, ShopState, Tx } from './state'

export interface SalesReturnDraft {
  saleId: number
  returnDate?: number
  reason?: string
  refundAmount?: number
  refundPaymentMethod?: string
}

export interface SalesReturnItemDraft {
  saleItemId: number
  productId: number
  productName: string
  variantId?: number
  returnQuantity: number
  serialUnitId?: number
  serialOrImei?: string
  restockOption?: 'RESTOCK_SELLABLE' | 'DEFECTIVE_DAMAGED' | string
}

/**
 * What the customer paid per returned unit of a line: line price after its discount, plus tax,
 * less its share of the invoice discount.
 */
export function refundUnitValue(sale: Sale, item: SaleItem): number {
  if (item.quantity <= 0) return 0
  const linesTotal = sale.totalAmount + sale.invoiceDiscount
  const factor = linesTotal > 0 ? sale.totalAmount / linesTotal : 0
  return roundMoney((item.totalPrice / item.quantity) * factor)
}

function refundUnitTax(sale: Sale, item: SaleItem): number {
  if (item.quantity <= 0) return 0
  const linesTotal = sale.totalAmount + sale.invoiceDiscount
  const factor = linesTotal > 0 ? sale.totalAmount / linesTotal : 0
  return (item.tax / item.quantity) * factor
}

const returnsOfSale = (s: Reader, saleId: number) =>
  s.where('sales_returns', 'saleId', saleId).filter(r => r.status === 'COMPLETED')

/** Units returned so far from one sale line (completed returns only). */
export function returnedQuantity(s: Reader, sale: Sale, saleItemId: number): number {
  let q = 0
  for (const r of returnsOfSale(s, sale.id)) {
    for (const i of s.where('sales_return_items', 'salesReturnId', r.id)) if (i.saleItemId === saleItemId) q += i.quantity
  }
  return q
}

function unitReturnedForSale(s: Reader, unitId: number, saleId: number): boolean {
  return returnsOfSale(s, saleId).some(r => s.where('sales_return_items', 'salesReturnId', r.id).some(i => i.serialUnitId === unitId && unitId > 0))
}

export interface ReturnableLine {
  item: SaleItem
  serialized: boolean
  returned: number
  remaining: number
  unitValue: number
  /** Units of this line sold on this invoice that have not come back yet. */
  units: SerialUnit[]
}

/** The sale's lines with what can still come back (the return screen). */
export function returnableLines(state: ShopState, sale: Sale): ReturnableLine[] {
  const soldUnits = state.where('product_serial_imei', 'saleId', sale.id)
  return state.where('sale_items', 'saleId', sale.id).sort((a, b) => a.id - b.id).map(item => {
    const returned = returnedQuantity(state, sale, item.id)
    const serialized = TrackingType.isSerialized(item.trackingType)
    const units = serialized
      ? soldUnits.filter(u => u.productId === item.productId && (item.variantId === 0 || u.variantId === item.variantId) &&
        !unitReturnedForSale(state, u.id, sale.id))
      : []
    return { item, serialized, returned, remaining: Math.max(item.quantity - returned, 0), unitValue: refundUnitValue(sale, item), units }
  })
}

/** createSalesReturn. Amounts always come from the sale lines. Returns the return id. */
export function createSalesReturn(tx: Tx, draft: SalesReturnDraft, items: SalesReturnItemDraft[]): number {
  check(items.length > 0, 'Return must contain at least one item')
  const now = tx.now
  const returnDate = draft.returnDate ?? now
  const createdBy = tx.ctx.actor
  const refundMethod = draft.refundPaymentMethod ?? 'CASH'
  const reason = draft.reason ?? ''
  const sale = tx.get('sales', draft.saleId) ?? fail(`Sale not found: ${draft.saleId}`)
  if (sale.status === 'CANCELLED') fail(`Cannot process return for cancelled sale #${sale.invoiceNumber}`)

  // 1. Limits and units
  const lines = new Map(tx.where('sale_items', 'saleId', sale.id).map(i => [i.id, i]))
  const seenUnits = new Set<number>()
  for (const item of items) {
    const serialUnitId = item.serialUnitId ?? 0
    check(item.returnQuantity > 0, `Return quantity must be greater than 0 for '${item.productName}'`)
    const saleItem = lines.get(item.saleItemId) ?? fail(`Sale line item not found for product '${item.productName}'`)
    if (TrackingType.requiresSerial(saleItem.trackingType) || TrackingType.requiresImei(saleItem.trackingType)) {
      check(serialUnitId > 0 && item.returnQuantity === 1, `Pick the exact serial/IMEI unit of '${item.productName}' that is being returned.`)
    }
    if (serialUnitId > 0) {
      if (seenUnits.has(serialUnitId)) fail(`Unit '${item.serialOrImei ?? ''}' was selected more than once`)
      seenUnits.add(serialUnitId)
      const unit = tx.get('product_serial_imei', serialUnitId) ?? fail(`Serial unit not found: ${serialUnitId}`)
      if (unit.saleId !== sale.id || unit.productId !== saleItem.productId) {
        fail(`Serial unit '${item.serialOrImei ?? ''}' was not sold under invoice #${sale.invoiceNumber}`)
      }
      if (unitReturnedForSale(tx, serialUnitId, sale.id)) fail(`Serial unit '${item.serialOrImei ?? ''}' has already been returned`)
    }
  }
  const byLine = new Map<number, SalesReturnItemDraft[]>()
  for (const d of items) byLine.set(d.saleItemId, [...(byLine.get(d.saleItemId) ?? []), d])
  for (const [saleItemId, drafts] of byLine) {
    const saleItem = lines.get(saleItemId)!
    const already = returnedQuantity(tx, sale, saleItemId)
    const remaining = saleItem.quantity - already
    const requested = drafts.reduce((a, d) => a + d.returnQuantity, 0)
    if (requested > remaining) {
      fail(`Cannot return ${requested} units of '${saleItem.productName}'. Remaining returnable quantity is only ${remaining} (Sold: ${saleItem.quantity}, Already Returned: ${already}).`)
    }
  }

  // 2. Number and amounts
  const returnNumber = tx.ctx.number('srt')
  const priced = items.map(d => {
    const unitValue = refundUnitValue(sale, lines.get(d.saleItemId)!)
    return { ...d, unitPrice: unitValue, totalAmount: roundMoney(unitValue * d.returnQuantity) }
  })
  const totalAmount = roundMoney(priced.reduce((a, d) => a + d.totalAmount, 0))
  const returnTax = roundMoney(items.reduce((a, d) => a + refundUnitTax(sale, lines.get(d.saleItemId)!) * d.returnQuantity, 0))
  const hasAccount = sale.customerId !== null && sale.customerId > 0
  // A walk-in customer has no account to hold a credit, so the whole value is handed back
  const refundAmount = hasAccount ? coerceIn(draft.refundAmount ?? 0, 0, totalAmount) : totalAmount
  const customerCredit = totalAmount - refundAmount

  const ret = tx.insert('sales_returns', {
    returnNumber, saleId: sale.id, invoiceNumber: sale.invoiceNumber, customerId: sale.customerId ?? 0,
    customerName: sale.customerName, customerPhone: sale.customerPhone, returnDate, subtotal: totalAmount, taxAmount: returnTax,
    totalAmount, refundAmount, customerCredit, refundPaymentMethod: refundMethod, reason, status: 'COMPLETED', createdBy,
    createdAt: now,
  })

  // 3. Lines and restock
  for (const d of priced) {
    const saleItem = lines.get(d.saleItemId)
    const costAtSale = saleItem?.unitCostAtSale ?? 0
    const serialUnitId = d.serialUnitId ?? 0
    const restock = d.restockOption ?? 'RESTOCK_SELLABLE'
    const variantId = d.variantId ?? 0
    tx.insert('sales_return_items', {
      salesReturnId: ret.id, saleItemId: d.saleItemId, productId: d.productId, productName: d.productName, variantId,
      quantity: d.returnQuantity, unitPrice: d.unitPrice, unitCostAtSale: costAtSale, totalPrice: d.totalAmount, serialUnitId,
      serialOrImei: d.serialOrImei ?? '', restockOption: restock,
    })

    // The customer's warranty ends with the return
    const warranties = tx.where('warranty_records', 'saleId', sale.id).filter(w => w.status === 'ACTIVE')
    if (serialUnitId > 0) {
      for (const w of warranties) if (w.serialImeiId === serialUnitId) tx.put('warranty_records', { ...w, status: 'RETURNED' })
    } else if (returnedQuantity(tx, sale, d.saleItemId) >= (saleItem?.quantity ?? Number.MAX_SAFE_INTEGER)) {
      for (const w of warranties) {
        if (w.productId === d.productId && w.serialImeiId === 0) tx.put('warranty_records', { ...w, status: 'RETURNED' })
      }
    }

    const product = tx.product(d.productId)
    if (product) {
      const oldStock = product.currentStock
      if (restock === 'RESTOCK_SELLABLE') {
        const newStock = oldStock + d.returnQuantity
        tx.insert('stock_transactions', {
          productId: product.id, productName: product.name, variantId, transactionType: StockMovementType.SALE_RETURN,
          quantityChange: d.returnQuantity, previousStock: oldStock, newStock, unitCost: costAtSale, referenceId: ret.id,
          referenceType: 'SALES_RETURN', balanceAfter: newStock, createdBy,
          notes: `Sales Return #${returnNumber} (Invoice #${sale.invoiceNumber})`, createdAt: now,
        })
        if (serialUnitId > 0) {
          const unit = tx.get('product_serial_imei', serialUnitId)
          if (unit && unit.saleId === sale.id) {
            tx.put('product_serial_imei', { ...unit, status: InventoryStatus.IN_STOCK, saleId: 0, invoiceNumber: '', updatedAt: now })
          }
        }
      } else if (serialUnitId > 0) {
        const unit = tx.get('product_serial_imei', serialUnitId)
        if (unit && unit.saleId === sale.id) {
          tx.put('product_serial_imei', {
            ...unit, status: InventoryStatus.DAMAGED, notes: `Returned defective by customer (#${returnNumber})`, updatedAt: now,
          })
        }
      }
    }
  }

  // 4. Customer account
  if (hasAccount) {
    const customer = tx.customer(sale.customerId)
    if (customer) {
      const newBalance = customer.currentDue - totalAmount
      tx.insert('customer_ledger_entries', {
        customerId: customer.id, transactionType: 'SALES_RETURN', referenceId: ret.id, referenceNumber: returnNumber,
        amount: totalAmount, signedAmount: -totalAmount, runningBalance: newBalance,
        description: `Sales Return #${returnNumber} for invoice #${sale.invoiceNumber}`, createdBy, createdAt: returnDate,
      })
      if (refundAmount > 0) {
        tx.insert('customer_ledger_entries', {
          customerId: customer.id, transactionType: 'CUSTOMER_REFUND', referenceId: ret.id, referenceNumber: `REF-${returnNumber}`,
          amount: refundAmount, signedAmount: refundAmount, runningBalance: newBalance + refundAmount,
          description: `Cash refund paid to customer for return #${returnNumber} via ${refundMethod}`, createdBy,
          createdAt: returnDate + 1,
        })
      }
    }
  }

  // 5. Cash out
  if (refundAmount > 0) {
    tx.insert('cash_register_transactions', {
      type: 'REFUND', amount: refundAmount, flowDirection: 'OUT', paymentMethod: refundMethod, referenceId: ret.id,
      referenceText: `Return #${returnNumber}`, notes: `Customer return refund (${sale.customerName})`, date: returnDate, createdAt: now,
    })
  }

  audit(tx, createdBy, 'SALES_RETURN_COMPLETED', 'SALES_RETURNS', returnNumber,
    `Completed sales return #${returnNumber} for invoice #${sale.invoiceNumber} (Total: ৳${kotlinDouble(totalAmount)}, Refund: ৳${kotlinDouble(refundAmount)}, Credit: ৳${kotlinDouble(customerCredit)})`)
  return ret.id
}

export { unitLabel }
