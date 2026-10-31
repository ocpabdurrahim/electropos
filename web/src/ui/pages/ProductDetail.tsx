import { useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import type { SerialUnit, Variant } from '../../data/schema'
import { useShop } from '../../data/store'
import { byTime } from '../../domain/caches'
import { STOCK_MOVEMENT_LABELS, TrackingType } from '../../domain/common'
import { formatDate, formatDateTime } from '../../domain/dates'
import { deactivateVariant, deleteProductIfUnused, saveSerialUnit, unassignedStock } from '../../domain/products'
import { ConfirmDialog, Empty, ErrorText, Field, Modal, PageHead, StatusPill, Tabs, Tile, matches, useAction, useCan, useMoney, useToast } from '../common'
import { AdjustStockDialog, ProductDialog, VariantDialog } from '../dialogs'
import { Icon } from '../icons'

type Tab = 'VARIANTS' | 'UNITS' | 'MOVEMENTS' | 'PRICES'

export default function ProductDetail() {
  const { id } = useParams()
  const { state } = useShop()
  const money = useMoney()
  const navigate = useNavigate()
  const allowed = useCan()
  const toast = useToast()
  const costs = allowed('VIEW_COSTS_AND_PROFIT')
  const edit = allowed('PRODUCTS_EDIT')
  const [tab, setTab] = useState<Tab>('VARIANTS')
  const [editing, setEditing] = useState(false)
  const [adjusting, setAdjusting] = useState(false)
  const [variant, setVariant] = useState<Variant | 'new' | null>(null)
  const [unit, setUnit] = useState<SerialUnit | null>(null)
  const [deleting, setDeleting] = useState(false)
  const [unitQuery, setUnitQuery] = useState('')
  const { run, error } = useAction()
  const p = state.get('products', Number(id))
  if (!p) return <div className="page"><Empty emoji="🔎" title="Product not found" /></div>
  const variants = state.where('product_variants', 'productId', p.id).sort((a, b) => a.id - b.id)
  const units = state.where('product_serial_imei', 'productId', p.id).sort((a, b) => b.id - a.id)
  const movements = [...state.where('stock_transactions', 'productId', p.id)].sort(byTime).reverse()
  const prices = [...state.where('product_price_history', 'productId', p.id)].sort((a, b) => b.changedAt - a.changedAt)
  const serialized = TrackingType.isSerialized(p.trackingType)
  const tabs: [Tab, string][] = [['VARIANTS', `Variants (${variants.filter(v => v.isActive).length})`]]
  if (serialized || units.length) tabs.push(['UNITS', `Units (${units.filter(u => u.status === 'IN_STOCK').length} in stock)`])
  tabs.push(['MOVEMENTS', 'Stock movements'])
  if (costs) tabs.push(['PRICES', 'Price history'])

  const remove = async () => {
    const msg = await run(tx => deleteProductIfUnused(tx, p.id))
    if (msg === null) {
      toast('Product deleted')
      navigate('/products')
    } else if (msg) toast(msg)
  }

  return (
    <div className="page">
      <PageHead title={p.name} sub={[p.brandName, p.categoryName, p.model, p.sku, p.barcode].filter(Boolean).join(' · ')} back={() => navigate(-1)}>
        {edit && <button className="btn tonal" onClick={() => setEditing(true)}><Icon name="edit" size={18} />Edit</button>}
        {edit && <button className="btn tonal" onClick={() => setAdjusting(true)} data-testid="adjust-stock"><Icon name="layers" size={18} />Adjust stock</button>}
      </PageHead>
      {!p.isActive && <div className="notice warn" style={{ marginBottom: 12 }}>This product is inactive (hidden from the POS).</div>}
      <div className="tiles" style={{ marginBottom: 14 }}>
        <Tile label="In stock" value={`${p.currentStock} ${p.unit}`} className={p.currentStock <= 0 ? 'danger' : p.currentStock <= p.minStock ? 'warning' : ''} sub={`alert at ${p.minStock}`} />
        <Tile label="Selling price" value={money.format(p.retailPrice)} sub={p.minSellingPrice > 0 ? `min ${money.format(p.minSellingPrice)}` : undefined} />
        {costs && <Tile label="Average cost" value={money.format(p.purchasePrice)} sub={`stock value ${money.format(p.purchasePrice * p.currentStock)}`} />}
        {p.warrantyPeriodMonths > 0 && <Tile label="Warranty" value={`${p.warrantyPeriod || p.warrantyPeriodMonths} ${(p.warrantyPeriod ? p.warrantyUnit : 'MONTHS').toLowerCase()}`} sub={p.warrantyType.toLowerCase()} />}
      </div>
      <Tabs options={tabs} value={tab} onChange={setTab} />
      {tab === 'VARIANTS' && (
        <div className="card list">
          {variants.length === 0 && <Empty emoji="🎨" title="No variants" body="Add variants such as 8/128 Black or 8/256 Blue to keep their stock and prices apart." />}
          {variants.map(v => (
            <div key={v.id} className="item">
              <div className="grow">
                <div className="title">{v.variantName}{!v.isActive && <span className="pill" style={{ marginLeft: 6 }}>removed</span>}</div>
                <div className="subtitle">{[v.sku, v.barcode].filter(Boolean).join(' · ') || '—'}</div>
              </div>
              <div className="end">
                <div className="num bold">{money.format(v.retailPrice > 0 ? v.retailPrice : p.retailPrice)}</div>
                {costs && <div className="tiny muted">cost {money.format(v.purchasePrice)}</div>}
                <span className="pill">{v.currentStock}</span>
              </div>
              {edit && <button className="icon-btn" onClick={() => setVariant(v)} aria-label="Edit"><Icon name="edit" size={18} /></button>}
              {edit && v.isActive && <button className="icon-btn" onClick={() => void run(tx => deactivateVariant(tx, v.id), 'Variant removed')} aria-label="Remove"><Icon name="trash" size={18} /></button>}
            </div>
          ))}
          {variants.length > 0 && unassignedStock(state, p) > 0 && <div className="item muted small">No variant: {unassignedStock(state, p)} (bought before variants)</div>}
          {edit && <div style={{ padding: 12 }}><button className="btn tonal small" onClick={() => setVariant('new')} data-testid="add-variant"><Icon name="plus" size={16} />Variant</button></div>}
        </div>
      )}
      {tab === 'UNITS' && (
        <div className="card">
          <div style={{ padding: 12 }}><input className="input" placeholder="Find an IMEI or serial" value={unitQuery} onChange={e => setUnitQuery(e.target.value)} /></div>
          <div className="list">
            {units.filter(u => matches(unitQuery, u.imei1, u.imei2, u.serialNumber)).map(u => (
              <div key={u.id} className="item">
                <div className="grow">
                  <div className="title mono">{[u.imei1, u.imei2, u.serialNumber ? `S/N ${u.serialNumber}` : ''].filter(Boolean).join(' / ')}</div>
                  <div className="subtitle">{u.invoiceNumber}{u.variantId ? ` · ${state.get('product_variants', u.variantId)?.variantName ?? ''}` : ''}{u.notes ? ` · ${u.notes}` : ''}</div>
                </div>
                {costs && <span className="tiny muted num">{money.format(u.purchasePrice)}</span>}
                <StatusPill status={u.status} />
                {edit && <button className="icon-btn" onClick={() => setUnit(u)} aria-label="Edit"><Icon name="edit" size={18} /></button>}
              </div>
            ))}
          </div>
        </div>
      )}
      {tab === 'MOVEMENTS' && (
        <div className="card table-wrap">
          <table className="table">
            <thead><tr><th>Date</th><th>Movement</th><th className="r">Qty</th><th className="r">After</th>{costs && <th className="r">Unit cost</th>}</tr></thead>
            <tbody>
              {movements.map(m => (
                <tr key={m.id}>
                  <td className="small">{formatDateTime(m.createdAt)}</td>
                  <td><div>{STOCK_MOVEMENT_LABELS[m.transactionType] ?? m.transactionType}</div><div className="tiny muted">{m.notes}</div></td>
                  <td className={`r num ${m.quantityChange < 0 ? 'danger' : 'success'}`}>{m.quantityChange > 0 ? '+' : ''}{m.quantityChange}</td>
                  <td className="r num">{m.newStock}</td>
                  {costs && <td className="r num">{money.plain(m.unitCost)}</td>}
                </tr>
              ))}
            </tbody>
          </table>
          {movements.length === 0 && <Empty emoji="📈" title="No stock movements" />}
        </div>
      )}
      {tab === 'PRICES' && (
        <div className="card list">
          {prices.map(h => (
            <div key={h.id} className="item">
              <div className="grow"><div className="title">{h.note}</div><div className="subtitle">{formatDate(h.changedAt)} · {h.changedBy}</div></div>
              <div className="end small num">cost {money.plain(h.purchasePrice)}<br />price {money.plain(h.retailPrice)}</div>
            </div>
          ))}
        </div>
      )}
      {edit && <div style={{ marginTop: 16 }}><button className="btn danger" onClick={() => setDeleting(true)}><Icon name="trash" size={18} />Delete product</button></div>}
      <ErrorText error={error} />
      {editing && <ProductDialog product={p} onClose={() => setEditing(false)} />}
      {adjusting && <AdjustStockDialog product={p} onClose={() => setAdjusting(false)} />}
      {variant && <VariantDialog productId={p.id} variant={variant === 'new' ? undefined : variant} onClose={() => setVariant(null)} />}
      {unit && <UnitDialog unit={unit} onClose={() => setUnit(null)} />}
      {deleting && (
        <ConfirmDialog title="Delete this product?" danger confirm="Delete"
          text="A product with sales, purchases or stock movements is deactivated instead, so its history stays." onConfirm={() => void remove()} onClose={() => setDeleting(false)} />
      )}
    </div>
  )
}

function UnitDialog({ unit, onClose }: { unit: SerialUnit; onClose: () => void }) {
  const [f, setF] = useState({ imei1: unit.imei1 ?? '', imei2: unit.imei2 ?? '', serialNumber: unit.serialNumber ?? '', notes: unit.notes })
  const { run, busy, error } = useAction()
  const save = async () => {
    const ok = await run(tx => saveSerialUnit(tx, { ...unit, ...f }), 'Unit saved')
    if (ok !== undefined) onClose()
  }
  return (
    <Modal onClose={onClose} narrow label="Unit">
      <h2>Edit unit</h2>
      <div className="stack">
        <Field label="IMEI 1"><input className="input mono" value={f.imei1} onChange={e => setF({ ...f, imei1: e.target.value })} /></Field>
        <Field label="IMEI 2"><input className="input mono" value={f.imei2} onChange={e => setF({ ...f, imei2: e.target.value })} /></Field>
        <Field label="Serial number"><input className="input mono" value={f.serialNumber} onChange={e => setF({ ...f, serialNumber: e.target.value })} /></Field>
        <Field label="Notes"><input className="input" value={f.notes} onChange={e => setF({ ...f, notes: e.target.value })} /></Field>
      </div>
      <ErrorText error={error} />
      <div className="actions"><button className="btn text" onClick={onClose}>Cancel</button><button className="btn" disabled={busy} onClick={save}>Save</button></div>
    </Modal>
  )
}
