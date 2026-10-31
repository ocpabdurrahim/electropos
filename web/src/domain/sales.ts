// SaleRepository: a sale at the counter, and cancelling one.
import type { Product, Sale } from '../data/schema'
import { audit, check, fail, InventoryStatus, StockMovementType, TrackingType, warrantyEndDate } from './common'
import { paidAgainstSale } from './customers'
import { kotlinDouble } from './money'
import type { Tx } from './state'

export interface SaleItemInput {
  productId: number
  productName: string
  variantId?: number
  quantity: number
  unitPrice: number
  discount?: number
  tax?: number
  trackingType?: string
  selectedSerialImeiIds?: number[]
  selectedSerialsOrImeis?: string[]
}

/** What the POS sends (SaleEntity fields the cashier fills; the rest is worked out here). */
export interface SaleDraft {
  customerId: number | null
  customerName: string
  customerPhone: string
  saleDate: number
  subTotal: number
  itemDiscount: number
  invoiceDiscount: number
  discountAmount: number
  taxAmount: number
  totalAmount: number
  paidAmount: number
  dueAmount: number
  paymentMethod: string
  notes: string
  salespersonName: string
}

/** Weighted cost of one unit: the variant's own cost when it has one, else the product's. */
function unitCost(tx: Tx, product: Product, variantId: number): number {
  if (variantId > 0) {
    const v = tx.variant(variantId)
    const vc = v?.purchasePrice ?? 0
    return vc > 0 ? vc : product.purchasePrice
  }
  return product.purchasePrice
}

