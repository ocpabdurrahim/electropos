// Every synced table with its Room columns (docs/data-model.md, "Documents").
// Field names, kinds and defaults are the Kotlin entities' (app/.../data/local/entities).
// `local` columns are never sent and ignored when received; `cache` columns are sent
// but every client recomputes them (see domain/caches.ts).

export type ColKind = 'b' | 'i' | 'n' | 's' | 'sn' | 'in'

export interface Col<T> {
  kind: ColKind
  def: T
  local?: true
  cache?: true
}

const b = (def = false): Col<boolean> => ({ kind: 'b', def })
const i = (def = 0): Col<number> => ({ kind: 'i', def })
const n = (def = 0): Col<number> => ({ kind: 'n', def })
const s = (def = ''): Col<string> => ({ kind: 's', def })
const sn = (): Col<string | null> => ({ kind: 'sn', def: null })
const iN = (): Col<number | null> => ({ kind: 'in', def: null })
const local = <T>(c: Col<T>): Col<T> => ({ ...c, local: true })
const cache = <T>(c: Col<T>): Col<T> => ({ ...c, cache: true })

export const SCHEMA = {
  shop_profile: {
    id: i(1), shopName: s(), slogan: s('Mobile, Computer & Accessories'), address: s(), phone: s(), alternativePhone: s(),
    email: s(), website: s(), logoUri: local(s()), currencySymbol: s('৳'), currencyCode: s('BDT'),
    invoicePrefix: s('INV-'), invoiceNextNumber: local(i(1001)), purchasePrefix: s('PUR-'), purchaseNextNumber: local(i(1)),
    purchaseReturnPrefix: s('PRT-'), purchaseReturnNextNumber: local(i(1)), salesReturnPrefix: s('SRT-'),
    salesReturnNextNumber: local(i(1)), customerPaymentPrefix: s('CPAY-'), customerPaymentNextNumber: local(i(1)),
    customerCodePrefix: s('CUS-'), customerNextNumber: local(i(1)), defaultTaxRate: n(), defaultDiscountRate: n(),
    lowStockAlertThreshold: i(5), adminPin: s(), isPinRequired: b(true), isSampleDataLoaded: b(), setupCompleted: b(),
    themeMode: local(s('SYSTEM')), rememberLogin: local(b()), lastActiveUserId: local(i()), updatedAt: i(),
  },
  users: {
    id: i(), name: s(), username: s(), pinHash: s(), salt: s(), role: s('ADMIN'), isActive: b(true), createdAt: i(),
    updatedAt: i(), lastLoginAt: local(i()),
  },
  categories: {
    id: i(), name: s(), description: s(), iconName: s('category'), isActive: b(true), createdAt: i(), updatedAt: i(),
  },
  subcategories: {
    id: i(), categoryId: i(), name: s(), description: s(), isActive: b(true), createdAt: i(), updatedAt: i(),
  },
  brands: {
    id: i(), name: s(), description: s(), originCountry: s(), logoUri: local(s()), isActive: b(true), createdAt: i(), updatedAt: i(),
  },
  units: {
    id: i(), name: s(), code: s('Pcs'), isActive: b(true), createdAt: i(),
  },
  suppliers: {
    id: i(), supplierCode: s(), name: s(), company: s(), phone: s(), alternativePhone: s(), whatsApp: s(), email: s(),
    address: s(), city: s(), openingDue: n(), currentDue: cache(n()), creditLimit: n(), notes: s(), isActive: b(true),
    createdAt: i(), updatedAt: i(),
  },
  customers: {
    id: i(), customerCode: s(), name: s(), phone: s(), alternativePhone: s(), whatsApp: s(), email: s(), address: s(),
    city: s(), customerType: s('REGULAR'), openingDue: n(), currentDue: cache(n()), creditLimit: n(), notes: s(),
    isActive: b(true), createdAt: i(), updatedAt: i(),
  },
  products: {
    id: i(), sku: s(), barcode: s(), name: s(), categoryId: i(), categoryName: s(), subcategoryId: i(), subcategoryName: s(),
    brandId: i(), brandName: s(), model: s(), description: s(), unitId: i(), unit: s('Pcs'), color: s(), variant: s(),
    productType: s('PHYSICAL'), trackingType: s('QUANTITY'), purchasePrice: cache(n()), wholesalePrice: n(), retailPrice: n(),
    minSellingPrice: n(), taxPercent: n(), discountPercent: n(), currentStock: cache(i()), minStock: i(5), maxStock: i(1000),
    openingStock: i(), supplierId: i(), supplierName: s(), hasVariants: b(), hasWarranty: b(), warrantyPeriod: i(),
    warrantyUnit: s('MONTHS'), warrantyPeriodMonths: i(), warrantyType: s('SERVICE'), isSerialRequired: b(),
    isImeiRequired: b(), isBarcodeRequired: b(), imageUri: local(s()), isActive: b(true), createdAt: i(), updatedAt: i(),
    notes: s(),
  },
  product_variants: {
    id: i(), productId: i(), variantName: s(), sku: s(), barcode: s(), color: s(), ram: s(), storage: s(),
    otherAttributes: s(), purchasePrice: cache(n()), wholesalePrice: n(), retailPrice: n(), minSellingPrice: n(),
    minStock: i(2), currentStock: cache(i()), isActive: b(true), createdAt: i(), updatedAt: i(),
  },
  product_serial_imei: {
    id: i(), productId: i(), variantId: i(), purchaseItemId: i(), serialNumber: sn(), imei1: sn(), imei2: sn(), ram: s(),
    storage: s(), color: s(), purchasePrice: n(), sellingPrice: n(), supplierId: i(), purchaseId: i(), saleId: i(),
    invoiceNumber: s(), status: s('IN_STOCK'), warrantyPeriodMonths: i(), warrantyStartDate: i(), warrantyEndDate: i(),
    notes: s(), createdAt: i(), updatedAt: i(),
  },
  product_price_history: {
    id: i(), productId: i(), purchasePrice: n(), wholesalePrice: n(), retailPrice: n(), minSellingPrice: n(), changedAt: i(),
    changedBy: s('Admin'), note: s(),
  },
  purchases: {
    id: i(), purchaseNumber: s(), invoiceNumber: s(), supplierInvoiceNumber: s(), referenceNumber: s(), supplierId: i(),
    supplierName: s(), purchaseDate: i(), paymentMethod: s('CASH'), subTotal: n(), itemDiscount: n(), invoiceDiscount: n(),
    discountAmount: n(), taxAmount: n(), transportCost: n(), otherCost: n(), totalAmount: n(), paidAmount: cache(n()),
    dueAmount: cache(n()), paymentStatus: cache(s('DUE')), notes: s(), status: s('COMPLETED'), createdBy: s('Admin'),
    createdAt: i(), updatedAt: i(),
  },
  purchase_items: {
    id: i(), purchaseId: i(), productId: i(), productName: s(), variantId: i(), quantity: i(), unitCost: n(), discount: n(),
    tax: n(), lineTotal: n(), totalCost: n(), trackingType: s('QUANTITY'), allocatedLandedCost: n(), serialImeisJson: s(),
  },
  purchase_returns: {
    id: i(), returnNumber: s(), purchaseId: i(), supplierId: i(), supplierName: s(), returnDate: i(), subtotal: n(),
    discount: n(), tax: n(), totalAmount: n(), refundAmount: n(), supplierCredit: n(), reason: s(), status: s('COMPLETED'),
    createdBy: s('Admin'), createdAt: i(),
  },
  purchase_return_items: {
    id: i(), returnId: i(), purchaseItemId: i(), productId: i(), productName: s(), variantId: i(), quantity: i(),
    unitCost: n(), totalAmount: n(), serialUnitId: i(), serialOrImei: s(),
  },
  supplier_payments: {
    id: i(), paymentNumber: s(), supplierId: i(), supplierName: s(), purchaseId: i(), paymentDate: i(), amount: n(),
    paymentMethod: s('CASH'), referenceNumber: s(), notes: s(), createdBy: s('Admin'), createdAt: i(),
  },
  supplier_ledger_entries: {
    id: i(), supplierId: i(), transactionType: s(), referenceId: i(), referenceNumber: s(), amount: n(), signedAmount: n(),
    runningBalance: cache(n()), description: s(), createdBy: s('Admin'), createdAt: i(),
  },
  sales: {
    id: i(), invoiceNumber: s(), customerId: iN(), customerName: s('Walk-in Customer'), customerPhone: s(), saleDate: i(),
    subTotal: n(), itemDiscount: n(), invoiceDiscount: n(), discountAmount: n(), taxAmount: n(), totalAmount: n(),
    paidAmount: cache(n()), dueAmount: cache(n()), paymentStatus: cache(s('PAID')), paymentMethod: s('CASH'), notes: s(),
    status: s('COMPLETED'), salespersonName: s('Admin'), totalCostOfGoodsSold: n(), totalProfit: n(), createdAt: i(),
    updatedAt: i(),
  },
  sale_items: {
    id: i(), saleId: i(), productId: i(), productName: s(), variantId: i(), quantity: i(), unitPrice: n(),
    unitCostAtSale: n(), discount: n(), tax: n(), totalPrice: n(), profit: n(), trackingType: s('QUANTITY'),
    serialImeisJson: s(),
  },
  sales_returns: {
    id: i(), returnNumber: s(), saleId: i(), invoiceNumber: s(), customerId: i(), customerName: s('Walk-in Customer'),
    customerPhone: s(), returnDate: i(), subtotal: n(), taxAmount: n(), discountAmount: n(), totalAmount: n(),
    refundAmount: n(), customerCredit: n(), refundPaymentMethod: s('CASH'), reason: s(), status: s('COMPLETED'),
    createdBy: s('Admin'), createdAt: i(),
  },
  sales_return_items: {
    id: i(), salesReturnId: i(), saleItemId: i(), productId: i(), productName: s(), variantId: i(), quantity: i(),
    unitPrice: n(), unitCostAtSale: n(), totalPrice: n(), serialUnitId: i(), serialOrImei: s(),
    restockOption: s('RESTOCK_SELLABLE'),
  },
  customer_payments: {
    id: i(), paymentNumber: s(), customerId: i(), customerName: s(), saleId: i(), paymentDate: i(), amount: n(),
    paymentMethod: s('CASH'), referenceNumber: s(), notes: s(), createdBy: s('Admin'), createdAt: i(),
  },
  customer_ledger_entries: {
    id: i(), customerId: i(), transactionType: s(), referenceId: i(), referenceNumber: s(), amount: n(), signedAmount: n(),
    runningBalance: cache(n()), description: s(), createdBy: s('Admin'), createdAt: i(),
  },
  stock_transactions: {
    id: i(), productId: i(), productName: s(), variantId: i(), transactionType: s(), quantityChange: i(), previousStock: i(),
    newStock: i(), unitCost: n(), referenceId: i(), referenceType: s(), serialUnitId: i(), balanceAfter: i(),
    createdBy: s('Admin'), notes: s(), createdAt: i(),
  },
  payment_transactions: {
    id: i(), transactionType: s(), customerId: i(), supplierId: i(), saleId: i(), purchaseId: i(), amount: n(),
    paymentMethod: s('CASH'), referenceNo: s(), notes: s(), transactionDate: i(), createdAt: i(),
  },
  expenses: {
    id: i(), categoryName: s(), title: s(), description: s(), amount: n(), paymentMethod: s('CASH'), referenceNo: s(),
    expenseDate: i(), notes: s(), createdAt: i(),
  },
  expense_categories: {
    id: i(), name: s(), isDefault: b(),
  },
  cash_register_transactions: {
    id: i(), type: s(), amount: n(), flowDirection: s(), paymentMethod: s('CASH'), referenceId: i(), referenceText: s(),
    notes: s(), date: i(), createdAt: i(),
  },
  warranty_records: {
    id: i(), saleId: i(), invoiceNumber: s(), customerId: i(), customerName: s(), customerPhone: s(), productId: i(),
    productName: s(), serialImeiId: i(), serialOrImei: s(), saleDate: i(), warrantyPeriodMonths: i(),
    warrantyStartDate: i(), warrantyEndDate: i(), status: s('ACTIVE'), claimNotes: s(), createdAt: i(),
  },
  audit_logs: {
    id: i(), userRole: s('ADMIN'), action: s(), module: s(), recordId: s(), description: s(), timestamp: i(),
  },
} satisfies Record<string, Record<string, Col<unknown>>>

