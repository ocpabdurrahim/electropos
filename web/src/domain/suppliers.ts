// SupplierRepository + SupplierPaymentRepository: suppliers, their ledger and payments.
import { blank, type Supplier, type SupplierLedgerEntry } from '../data/schema'
import { audit, check, fail } from './common'
import { kotlinDouble } from './money'
import type { Tx } from './state'

export const SupplierLedgerType = {
  PURCHASE: 'PURCHASE',
  PURCHASE_RETURN: 'PURCHASE_RETURN',
  SUPPLIER_PAYMENT: 'SUPPLIER_PAYMENT',
  SUPPLIER_REFUND: 'SUPPLIER_REFUND',
  OPENING_BALANCE: 'OPENING_BALANCE',
  DEBIT_ADJUSTMENT: 'DEBIT_ADJUSTMENT',
  CREDIT_ADJUSTMENT: 'CREDIT_ADJUSTMENT',
}

/** + raises what the shop owes the supplier, - lowers it (SupplierLedgerSign.getSignedAmount). */
export function signedAmount(type: string, amount: number): number {
  const abs = Math.abs(amount)
  switch (type) {
    case 'PURCHASE_RETURN': case 'SUPPLIER_PAYMENT': case 'CREDIT_ADJUSTMENT': return -abs
    default: return abs
  }
}

/** recordLedgerTransaction: one ledger row (the supplier's due is a cache). */
export function recordLedgerTransaction(tx: Tx, e: {
  supplierId: number
  transactionType: string
  amount: number
  referenceId?: number
  referenceNumber?: string
  description?: string
  createdBy?: string
  timestamp?: number
}): SupplierLedgerEntry {
  const signed = signedAmount(e.transactionType, e.amount)
  return tx.insert('supplier_ledger_entries', {
    supplierId: e.supplierId, transactionType: e.transactionType, referenceId: e.referenceId ?? 0,
    referenceNumber: e.referenceNumber ?? '', amount: Math.abs(e.amount), signedAmount: signed,
    runningBalance: tx.supplierBalance(e.supplierId) + signed, description: e.description ?? '', createdBy: e.createdBy ?? 'Admin',
    createdAt: e.timestamp ?? tx.now,
  })
}

export type SupplierInput = Partial<Supplier> & Pick<Supplier, 'name' | 'phone'>

/** saveSupplier: new (id 0) or changed supplier. Returns its id. */
export function saveSupplier(tx: Tx, input: SupplierInput): number {
  const supplier: Supplier = { ...blank('suppliers', { createdAt: tx.now }), ...input } as Supplier
  check(supplier.name.trim() !== '', 'Supplier name is required')
  check(supplier.phone.trim() !== '', 'Phone number is required')
  let code: string
  if (supplier.supplierCode.trim() === '') code = tx.ctx.number('sup')
  else {
    const existing = tx.where('suppliers', 'supplierCode', supplier.supplierCode)[0]
    if (existing && existing.id !== supplier.id) fail(`Supplier code '${supplier.supplierCode}' already exists.`)
    code = supplier.supplierCode
  }

  if (supplier.id === 0) {
    const { id: _ignored, ...fields } = supplier
    void _ignored
    const created = tx.insert('suppliers', { ...fields, supplierCode: code, currentDue: supplier.openingDue, updatedAt: tx.now })
    if (supplier.openingDue !== 0) {
      const advance = supplier.openingDue < 0
      recordLedgerTransaction(tx, {
        supplierId: created.id,
        transactionType: advance ? SupplierLedgerType.CREDIT_ADJUSTMENT : SupplierLedgerType.OPENING_BALANCE,
        amount: Math.abs(supplier.openingDue), referenceId: created.id, referenceNumber: 'OPENING',
        description: advance ? 'Opening advance paid to supplier' : 'Opening balance carried forward', createdBy: 'Admin',
        timestamp: supplier.createdAt,
      })
    }
    audit(tx, 'ADMIN', 'SUPPLIER_CREATED', 'SUPPLIERS', code,
      `Created supplier ${supplier.name} (${code}) with opening due ৳${kotlinDouble(supplier.openingDue)}`)
    return created.id
  }

  const existing = tx.supplier(supplier.id) ?? fail('Supplier not found')
  const difference = supplier.openingDue - existing.openingDue
  tx.put('suppliers', {
    ...supplier, supplierCode: code, currentDue: existing.currentDue + difference, isActive: existing.isActive,
    createdAt: existing.createdAt, updatedAt: tx.now,
  })
  if (difference !== 0) {
    recordLedgerTransaction(tx, {
      supplierId: supplier.id,
      transactionType: difference > 0 ? SupplierLedgerType.DEBIT_ADJUSTMENT : SupplierLedgerType.CREDIT_ADJUSTMENT,
      amount: Math.abs(difference), referenceId: supplier.id, referenceNumber: 'ADJ-OPENING',
      description: `Opening balance adjustment from ৳${kotlinDouble(existing.openingDue)} to ৳${kotlinDouble(supplier.openingDue)}`,
      createdBy: 'Admin',
    })
  }
  audit(tx, 'ADMIN', 'SUPPLIER_UPDATED', 'SUPPLIERS', code, `Updated supplier ${supplier.name} (${code})`)
  return supplier.id
}