/** createSale. Returns the new sale's id. */
export function createSale(tx: Tx, sale: SaleDraft, items: SaleItemInput[]): number {
  check(items.length > 0, 'Sale must contain at least one item')
  const now = tx.now

  // 1. Customer and credit rules
  const effectiveCustomerId = sale.customerId !== null && sale.customerId > 0 ? sale.customerId : null
  if (sale.dueAmount > 0.001) {
    if (effectiveCustomerId === null) {
      fail('Credit/Due sales require an identifiable customer. Walk-in customer cannot have unpaid due.')
    }
    const customer = tx.customer(effectiveCustomerId) ?? fail(`Customer not found: ${effectiveCustomerId}`)
    if (!customer.isActive) fail(`Customer '${customer.name}' is deactivated and cannot make credit purchases.`)
    if (customer.creditLimit > 0 && customer.currentDue + sale.dueAmount > customer.creditLimit) {
      fail(`Customer '${customer.name}' would exceed credit limit of ৳${kotlinDouble(customer.creditLimit)}. Current due: ৳${kotlinDouble(customer.currentDue)}, New due: ৳${kotlinDouble(customer.currentDue + sale.dueAmount)}`)
    }
  }

  // 2. Stock and serial/IMEI units
  const seen = new Set<number>()
  let totalCOGS = 0
  for (const input of items) {
    const variantId = input.variantId ?? 0
    const ids = input.selectedSerialImeiIds ?? []
    check(input.quantity > 0, `Item '${input.productName}' quantity must be greater than 0`)
    const product = tx.product(input.productId) ?? fail(`Product not found: ${input.productName}`)
    if (product.currentStock < input.quantity) {
      fail(`Insufficient stock for '${product.name}'. Available: ${product.currentStock}, Requested: ${input.quantity}`)
    }
    if (variantId > 0) {
      const variant = tx.variant(variantId) ?? fail(`Variant not found for item: ${input.productName}`)
      if (variant.currentStock < input.quantity) {
        fail(`Insufficient stock for variant '${variant.variantName}'. Available: ${variant.currentStock}, Requested: ${input.quantity}`)
      }
    }
    if (TrackingType.requiresSerial(product.trackingType) || TrackingType.requiresImei(product.trackingType)) {
      if (ids.length !== input.quantity) {
        fail(`Please select exactly ${input.quantity} serialized/IMEI unit(s) for '${product.name}' (selected: ${ids.length})`)
      }
      for (const serialId of ids) {
        if (seen.has(serialId)) fail('Duplicate serialized unit selected in cart')
        seen.add(serialId)
        const unit = tx.get('product_serial_imei', serialId) ?? fail(`Serialized unit ID ${serialId} not found`)
        if (unit.productId !== product.id) fail(`Serial unit '${unit.serialNumber ?? unit.imei1}' does not belong to product '${product.name}'`)
        if (variantId > 0 && unit.variantId !== variantId) fail(`Unit '${unit.imei1 ?? unit.serialNumber}' is a different variant of '${product.name}'`)
        if (unit.status !== InventoryStatus.IN_STOCK) {
          fail(`Serialized unit '${unit.serialNumber ?? unit.imei1}' is not in stock (current status: ${unit.status})`)
        }
      }
    }
    totalCOGS += unitCost(tx, product, variantId) * input.quantity
  }

  // 3. Invoice number and payment status
  const invoiceNumber = tx.ctx.number('inv')
  const paymentStatus = sale.dueAmount <= 0.001 ? 'PAID' : sale.paidAmount <= 0.001 ? 'DUE' : 'PARTIAL'
  // Tax collected belongs to the government, not to the shop's profit
  const finalProfit = sale.totalAmount - sale.taxAmount - totalCOGS

  // 4. The sale
  const saleRow = tx.insert('sales', {
    ...sale,
    invoiceNumber,
    customerId: effectiveCustomerId,
    paymentStatus,
    status: 'COMPLETED',
    totalCostOfGoodsSold: totalCOGS,
    totalProfit: finalProfit,
    createdAt: sale.saleDate > 0 ? sale.saleDate : now,
    updatedAt: now,
  })
  const saleId = saleRow.id

  // 5. Lines, stock, serials and warranty
  for (const input of items) {
    const variantId = input.variantId ?? 0
    const ids = input.selectedSerialImeiIds ?? []
    const labels = input.selectedSerialsOrImeis ?? []
    const discount = input.discount ?? 0
    const tax = input.tax ?? 0
    const product = tx.product(input.productId)!
    const itemCost = unitCost(tx, product, variantId)
    const lineGross = input.quantity * input.unitPrice
    const lineTotal = Math.max(lineGross - discount + tax, 0)
    const itemProfit = lineTotal - tax - itemCost * input.quantity
    const variantName = variantId > 0 ? tx.get('product_variants', variantId)?.variantName : undefined
    tx.insert('sale_items', {
      saleId, productId: product.id, productName: product.name + (variantName !== undefined ? ` (${variantName})` : ''),
      variantId, quantity: input.quantity, unitPrice: input.unitPrice, unitCostAtSale: itemCost, discount, tax,
      totalPrice: lineTotal, profit: itemProfit, trackingType: product.trackingType, serialImeisJson: JSON.stringify(labels),
    })

    const previousStock = product.currentStock
    const newStock = previousStock - input.quantity
    tx.insert('stock_transactions', {
      productId: product.id, productName: product.name, variantId, transactionType: StockMovementType.SALE,
      quantityChange: -input.quantity, previousStock, newStock, unitCost: itemCost, referenceId: saleId, referenceType: 'SALE',
      balanceAfter: newStock, createdBy: sale.salespersonName, notes: `Sale Invoice #${invoiceNumber}`, createdAt: now,
    })

    for (const serialId of ids) {
      const unit = tx.get('product_serial_imei', serialId)
      if (!unit || unit.status !== InventoryStatus.IN_STOCK) {
        fail(`Failed to mark serial ID ${serialId} as SOLD. Possible concurrency collision.`)
      }
      tx.put('product_serial_imei', { ...unit!, status: InventoryStatus.SOLD, saleId, invoiceNumber, updatedAt: now })
    }

    if (product.warrantyPeriodMonths > 0) {
      const end = warrantyEndDate(product, now)
      const units: [number, string][] = ids.length > 0 ? ids.map((id, i) => [id, labels[i] ?? '']) : [[0, '']]
      for (const [unitId, label] of units) {
        tx.insert('warranty_records', {
          saleId, invoiceNumber, customerId: effectiveCustomerId ?? 0, customerName: sale.customerName,
          customerPhone: sale.customerPhone, productId: product.id, productName: product.name, serialImeiId: unitId,
          serialOrImei: label, saleDate: now, warrantyPeriodMonths: product.warrantyPeriodMonths, warrantyStartDate: now,
          warrantyEndDate: end, status: 'ACTIVE', claimNotes: '', createdAt: now,
        })
      }
    }
  }

  // 6. Customer ledger
  if (effectiveCustomerId !== null) {
    const customer = tx.customer(effectiveCustomerId) ?? fail(`Customer not found: ${effectiveCustomerId}`)
    const before = customer.currentDue
    const afterSale = before + sale.totalAmount
    tx.insert('customer_ledger_entries', {
      customerId: customer.id, transactionType: 'SALE', referenceId: saleId, referenceNumber: invoiceNumber,
      amount: sale.totalAmount, signedAmount: sale.totalAmount, runningBalance: afterSale,
      description: `Sale Invoice #${invoiceNumber}`, createdBy: sale.salespersonName, createdAt: sale.saleDate,
    })
    if (sale.paidAmount > 0) {
      tx.insert('customer_ledger_entries', {
        customerId: customer.id, transactionType: 'CUSTOMER_PAYMENT', referenceId: saleId, referenceNumber: `PAY-${invoiceNumber}`,
        amount: sale.paidAmount, signedAmount: -sale.paidAmount, runningBalance: afterSale - sale.paidAmount,
        description: `Down payment for sale invoice #${invoiceNumber} via ${sale.paymentMethod}`,
        createdBy: sale.salespersonName, createdAt: sale.saleDate + 1,
      })
    }
  }

  // 7. Money taken at the counter
  if (sale.paidAmount > 0) {
    tx.insert('payment_transactions', {
      transactionType: 'CUSTOMER_PAYMENT', customerId: effectiveCustomerId ?? 0, saleId, amount: sale.paidAmount,
      paymentMethod: sale.paymentMethod, referenceNo: invoiceNumber, notes: `Payment for sale invoice ${invoiceNumber}`,
      transactionDate: now, createdAt: now,
    })
    tx.insert('cash_register_transactions', {
      type: 'SALE_CASH', amount: sale.paidAmount, flowDirection: 'IN', paymentMethod: sale.paymentMethod, referenceId: saleId,
      referenceText: `Invoice #${invoiceNumber}`, notes: `POS collection from ${sale.customerName}`, date: now, createdAt: now,
    })
  }

  // 9. Audit
  audit(tx, sale.salespersonName, 'SALE_COMPLETED', 'SALES', invoiceNumber,
    `Completed sale #${invoiceNumber} for ${sale.customerName}: ৳${kotlinDouble(sale.totalAmount)} (Paid: ৳${kotlinDouble(sale.paidAmount)}, Due: ৳${kotlinDouble(sale.dueAmount)})`)
  return saleId
}

