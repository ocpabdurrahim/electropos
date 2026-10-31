// PurchaseRepository: buying stock (with IMEIs/serials) and cancelling a purchase.
import type { Purchase, PurchaseItem, SerialUnit } from '../data/schema'
import { audit, check, fail, InventoryStatus, StockMovementType, warrantyEndDate } from './common'
import { coerceIn, kotlinDouble, kotlinToInt } from './money'
import type { Tx } from './state'
import { recordLedgerTransaction, SupplierLedgerType } from './suppliers'

export interface SerialImeiInput {
  serialNumber?: string
  imei1?: string
  imei2?: string
}

export interface PurchaseItemDraft {
  productId: number
  productName: string
  variantId?: number
  variantName?: string
  quantity: number
  unitCost: number
  discount?: number
  tax?: number
  trackingType?: string
  serialsOrImeis?: SerialImeiInput[]
}

/** The PurchaseEntity fields the purchase form fills. */
export interface PurchaseDraft {
  supplierId: number
  supplierInvoiceNumber?: string
  referenceNumber?: string
  purchaseDate?: number
  paymentMethod?: string
  invoiceDiscount?: number
  taxAmount?: number
  transportCost?: number
  otherCost?: number
  paidAmount?: number
  notes?: string
}

const isSerial = (t: string) => t === 'SERIAL' || t === 'SERIAL_AND_IMEI'
const isImei = (t: string) => t === 'IMEI' || t === 'SERIAL_AND_IMEI'

function unitBySerial(tx: Tx, serial: string): SerialUnit | undefined {
  return tx.where('product_serial_imei', 'serialNumber', serial).sort((a, b) => a.id - b.id)[0]
}

function unitByImei(tx: Tx, imei: string): SerialUnit | undefined {
  return [...tx.where('product_serial_imei', 'imei1', imei), ...tx.where('product_serial_imei', 'imei2', imei)].sort((a, b) => a.id - b.id)[0]
}

