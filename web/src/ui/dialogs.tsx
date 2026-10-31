// Forms used from several pages.
import { useMemo, useState } from 'react'
import { blank, type Customer, type Product, type Supplier, type Variant } from '../data/schema'
import { useShop } from '../data/store'
import { STOCK_MOVEMENT_LABELS, TrackingType } from '../domain/common'
import { recordCustomerPayment, saveCustomer } from '../domain/customers'
import { ADJUSTMENT_REASONS, adjustStock, costFromPurchases, saveProduct, saveVariant } from '../domain/products'
import { recordSupplierPayment, saveSupplier } from '../domain/suppliers'
import { amountOf, amountText, ErrorText, Field, MethodSelect, Modal, MoneyInput, useAction, useCan, useMoney } from './common'

const intOf = (s: string) => {
  const n = Number(s.trim())
  return Number.isFinite(n) ? Math.trunc(n) : 0
}

// ---- Customers --------------------------------------------------------------------------

export function CustomerDialog({ customer, onClose, onSaved }: { customer?: Customer; onClose: () => void; onSaved?: (id: number) => void }) {
  const c = customer ?? blank('customers')
  const [f, setF] = useState({
    name: c.name, phone: c.phone, alternativePhone: c.alternativePhone, email: c.email, address: c.address, city: c.city,
    customerType: c.customerType, openingDue: amountText(c.openingDue), creditLimit: amountText(c.creditLimit), notes: c.notes,
    customerCode: c.customerCode,
  })
  const { run, busy, error } = useAction()
  const save = async () => {
    const id = await run(tx => saveCustomer(tx, {
      ...c, ...f, name: f.name.trim(), phone: f.phone.trim(), openingDue: amountOf(f.openingDue), creditLimit: amountOf(f.creditLimit),
    }), customer ? 'Customer saved' : 'Customer added')
    if (id !== undefined) {
      onSaved?.(id)
      onClose()
    }
  }
  const set = (k: keyof typeof f) => (e: { target: { value: string } }) => setF({ ...f, [k]: e.target.value })
  return (
    <Modal onClose={onClose} label="Customer">
      <h2>{customer ? 'Edit customer' : 'New customer'}</h2>
      <div className="form-grid">
        <Field label="Name *"><input className="input" autoFocus value={f.name} onChange={set('name')} data-testid="customer-name" /></Field>
        <Field label="Phone *"><input className="input" type="tel" value={f.phone} onChange={set('phone')} data-testid="customer-phone" /></Field>
        <Field label="Other phone"><input className="input" type="tel" value={f.alternativePhone} onChange={set('alternativePhone')} /></Field>
        <Field label="Email"><input className="input" type="email" value={f.email} onChange={set('email')} /></Field>
        <Field label="Address" full><input className="input" value={f.address} onChange={set('address')} /></Field>
        <Field label="City"><input className="input" value={f.city} onChange={set('city')} /></Field>
        <Field label="Type">
          <select className="input" value={f.customerType} onChange={set('customerType')}>
            <option value="REGULAR">Regular</option><option value="CASH">Cash</option><option value="CREDIT">Credit</option>
          </select>
        </Field>
        <Field label="Opening due" hint="What they already owed before (negative = advance)">
          <input className="input num" inputMode="decimal" value={f.openingDue} onChange={set('openingDue')} />
        </Field>
        <Field label="Credit limit" hint="0 = no limit"><MoneyInput value={f.creditLimit} onChange={v => setF({ ...f, creditLimit: v })} /></Field>
        {customer && <Field label="Customer code"><input className="input" value={f.customerCode} onChange={set('customerCode')} /></Field>}
        <Field label="Notes" full><textarea className="input" rows={2} value={f.notes} onChange={set('notes')} /></Field>
      </div>
      <ErrorText error={error} />
      <div className="actions">
        <button className="btn text" onClick={onClose}>Cancel</button>
        <button className="btn" disabled={busy || !f.name.trim() || !f.phone.trim()} onClick={save} data-testid="customer-save">{busy ? 'Saving…' : 'Save'}</button>
      </div>
    </Modal>
  )
}

