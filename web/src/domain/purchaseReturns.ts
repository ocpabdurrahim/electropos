// PurchaseReturnRepository: goods going back to the supplier.
import type { Product, Purchase, PurchaseItem, SerialUnit } from '../data/schema'
import { audit, check, fail, InventoryStatus, StockMovementType, TrackingType } from './common'
import { coerceIn, kotlinDouble, roundMoney } from './money'
import type { Reader, ShopState, Tx } from './state'
import { recordLedgerTransaction, SupplierLedgerType } from './suppliers'

export interface PurchaseReturnDraft {
  purchaseId: number
  returnDate?: number
  refundAmount?: number
  reason?: string
}

export interface PurchaseReturnItemDraft {
  purchaseItemId: number
  productId: number
  productName: string
  variantId?: number
  returnQuantity: number
  serialUnitId?: number
  serialOrImei?: string
}

/** Credit per returned unit: line net (after its discount, plus tax) and its share of invoice discount/tax. */
export function returnUnitValue(purchase: Purchase, item: PurchaseItem): number {
  if (item.quantity <= 0) return 0
  const lineNet = item.lineTotal > 0 || item.unitCost === 0 ? item.lineTotal : item.quantity * item.unitCost - item.discount + item.tax
  const lineShare = purchase.subTotal > 0 ? lineNet / purchase.subTotal : 0
  const invoiceAdjustment = (purchase.taxAmount - purchase.invoiceDiscount) * lineShare
  return roundMoney(Math.max((lineNet + invoiceAdjustment) / item.quantity, 0))
}

const serializedLine = (item: PurchaseItem) => TrackingType.isSerialized(item.trackingType)

/** Units returned so far from one purchase line (returns not cancelled). */
export function returnedFromLine(s: Reader, purchaseId: number, purchaseItemId: number): number {
  let q = 0
  for (const r of s.where('purchase_returns', 'purchaseId', purchaseId)) {
    if (r.status === 'CANCELLED') continue
    for (const i of s.where('purchase_return_items', 'returnId', r.id)) if (i.purchaseItemId === purchaseItemId) q += i.quantity
  }
  return q
}

export interface ReturnableItem {
  purchaseItem: PurchaseItem
  product: Product
  alreadyReturnedQty: number
  availableReturnQty: number
  serialUnits: SerialUnit[]
  returnUnitValue: number
  isSerialized: boolean
}

/** getReturnableItemsForPurchase. */
export function returnableItems(state: ShopState, purchaseId: number): ReturnableItem[] {
  const purchase = state.get('purchases', purchaseId)
  if (!purchase) return []
  const out: ReturnableItem[] = []
  for (const item of state.where('purchase_items', 'purchaseId', purchaseId).sort((a, b) => a.id - b.id)) {
    const product = state.get('products', item.productId)
    if (!product) continue
    const returned = returnedFromLine(state, purchaseId, item.id)
    const serials = state.where('product_serial_imei', 'productId', product.id)
      .filter(u => u.status === 'IN_STOCK' && (u.purchaseItemId > 0 ? u.purchaseItemId === item.id : u.purchaseId === purchaseId))
      .sort((a, b) => a.id - b.id)
    out.push({
      purchaseItem: item, product, alreadyReturnedQty: returned, availableReturnQty: Math.max(item.quantity - returned, 0),
      serialUnits: serials, returnUnitValue: returnUnitValue(purchase, item), isSerialized: serializedLine(item) || serials.length > 0,
    })
  }
  return out
}