export type TableName = keyof typeof SCHEMA
type Spec = typeof SCHEMA
export type Row<T extends TableName> = { [K in keyof Spec[T]]: Spec[T][K] extends Col<infer V> ? V : never }

/** The 31 synced collections, in the contract's order. */
export const TABLES = Object.keys(SCHEMA) as TableName[]

/** Read only by OWNER, ADMIN and MANAGER (firestore.rules canRead). */
export const RESTRICTED_TABLES: TableName[] = ['users']

/** Tables a salesperson may write (firestore.rules isSellingSide). */
export const SELLING_SIDE: TableName[] = [
  'sales', 'sale_items', 'sales_returns', 'sales_return_items', 'customers', 'customer_payments', 'customer_ledger_entries',
  'stock_transactions', 'product_serial_imei', 'payment_transactions', 'cash_register_transactions', 'warranty_records',
  'audit_logs',
]

export type ShopProfile = Row<'shop_profile'>
export type User = Row<'users'>
export type Category = Row<'categories'>
export type Subcategory = Row<'subcategories'>
export type Brand = Row<'brands'>
export type Unit = Row<'units'>
export type Supplier = Row<'suppliers'>
export type Customer = Row<'customers'>
export type Product = Row<'products'>
export type Variant = Row<'product_variants'>
export type SerialUnit = Row<'product_serial_imei'>
export type PriceHistory = Row<'product_price_history'>
export type Purchase = Row<'purchases'>
export type PurchaseItem = Row<'purchase_items'>
export type PurchaseReturn = Row<'purchase_returns'>
export type PurchaseReturnItem = Row<'purchase_return_items'>
export type SupplierPayment = Row<'supplier_payments'>
export type SupplierLedgerEntry = Row<'supplier_ledger_entries'>
export type Sale = Row<'sales'>
export type SaleItem = Row<'sale_items'>
export type SalesReturn = Row<'sales_returns'>
export type SalesReturnItem = Row<'sales_return_items'>
export type CustomerPayment = Row<'customer_payments'>
export type CustomerLedgerEntry = Row<'customer_ledger_entries'>
export type StockTx = Row<'stock_transactions'>
export type PaymentTx = Row<'payment_transactions'>
export type Expense = Row<'expenses'>
export type ExpenseCategory = Row<'expense_categories'>
export type CashTx = Row<'cash_register_transactions'>
export type Warranty = Row<'warranty_records'>
export type AuditLog = Row<'audit_logs'>