export function CustomerPaymentDialog({ customerId, saleId, onClose }: { customerId: number; saleId?: number; onClose: () => void }) {
  const { state } = useShop()
  const money = useMoney()
  const customer = state.get('customers', customerId)
  const openSales = state.where('sales', 'customerId', customerId).filter(s => s.status !== 'CANCELLED' && s.dueAmount > 0.005)
    .sort((a, b) => a.saleDate - b.saleDate)
  const [amount, setAmount] = useState(amountText(saleId ? state.get('sales', saleId)?.dueAmount ?? 0 : Math.max(customer?.currentDue ?? 0, 0)))
  const [method, setMethod] = useState('CASH')
  const [link, setLink] = useState(String(saleId ?? 0))
  const [reference, setReference] = useState('')
  const [notes, setNotes] = useState('')
  const { run, busy, error } = useAction()
  if (!customer) return null
  const save = async () => {
    const id = await run(tx => recordCustomerPayment(tx, {
      customerId, amount: amountOf(amount), paymentMethod: method, referenceNumber: reference.trim(), notes: notes.trim(), saleId: Number(link),
    }), 'Payment recorded')
    if (id !== undefined) onClose()
  }
  return (
    <Modal onClose={onClose} narrow label="Customer payment">
      <h2>Payment from {customer.name}</h2>
      <p className="muted small" style={{ marginTop: -6 }}>Due now: <b>{money.format(customer.currentDue)}</b></p>
      <div className="stack">
        <Field label="Amount *"><MoneyInput value={amount} onChange={setAmount} autoFocus testId="payment-amount" /></Field>
        <Field label="Method"><MethodSelect value={method} onChange={setMethod} /></Field>
        <Field label="For invoice" hint="Optional: settles that invoice first; otherwise the oldest are paid first">
          <select className="input" value={link} onChange={e => setLink(e.target.value)}>
            <option value="0">Any (oldest first)</option>
            {openSales.map(s => <option key={s.id} value={s.id}>{s.invoiceNumber} — due {money.format(s.dueAmount)}</option>)}
          </select>
        </Field>
        <Field label="Reference (TrxID, cheque…)"><input className="input" value={reference} onChange={e => setReference(e.target.value)} /></Field>
        <Field label="Notes"><input className="input" value={notes} onChange={e => setNotes(e.target.value)} /></Field>
      </div>
      <ErrorText error={error} />
      <div className="actions">
        <button className="btn text" onClick={onClose}>Cancel</button>
        <button className="btn" disabled={busy || amountOf(amount) <= 0} onClick={save} data-testid="payment-save">{busy ? 'Saving…' : 'Record payment'}</button>
      </div>
    </Modal>
  )
}

// ---- Suppliers --------------------------------------------------------------------------

export function SupplierDialog({ supplier, onClose, onSaved }: { supplier?: Supplier; onClose: () => void; onSaved?: (id: number) => void }) {
  const s = supplier ?? blank('suppliers')
  const [f, setF] = useState({
    name: s.name, company: s.company, phone: s.phone, alternativePhone: s.alternativePhone, whatsApp: s.whatsApp, email: s.email,
    address: s.address, city: s.city, openingDue: amountText(s.openingDue), creditLimit: amountText(s.creditLimit), notes: s.notes,
    supplierCode: s.supplierCode,
  })
  const { run, busy, error } = useAction()
  const set = (k: keyof typeof f) => (e: { target: { value: string } }) => setF({ ...f, [k]: e.target.value })
  const save = async () => {
    const id = await run(tx => saveSupplier(tx, {
      ...s, ...f, name: f.name.trim(), phone: f.phone.trim(), openingDue: amountOf(f.openingDue), creditLimit: amountOf(f.creditLimit),
    }), supplier ? 'Supplier saved' : 'Supplier added')
    if (id !== undefined) {
      onSaved?.(id)
      onClose()
    }
  }
  return (
    <Modal onClose={onClose} label="Supplier">
      <h2>{supplier ? 'Edit supplier' : 'New supplier'}</h2>
      <div className="form-grid">
        <Field label="Name *"><input className="input" autoFocus value={f.name} onChange={set('name')} data-testid="supplier-name" /></Field>
        <Field label="Company"><input className="input" value={f.company} onChange={set('company')} /></Field>
        <Field label="Phone *"><input className="input" type="tel" value={f.phone} onChange={set('phone')} data-testid="supplier-phone" /></Field>
        <Field label="Other phone"><input className="input" type="tel" value={f.alternativePhone} onChange={set('alternativePhone')} /></Field>
        <Field label="WhatsApp"><input className="input" type="tel" value={f.whatsApp} onChange={set('whatsApp')} /></Field>
        <Field label="Email"><input className="input" type="email" value={f.email} onChange={set('email')} /></Field>
        <Field label="Address" full><input className="input" value={f.address} onChange={set('address')} /></Field>
        <Field label="City"><input className="input" value={f.city} onChange={set('city')} /></Field>
        <Field label="Opening due" hint="What the shop owed them before (negative = advance paid)">
          <input className="input num" inputMode="decimal" value={f.openingDue} onChange={set('openingDue')} />
        </Field>
        <Field label="Credit limit"><MoneyInput value={f.creditLimit} onChange={v => setF({ ...f, creditLimit: v })} /></Field>
        {supplier && <Field label="Supplier code"><input className="input" value={f.supplierCode} onChange={set('supplierCode')} /></Field>}
        <Field label="Notes" full><textarea className="input" rows={2} value={f.notes} onChange={set('notes')} /></Field>
      </div>
      <ErrorText error={error} />
      <div className="actions">
        <button className="btn text" onClick={onClose}>Cancel</button>
        <button className="btn" disabled={busy || !f.name.trim() || !f.phone.trim()} onClick={save} data-testid="supplier-save">{busy ? 'Saving…' : 'Save'}</button>
      </div>
    </Modal>
  )
}

