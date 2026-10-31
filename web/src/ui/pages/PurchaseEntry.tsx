// Purchase entry with IMEI / serial rows (PurchaseRepository.createPurchase).
import { useState } from 'react'
import { useNavigate, useSearchParams } from 'react-router-dom'
import type { Product } from '../../data/schema'
import { store, useShop } from '../../data/store'
import { TrackingType } from '../../domain/common'
import { dateInput, fromDateInput, onDayWithTimeOf } from '../../domain/dates'
import { createPurchase, type PurchaseItemDraft } from '../../domain/purchases'
import { amountOf, amountText, ErrorText, Field, MethodSelect, MoneyInput, PageHead, matches, useAction, useMoney } from '../common'
import { ProductDialog, SupplierDialog } from '../dialogs'
import { Icon } from '../icons'

interface Line {
  key: number
  productId: number
  variantId: number
  quantity: string
  unitCost: string
  discount: string
  tax: string
  units: { serialNumber: string; imei1: string; imei2: string }[]
}

let seq = 0
const intOf = (s: string) => Math.max(0, Math.trunc(Number(s) || 0))

export default function PurchaseEntry() {
  const { state } = useShop()
  const money = useMoney()
  const navigate = useNavigate()
  const [params] = useSearchParams()
  const [supplierId, setSupplierId] = useState(params.get('supplier') ?? '')
  const [newSupplier, setNewSupplier] = useState(false)
  const [newProduct, setNewProduct] = useState(false)
  const [lines, setLines] = useState<Line[]>([])
  const [productQuery, setProductQuery] = useState('')
  const [date, setDate] = useState(dateInput(Date.now()))
  const [supplierInvoice, setSupplierInvoice] = useState('')
  const [reference, setReference] = useState('')
  const [invoiceDiscount, setInvoiceDiscount] = useState('')
  const [tax, setTax] = useState('')
  const [transport, setTransport] = useState('')
  const [other, setOther] = useState('')
  const [paid, setPaid] = useState('')
  const [method, setMethod] = useState('CASH')
  const [notes, setNotes] = useState('')
  const { run, busy, error, setError } = useAction()

  const suppliers = state.list('suppliers').filter(s => s.isActive).sort((a, b) => a.name.localeCompare(b.name))
  const products = state.list('products').filter(p => p.isActive)
  const found = productQuery.trim() ? products.filter(p => matches(productQuery, p.name, p.sku, p.barcode, p.model, p.brandName)).slice(0, 8) : []

  const addProduct = (p: Product) => {
    setLines(l => [...l, {
      key: ++seq, productId: p.id, variantId: 0, quantity: '1', unitCost: amountText(p.purchasePrice), discount: '', tax: '',
      units: TrackingType.isSerialized(p.trackingType) ? [{ serialNumber: '', imei1: '', imei2: '' }] : [],
    }])
    setProductQuery('')
  }
  const update = (key: number, patch: Partial<Line>) => setLines(ls => ls.map(l => {
    if (l.key !== key) return l
    const next = { ...l, ...patch }
    const product = state.get('products', next.productId)
    if (product && TrackingType.isSerialized(product.trackingType)) {
      const q = intOf(next.quantity)
      const units = [...next.units]
      while (units.length < q) units.push({ serialNumber: '', imei1: '', imei2: '' })
      next.units = units.slice(0, q)
    }
    return next
  }))
  const pasteUnits = (key: number, text: string) => {
    const codes = text.split(/[\s,;]+/).map(s => s.trim()).filter(Boolean)
    if (codes.length === 0) return
    const line = lines.find(l => l.key === key)!
    const product = state.get('products', line.productId)!
    const imei = TrackingType.requiresImei(product.trackingType)
    update(key, { quantity: String(codes.length), units: codes.map(c => (imei ? { serialNumber: '', imei1: c, imei2: '' } : { serialNumber: c, imei1: '', imei2: '' })) })
  }

  const lineTotal = (l: Line) => intOf(l.quantity) * amountOf(l.unitCost) - amountOf(l.discount) + amountOf(l.tax)
  const subtotal = lines.reduce((a, l) => a + lineTotal(l), 0)
  const grand = Math.max(0, subtotal - amountOf(invoiceDiscount) + amountOf(tax) + amountOf(transport) + amountOf(other))

  const save = async () => {
    if (!supplierId) { setError('Choose a supplier.'); return }
    const items: PurchaseItemDraft[] = lines.map(l => {
      const product = state.get('products', l.productId)!
      const variant = l.variantId ? state.get('product_variants', l.variantId) : undefined
      return {
        productId: product.id, productName: product.name, variantId: l.variantId, variantName: variant?.variantName ?? '',
        quantity: intOf(l.quantity), unitCost: amountOf(l.unitCost), discount: amountOf(l.discount), tax: amountOf(l.tax),
        trackingType: product.trackingType, serialsOrImeis: TrackingType.isSerialized(product.trackingType) ? l.units : [],
      }
    })
    const day = fromDateInput(date) ?? Date.now()
    const id = await run(tx => createPurchase(tx, {
      supplierId: Number(supplierId), supplierInvoiceNumber: supplierInvoice.trim(), referenceNumber: reference.trim(),
      purchaseDate: onDayWithTimeOf(day), paymentMethod: method, invoiceDiscount: amountOf(invoiceDiscount), taxAmount: amountOf(tax),
      transportCost: amountOf(transport), otherCost: amountOf(other), paidAmount: amountOf(paid), notes: notes.trim(),
    }, items), 'Purchase saved')
    if (id !== undefined) navigate(`/purchases/${id}`, { replace: true })
  }

  return (
    <div className="page">
      <PageHead title="New purchase" back={() => navigate(-1)} />
      <div className="grid-2">
        <div className="stack">
          <div className="card pad">
            <div className="form-grid">
              <Field label="Supplier *">
                <div className="row">
                  <select className="input" value={supplierId} onChange={e => setSupplierId(e.target.value)} data-testid="purchase-supplier">
                    <option value="">Choose…</option>
                    {suppliers.map(s => <option key={s.id} value={s.id}>{s.name}</option>)}
                  </select>
                  <button className="btn tonal small" onClick={() => setNewSupplier(true)}><Icon name="plus" size={16} /></button>
                </div>
              </Field>
              <Field label="Date"><input className="input" type="date" value={date} onChange={e => setDate(e.target.value)} /></Field>
              <Field label="Supplier's invoice no."><input className="input" value={supplierInvoice} onChange={e => setSupplierInvoice(e.target.value)} /></Field>
              <Field label="Reference"><input className="input" value={reference} onChange={e => setReference(e.target.value)} /></Field>
            </div>
          </div>
          <div className="card pad stack">
            <div className="row">
              <div className="grow" style={{ position: 'relative' }}>
                <input className="input" placeholder="Add a product: name, SKU or barcode" value={productQuery} onChange={e => setProductQuery(e.target.value)}
                  onKeyDown={e => { if (e.key === 'Enter' && found.length > 0) addProduct(found[0]) }} data-testid="purchase-product-search" />
                {found.length > 0 && (
                  <div className="card list" style={{ position: 'absolute', left: 0, right: 0, zIndex: 5, marginTop: 4 }}>
                    {found.map(p => (
                      <button key={p.id} className="item" onClick={() => addProduct(p)} data-testid="purchase-product-option">
                        <div className="grow"><div className="title">{p.name}</div><div className="subtitle">{p.trackingType.replace(/_/g, ' ').toLowerCase()} · {p.currentStock} in stock</div></div>
                      </button>
                    ))}
                  </div>
                )}
              </div>
              <button className="btn tonal small" onClick={() => setNewProduct(true)}><Icon name="plus" size={16} />New product</button>
            </div>
            {lines.length === 0 && <div className="muted small">No items yet.</div>}
            {lines.map(l => {
              const product = state.get('products', l.productId)!
              const variants = state.where('product_variants', 'productId', product.id).filter(v => v.isActive)
              const imei = TrackingType.requiresImei(product.trackingType)
              const serial = TrackingType.requiresSerial(product.trackingType)
              return (
                <div key={l.key} className="line-card" data-testid="purchase-line">
                  <div className="row">
                    <b className="grow">{product.name}</b>
                    <span className="num bold">{money.format(lineTotal(l))}</span>
                    <button className="icon-btn" onClick={() => setLines(ls => ls.filter(x => x.key !== l.key))} aria-label="Remove"><Icon name="trash" size={18} /></button>
                  </div>
                  {variants.length > 0 && (
                    <Field label="Variant">
                      <select className="input" value={l.variantId} onChange={e => {
                        const v = state.get('product_variants', Number(e.target.value))
                        update(l.key, { variantId: Number(e.target.value), unitCost: v && v.purchasePrice > 0 ? amountText(v.purchasePrice) : l.unitCost })
                      }}>
                        <option value={0}>No variant</option>
                        {variants.map(v => <option key={v.id} value={v.id}>{v.variantName}</option>)}
                      </select>
                    </Field>
                  )}
                  <div className="line-grid">
                    <Field label="Qty"><input className="input num" inputMode="numeric" value={l.quantity} onChange={e => update(l.key, { quantity: e.target.value })} data-testid="purchase-qty" /></Field>
                    <Field label="Unit cost"><input className="input num" inputMode="decimal" value={l.unitCost} onChange={e => update(l.key, { unitCost: e.target.value })} data-testid="purchase-cost" /></Field>
                    <Field label="Discount"><input className="input num" inputMode="decimal" value={l.discount} onChange={e => update(l.key, { discount: e.target.value })} /></Field>
                    <Field label="Tax"><input className="input num" inputMode="decimal" value={l.tax} onChange={e => update(l.key, { tax: e.target.value })} /></Field>
                  </div>
                  {TrackingType.isSerialized(product.trackingType) && (
                    <div className="stack" style={{ gap: 6 }}>
                      <div className="small muted">{imei ? 'IMEI' : 'Serial'} of each unit ({l.units.length}). Scan one per box, or paste a list:</div>
                      <textarea className="input mono" rows={2} placeholder="Paste IMEIs / serials, one per line" onBlur={e => { pasteUnits(l.key, e.target.value); e.target.value = '' }} data-testid="purchase-paste" />
                      <div className="imei-rows">
                        {l.units.map((u, i) => (
                          <div key={i} className="row">
                            <span className="tiny muted" style={{ width: 22 }}>{i + 1}</span>
                            {imei && <input className="input mono" placeholder="IMEI 1" value={u.imei1} data-testid="purchase-imei"
                              onChange={e => update(l.key, { units: l.units.map((x, j) => (j === i ? { ...x, imei1: e.target.value } : x)) })} />}
                            {imei && <input className="input mono" placeholder="IMEI 2 (optional)" value={u.imei2}
                              onChange={e => update(l.key, { units: l.units.map((x, j) => (j === i ? { ...x, imei2: e.target.value } : x)) })} />}
                            {serial && <input className="input mono" placeholder="Serial number" value={u.serialNumber}
                              onChange={e => update(l.key, { units: l.units.map((x, j) => (j === i ? { ...x, serialNumber: e.target.value } : x)) })} />}
                          </div>
                        ))}
                      </div>
                    </div>
                  )}
                </div>
              )
            })}
          </div>
        </div>
        <div className="card pad stack">
          <div className="k-v"><span className="k">Items</span><span className="num">{money.format(subtotal)}</span></div>
          <div className="form-grid">
            <Field label="Invoice discount"><MoneyInput value={invoiceDiscount} onChange={setInvoiceDiscount} /></Field>
            <Field label="Tax"><MoneyInput value={tax} onChange={setTax} /></Field>
            <Field label="Transport"><MoneyInput value={transport} onChange={setTransport} /></Field>
            <Field label="Other costs"><MoneyInput value={other} onChange={setOther} /></Field>
          </div>
          <div className="k-v total"><span>Total</span><span className="num" data-testid="purchase-total">{money.format(grand)}</span></div>
          <div className="form-grid">
            <Field label="Paid now">
              <div className="row"><div className="grow"><MoneyInput value={paid} onChange={setPaid} testId="purchase-paid" /></div>
                <button className="btn tonal small" onClick={() => setPaid(amountText(grand))}>All</button></div>
            </Field>
            <Field label="Method"><MethodSelect value={method} onChange={setMethod} /></Field>
          </div>
          {grand - amountOf(paid) > 0.004 && <div className="k-v"><span className="k">Owed to the supplier</span><b className="num danger">{money.format(Math.max(grand - amountOf(paid), 0))}</b></div>}
          <Field label="Notes"><input className="input" value={notes} onChange={e => setNotes(e.target.value)} /></Field>
          <ErrorText error={error} />
          <button className="btn big" disabled={busy || lines.length === 0} onClick={save} data-testid="purchase-save">{busy ? 'Saving…' : 'Save purchase'}</button>
        </div>
      </div>
      {newSupplier && <SupplierDialog onClose={() => setNewSupplier(false)} onSaved={id => setSupplierId(String(id))} />}
      {newProduct && <ProductDialog onClose={() => setNewProduct(false)} onSaved={id => { const p = store.current().get('products', id); if (p) addProduct(p) }} />}
    </div>
  )
}