/** createPurchase. Returns the purchase id. */
export function createPurchase(tx: Tx, draft: PurchaseDraft, items: PurchaseItemDraft[]): number {
  const now = tx.now
  const createdBy = tx.ctx.actor
  const purchaseDate = draft.purchaseDate ?? now
  const paymentMethod = draft.paymentMethod ?? 'CASH'
  const invoiceDiscount = draft.invoiceDiscount ?? 0
  const taxAmount = draft.taxAmount ?? 0
  const transportCost = draft.transportCost ?? 0
  const otherCost = draft.otherCost ?? 0
  const supplierInvoiceNumber = draft.supplierInvoiceNumber ?? ''

  check(items.length > 0, 'Purchase must have at least one product.')
  const supplier = tx.get('suppliers', draft.supplierId) ?? fail('Supplier not found.')

  // A unit that went back to the supplier (RETURNED) or whose purchase was cancelled can be bought again;
  // its existing row is reused so the unique serial/IMEI indexes stay valid.
  const seenSerials = new Set<string>()
  const seenImeis = new Set<string>()
  const reusable = new Map<string, SerialUnit>()
  items.forEach((item, itemIndex) => {
    const tracking = item.trackingType ?? 'QUANTITY'
    check(item.quantity > 0, `Quantity must be greater than zero for ${item.productName}`)
    check(item.unitCost >= 0, `Unit cost cannot be negative for ${item.productName}`)
    const needSerial = isSerial(tracking)
    const needImei = isImei(tracking)
    if (!needSerial && !needImei) return
    const units = item.serialsOrImeis ?? []
    check(units.length === item.quantity, `Product '${item.productName}' requires ${item.quantity} serial/IMEI entries (provided: ${units.length}).`)
    units.forEach((u, inputIndex) => {
      const serial = (u.serialNumber ?? '').trim()
      const imei1 = (u.imei1 ?? '').trim()
      const imei2 = (u.imei2 ?? '').trim()
      if (needSerial) check(serial !== '', `Serial number is required for ${item.productName}`)
      if (needImei) check(imei1 !== '', `IMEI 1 is required for ${item.productName}`)
      if (serial !== '') {
        if (seenSerials.has(serial)) fail(`Duplicate serial number '${serial}' entered in this purchase.`)
        seenSerials.add(serial)
      }
      if (imei2 !== '' && imei1 === imei2) fail(`IMEI 1 and IMEI 2 cannot be identical (${imei1}).`)
      for (const imei of [imei1, imei2].filter(x => x !== '')) {
        if (seenImeis.has(imei)) fail(`Duplicate IMEI '${imei}' entered in this purchase.`)
        seenImeis.add(imei)
      }
      const found = [
        serial !== '' ? unitBySerial(tx, serial) : undefined,
        imei1 !== '' ? unitByImei(tx, imei1) : undefined,
        imei2 !== '' ? unitByImei(tx, imei2) : undefined,
      ].filter((x): x is SerialUnit => !!x)
      const distinct = [...new Map(found.map(f => [f.id, f])).values()]
      const label = [imei1, serial].find(x => x !== '') ?? imei2
      if (distinct.length > 1) fail(`'${label}' matches more than one existing unit in inventory. Check the serial and IMEI numbers.`)
      const existing = distinct[0]
      if (existing) {
        if (existing.status === InventoryStatus.RETURNED || existing.status === InventoryStatus.CANCELLED) {
          reusable.set(`${itemIndex}:${inputIndex}`, existing)
        } else fail(`'${label}' already exists in inventory (status: ${existing.status}).`)
      }
    })
  })

  const subtotal = items.reduce((a, it) => a + (it.quantity * it.unitCost - (it.discount ?? 0) + (it.tax ?? 0)), 0)
  const itemDiscounts = items.reduce((a, it) => a + (it.discount ?? 0), 0)
  const grandTotal = Math.max(0, subtotal - invoiceDiscount + taxAmount + transportCost + otherCost)
  const paidAmount = coerceIn(draft.paidAmount ?? 0, 0, grandTotal)
  const dueAmount = grandTotal - paidAmount
  const paymentStatus = paidAmount >= grandTotal && grandTotal > 0 ? 'PAID' : paidAmount > 0 ? 'PARTIAL' : 'DUE'
  const purchaseNumber = tx.ctx.number('pur')
  const additional = transportCost + otherCost

  const purchase = tx.insert('purchases', {
    purchaseNumber, invoiceNumber: purchaseNumber, supplierInvoiceNumber, referenceNumber: draft.referenceNumber ?? '',
    supplierId: supplier.id, supplierName: supplier.name, purchaseDate, paymentMethod, subTotal: subtotal,
    itemDiscount: itemDiscounts, invoiceDiscount, discountAmount: itemDiscounts + invoiceDiscount, taxAmount, transportCost,
    otherCost, totalAmount: grandTotal, paidAmount, dueAmount, paymentStatus, notes: draft.notes ?? '', status: 'COMPLETED',
    createdBy, createdAt: now, updatedAt: now,
  })
  const purchaseId = purchase.id

  items.forEach((item, itemIndex) => {
    const variantId = item.variantId ?? 0
    const discount = item.discount ?? 0
    const tax = item.tax ?? 0
    const product = tx.product(item.productId) ?? fail(`Product not found: ${item.productName}`)
    const lineTotal = item.quantity * item.unitCost - discount + tax
    // Landed cost (transport + other) and this line's share of the invoice discount and tax
    const lineShare = subtotal > 0 ? lineTotal / subtotal : 1 / items.length
    const allocatedLanded = additional * lineShare
    const invoiceAdjustment = (taxAmount - invoiceDiscount) * lineShare
    const effectiveUnitCost = Math.max((lineTotal + allocatedLanded + invoiceAdjustment) / item.quantity, 0)

    const oldStock = Math.max(product.currentStock, 0)
    const totalUnits = oldStock + item.quantity
    const newWeightedCost = totalUnits > 0 ? (oldStock * product.purchasePrice + item.quantity * effectiveUnitCost) / totalUnits : effectiveUnitCost

    const serialJson = JSON.stringify((item.serialsOrImeis ?? []).map(s => ({ serial: s.serialNumber ?? '', imei1: s.imei1 ?? '', imei2: s.imei2 ?? '' })))
    const variantName = (item.variantName ?? '').trim()
    const line = tx.insert('purchase_items', {
      purchaseId, productId: item.productId, productName: product.name + (variantName !== '' ? ` (${item.variantName})` : ''),
      variantId, quantity: item.quantity, unitCost: item.unitCost, discount, tax, lineTotal, totalCost: lineTotal,
      trackingType: item.trackingType ?? 'QUANTITY', allocatedLandedCost: allocatedLanded, serialImeisJson: serialJson,
    })

    tx.insert('product_price_history', {
      productId: product.id, purchasePrice: newWeightedCost, wholesalePrice: product.wholesalePrice, retailPrice: product.retailPrice,
      minSellingPrice: product.minSellingPrice, changedAt: now, changedBy: createdBy,
      note: `Purchase #${purchaseNumber} (+${item.quantity} units @ ৳${kotlinToInt(effectiveUnitCost)})`,
    })

    tx.insert('stock_transactions', {
      productId: product.id, productName: product.name, variantId, transactionType: StockMovementType.PURCHASE,
      quantityChange: item.quantity, previousStock: oldStock, newStock: oldStock + item.quantity, unitCost: effectiveUnitCost,
      referenceId: purchaseId, referenceType: 'PURCHASE', balanceAfter: oldStock + item.quantity, createdBy,
      notes: `Purchase #${purchaseNumber} from ${supplier.name}`, createdAt: now,
    })

    ;(item.serialsOrImeis ?? []).forEach((s, inputIndex) => {
      const serialNumber = (s.serialNumber ?? '').trim() || null
      const imei1 = (s.imei1 ?? '').trim() || null
      const imei2 = (s.imei2 ?? '').trim() || null
      const warrantyEnd = product.warrantyPeriodMonths > 0 ? warrantyEndDate(product, now) : 0
      const common = {
        productId: product.id, variantId, purchaseItemId: line.id, serialNumber, imei1, imei2, ram: product.variant,
        color: product.color, purchasePrice: effectiveUnitCost, sellingPrice: product.retailPrice, supplierId: supplier.id,
        purchaseId, saleId: 0, invoiceNumber: purchaseNumber, status: InventoryStatus.IN_STOCK,
        warrantyPeriodMonths: product.warrantyPeriodMonths, warrantyStartDate: now, warrantyEndDate: warrantyEnd, updatedAt: now,
      }
      const reused = reusable.get(`${itemIndex}:${inputIndex}`)
      if (reused) {
        tx.put('product_serial_imei', { ...reused, ...common, notes: `Bought again in purchase #${purchaseNumber} (was ${reused.status})` })
      } else {
        tx.insert('product_serial_imei', { ...common, storage: '', notes: '', createdAt: now })
      }
    })
  })

  recordLedgerTransaction(tx, {
    supplierId: supplier.id, transactionType: SupplierLedgerType.PURCHASE, amount: grandTotal, referenceId: purchaseId,
    referenceNumber: purchaseNumber,
    description: `Purchase Invoice #${purchaseNumber} (Supplier Inv: ${supplierInvoiceNumber.trim() === '' ? 'N/A' : supplierInvoiceNumber})`,
    createdBy, timestamp: purchaseDate,
  })

  if (paidAmount > 0) {
    const voucher = `PAY-${purchaseNumber}`
    tx.insert('supplier_payments', {
      paymentNumber: voucher, supplierId: supplier.id, supplierName: supplier.name, purchaseId, paymentDate: purchaseDate,
      amount: paidAmount, paymentMethod, referenceNumber: purchaseNumber, notes: `Down payment for purchase ${purchaseNumber}`,
      createdBy, createdAt: now,
    })
    recordLedgerTransaction(tx, {
      supplierId: supplier.id, transactionType: SupplierLedgerType.SUPPLIER_PAYMENT, amount: paidAmount, referenceId: purchaseId,
      referenceNumber: voucher, description: `Down payment for purchase #${purchaseNumber} via ${paymentMethod}`, createdBy,
      timestamp: purchaseDate + 1,
    })
    tx.insert('payment_transactions', {
      transactionType: 'SUPPLIER_PAYMENT', supplierId: supplier.id, purchaseId, amount: paidAmount, paymentMethod,
      referenceNo: purchaseNumber, notes: `Paid for purchase ${purchaseNumber}`, transactionDate: now, createdAt: now,
    })
    tx.insert('cash_register_transactions', {
      type: 'SUPPLIER_PAYMENT', amount: paidAmount, flowDirection: 'OUT', paymentMethod, referenceId: purchaseId,
      referenceText: `Purchase #${purchaseNumber}`, notes: `Payment to ${supplier.name}`, date: now, createdAt: now,
    })
  }

  audit(tx, 'ADMIN', 'PURCHASE_COMPLETED', 'PURCHASES', purchaseNumber,
    `Completed purchase #${purchaseNumber} from ${supplier.name} for ৳${kotlinDouble(grandTotal)} (Paid: ৳${kotlinDouble(paidAmount)}, Due: ৳${kotlinDouble(dueAmount)})`)
  return purchaseId
}