export function SupplierPaymentDialog({ supplierId, purchaseId, onClose }: { supplierId: number; purchaseId?: number; onClose: () => void }) {
  const { state } = useShop()
  const money = useMoney()
  const supplier = state.get('suppliers', supplierId)
  const open = state.where('purchases', 'supplierId', supplierId).filter(p => p.status !== 'CANCELLED' && p.dueAmount > 0.005)
    .sort((a, b) => a.purchaseDate - b.purchaseDate)
  const [amount, setAmount] = useState(amountText(purchaseId ? state.get('purchases', purchaseId)?.dueAmount ?? 0 : Math.max(supplier?.currentDue ?? 0, 0)))
  const [method, setMethod] = useState('CASH')
  const [link, setLink] = useState(String(purchaseId ?? 0))
  const [reference, setReference] = useState('')
  const [notes, setNotes] = useState('')
  const { run, busy, error } = useAction()
  if (!supplier) return null
  const save = async () => {
    const id = await run(tx => recordSupplierPayment(tx, {
      supplierId, amount: amountOf(amount), paymentMethod: method, referenceNumber: reference.trim(), purchaseId: Number(link), notes: notes.trim(),
    }), 'Payment recorded')
    if (id !== undefined) onClose()
  }
  return (
    <Modal onClose={onClose} narrow label="Supplier payment">
      <h2>Pay {supplier.name}</h2>
      <p className="muted small" style={{ marginTop: -6 }}>You owe: <b>{money.format(supplier.currentDue)}</b></p>
      <div className="stack">
        <Field label="Amount *"><MoneyInput value={amount} onChange={setAmount} autoFocus testId="spay-amount" /></Field>
        <Field label="Method"><MethodSelect value={method} onChange={setMethod} /></Field>
        <Field label="For purchase" hint="Optional: settles that purchase first">
          <select className="input" value={link} onChange={e => setLink(e.target.value)}>
            <option value="0">Any (oldest first)</option>
            {open.map(p => <option key={p.id} value={p.id}>{p.purchaseNumber} — due {money.format(p.dueAmount)}</option>)}
          </select>
        </Field>
        <Field label="Reference"><input className="input" value={reference} onChange={e => setReference(e.target.value)} /></Field>
        <Field label="Notes"><input className="input" value={notes} onChange={e => setNotes(e.target.value)} /></Field>
      </div>
      <ErrorText error={error} />
      <div className="actions">
        <button className="btn text" onClick={onClose}>Cancel</button>
        <button className="btn" disabled={busy || amountOf(amount) <= 0} onClick={save} data-testid="spay-save">{busy ? 'Saving…' : 'Record payment'}</button>
      </div>
    </Modal>
  )
}