/** A row with every column at the entity's default, then [fields] on top. */
export function blank<T extends TableName>(table: T, fields: Partial<Row<T>> = {}): Row<T> {
  const out: Record<string, unknown> = {}
  for (const [k, c] of Object.entries(SCHEMA[table]) as [string, Col<unknown>][]) out[k] = c.def
  return { ...out, ...fields } as Row<T>
}

/** Firestore document fields → row, converting by the column kinds; missing fields take the defaults. */
export function fromDoc<T extends TableName>(table: T, docId: string, data: Record<string, unknown>): Row<T> {
  const out: Record<string, unknown> = {}
  for (const [k, c] of Object.entries(SCHEMA[table]) as [string, Col<unknown>][]) {
    out[k] = c.local ? c.def : convert(data[k], c)
  }
  const id = Number(docId)
  out.id = Number.isSafeInteger(id) ? id : out.id
  return out as Row<T>
}

function convert(v: unknown, c: Col<unknown>): unknown {
  switch (c.kind) {
    case 'b': return v === true || v === 1 ? true : v === false || v === 0 ? false : c.def
    case 'i': return typeof v === 'number' && Number.isFinite(v) ? Math.trunc(v) : c.def
    case 'n': return typeof v === 'number' && Number.isFinite(v) ? v : c.def
    case 's': return typeof v === 'string' ? v : c.def
    case 'sn': return typeof v === 'string' ? v : null
    case 'in': return typeof v === 'number' && Number.isFinite(v) ? Math.trunc(v) : null
  }
}

/** Row → document fields: every column except the local-only ones (the writer adds _ts and _del). */
export function toFields<T extends TableName>(table: T, row: Row<T>): Record<string, unknown> {
  const out: Record<string, unknown> = {}
  const r = row as Record<string, unknown>
  for (const [k, c] of Object.entries(SCHEMA[table]) as [string, Col<unknown>][]) {
    if (c.local) continue
    const v = r[k]
    out[k] = v === undefined ? c.def : v
  }
  return out
}
