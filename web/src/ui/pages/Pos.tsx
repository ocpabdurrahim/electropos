// The counter: the POSViewModel rules (cart, variants, IMEI units, price and discount edits,
// walk-in credit block) and SaleRepository.createSale.
import { useMemo, useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import type { Customer, Product, SerialUnit, Variant } from '../../data/schema'
import { store, useShop } from '../../data/store'
import { InventoryStatus, TrackingType, unitLabel } from '../../domain/common'
import { methodLabel, PAYMENT_METHODS, toInput } from '../../domain/money'
import { findByCode, unassignedStock } from '../../domain/products'
import { createSale, type SaleItemInput } from '../../domain/sales'
import { currentActor } from '../../data/actions'
import {
  amountOf, amountText, Chips, Empty, ErrorText, Field, Modal, MoneyInput, PageHead, SearchBox, matches, useAction, useMoney,
} from '../common'
import { CustomerDialog } from '../dialogs'
import { Icon } from '../icons'

interface CartLine {
  key: string
  product: Product
  variant: Variant | null
  quantity: number
  unitPrice: number
  discount: number
  taxPercent: number
  unitIds: number[]
  unitLabels: string[]
}

const gross = (l: CartLine) => l.quantity * l.unitPrice
const lineTax = (l: CartLine) => (gross(l) - l.discount) * (l.taxPercent / 100)
const lineTotal = (l: CartLine) => Math.max(gross(l) - l.discount + lineTax(l), 0)
const serialized = (p: Product) => TrackingType.requiresSerial(p.trackingType) || TrackingType.requiresImei(p.trackingType)
let keySeq = 0

export default function Pos() {
  const { state } = useShop()
  const money = useMoney()
  const navigate = useNavigate()
  const [cart, setCart] = useState<CartLine[]>([])
  const [query, setQuery] = useState('')
  const [category, setCategory] = useState('ALL')
  const [customer, setCustomer] = useState<Customer | null>(null)
  const [customerQuery, setCustomerQuery] = useState('')
  const [newCustomer, setNewCustomer] = useState(false)
  const [invoiceDiscountInput, setInvoiceDiscountInput] = useState('')
  const [paidInput, setPaidInput] = useState('')
  const [method, setMethod] = useState('CASH')
  const [notes, setNotes] = useState('')
  const [notice, setNotice] = useState<string | null>(null)
  const [variantFor, setVariantFor] = useState<Product | null>(null)
  const [unitsFor, setUnitsFor] = useState<string | null>(null)
  const [editing, setEditing] = useState<string | null>(null)
  const [done, setDone] = useState<{ id: number; invoice: string } | null>(null)
  const [showCart, setShowCart] = useState(false)
  const searchRef = useRef<HTMLDivElement>(null)
  const { run, busy, error, setError } = useAction()

  const products = useMemo(() => state.list('products').filter(p => p.isActive).sort((a, b) => a.name.localeCompare(b.name)), [state])
  const variantsOf = (productId: number) => state.where('product_variants', 'productId', productId).filter(v => v.isActive).sort((a, b) => a.id - b.id)
  const categories = useMemo(() => [...new Set(products.map(p => p.categoryName).filter(Boolean))].sort(), [products])
  const shown = products.filter(p => (category === 'ALL' || p.categoryName === category) &&
    matches(query, p.name, p.sku, p.barcode, p.model, p.brandName, p.categoryName)).slice(0, 120)

  const available = (product: Product, variant: Variant | null) => {
    const variants = variantsOf(product.id)
    if (variant) return state.get('product_variants', variant.id)?.currentStock ?? 0
    if (variants.length > 0) return unassignedStock(state, state.get('products', product.id) ?? product)
    return state.get('products', product.id)?.currentStock ?? 0
  }

  const label = (p: Product, v: Variant | null) => p.name + (v ? ` (${v.variantName})` : '')

  function addToCart(product: Product, variant: Variant | null, openUnits = true) {
    setNotice(null)
    const avail = available(product, variant)
    const existing = cart.find(l => l.product.id === product.id && (l.variant?.id ?? 0) === (variant?.id ?? 0))
    if (existing && !serialized(product)) {
      if (existing.quantity + 1 > avail) { setNotice(`Insufficient stock for '${label(product, variant)}' (Available: ${avail})`); return }
      setCart(c => c.map(l => (l.key === existing.key ? { ...l, quantity: l.quantity + 1 } : l)))
      return
    }
    if (avail < 1) { setNotice(`'${label(product, variant)}' is out of stock`); return }
    const line: CartLine = {
      key: `l${++keySeq}`, product, variant, quantity: 1,
      unitPrice: variant && variant.retailPrice > 0 ? variant.retailPrice : product.retailPrice, discount: 0, taxPercent: product.taxPercent,
      unitIds: [], unitLabels: [],
    }
    setCart(c => [...c, line])
    if (serialized(product) && openUnits) setUnitsFor(line.key)
  }

  function requestAdd(product: Product) {
    if (variantsOf(product.id).length === 0) addToCart(product, null)
    else setVariantFor(product)
  }

  function addUnit(product: Product, unit: SerialUnit, variant: Variant | null) {
    const text = unitLabel(unit)
    if (unit.status !== InventoryStatus.IN_STOCK) { setNotice(`'${text}' is not in stock (status: ${unit.status})`); return }
    const existing = cart.find(l => l.product.id === product.id && (l.variant?.id ?? 0) === (variant?.id ?? 0))
    if (existing) {
      if (existing.unitIds.includes(unit.id)) { setNotice(`'${text}' is already in the cart`); return }
      const qty = Math.max(existing.quantity, existing.unitIds.length + 1)
      if (qty > product.currentStock) { setNotice(`Insufficient stock for '${product.name}' (Available: ${product.currentStock})`); return }
      setCart(c => c.map(l => (l.key === existing.key ? { ...l, quantity: qty, unitIds: [...l.unitIds, unit.id], unitLabels: [...l.unitLabels, text] } : l)))
    } else {
      setCart(c => [...c, {
        key: `l${++keySeq}`, product, variant, quantity: 1,
        unitPrice: variant && variant.retailPrice > 0 ? variant.retailPrice : product.retailPrice, discount: 0, taxPercent: product.taxPercent,
        unitIds: [unit.id], unitLabels: [text],
      }])
    }
    setNotice(`Added ${text}`)
  }

  /** Scanner or keyboard: Enter on a code adds what it points to (product, variant or one IMEI/serial unit). */
  function onEnter() {
    const code = query.trim()
    if (!code) return
    const match = findByCode(state, code)
    if (match) {
      if (match.unit) addUnit(match.product, match.unit, match.variant ?? null)
      else addToCart(match.product, match.variant ?? null)
      setQuery('')
      return
    }
    if (shown.length === 1) {
      requestAdd(shown[0])
      setQuery('')
      return
    }
    setNotice(`No product found for '${code}'`)
  }

  function setQuantity(key: string, qty: number) {
    const line = cart.find(l => l.key === key)
    if (!line) return
    if (qty <= 0) { setCart(c => c.filter(l => l.key !== key)); return }
    const avail = available(line.product, line.variant)
    if (qty > avail) { setNotice(`Cannot exceed available stock (${avail}) for '${line.product.name}'`); return }
    setNotice(null)
    setCart(c => c.map(l => (l.key !== key ? l : serialized(l.product) && qty < l.unitIds.length
      ? { ...l, quantity: qty, unitIds: l.unitIds.slice(0, qty), unitLabels: l.unitLabels.slice(0, qty) } : { ...l, quantity: qty })))
  }

  const subtotal = cart.reduce((a, l) => a + gross(l), 0)
  const itemDiscounts = cart.reduce((a, l) => a + l.discount, 0)
  const totalTax = cart.reduce((a, l) => a + lineTax(l), 0)
  const linesTotal = cart.reduce((a, l) => a + lineTotal(l), 0)
  const invoiceDiscount = Math.min(Math.max(amountOf(invoiceDiscountInput), 0), linesTotal)
  const grandTotal = Math.max(linesTotal - invoiceDiscount, 0)
  const paid = amountOf(paidInput)
  const due = Math.max(grandTotal - paid, 0)
  const change = Math.max(paid - grandTotal, 0)
  const count = cart.reduce((a, l) => a + l.quantity, 0)

  function clear() {
    setCart([])
    setInvoiceDiscountInput('')
    setPaidInput('')
    setNotes('')
    setNotice(null)
    setError(null)
  }

  async function complete() {
    setNotice(null)
    if (cart.length === 0) { setError('Cart is empty'); return }
    const missing = cart.find(l => serialized(l.product) && l.unitIds.length !== l.quantity)
    if (missing) { setError(`Please select all serials/IMEIs for '${missing.product.name}' (${missing.unitIds.length}/${missing.quantity} selected)`); return }
    if (due > 0.001 && !customer) { setError('Walk-in customers cannot have unpaid credit. Please select a registered customer or pay in full.'); return }
    const items: SaleItemInput[] = cart.map(l => ({
      productId: l.product.id, productName: l.product.name, variantId: l.variant?.id ?? 0, quantity: l.quantity, unitPrice: l.unitPrice,
      discount: l.discount, tax: lineTax(l), trackingType: l.product.trackingType, selectedSerialImeiIds: l.unitIds, selectedSerialsOrImeis: l.unitLabels,
    }))
    const id = await run(tx => createSale(tx, {
      customerId: customer?.id ?? 0, customerName: customer?.name ?? 'Walk-in Customer', customerPhone: customer?.phone ?? '',
      saleDate: Date.now(), subTotal: subtotal, itemDiscount: itemDiscounts, invoiceDiscount, discountAmount: itemDiscounts + invoiceDiscount,
      taxAmount: totalTax, totalAmount: grandTotal, paidAmount: Math.min(paid, grandTotal), dueAmount: due, paymentMethod: method,
      notes: notes.trim(), salespersonName: currentActor(),
    }, items))
    if (id !== undefined) {
      setDone({ id, invoice: store.current().get('sales', id)?.invoiceNumber ?? '' })
      clear()
      setCustomer(null)
      setShowCart(false)
    }
  }

  const customerMatches = customerQuery.trim()
    ? state.list('customers').filter(c => c.isActive && matches(customerQuery, c.name, c.phone, c.customerCode)).slice(0, 8)
    : []
  const editLine = cart.find(l => l.key === editing)
  const unitsLine = cart.find(l => l.key === unitsFor)

  if (done) {
    const sale = state.get('sales', done.id)
    return (
      <div className="page" style={{ maxWidth: 560 }}>
        <div className="card pad stack" style={{ textAlign: 'center' }} data-testid="sale-done">
          <div style={{ fontSize: 44 }}>✅</div>
          <h2 style={{ margin: 0 }}>Sale completed</h2>
          <div className="muted">Invoice <b data-testid="done-invoice">{sale?.invoiceNumber ?? done.invoice}</b></div>
          {sale && <div className="big num" style={{ fontSize: 28, fontWeight: 800 }}>{money.format(sale.totalAmount)}</div>}
          {sale && sale.dueAmount > 0.005 && <div className="pill red" style={{ alignSelf: 'center' }}>Due {money.format(sale.dueAmount)}</div>}
          <div className="row wrap" style={{ justifyContent: 'center' }}>
            <button className="btn tonal" onClick={() => navigate(`/print/invoice/${done.id}?f=80`)} data-testid="print-80"><Icon name="printer" size={18} />80 mm receipt</button>
            <button className="btn tonal" onClick={() => navigate(`/print/invoice/${done.id}?f=a4`)} data-testid="print-a4"><Icon name="printer" size={18} />A4 invoice</button>
            <button className="btn outline" onClick={() => navigate(`/sales/${done.id}`)}>View sale</button>
          </div>
          <button className="btn big" onClick={() => setDone(null)} data-testid="next-sale">New sale</button>
        </div>
      </div>
    )
  }

  return (
    <div className="page" style={{ maxWidth: 1400 }}>
      <PageHead title="Point of sale" sub={`${count} item${count === 1 ? '' : 's'} in cart`}>
        {cart.length > 0 && <button className="btn text" onClick={clear}><Icon name="trash" size={18} />Clear</button>}
      </PageHead>
      <div className="pos">
        <div className={`stack ${showCart ? 'hide-mobile' : ''}`} ref={searchRef}>
          <div className="scan">
            <SearchBox value={query} onChange={setQuery} onEnter={onEnter} autoFocus placeholder="Search or scan barcode / IMEI, then Enter" testId="pos-search" />
          </div>
          {notice && <div className="notice small" data-testid="pos-notice">{notice}</div>}
          {categories.length > 0 && <Chips options={[['ALL', 'All'], ...categories.map(c => [c, c] as [string, string])]} value={category} onChange={setCategory} />}
          {shown.length === 0 ? <Empty emoji="📦" title="No products" body="Add products on the Products page or on the phone." /> : (
            <div className="pos-products">
              {shown.map(p => {
                const variants = variantsOf(p.id)
                return (
                  <button key={p.id} className="pos-product" onClick={() => requestAdd(p)} disabled={p.currentStock <= 0} data-testid="pos-product">
                    <span className="n">{p.name}</span>
                    <span className="tiny muted">{[p.brandName, variants.length ? `${variants.length} variants` : '', TrackingType.isSerialized(p.trackingType) ? p.trackingType.replace(/_/g, ' ') : ''].filter(Boolean).join(' · ')}</span>
                    <span className="row" style={{ justifyContent: 'space-between' }}>
                      <span className="p num">{money.format(p.retailPrice)}</span>
                      <span className={`pill ${p.currentStock <= 0 ? 'red' : p.currentStock <= p.minStock ? 'amber' : ''}`}>{p.currentStock}</span>
                    </span>
                  </button>
                )
              })}
            </div>
          )}
        </div>

        <div className={`card pos-cart ${showCart ? '' : 'hidden-mobile'}`}>
          <div className="card-head">
            <h2>Cart</h2>
            <button className="btn text small hide-desktop" onClick={() => setShowCart(false)} style={{ display: showCart ? undefined : 'none' }}>Add items</button>
          </div>
          <div className="lines">
            {cart.length === 0 && <div className="muted small" style={{ padding: '6px 16px 14px' }}>Tap a product or scan its barcode.</div>}
            {cart.map(l => (
              <div key={l.key} className="cart-line" data-testid="cart-line">
                <div className="row">
                  <div className="grow">
                    <div className="name">{label(l.product, l.variant)}</div>
                    <div className="tiny muted">{money.format(l.unitPrice)} each{l.discount > 0 ? ` · −${money.format(l.discount)}` : ''}{l.taxPercent > 0 ? ` · tax ${l.taxPercent}%` : ''}</div>
                  </div>
                  <b className="num">{money.format(lineTotal(l))}</b>
                </div>
                <div className="row" style={{ marginTop: 6 }}>
                  <div className="qty">
                    <button onClick={() => setQuantity(l.key, l.quantity - 1)} aria-label="Less">−</button>
                    <span>{l.quantity}</span>
                    <button onClick={() => setQuantity(l.key, l.quantity + 1)} aria-label="More" data-testid="qty-plus">+</button>
                  </div>
                  {serialized(l.product) && (
                    <button className={`btn small ${l.unitIds.length === l.quantity ? 'tonal' : ''}`} onClick={() => setUnitsFor(l.key)} data-testid="pick-units">
                      {TrackingType.requiresImei(l.product.trackingType) ? 'IMEI' : 'Serial'} {l.unitIds.length}/{l.quantity}
                    </button>
                  )}
                  <button className="btn text small" onClick={() => setEditing(l.key)} data-testid="edit-price">Price</button>
                  <button className="icon-btn right" onClick={() => setCart(c => c.filter(x => x.key !== l.key))} aria-label="Remove"><Icon name="trash" size={18} /></button>
                </div>
                {l.unitLabels.length > 0 && <div className="tiny muted mono" style={{ marginTop: 4 }}>{l.unitLabels.join(', ')}</div>}
              </div>
            ))}
          </div>
          <div style={{ padding: '12px 16px', borderTop: '1px solid var(--border)' }} className="stack">
            <Field label="Customer">
              {customer ? (
                <div className="row">
                  <div className="grow"><b>{customer.name}</b> <span className="muted small">{customer.phone}{customer.currentDue > 0.005 ? ` · owes ${money.format(state.get('customers', customer.id)?.currentDue ?? 0)}` : ''}</span></div>
                  <button className="btn text small" onClick={() => setCustomer(null)}>Walk-in</button>
                </div>
              ) : (
                <div style={{ position: 'relative' }}>
                  <div className="row">
                    <input className="input" placeholder="Walk-in customer — type a name or phone" value={customerQuery} onChange={e => setCustomerQuery(e.target.value)} data-testid="pos-customer" />
                    <button className="btn tonal small" onClick={() => setNewCustomer(true)}><Icon name="plus" size={16} />New</button>
                  </div>
                  {customerMatches.length > 0 && (
                    <div className="card list" style={{ position: 'absolute', left: 0, right: 0, zIndex: 5, marginTop: 4 }}>
                      {customerMatches.map(c => (
                        <button key={c.id} className="item" onClick={() => { setCustomer(c); setCustomerQuery('') }} data-testid="customer-option">
                          <div className="grow"><div className="title">{c.name}</div><div className="subtitle">{c.phone} · {c.customerCode}</div></div>
                          {c.currentDue > 0.005 && <span className="pill red">{money.format(c.currentDue)}</span>}
                        </button>
                      ))}
                    </div>
                  )}
                </div>
              )}
            </Field>
            <div className="k-v"><span className="k">Subtotal</span><span className="num">{money.format(subtotal)}</span></div>
            {itemDiscounts > 0 && <div className="k-v"><span className="k">Item discounts</span><span className="num">−{money.format(itemDiscounts)}</span></div>}
            {totalTax > 0.004 && <div className="k-v"><span className="k">Tax</span><span className="num">{money.format(totalTax)}</span></div>}
            <div className="pos-row">
              <Field label="Invoice discount"><MoneyInput value={invoiceDiscountInput} onChange={setInvoiceDiscountInput} testId="pos-discount" /></Field>
              <Field label="Payment method">
                <select className="input" value={method} onChange={e => setMethod(e.target.value)} data-testid="pos-method">
                  {PAYMENT_METHODS.map(m => <option key={m} value={m}>{methodLabel(m)}</option>)}
                </select>
              </Field>
            </div>
            <div className="k-v total"><span>Total</span><span className="num" data-testid="pos-total">{money.format(grandTotal)}</span></div>
            <Field label="Paid now">
              <div className="row">
                <div className="grow"><MoneyInput value={paidInput} onChange={setPaidInput} testId="pos-paid" /></div>
                <button className="btn tonal small" onClick={() => setPaidInput(toInput(grandTotal))} data-testid="pos-exact">Exact</button>
              </div>
            </Field>
            {due > 0.004 && <div className="k-v"><span className="k">Due</span><b className="num danger">{money.format(due)}</b></div>}
            {change > 0.004 && <div className="k-v"><span className="k">Change</span><b className="num success">{money.format(change)}</b></div>}
            <Field label="Notes"><input className="input" value={notes} onChange={e => setNotes(e.target.value)} /></Field>
            <ErrorText error={error} />
            <button className="btn big" disabled={busy || cart.length === 0} onClick={complete} data-testid="complete-sale">
              {busy ? 'Saving…' : `Complete sale · ${money.format(grandTotal)}`}
            </button>
          </div>
        </div>
      </div>

      {cart.length > 0 && !showCart && (
        <button className="cart-bar" onClick={() => setShowCart(true)} data-testid="cart-bar">
          <Icon name="pos" size={20} /><span className="grow" style={{ textAlign: 'left' }}>{count} item{count === 1 ? '' : 's'}</span>
          <span className="num">{money.format(grandTotal)}</span>
        </button>
      )}

      {variantFor && (
        <Modal onClose={() => setVariantFor(null)} narrow label="Choose variant">
          <h2>{variantFor.name}</h2>
          <div className="card list">
            {variantsOf(variantFor.id).map(v => (
              <button key={v.id} className="item" disabled={v.currentStock <= 0} onClick={() => { addToCart(variantFor, v); setVariantFor(null) }} data-testid="variant-option">
                <div className="grow"><div className="title">{v.variantName}</div><div className="subtitle">{v.currentStock} in stock</div></div>
                <b className="num">{money.format(v.retailPrice > 0 ? v.retailPrice : variantFor.retailPrice)}</b>
              </button>
            ))}
            {unassignedStock(state, state.get('products', variantFor.id) ?? variantFor) > 0 && (
              <button className="item" onClick={() => { addToCart(variantFor, null); setVariantFor(null) }}>
                <div className="grow"><div className="title">No variant</div><div className="subtitle">{unassignedStock(state, state.get('products', variantFor.id) ?? variantFor)} bought before variants</div></div>
              </button>
            )}
          </div>
          <div className="actions"><button className="btn text" onClick={() => setVariantFor(null)}>Cancel</button></div>
        </Modal>
      )}

      {unitsLine && (
        <UnitPicker line={unitsLine} hasVariants={variantsOf(unitsLine.product.id).length > 0}
          onChange={(ids, labels) => setCart(c => c.map(l => (l.key === unitsLine.key ? { ...l, unitIds: ids, unitLabels: labels, quantity: Math.max(l.quantity, ids.length) } : l)))}
          onClose={() => setUnitsFor(null)} />
      )}

      {editLine && (
        <PriceDialog line={editLine} onClose={() => setEditing(null)}
          onApply={(unitPrice, discount) => setCart(c => c.map(l => (l.key === editLine.key ? { ...l, unitPrice, discount } : l)))} />
      )}

      {newCustomer && <CustomerDialog onClose={() => setNewCustomer(false)} onSaved={id => { const c = store.current().get('customers', id); if (c) setCustomer(c) }} />}
    </div>
  )
}

function UnitPicker({ line, hasVariants, onChange, onClose }: {
  line: CartLine
  hasVariants: boolean
  onChange: (ids: number[], labels: string[]) => void
  onClose: () => void
}) {
  const { state } = useShop()
  const [filter, setFilter] = useState('')
  // Only units of the chosen variant (or units without one, for a "no variant" line)
  const units = state.where('product_serial_imei', 'productId', line.product.id)
    .filter(u => u.status === 'IN_STOCK' && (!hasVariants || u.variantId === (line.variant?.id ?? 0)))
    .filter(u => matches(filter, u.imei1, u.imei2, u.serialNumber))
    .sort((a, b) => a.id - b.id)
  const toggle = (u: SerialUnit) => {
    const i = line.unitIds.indexOf(u.id)
    if (i >= 0) onChange(line.unitIds.filter(x => x !== u.id), line.unitLabels.filter((_, j) => j !== i))
    else onChange([...line.unitIds, u.id], [...line.unitLabels, unitLabel(u)])
  }
  return (
    <Modal onClose={onClose} narrow label="Pick units">
      <h2>{line.product.name}</h2>
      <p className="muted small" style={{ marginTop: -6 }}>Tick the unit(s) being sold: {line.unitIds.length} picked. Picking more raises the quantity.</p>
      <SearchBox value={filter} onChange={setFilter} placeholder="Type or scan an IMEI / serial" autoFocus />
      <div className="unit-list" style={{ marginTop: 10 }}>
        {units.length === 0 && <div className="muted small" style={{ padding: 12 }}>No units in stock{hasVariants ? ' for this variant' : ''}.</div>}
        {units.map(u => (
          <label key={u.id}>
            <input type="checkbox" checked={line.unitIds.includes(u.id)} onChange={() => toggle(u)} data-testid="unit-option" />
            <span className="grow mono">{unitLabel(u)}{u.imei2 ? ` / ${u.imei2}` : ''}{u.serialNumber && u.imei1 ? ` · S/N ${u.serialNumber}` : ''}</span>
          </label>
        ))}
      </div>
      <div className="actions"><button className="btn" onClick={onClose} data-testid="units-done">Done</button></div>
    </Modal>
  )
}

function PriceDialog({ line, onApply, onClose }: { line: CartLine; onApply: (price: number, discount: number) => void; onClose: () => void }) {
  const money = useMoney()
  const [price, setPrice] = useState(amountText(line.unitPrice))
  const [discount, setDiscount] = useState(amountText(line.discount))
  const [error, setError] = useState<string | null>(null)
  const apply = () => {
    const unitPrice = amountOf(price)
    const d = amountOf(discount)
    if (unitPrice <= 0) return setError('Price must be more than zero')
    if (d < 0) return setError('Discount cannot be negative')
    const g = unitPrice * line.quantity
    if (d > g) return setError('Discount cannot be more than the line total')
    const min = line.variant && line.variant.minSellingPrice > 0 ? line.variant.minSellingPrice : line.product.minSellingPrice
    if (min > 0 && (g - d) / line.quantity + 0.005 < min) return setError(`Below the minimum selling price (${money.format(min)} each)`)
    onApply(unitPrice, d)
    onClose()
  }
  return (
    <Modal onClose={onClose} narrow sheet={false} label="Price">
      <h2>Price and discount</h2>
      <div className="stack">
        <Field label="Unit price"><MoneyInput value={price} onChange={setPrice} autoFocus testId="line-price" /></Field>
        <Field label={`Discount on this line (${line.quantity} pcs)`}><MoneyInput value={discount} onChange={setDiscount} testId="line-discount" /></Field>
      </div>
      <ErrorText error={error} />
      <div className="actions">
        <button className="btn text" onClick={onClose}>Cancel</button>
        <button className="btn" onClick={apply} data-testid="line-apply">Apply</button>
      </div>
    </Modal>
  )
}