// ---- Products ---------------------------------------------------------------------------

export function ProductDialog({ product, onClose, onSaved }: { product?: Product; onClose: () => void; onSaved?: (id: number) => void }) {
  const { state } = useShop()
  const p = product ?? blank('products', { minStock: state.profile.lowStockAlertThreshold || 5, taxPercent: state.profile.defaultTaxRate })
  const categories = state.list('categories').filter(c => c.isActive || c.id === p.categoryId).sort((a, b) => a.name.localeCompare(b.name))
  const brands = state.list('brands').filter(b => b.isActive || b.id === p.brandId).sort((a, b) => a.name.localeCompare(b.name))
  const units = state.list('units').filter(u => u.isActive || u.id === p.unitId).sort((a, b) => a.name.localeCompare(b.name))
  const suppliers = state.list('suppliers').filter(s => s.isActive || s.id === p.supplierId).sort((a, b) => a.name.localeCompare(b.name))
  const costLocked = !!product && costFromPurchases(state, product.id)
  const [f, setF] = useState({
    name: p.name, sku: p.sku, barcode: p.barcode, model: p.model, description: p.description, color: p.color, variant: p.variant,
    categoryId: String(p.categoryId), subcategoryId: String(p.subcategoryId), brandId: String(p.brandId), unitId: String(p.unitId),
    supplierId: String(p.supplierId), trackingType: p.trackingType, productType: p.productType,
    purchasePrice: amountText(p.purchasePrice), wholesalePrice: amountText(p.wholesalePrice), retailPrice: amountText(p.retailPrice),
    minSellingPrice: amountText(p.minSellingPrice), taxPercent: amountText(p.taxPercent), discountPercent: amountText(p.discountPercent),
    openingStock: '', minStock: String(p.minStock), maxStock: String(p.maxStock), warrantyPeriod: String(p.warrantyPeriod || p.warrantyPeriodMonths || ''),
    warrantyUnit: p.warrantyPeriod > 0 ? p.warrantyUnit : 'MONTHS', warrantyType: p.warrantyType, notes: p.notes,
  })
  const subcategories = state.list('subcategories').filter(s => String(s.categoryId) === f.categoryId && (s.isActive || String(s.id) === f.subcategoryId))
  const { run, busy, error } = useAction()
  const set = (k: keyof typeof f) => (e: { target: { value: string } }) => setF({ ...f, [k]: e.target.value })
  const serialized = TrackingType.isSerialized(f.trackingType)
  const save = async () => {
    const category = state.get('categories', Number(f.categoryId))
    const sub = state.get('subcategories', Number(f.subcategoryId))
    const brand = state.get('brands', Number(f.brandId))
    const unit = state.get('units', Number(f.unitId))
    const supplier = state.get('suppliers', Number(f.supplierId))
    const period = intOf(f.warrantyPeriod)
    const months = period <= 0 ? 0 : f.warrantyUnit === 'YEARS' ? period * 12 : f.warrantyUnit === 'DAYS' ? Math.max(1, Math.floor((period + 29) / 30)) : period
    const id = await run(tx => saveProduct(tx, {
      ...p,
      name: f.name.trim(), sku: f.sku.trim(), barcode: f.barcode.trim(), model: f.model.trim(), description: f.description.trim(),
      color: f.color.trim(), variant: f.variant.trim(),
      categoryId: category?.id ?? 0, categoryName: category?.name ?? '', subcategoryId: sub?.id ?? 0, subcategoryName: sub?.name ?? '',
      brandId: brand?.id ?? 0, brandName: brand?.name ?? '', unitId: unit?.id ?? 0, unit: unit?.code || unit?.name || p.unit || 'Pcs',
      supplierId: supplier?.id ?? 0, supplierName: supplier?.name ?? '', trackingType: f.trackingType, productType: f.productType,
      purchasePrice: costLocked ? p.purchasePrice : amountOf(f.purchasePrice), wholesalePrice: amountOf(f.wholesalePrice),
      retailPrice: amountOf(f.retailPrice), minSellingPrice: amountOf(f.minSellingPrice), taxPercent: amountOf(f.taxPercent),
      discountPercent: amountOf(f.discountPercent), currentStock: product ? p.currentStock : intOf(f.openingStock),
      minStock: intOf(f.minStock), maxStock: intOf(f.maxStock), hasWarranty: months > 0, warrantyPeriod: period,
      warrantyUnit: f.warrantyUnit, warrantyPeriodMonths: months, warrantyType: months > 0 ? f.warrantyType : p.warrantyType,
      isSerialRequired: TrackingType.requiresSerial(f.trackingType), isImeiRequired: TrackingType.requiresImei(f.trackingType),
      notes: f.notes.trim(),
    }), product ? 'Product saved' : 'Product added')
    if (id !== undefined) {
      onSaved?.(id)
      onClose()
    }
  }
  return (
    <Modal onClose={onClose} wide label="Product">
      <h2>{product ? 'Edit product' : 'New product'}</h2>
      <div className="form-grid">
        <Field label="Name *" full><input className="input" autoFocus value={f.name} onChange={set('name')} data-testid="product-name" /></Field>
        <Field label="Category">
          <select className="input" value={f.categoryId} onChange={e => setF({ ...f, categoryId: e.target.value, subcategoryId: '0' })}>
            <option value="0">—</option>{categories.map(c => <option key={c.id} value={c.id}>{c.name}</option>)}
          </select>
        </Field>
        <Field label="Subcategory">
          <select className="input" value={f.subcategoryId} onChange={set('subcategoryId')}>
            <option value="0">—</option>{subcategories.map(c => <option key={c.id} value={c.id}>{c.name}</option>)}
          </select>
        </Field>
        <Field label="Brand">
          <select className="input" value={f.brandId} onChange={set('brandId')}>
            <option value="0">—</option>{brands.map(c => <option key={c.id} value={c.id}>{c.name}</option>)}
          </select>
        </Field>
        <Field label="Model"><input className="input" value={f.model} onChange={set('model')} /></Field>
        <Field label="SKU"><input className="input" value={f.sku} onChange={set('sku')} /></Field>
        <Field label="Barcode"><input className="input" value={f.barcode} onChange={set('barcode')} data-testid="product-barcode" /></Field>
        <Field label="Tracking" hint="IMEI / serial products get their units through purchases">
          <select className="input" value={f.trackingType} onChange={set('trackingType')} data-testid="product-tracking">
            <option value="QUANTITY">Quantity</option><option value="IMEI">IMEI</option><option value="SERIAL">Serial number</option>
            <option value="SERIAL_AND_IMEI">Serial + IMEI</option>
          </select>
        </Field>
        <Field label="Unit">
          <select className="input" value={f.unitId} onChange={set('unitId')}>
            <option value="0">{p.unit || 'Pcs'}</option>{units.map(u => <option key={u.id} value={u.id}>{u.name} ({u.code})</option>)}
          </select>
        </Field>
        <Field label="Cost price" hint={costLocked ? 'Average cost from purchases' : undefined}>
          <MoneyInput value={f.purchasePrice} onChange={v => setF({ ...f, purchasePrice: v })} disabled={costLocked} testId="product-cost" />
        </Field>
        <Field label="Selling price *"><MoneyInput value={f.retailPrice} onChange={v => setF({ ...f, retailPrice: v })} testId="product-price" /></Field>
        <Field label="Wholesale price"><MoneyInput value={f.wholesalePrice} onChange={v => setF({ ...f, wholesalePrice: v })} /></Field>
        <Field label="Minimum selling price"><MoneyInput value={f.minSellingPrice} onChange={v => setF({ ...f, minSellingPrice: v })} /></Field>
        <Field label="Tax %"><input className="input num" inputMode="decimal" value={f.taxPercent} onChange={set('taxPercent')} /></Field>
        {!product && !serialized && <Field label="Opening stock"><input className="input num" inputMode="numeric" value={f.openingStock} onChange={set('openingStock')} data-testid="product-opening" /></Field>}
        <Field label="Low-stock alert at"><input className="input num" inputMode="numeric" value={f.minStock} onChange={set('minStock')} /></Field>
        <Field label="Maximum stock"><input className="input num" inputMode="numeric" value={f.maxStock} onChange={set('maxStock')} /></Field>
        <Field label="Warranty">
          <div className="row">
            <input className="input num" style={{ width: 80 }} inputMode="numeric" value={f.warrantyPeriod} onChange={set('warrantyPeriod')} placeholder="0" />
            <select className="input" value={f.warrantyUnit} onChange={set('warrantyUnit')}>
              <option value="DAYS">days</option><option value="MONTHS">months</option><option value="YEARS">years</option>
            </select>
          </div>
        </Field>
        <Field label="Warranty type">
          <select className="input" value={f.warrantyType} onChange={set('warrantyType')}>
            <option value="SERVICE">Service</option><option value="REPLACEMENT">Replacement</option><option value="NONE">None</option>
          </select>
        </Field>
        <Field label="Default supplier">
          <select className="input" value={f.supplierId} onChange={set('supplierId')}>
            <option value="0">—</option>{suppliers.map(s => <option key={s.id} value={s.id}>{s.name}</option>)}
          </select>
        </Field>
        <Field label="Color"><input className="input" value={f.color} onChange={set('color')} /></Field>
        <Field label="Variant / spec" hint="e.g. 8GB/128GB"><input className="input" value={f.variant} onChange={set('variant')} /></Field>
        <Field label="Description" full><textarea className="input" rows={2} value={f.description} onChange={set('description')} /></Field>
      </div>
      <ErrorText error={error} />
      <div className="actions">
        <button className="btn text" onClick={onClose}>Cancel</button>
        <button className="btn" disabled={busy || !f.name.trim()} onClick={save} data-testid="product-save">{busy ? 'Saving…' : 'Save'}</button>
      </div>
    </Modal>
  )
}

