import { useMemo, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import type { Purchase } from '../../data/schema'
import { useShop } from '../../data/store'
import { parseSerials, unitLabel } from '../../domain/common'
import { formatDateTime } from '../../domain/dates'
import { methodLabel, roundMoney } from '../../domain/money'
import { createPurchaseReturn, returnableItems, type PurchaseReturnItemDraft } from '../../domain/purchaseReturns'
import { cancelPurchase } from '../../domain/purchases'
import { amountOf, ConfirmDialog, Empty, ErrorText, Field, Modal, MoneyInput, PageHead, StatusPill, useAction, useMoney } from '../common'
import { SupplierPaymentDialog } from '../dialogs'
import { Icon } from '../icons'

export default function PurchaseDetail() {
  const { id } = useParams()
  const { state } = useShop()
  const money = useMoney()
  const navigate = useNavigate()
  const [returning, setReturning] = useState(false)
  const [cancelling, setCancelling] = useState(false)
  const [paying, setPaying] = useState(false)
  const [reason, setReason] = useState('')
  const { run, error } = useAction()
  const p = state.get('purchases', Number(id))
  if (!p) return <div className="page"><Empty emoji="🔎" title="Purchase not found" /></div>
  const items = state.where('purchase_items', 'purchaseId', p.id).sort((a, b) => a.id - b.id)
  const returns = state.where('purchase_returns', 'purchaseId', p.id).sort((a, b) => a.returnDate - b.returnDate)
  const payments = state.where('supplier_payments', 'supplierId', p.supplierId).filter(x => x.purchaseId === p.id)
  const cancelled = p.status === 'CANCELLED'
  return (
    <div className="page">
      <PageHead title={p.purchaseNumber} sub={`${p.supplierName} · ${formatDateTime(p.purchaseDate)}`} back={() => navigate(-1)} />
      {cancelled && <div className="notice err" style={{ marginBottom: 14 }}>This purchase was cancelled.</div>}
      <div className="grid-2">
        <div className="stack">
          <div className="card list">
            <div className="card-head"><h2>Items</h2></div>
            {items.map(i => {
              const serials = parseSerials(i.serialImeisJson)
              return (
                <div key={i.id} className="item">
                  <div className="grow">
                    <div className="title">{i.productName}</div>
                    <div className="subtitle">{i.quantity} × {money.format(i.unitCost)}{i.discount > 0 ? ` − ${money.format(i.discount)}` : ''}{i.tax > 0 ? ` + tax ${money.format(i.tax)}` : ''}{i.allocatedLandedCost > 0 ? ` · landed ${money.format(i.allocatedLandedCost)}` : ''}</div>
                    {serials.length > 0 && <div className="tiny muted mono">{serials.join(', ')}</div>}
                  </div>
                  <b className="num">{money.format(i.lineTotal)}</b>
                </div>
              )
            })}
          </div>
          {returns.length > 0 && (
            <div className="card list">
              <div className="card-head"><h2>Returns</h2></div>
              {returns.map(r => (
                <div key={r.id} className="item">
                  <div className="grow"><div className="title">{r.returnNumber}</div>
                    <div className="subtitle">{formatDateTime(r.returnDate)}{r.reason ? ` · ${r.reason}` : ''}</div>
                    <div className="tiny muted">{state.where('purchase_return_items', 'returnId', r.id).map(x => `${x.quantity} × ${x.productName}${x.serialOrImei ? ` (${x.serialOrImei})` : ''}`).join('; ')}</div></div>
                  <div className="end"><b className="num">−{money.format(r.totalAmount)}</b><div className="tiny muted">refund {money.format(r.refundAmount)}</div></div>
                </div>
              ))}
            </div>
          )}
        </div>
        <div className="stack">
          <div className="card pad">
            <div className="row" style={{ marginBottom: 8 }}><h2 className="grow" style={{ margin: 0 }}>Bill</h2>{cancelled ? <StatusPill status="CANCELLED" /> : <StatusPill status={p.paymentStatus} />}</div>
            <div className="k-v"><span className="k">Supplier</span><button className="btn text small" style={{ padding: 0 }} onClick={() => navigate(`/suppliers/${p.supplierId}`)}>{p.supplierName}</button></div>
            {p.supplierInvoiceNumber && <div className="k-v"><span className="k">Supplier invoice</span><span>{p.supplierInvoiceNumber}</span></div>}
            <div className="k-v"><span className="k">Items</span><span className="num">{money.format(p.subTotal)}</span></div>
            {p.invoiceDiscount > 0 && <div className="k-v"><span className="k">Invoice discount</span><span className="num">−{money.format(p.invoiceDiscount)}</span></div>}
            {p.taxAmount > 0 && <div className="k-v"><span className="k">Tax</span><span className="num">{money.format(p.taxAmount)}</span></div>}
            {p.transportCost > 0 && <div className="k-v"><span className="k">Transport</span><span className="num">{money.format(p.transportCost)}</span></div>}
            {p.otherCost > 0 && <div className="k-v"><span className="k">Other costs</span><span className="num">{money.format(p.otherCost)}</span></div>}
            <div className="k-v total"><span>Total</span><span className="num">{money.format(p.totalAmount)}</span></div>
            <div className="k-v"><span className="k">Paid</span><span className="num">{money.format(p.paidAmount)}</span></div>
            <div className="k-v"><span className="k">Due</span><b className={`num ${p.dueAmount > 0.005 ? 'danger' : ''}`}>{money.format(p.dueAmount)}</b></div>
            {payments.length > 0 && <div className="small muted" style={{ marginTop: 8 }}>{payments.map(x => `${x.paymentNumber} ${money.format(x.amount)} (${methodLabel(x.paymentMethod)})`).join(', ')}</div>}
            {p.notes && <p className="small muted">Note: {p.notes}</p>}
          </div>
          {!cancelled && (
            <div className="card pad stack">
              {p.dueAmount > 0.005 && <button className="btn" onClick={() => setPaying(true)}><Icon name="cash" size={18} />Pay supplier</button>}
              <button className="btn tonal" onClick={() => setReturning(true)} data-testid="purchase-return"><Icon name="undo" size={18} />Return to supplier</button>
              {returns.filter(r => r.status !== 'CANCELLED').length === 0 && (
                <button className="btn danger" onClick={() => setCancelling(true)} data-testid="purchase-cancel"><Icon name="ban" size={18} />Cancel purchase</button>
              )}
              <ErrorText error={error} />
            </div>
          )}
        </div>
      </div>
      {returning && <PurchaseReturnDialog purchase={p} onClose={() => setReturning(false)} />}
      {paying && <SupplierPaymentDialog supplierId={p.supplierId} purchaseId={p.id} onClose={() => setPaying(false)} />}
      {cancelling && (
        <ConfirmDialog title="Cancel this purchase?" danger confirm="Cancel purchase"
          text="Its stock is taken out again, its IMEI units are marked cancelled and the supplier's ledger is reversed. Any down payment is recorded as refunded."
          onConfirm={() => void run(tx => { cancelPurchase(tx, p.id, reason.trim() || 'Cancelled'); return true }, 'Purchase cancelled')} onClose={() => setCancelling(false)}>
          <Field label="Reason"><input className="input" value={reason} onChange={e => setReason(e.target.value)} autoFocus /></Field>
        </ConfirmDialog>
      )}
    </div>
  )
}

function PurchaseReturnDialog({ purchase, onClose }: { purchase: Purchase; onClose: () => void }) {
  const { state } = useShop()
  const money = useMoney()
  const lines = useMemo(() => returnableItems(state, purchase.id), [state, purchase.id])
  const [qty, setQty] = useState<Record<number, number>>({})
  const [units, setUnits] = useState<Record<number, number[]>>({})
  const [refund, setRefund] = useState('')
  const [reason, setReason] = useState('')
  const { run, busy, error, setError } = useAction()
  const total = roundMoney(lines.reduce((a, l) => a + l.returnUnitValue * (l.isSerialized ? (units[l.purchaseItem.id]?.length ?? 0) : (qty[l.purchaseItem.id] ?? 0)), 0))
  const submit = async () => {
    const drafts: PurchaseReturnItemDraft[] = []
    for (const l of lines) {
      const base = { purchaseItemId: l.purchaseItem.id, productId: l.purchaseItem.productId, productName: l.purchaseItem.productName, variantId: l.purchaseItem.variantId }
      if (l.isSerialized) {
        for (const unitId of units[l.purchaseItem.id] ?? []) {
          const u = state.get('product_serial_imei', unitId)
          drafts.push({ ...base, returnQuantity: 1, serialUnitId: unitId, serialOrImei: u ? unitLabel(u) : '' })
        }
      } else if ((qty[l.purchaseItem.id] ?? 0) > 0) drafts.push({ ...base, returnQuantity: qty[l.purchaseItem.id] })
    }
    if (drafts.length === 0) { setError('At least one item must be selected for return.'); return }
    const id = await run(tx => createPurchaseReturn(tx, { purchaseId: purchase.id, returnDate: Date.now(), refundAmount: Math.min(amountOf(refund), total), reason: reason.trim() }, drafts), 'Return saved')
    if (id !== undefined) onClose()
  }
  return (
    <Modal onClose={onClose} wide label="Return to supplier">
      <h2>Return to {purchase.supplierName}</h2>
      <div className="stack">
        {lines.map(l => (
          <div key={l.purchaseItem.id} className="line-card">
            <div className="row">
              <div className="grow"><b>{l.purchaseItem.productName}</b>
                <div className="tiny muted">Bought {l.purchaseItem.quantity}, returned {l.alreadyReturnedQty} · {money.format(l.returnUnitValue)} credited each · {l.product.currentStock} in stock</div></div>
              {!l.isSerialized && (
                <div className="qty">
                  <button onClick={() => setQty({ ...qty, [l.purchaseItem.id]: Math.max((qty[l.purchaseItem.id] ?? 0) - 1, 0) })}>−</button>
                  <span>{qty[l.purchaseItem.id] ?? 0}</span>
                  <button onClick={() => setQty({ ...qty, [l.purchaseItem.id]: Math.min((qty[l.purchaseItem.id] ?? 0) + 1, l.availableReturnQty) })} data-testid="preturn-plus">+</button>
                </div>
              )}
            </div>
            {l.isSerialized && (
              <div className="unit-list">
                {l.serialUnits.length === 0 && <div className="muted small" style={{ padding: 10 }}>No unit of this line is in stock.</div>}
                {l.serialUnits.map(u => {
                  const picked = units[l.purchaseItem.id] ?? []
                  return (
                    <label key={u.id}>
                      <input type="checkbox" checked={picked.includes(u.id)} data-testid="preturn-unit"
                        onChange={() => setUnits({ ...units, [l.purchaseItem.id]: picked.includes(u.id) ? picked.filter(x => x !== u.id) : [...picked, u.id] })} />
                      <span className="mono">{unitLabel(u)}</span>
                    </label>
                  )
                })}
              </div>
            )}
          </div>
        ))}
        <div className="form-grid">
          <Field label="Cash refunded by the supplier" hint="The rest lowers what you owe them"><MoneyInput value={refund} onChange={setRefund} /></Field>
          <Field label="Reason"><input className="input" value={reason} onChange={e => setReason(e.target.value)} /></Field>
        </div>
        <div className="k-v total"><span>Return value</span><span className="num">{money.format(total)}</span></div>
      </div>
      <ErrorText error={error} />
      <div className="actions">
        <button className="btn text" onClick={onClose}>Cancel</button>
        <button className="btn" disabled={busy || total <= 0} onClick={submit} data-testid="preturn-save">{busy ? 'Saving…' : 'Save return'}</button>
      </div>
    </Modal>
  )
}