/** Cost per unit that went into stock for a purchase line (line net + landed costs + invoice discount/tax share). */
export function effectiveUnitCost(purchase: Purchase, item: PurchaseItem): number {
  if (item.quantity <= 0) return 0
  const lineShare = purchase.subTotal > 0 ? item.lineTotal / purchase.subTotal : 0
  const invoiceAdjustment = (purchase.taxAmount - purchase.invoiceDiscount) * lineShare
  return Math.max((item.lineTotal + item.allocatedLandedCost + invoiceAdjustment) / item.quantity, 0)
}

/** cancelPurchase: stock out again, units CANCELLED, ledger reversed, the down payment counted as refunded. */
export function cancelPurchase(tx: Tx, purchaseId: number, reason: string) {
  const now = tx.now
  const cancelledBy = tx.ctx.actor
  const purchase = tx.get('purchases', purchaseId) ?? fail('Purchase not found')
  if (purchase.status === 'CANCELLED') fail('Purchase is already cancelled')
  const returns = tx.where('purchase_returns', 'purchaseId', purchase.id).filter(r => r.status !== 'CANCELLED')
  if (returns.length > 0) {
    fail(`Cannot cancel purchase #${purchase.purchaseNumber} because it has ${returns.length} existing purchase return(s). Please review return records.`)
  }
  const serials = tx.where('product_serial_imei', 'purchaseId', purchase.id).sort((a, b) => a.id - b.id)
  if (serials.some(s => s.status === InventoryStatus.SOLD)) {
    fail(`Cannot cancel purchase #${purchase.purchaseNumber} because some serialized items have already been sold.`)
  }
  const items = tx.where('purchase_items', 'purchaseId', purchaseId).sort((a, b) => a.id - b.id)
  for (const item of items) {
    const product = tx.product(item.productId) ?? fail(`Product not found for item: ${item.productName}`)
    if (product.currentStock < item.quantity) {
      fail(`Cannot cancel purchase #${purchase.purchaseNumber}. Required stock to reverse: ${item.quantity}, but only ${product.currentStock} units remain in stock for '${product.name}'.`)
    }
    if (item.variantId > 0) {
      const variant = tx.variant(item.variantId)
      if (variant && variant.currentStock < item.quantity) {
        fail(`Cannot cancel purchase #${purchase.purchaseNumber}. Required variant stock to reverse: ${item.quantity}, but only ${variant.currentStock} units remain in stock for variant '${variant.variantName}'.`)
      }
    }
  }

  for (const item of items) {
    const product = tx.product(item.productId)!
    const oldStock = product.currentStock
    const newStock = oldStock - item.quantity
    tx.insert('stock_transactions', {
      productId: product.id, productName: product.name, variantId: item.variantId,
      transactionType: StockMovementType.PURCHASE_CANCELLED, quantityChange: -item.quantity, previousStock: oldStock, newStock,
      unitCost: item.unitCost, referenceId: purchase.id, referenceType: 'PURCHASE', balanceAfter: newStock, createdBy: cancelledBy,
      notes: `Reversal for cancelled purchase #${purchase.purchaseNumber}. Reason: ${reason}`, createdAt: now,
    })
  }

  for (const s of serials) {
    tx.put('product_serial_imei', {
      ...s, status: InventoryStatus.CANCELLED, notes: `Purchase #${purchase.purchaseNumber} cancelled: ${reason}`, updatedAt: now,
    })
  }

  recordLedgerTransaction(tx, {
    supplierId: purchase.supplierId, transactionType: SupplierLedgerType.CREDIT_ADJUSTMENT, amount: purchase.totalAmount,
    referenceId: purchase.id, referenceNumber: `REV-${purchase.purchaseNumber}`,
    description: `Reversal of cancelled purchase #${purchase.purchaseNumber}`, createdBy: cancelledBy,
  })

  // Money paid against this purchase (its down payment) is treated as refunded by the supplier
  const paidOnPurchase = tx.where('supplier_payments', 'supplierId', purchase.supplierId)
    .filter(p => p.purchaseId === purchase.id).reduce((a, p) => a + p.amount, 0)
  if (paidOnPurchase > 0) {
    recordLedgerTransaction(tx, {
      supplierId: purchase.supplierId, transactionType: SupplierLedgerType.DEBIT_ADJUSTMENT, amount: paidOnPurchase,
      referenceId: purchase.id, referenceNumber: `REF-${purchase.purchaseNumber}`,
      description: `Reversal of payment on cancelled purchase #${purchase.purchaseNumber}`, createdBy: cancelledBy,
    })
    tx.insert('cash_register_transactions', {
      type: 'REFUND', amount: paidOnPurchase, flowDirection: 'IN', paymentMethod: purchase.paymentMethod, referenceId: purchase.id,
      referenceText: `Reversal of purchase #${purchase.purchaseNumber}`, notes: 'Supplier refunded paid amount on purchase cancellation',
      date: now, createdAt: now,
    })
  }

  tx.put('purchases', { ...tx.get('purchases', purchase.id)!, status: 'CANCELLED', updatedAt: now })
  audit(tx, 'ADMIN', 'PURCHASE_CANCELLED', 'PURCHASES', purchase.purchaseNumber,
    `Cancelled purchase #${purchase.purchaseNumber}. Reason: ${reason}`)
}