export function VariantDialog({ productId, variant, onClose }: { productId: number; variant?: Variant; onClose: () => void }) {
  const { state } = useShop()
  const v = variant ?? blank('product_variants', { productId })
  const costLocked = !!variant && state.derived.variantCost.has(variant.id)
  const [f, setF] = useState({
    variantName: v.variantName, sku: v.sku, barcode: v.barcode, color: v.color, ram: v.ram, storage: v.storage, otherAttributes: v.otherAttributes,
    purchasePrice: amountText(v.purchasePrice), retailPrice: amountText(v.retailPrice), wholesalePrice: amountText(v.wholesalePrice),
    minSellingPrice: amountText(v.minSellingPrice), minStock: String(v.minStock),
  })
  const { run, busy, error } = useAction()
  const set = (k: keyof typeof f) => (e: { target: { value: string } }) => setF({ ...f, [k]: e.target.value })
  const save = async () => {
    const id = await run(tx => saveVariant(tx, {
      ...v, ...f, productId, purchasePrice: costLocked ? v.purchasePrice : amountOf(f.purchasePrice), retailPrice: amountOf(f.retailPrice),
      wholesalePrice: amountOf(f.wholesalePrice), minSellingPrice: amountOf(f.minSellingPrice), minStock: intOf(f.minStock),
    }), variant ? 'Variant saved' : 'Variant added')
    if (id !== undefined) onClose()
  }
  return (
    <Modal onClose={onClose} label="Variant">
      <h2>{variant ? 'Edit variant' : 'New variant'}</h2>
      <div className="form-grid">
        <Field label="Name *" full hint="e.g. 8GB / 256GB / Black"><input className="input" autoFocus value={f.variantName} onChange={set('variantName')} data-testid="variant-name" /></Field>
        <Field label="RAM"><input className="input" value={f.ram} onChange={set('ram')} /></Field>
        <Field label="Storage"><input className="input" value={f.storage} onChange={set('storage')} /></Field>
        <Field label="Color"><input className="input" value={f.color} onChange={set('color')} /></Field>
        <Field label="Other"><input className="input" value={f.otherAttributes} onChange={set('otherAttributes')} /></Field>
        <Field label="SKU"><input className="input" value={f.sku} onChange={set('sku')} /></Field>
        <Field label="Barcode"><input className="input" value={f.barcode} onChange={set('barcode')} /></Field>
        <Field label="Cost price" hint={costLocked ? 'Average cost from purchases' : undefined}>
          <MoneyInput value={f.purchasePrice} onChange={v2 => setF({ ...f, purchasePrice: v2 })} disabled={costLocked} />
        </Field>
        <Field label="Selling price"><MoneyInput value={f.retailPrice} onChange={v2 => setF({ ...f, retailPrice: v2 })} testId="variant-price" /></Field>
        <Field label="Wholesale price"><MoneyInput value={f.wholesalePrice} onChange={v2 => setF({ ...f, wholesalePrice: v2 })} /></Field>
        <Field label="Minimum selling price"><MoneyInput value={f.minSellingPrice} onChange={v2 => setF({ ...f, minSellingPrice: v2 })} /></Field>
        <Field label="Low-stock alert at"><input className="input num" value={f.minStock} onChange={set('minStock')} /></Field>
      </div>
      <p className="muted small">New variants start with no stock; buy them through a purchase.</p>
      <ErrorText error={error} />
      <div className="actions">
        <button className="btn text" onClick={onClose}>Cancel</button>
        <button className="btn" disabled={busy || !f.variantName.trim()} onClick={save} data-testid="variant-save">{busy ? 'Saving…' : 'Save'}</button>
      </div>
    </Modal>
  )
}