export interface SupplierPaymentInput {
  supplierId: number
  amount: number
  paymentMethod: string
  referenceNumber?: string
  purchaseId?: number
  notes?: string
}

/** recordSupplierPayment: voucher, ledger, payment + cash rows. Returns the payment id. */
export function recordSupplierPayment(tx: Tx, input: SupplierPaymentInput): number {
  const { supplierId, amount, paymentMethod } = input
  const referenceNumber = input.referenceNumber ?? ''
  const purchaseId = input.purchaseId ?? 0
  const notes = input.notes ?? ''
  const createdBy = tx.ctx.actor
  check(amount > 0, 'Payment amount must be greater than zero.')
  const supplier = tx.get('suppliers', supplierId) ?? fail('Supplier not found.')
  if (purchaseId > 0) {
    const purchase = tx.get('purchases', purchaseId)
    check(purchase && purchase.supplierId === supplierId && purchase.status !== 'CANCELLED',
      'The selected purchase does not belong to this supplier.')
  }
  const paymentNumber = tx.ctx.number('spay')
  const payment = tx.insert('supplier_payments', {
    paymentNumber, supplierId, supplierName: supplier.name, purchaseId, paymentDate: tx.now, amount, paymentMethod,
    referenceNumber, notes, createdBy, createdAt: tx.now,
  })
  recordLedgerTransaction(tx, {
    supplierId, transactionType: SupplierLedgerType.SUPPLIER_PAYMENT, amount, referenceId: payment.id,
    referenceNumber: paymentNumber, description: notes.trim() !== '' ? notes : `Supplier payment voucher #${paymentNumber} via ${paymentMethod}`,
    createdBy,
  })
  tx.insert('payment_transactions', {
    transactionType: 'SUPPLIER_PAYMENT', supplierId, purchaseId, amount, paymentMethod,
    referenceNo: referenceNumber.trim() !== '' ? referenceNumber : paymentNumber,
    notes: notes.trim() !== '' ? notes : `Supplier payment to ${supplier.name}`, transactionDate: tx.now, createdAt: tx.now,
  })
  tx.insert('cash_register_transactions', {
    type: 'SUPPLIER_PAYMENT', amount, flowDirection: 'OUT', paymentMethod, referenceId: payment.id,
    referenceText: `Payment #${paymentNumber}`, notes: `Paid to ${supplier.name}`, date: tx.now, createdAt: tx.now,
  })
  audit(tx, 'ADMIN', 'SUPPLIER_PAYMENT_RECORDED', 'SUPPLIERS', paymentNumber,
    `Recorded payment of ৳${kotlinDouble(amount)} to supplier ${supplier.name} (${paymentNumber}) via ${paymentMethod}`)
  return payment.id
}

/** Totals of a supplier's ledger (getSupplierFinancialSummary). */
export function supplierSummary(entries: readonly SupplierLedgerEntry[]) {
  const t = { openingBalance: 0, totalPurchases: 0, totalReturns: 0, totalPaid: 0, totalRefunds: 0, debitAdjustments: 0, creditAdjustments: 0 }
  for (const e of entries) {
    switch (e.transactionType) {
      case 'OPENING_BALANCE': t.openingBalance += e.amount; break
      case 'PURCHASE': t.totalPurchases += e.amount; break
      case 'PURCHASE_RETURN': t.totalReturns += e.amount; break
      case 'SUPPLIER_PAYMENT': t.totalPaid += e.amount; break
      case 'SUPPLIER_REFUND': t.totalRefunds += e.amount; break
      case 'DEBIT_ADJUSTMENT': t.debitAdjustments += e.amount; break
      case 'CREDIT_ADJUSTMENT': t.creditAdjustments += e.amount; break
    }
  }
  const currentDue = t.openingBalance + t.totalPurchases - t.totalReturns - t.totalPaid + t.totalRefunds + t.debitAdjustments - t.creditAdjustments
  return { ...t, currentDue }
}