/** cancelSale: stock back, units back in stock, warranty off, invoice reversed and the counter payment handed back. */
export function cancelSale(tx: Tx, saleId: number, reason: string) {
  const cancelledBy = tx.ctx.actor
  const now = tx.now
  const sale: Sale = tx.get('sales', saleId) ?? fail(`Sale not found: ${saleId}`)
  if (sale.status === 'CANCELLED') fail(`Sale #${sale.invoiceNumber} is already cancelled`)
  const returns = tx.where('sales_returns', 'saleId', saleId).filter(r => r.status === 'COMPLETED')
  if (returns.length > 0) {
    fail(`Cannot cancel sale #${sale.invoiceNumber} because it has ${returns.length} completed return(s). Please review return records.`)
  }

  // 1. Status
  tx.put('sales', { ...sale, status: 'CANCELLED', updatedAt: now })

  // 2. Stock back for each line
  for (const item of tx.where('sale_items', 'saleId', saleId).sort((a, b) => a.id - b.id)) {
    const product = tx.product(item.productId)
    if (!product) continue
    const prev = product.currentStock
    const next = prev + item.quantity
    tx.insert('stock_transactions', {
      productId: product.id, productName: product.name, variantId: item.variantId, transactionType: StockMovementType.SALE_RETURN,
      quantityChange: item.quantity, previousStock: prev, newStock: next, unitCost: item.unitCostAtSale, referenceId: saleId,
      referenceType: 'SALE_CANCELLED', balanceAfter: next, createdBy: cancelledBy,
      notes: `Cancelled Sale #${sale.invoiceNumber}: ${reason}`, createdAt: now,
    })
  }

  // 3. Units back in stock
  for (const unit of tx.where('product_serial_imei', 'saleId', saleId).sort((a, b) => a.id - b.id)) {
    tx.put('product_serial_imei', { ...unit, status: InventoryStatus.IN_STOCK, saleId: 0, invoiceNumber: '', updatedAt: now })
  }
  for (const w of tx.where('warranty_records', 'saleId', saleId)) {
    if (w.status === 'ACTIVE') tx.put('warranty_records', { ...w, status: 'CANCELLED' })
  }

  // 4. Customer account: the invoice goes away, the money paid at the counter is handed back
  let refundedAtCancel = sale.paidAmount
  if (sale.customerId !== null && sale.customerId > 0) {
    const customer = tx.customer(sale.customerId)
    if (customer) {
      const paidAtCounter = paidAgainstSale(tx, sale)
      refundedAtCancel = paidAtCounter
      const afterCancel = tx.customerBalance(customer.id) - sale.totalAmount
      tx.insert('customer_ledger_entries', {
        customerId: customer.id, transactionType: 'SALE_CANCEL', referenceId: saleId, referenceNumber: `CAN-${sale.invoiceNumber}`,
        amount: sale.totalAmount, signedAmount: -sale.totalAmount, runningBalance: afterCancel,
        description: `Reversal for cancelled sale #${sale.invoiceNumber} (${reason})`, createdBy: cancelledBy, createdAt: now,
      })
      if (paidAtCounter > 0) {
        tx.insert('customer_ledger_entries', {
          customerId: customer.id, transactionType: 'CUSTOMER_REFUND', referenceId: saleId,
          referenceNumber: `REF-CAN-${sale.invoiceNumber}`, amount: paidAtCounter, signedAmount: paidAtCounter,
          runningBalance: afterCancel + paidAtCounter, description: `Money paid on cancelled sale #${sale.invoiceNumber} handed back`,
          createdBy: cancelledBy, createdAt: now + 1,
        })
      }
    }
  }

  // 5. Cash out
  if (refundedAtCancel > 0) {
    tx.insert('cash_register_transactions', {
      type: 'REFUND', amount: refundedAtCancel, flowDirection: 'OUT', paymentMethod: sale.paymentMethod, referenceId: saleId,
      referenceText: `Cancel #${sale.invoiceNumber}`, notes: `Cash refund for cancelled sale (${sale.customerName})`, date: now,
      createdAt: now,
    })
  }

  audit(tx, cancelledBy, 'SALE_CANCELLED', 'SALES', sale.invoiceNumber,
    `Cancelled sale #${sale.invoiceNumber} for ${sale.customerName}. Reason: ${reason}`)
}
