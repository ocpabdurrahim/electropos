// CustomerRepository: customers, opening balances and payments.
import { blank, type Customer, type Sale } from '../data/schema'
import { audit, check, fail } from './common'
import { kotlinDouble } from './money'
import type { Tx } from './state'

export type CustomerInput = Partial<Customer> & Pick<Customer, 'name' | 'phone'>

/** Active customer with this phone (CustomerDao.getCustomerByPhone). */
function activeByPhone(tx: Tx, phone: string): Customer | undefined {
  return tx.where('customers', 'phone', phone).filter(c => c.isActive).sort((a, b) => a.id - b.id)[0]
}

const codeUsed = (tx: Tx, code: string, excludeId = 0) =>
  tx.where('customers', 'customerCode', code).some(c => c.id !== excludeId)

/** saveCustomer: a new customer (id 0) or changes to one. Returns the customer id. */
export function saveCustomer(tx: Tx, input: CustomerInput): number {
  const customer: Customer = { ...blank('customers'), ...input } as Customer
  check(customer.name.trim() !== '', 'Customer name cannot be empty')
  check(customer.phone.trim() !== '', 'Customer phone cannot be empty')
  const cleanPhone = customer.phone.trim()
  const existingByPhone = activeByPhone(tx, cleanPhone)
  if (existingByPhone && existingByPhone.id !== customer.id && existingByPhone.isActive) {
    fail(`Phone ${cleanPhone} already belongs to customer '${existingByPhone.name}' (${existingByPhone.customerCode}).`)
  }

  if (customer.id === 0) {
    let finalCode: string
    if (customer.customerCode.trim() === '') finalCode = tx.ctx.number('cus')
    else {
      finalCode = customer.customerCode.trim()
      if (codeUsed(tx, finalCode)) fail(`Customer code '${finalCode}' is already in use`)
    }
    const { id: _ignored, ...fields } = customer
    void _ignored
    const created = tx.insert('customers', {
      ...fields, customerCode: finalCode, currentDue: customer.openingDue, createdAt: tx.now, updatedAt: tx.now,
    })
    if (customer.openingDue !== 0) {
      tx.insert('customer_ledger_entries', {
        customerId: created.id, transactionType: 'OPENING_BALANCE', referenceId: created.id, referenceNumber: 'OPENING',
        amount: Math.abs(customer.openingDue), signedAmount: customer.openingDue, runningBalance: customer.openingDue,
        description: 'Initial opening balance carried forward', createdBy: 'Admin', createdAt: tx.now,
      })
    }
    audit(tx, 'ADMIN', 'CUSTOMER_CREATED', 'CUSTOMERS', finalCode,
      `Created customer '${created.name}' (${finalCode}) with opening due ৳${kotlinDouble(customer.openingDue)}`)
    return created.id
  }

  const existing = tx.customer(customer.id) ?? fail(`Customer not found: ${customer.id}`)
  const finalCode = customer.customerCode.trim() || existing.customerCode
  if (codeUsed(tx, finalCode, customer.id)) fail(`Customer code '${finalCode}' is already in use by another customer`)
  let adjustment = 0
  if (existing.openingDue !== customer.openingDue) {
    adjustment = customer.openingDue - existing.openingDue
    tx.insert('customer_ledger_entries', {
      customerId: customer.id, transactionType: 'DEBIT_ADJUSTMENT', referenceId: customer.id, referenceNumber: 'ADJ-OPENING',
      amount: Math.abs(adjustment), signedAmount: adjustment, runningBalance: existing.currentDue + adjustment,
      description: `Opening balance adjusted from ৳${kotlinDouble(existing.openingDue)} to ৳${kotlinDouble(customer.openingDue)}`,
      createdBy: 'Admin', createdAt: tx.now,
    })
  }
  const updated: Customer = {
    ...customer, customerCode: finalCode, phone: cleanPhone, currentDue: existing.currentDue + adjustment,
    isActive: existing.isActive, createdAt: existing.createdAt, updatedAt: tx.now,
  }
  tx.put('customers', updated)
  audit(tx, 'ADMIN', 'CUSTOMER_UPDATED', 'CUSTOMERS', finalCode, `Updated customer '${updated.name}' (${finalCode})`)
  return customer.id
}