/** createPurchaseReturn. Returns the return id. */
export function createPurchaseReturn(tx: Tx, draft: PurchaseReturnDraft, items: PurchaseReturnItemDraft[]): number {
  const now = tx.now
  const createdBy = tx.ctx.actor
  const returnDate = draft.returnDate ?? now
  const reason = draft.reason ?? ''
  check(items.length > 0, 'At least one item must be selected for return.')
  const purchase = tx.get('purchases', draft.purchaseId) ?? fail(`Purchase #${draft.purchaseId} not found.`)
  const returnNumber = tx.ctx.number('prt')
  if (purchase.status === 'CANCELLED') fail(`Purchase #${purchase.purchaseNumber} is cancelled; nothing can be returned.`)
  const lines = new Map(tx.where('purchase_items', 'purchaseId', purchase.id).map(i => [i.id, i]))

  const seenUnits = new Set<number>()
  for (const item of items) {
    const unitId = item.serialUnitId ?? 0
    check(item.returnQuantity > 0, `Return quantity must be greater than zero for ${item.productName}`)
    const line = lines.get(item.purchaseItemId) ?? fail(`Purchase line not found for ${item.productName}`)
    if (serializedLine(line)) {
      check(unitId > 0 && item.returnQuantity === 1, `Pick the exact serial/IMEI unit of '${item.productName}' to return.`)
    }
    if (unitId > 0) {
      if (seenUnits.has(unitId)) fail(`Unit '${item.serialOrImei ?? ''}' was selected more than once.`)
      seenUnits.add(unitId)
      const unit = tx.get('product_serial_imei', unitId)
      if (!unit || unit.status !== InventoryStatus.IN_STOCK) {
        fail(`Serial unit '${item.serialOrImei ?? ''}' is not in stock or cannot be returned.`)
      }
      const belongs = unit!.purchaseItemId > 0 ? unit!.purchaseItemId === line.id : unit!.purchaseId === purchase.id
      if (!belongs || unit!.productId !== line.productId) fail(`Unit '${item.serialOrImei ?? ''}' was not bought on this purchase line.`)
    }
  }
  const group = <K>(key: (d: PurchaseReturnItemDraft) => K) => {
    const m = new Map<K, PurchaseReturnItemDraft[]>()
    for (const d of items) m.set(key(d), [...(m.get(key(d)) ?? []), d])
    return m
  }
  for (const [lineId, drafts] of group(d => d.purchaseItemId)) {
    const line = lines.get(lineId)!
    const soFar = returnedFromLine(tx, purchase.id, lineId)
    const max = line.quantity - soFar
    const requested = drafts.reduce((a, d) => a + d.returnQuantity, 0)
    if (requested > max) {
      fail(`Cannot return ${requested} of '${line.productName}'. Only ${max} available to return (Purchased: ${line.quantity}, Already Returned: ${soFar}).`)
    }
  }
  for (const [productId, drafts] of group(d => d.productId)) {
    const product = tx.product(productId) ?? fail(`Product '${drafts[0].productName}' not found`)
    const requested = drafts.reduce((a, d) => a + d.returnQuantity, 0)
    if (product.currentStock < requested) {
      fail(`Cannot return ${requested} units of '${product.name}'. Current physical stock is only ${product.currentStock}.`)
    }
  }
  for (const [variantId, drafts] of group(d => d.variantId ?? 0)) {
    if (variantId <= 0) continue
    const variant = tx.variant(variantId)
    if (!variant) continue
    const requested = drafts.reduce((a, d) => a + d.returnQuantity, 0)
    if (variant.currentStock < requested) {
      fail(`Cannot return ${requested} units of variant '${variant.variantName}'. Current variant stock is only ${variant.currentStock}.`)
    }
  }

  const priced = items.map(d => {
    const unitValue = returnUnitValue(purchase, lines.get(d.purchaseItemId)!)
    return { ...d, unitCost: unitValue, totalAmount: roundMoney(unitValue * d.returnQuantity) }
  })
  const totalAmount = roundMoney(priced.reduce((a, d) => a + d.totalAmount, 0))
  const refundAmount = coerceIn(draft.refundAmount ?? 0, 0, totalAmount)
  const supplierCredit = totalAmount - refundAmount

  const ret = tx.insert('purchase_returns', {
    returnNumber, purchaseId: purchase.id, supplierId: purchase.supplierId, supplierName: purchase.supplierName, returnDate,
    subtotal: totalAmount, discount: 0, tax: 0, totalAmount, refundAmount, supplierCredit, reason, status: 'COMPLETED', createdBy,
    createdAt: now,
  })
  for (const d of priced) {
    tx.insert('purchase_return_items', {
      returnId: ret.id, purchaseItemId: d.purchaseItemId, productId: d.productId, productName: d.productName,
      variantId: d.variantId ?? 0, quantity: d.returnQuantity, unitCost: d.unitCost, totalAmount: d.totalAmount,
      serialUnitId: d.serialUnitId ?? 0, serialOrImei: d.serialOrImei ?? '',
    })
  }

  for (const d of priced) {
    const unitId = d.serialUnitId ?? 0
    const product = tx.product(d.productId)
    if (product) {
      const oldStock = product.currentStock
      const newStock = oldStock - d.returnQuantity
      const label = (d.serialOrImei ?? '').trim() === '' ? `${d.returnQuantity} units` : d.serialOrImei!
      tx.insert('stock_transactions', {
        productId: product.id, productName: product.name, variantId: d.variantId ?? 0,
        transactionType: StockMovementType.PURCHASE_RETURN, quantityChange: -d.returnQuantity, previousStock: oldStock, newStock,
        unitCost: d.unitCost, referenceId: ret.id, referenceType: 'PURCHASE_RETURN', serialUnitId: unitId, balanceAfter: newStock,
        createdBy, notes: `Return #${returnNumber} (Purchase #${purchase.purchaseNumber}): ${label}`, createdAt: now,
      })
    }
    if (unitId > 0) {
      const unit = tx.get('product_serial_imei', unitId)
      if (unit) {
        tx.put('product_serial_imei', {
          ...unit, status: InventoryStatus.RETURNED,
          notes: `Returned via Return #${returnNumber} to supplier ${purchase.supplierName}`, updatedAt: now,
        })
      }
    }
  }

  recordLedgerTransaction(tx, {
    supplierId: purchase.supplierId, transactionType: SupplierLedgerType.PURCHASE_RETURN, amount: totalAmount, referenceId: ret.id,
    referenceNumber: returnNumber,
    description: `Purchase Return #${returnNumber} (Purchase #${purchase.purchaseNumber}): ${reason.trim() === '' ? 'Goods returned to vendor' : reason}`,
    createdBy, timestamp: returnDate,
  })
  if (refundAmount > 0) {
    recordLedgerTransaction(tx, {
      supplierId: purchase.supplierId, transactionType: SupplierLedgerType.SUPPLIER_REFUND, amount: refundAmount,
      referenceId: ret.id, referenceNumber: returnNumber, description: `Cash refund received for return #${returnNumber}`,
      createdBy, timestamp: returnDate + 1,
    })
    tx.insert('cash_register_transactions', {
      type: 'REFUND', amount: refundAmount, flowDirection: 'IN', paymentMethod: 'CASH', referenceId: ret.id,
      referenceText: `Return #${returnNumber}`, notes: `Cash refund received from supplier for return #${returnNumber}`,
      date: now, createdAt: now,
    })
  }

  audit(tx, 'ADMIN', 'PURCHASE_RETURN_CREATED', 'PURCHASES', returnNumber,
    `Processed purchase return #${returnNumber} for purchase #${purchase.purchaseNumber} totaling ৳${kotlinDouble(totalAmount)}`)
  return ret.id
}