export function AdjustStockDialog({ product, onClose }: { product: Product; onClose: () => void }) {
  const { state } = useShop()
  const variants = useMemo(() => state.where('product_variants', 'productId', product.id).filter(v => v.isActive), [state, product.id])
  const [direction, setDirection] = useState<'IN' | 'OUT'>('OUT')
  const [qty, setQty] = useState('1')
  const [reason, setReason] = useState('ADJUSTMENT_DAMAGE')
  const [variantId, setVariantId] = useState('0')
  const [notes, setNotes] = useState('')
  const { run, busy, error } = useAction()
  const reasons = direction === 'IN' ? ['ADJUSTMENT_FOUND', 'ADJUSTMENT_MANUAL'] : ADJUSTMENT_REASONS.filter(r => r !== 'ADJUSTMENT_FOUND')
  const save = async () => {
    const n = intOf(qty)
    const delta = direction === 'IN' ? n : -n
    const done = await run(tx => { adjustStock(tx, product.id, delta, reason, notes.trim(), Number(variantId)); return true }, 'Stock adjusted')
    if (done !== undefined) onClose()
  }
  return (
    <Modal onClose={onClose} narrow label="Adjust stock">
      <h2>Adjust stock</h2>
      <p className="muted small" style={{ marginTop: -6 }}>{product.name}: {product.currentStock} in stock</p>
      <div className="stack">
        <div className="segmented">
          <button className={direction === 'IN' ? 'on' : ''} onClick={() => { setDirection('IN'); setReason('ADJUSTMENT_FOUND') }}>Add</button>
          <button className={direction === 'OUT' ? 'on' : ''} onClick={() => { setDirection('OUT'); setReason('ADJUSTMENT_DAMAGE') }}>Remove</button>
        </div>
        <Field label="Quantity"><input className="input num" inputMode="numeric" value={qty} onChange={e => setQty(e.target.value)} data-testid="adjust-qty" /></Field>
        <Field label="Reason">
          <select className="input" value={reason} onChange={e => setReason(e.target.value)}>
            {reasons.map(r => <option key={r} value={r}>{STOCK_MOVEMENT_LABELS[r]}</option>)}
          </select>
        </Field>
        {variants.length > 0 && (
          <Field label="Variant">
            <select className="input" value={variantId} onChange={e => setVariantId(e.target.value)}>
              <option value="0">No variant</option>
              {variants.map(v => <option key={v.id} value={v.id}>{v.variantName} ({v.currentStock})</option>)}
            </select>
          </Field>
        )}
        <Field label="Notes"><input className="input" value={notes} onChange={e => setNotes(e.target.value)} /></Field>
        {TrackingType.isSerialized(product.trackingType) && (
          <div className="notice warn small">This product is tracked by IMEI/serial; units are added by purchases and removed by sales or returns.</div>
        )}
      </div>
      <ErrorText error={error} />
      <div className="actions">
        <button className="btn text" onClick={onClose}>Cancel</button>
        <button className="btn" disabled={busy || intOf(qty) <= 0} onClick={save} data-testid="adjust-save">{busy ? 'Saving…' : 'Save'}</button>
      </div>
    </Modal>
  )
}

export function useCostsVisible() {
  const allowed = useCan()
  return allowed('VIEW_COSTS_AND_PROFIT')
}