export function deactivateCustomer(tx: Tx, customerId: number) {
  const customer = tx.get('customers', customerId) ?? fail(`Customer not found: ${customerId}`)
  tx.put('customers', { ...customer, isActive: false, updatedAt: tx.now })
  audit(tx, 'ADMIN', 'CUSTOMER_DEACTIVATED', 'CUSTOMERS', customer.customerCode,
    `Deactivated customer '${customer.name}' (${customer.customerCode})`)
}

export interface CustomerPaymentInput {
  customerId: number
  amount: number
  paymentMethod: string
  referenceNumber?: string
  notes?: string
  saleId?: number
  paymentDate?: number
}

/** recordCustomerPayment: voucher, ledger, payment + cash rows. Returns the payment id. */
export function recordCustomerPayment(tx: Tx, input: CustomerPaymentInput): number {
  const { customerId, amount, paymentMethod } = input
  const referenceNumber = input.referenceNumber ?? ''
  const notes = input.notes ?? ''
  const saleId = input.saleId ?? 0
  const paymentDate = input.paymentDate ?? tx.now
  const createdBy = tx.ctx.actor
  check(amount > 0, 'Payment amount must be greater than 0')
  const customer = tx.customer(customerId) ?? fail(`Customer not found: ${customerId}`)
  const voucher = tx.ctx.number('cpay')
  if (saleId > 0) {
    const sale = tx.get('sales', saleId)
    check(sale && sale.customerId === customerId && sale.status !== 'CANCELLED', 'The selected invoice does not belong to this customer.')
  }
  const payment = tx.insert('customer_payments', {
    paymentNumber: voucher, customerId: customer.id, customerName: customer.name, saleId, paymentDate, amount, paymentMethod,
    referenceNumber, notes, createdBy, createdAt: tx.now,
  })
  tx.insert('customer_ledger_entries', {
    customerId: customer.id, transactionType: 'CUSTOMER_PAYMENT', referenceId: payment.id, referenceNumber: voucher, amount,
    signedAmount: -amount, runningBalance: customer.currentDue - amount,
    description: `Customer payment via ${paymentMethod}` + (notes.trim() !== '' ? ` - ${notes}` : ''), createdBy, createdAt: paymentDate,
  })
  tx.insert('payment_transactions', {
    transactionType: 'CUSTOMER_PAYMENT', customerId: customer.id, saleId, amount, paymentMethod, referenceNo: voucher, notes,
    transactionDate: paymentDate, createdAt: tx.now,
  })
  tx.insert('cash_register_transactions', {
    type: 'CUSTOMER_COLLECTION', amount, flowDirection: 'IN', paymentMethod, referenceId: payment.id,
    referenceText: `Payment #${voucher}`, notes: `Collection from ${customer.name}`, date: paymentDate, createdAt: tx.now,
  })
  audit(tx, createdBy, 'CUSTOMER_PAYMENT_CREATED', 'CUSTOMERS', voucher,
    `Recorded payment of ৳${kotlinDouble(amount)} from ${customer.name} via ${paymentMethod} (#${voucher})`)
  return payment.id
}

/** Money taken against one sale: its down payment at the counter plus payments recorded for that invoice. */
export function paidAgainstSale(tx: Tx, sale: Sale): number {
  const customerId = sale.customerId
  if (customerId === null) return sale.paidAmount
  const counter = `PAY-${sale.invoiceNumber}`
  const down = tx.where('customer_ledger_entries', 'customerId', customerId)
    .filter(e => e.transactionType === 'CUSTOMER_PAYMENT' && e.referenceId === sale.id && e.referenceNumber === counter)
    .reduce((a, e) => a + e.amount, 0)
  const linked = tx.where('customer_payments', 'customerId', customerId).filter(p => p.saleId === sale.id).reduce((a, p) => a + p.amount, 0)
  return down + linked
}
