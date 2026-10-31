import { useMemo, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import type { Sale } from '../../data/schema'
import { useShop } from '../../data/store'
import { parseSerials, unitLabel } from '../../domain/common'
import { formatDate, formatDateTime } from '../../domain/dates'
import { methodLabel, roundMoney } from '../../domain/money'
import { cancelSale } from '../../domain/sales'
import { createSalesReturn, returnableLines, type SalesReturnItemDraft } from '../../domain/salesReturns'
import {
  amountOf, ConfirmDialog, Empty, ErrorText, Field, MethodSelect, Modal, MoneyInput, PageHead, StatusPill, useAction, useCan, useMoney,
} from '../common'
import { CustomerPaymentDialog } from '../dialogs'
import { Icon } from '../icons'

export default function SaleDetail() {
  const { id } = useParams()
  const { state } = useShop()
  const money = useMoney()
  const navigate = useNavigate()
  const allowed = useCan()
  const costs = allowed('VIEW_COSTS_AND_PROFIT')
  const [returning, setReturning] = useState(false)
  const [cancelling, setCancelling] = useState(false)
  const [reason, setReason] = useState('')
  const [paying, setPaying] = useState(false)
  const { run, error } = useAction()
  const sale = state.get('sales', Number(id))
  if (!sale) return <div className="page"><Empty emoji="🔎" title="Sale not found" body="It may still be on its way from another device." /></div>

  const items = state.where('sale_items', 'saleId', sale.id).sort((a, b) => a.id - b.id)
  const returns = state.where('sales_returns', 'saleId', sale.id).sort((a, b) => a.returnDate - b.returnDate)
  const completedReturns = returns.filter(r => r.status === 'COMPLETED')
  const payments = sale.customerId ? state.where('customer_payments', 'customerId', sale.customerId).filter(p => p.saleId === sale.id) : []
  const warranties = state.where('warranty_records', 'saleId', sale.id)
  const cancelled = sale.status === 'CANCELLED'
  const customer = sale.customerId ? state.get('customers', sale.customerId) : undefined

  return (
    <div className="page">
      <PageHead title={sale.invoiceNumber} sub={`${formatDateTime(sale.saleDate)} · ${sale.salespersonName}`} back={() => navigate(-1)}>
        <button className="btn tonal" onClick={() => navigate(`/print/invoice/${sale.id}?f=80`)}><Icon name="printer" size={18} />80 mm</button>
        <button className="btn tonal" onClick={() => navigate(`/print/invoice/${sale.id}?f=a4`)}><Icon name="printer" size={18} />A4</button>
      </PageHead>
      {cancelled && <div className="notice err" style={{ marginBottom: 14 }}>This sale was cancelled.</div>}
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
                    <div className="subtitle">{i.quantity} × {money.format(i.unitPrice)}{i.discount > 0 ? ` − ${money.format(i.discount)}` : ''}{i.tax > 0 ? ` + tax ${money.format(i.tax)}` : ''}</div>
                    {serials.length > 0 && <div className="tiny muted mono">{serials.join(', ')}</div>}
                    {costs && <div className="tiny muted">Cost {money.format(i.unitCostAtSale)} · profit {money.format(i.profit)}</div>}
                  </div>
                  <b className="num">{money.format(i.totalPrice)}</b>
                </div>
              )
            })}
          </div>
          {returns.length > 0 && (
            <div className="card list">
              <div className="card-head"><h2>Returns</h2></div>
              {returns.map(r => (
                <div key={r.id} className="item">
                  <div className="grow">
                    <div className="title">{r.returnNumber}</div>
                    <div className="subtitle">{formatDateTime(r.returnDate)}{r.reason ? ` · ${r.reason}` : ''}</div>
                    <div className="tiny muted">{state.where('sales_return_items', 'salesReturnId', r.id).map(x => `${x.quantity} × ${x.productName}${x.serialOrImei ? ` (${x.serialOrImei})` : ''}${x.restockOption === 'DEFECTIVE_DAMAGED' ? ' — damaged' : ''}`).join('; ')}</div>
                  </div>
                  <div className="end"><b className="num">−{money.format(r.totalAmount)}</b><div className="tiny muted">refund {money.format(r.refundAmount)}{r.customerCredit > 0 ? ` · credit ${money.format(r.customerCredit)}` : ''}</div></div>
                </div>
              ))}
            </div>
          )}
          {warranties.length > 0 && (
            <div className="card list">
              <div className="card-head"><h2>Warranty</h2></div>
              {warranties.map(w => (
                <div key={w.id} className="item">
                  <div className="grow"><div className="title">{w.productName}</div><div className="subtitle">{w.serialOrImei || 'No serial'} · until {formatDate(w.warrantyEndDate)}</div></div>
                  <StatusPill status={w.status} />
                </div>
              ))}
            </div>
          )}
        </div>
        <div className="stack">
          <div className="card pad">
            <div className="row" style={{ marginBottom: 8 }}>
              <h2 className="grow" style={{ margin: 0 }}>Bill</h2>
              {cancelled ? <StatusPill status="CANCELLED" /> : <StatusPill status={sale.paymentStatus} />}
            </div>
            <div className="k-v"><span className="k">Customer</span>
              {customer ? <button className="btn text small" style={{ padding: 0 }} onClick={() => navigate(`/customers/${customer.id}`)}>{sale.customerName}</button> : <span>{sale.customerName}</span>}
            </div>
            {sale.customerPhone && <div className="k-v"><span className="k">Phone</span><span>{sale.customerPhone}</span></div>}
            <div className="k-v"><span className="k">Payment</span><span>{methodLabel(sale.paymentMethod)}</span></div>
            <div className="k-v"><span className="k">Subtotal</span><span className="num">{money.format(sale.subTotal)}</span></div>
            {sale.itemDiscount > 0 && <div className="k-v"><span className="k">Item discounts</span><span className="num">−{money.format(sale.itemDiscount)}</span></div>}
            {sale.invoiceDiscount > 0 && <div className="k-v"><span className="k">Invoice discount</span><span className="num">−{money.format(sale.invoiceDiscount)}</span></div>}
            {sale.taxAmount > 0 && <div className="k-v"><span className="k">Tax</span><span className="num">{money.format(sale.taxAmount)}</span></div>}
            <div className="k-v total"><span>Total</span><span className="num">{money.format(sale.totalAmount)}</span></div>
            <div className="k-v"><span className="k">Paid</span><span className="num">{money.format(sale.paidAmount)}</span></div>
            <div className="k-v"><span className="k">Due</span><b className={`num ${sale.dueAmount > 0.005 ? 'danger' : ''}`} data-testid="sale-due">{money.format(sale.dueAmount)}</b></div>
            {completedReturns.length > 0 && <div className="k-v"><span className="k">Returned</span><span className="num">−{money.format(completedReturns.reduce((a, r) => a + r.totalAmount, 0))}</span></div>}
            {costs && <div className="k-v"><span className="k">Profit</span><span className="num">{money.format(sale.totalProfit)}</span></div>}
            {payments.length > 0 && <div className="small muted" style={{ marginTop: 8 }}>Payments for this invoice: {payments.map(p => `${p.paymentNumber} ${money.format(p.amount)}`).join(', ')}</div>}
            {sale.notes && <p className="small muted">Note: {sale.notes}</p>}
          </div>
          {!cancelled && (
            <div className="card pad stack">
              {customer && sale.dueAmount > 0.005 && allowed('CUSTOMERS') && (
                <button className="btn" onClick={() => setPaying(true)} data-testid="collect-due"><Icon name="cash" size={18} />Collect payment</button>
              )}
              {allowed('SALES_RETURN') && <button className="btn tonal" onClick={() => setReturning(true)} data-testid="start-return"><Icon name="undo" size={18} />Return items</button>}
              {allowed('CANCEL_SALE') && completedReturns.length === 0 && (
                <button className="btn danger" onClick={() => setCancelling(true)} data-testid="cancel-sale"><Icon name="ban" size={18} />Cancel sale</button>
              )}
              <ErrorText error={error} />
            </div>
          )}
        </div>
      </div>
      {returning && <ReturnDialog sale={sale} onClose={() => setReturning(false)} />}
      {paying && customer && <CustomerPaymentDialog customerId={customer.id} saleId={sale.id} onClose={() => setPaying(false)} />}
      {cancelling && (
        <ConfirmDialog title="Cancel this sale?" danger confirm="Cancel sale"
          text="Stock goes back, IMEI units become available again, the invoice is reversed on the customer's account and the money paid at the counter is recorded as handed back."
          onConfirm={() => void run(tx => cancelSale(tx, sale.id, reason.trim() || 'Cancelled'), 'Sale cancelled')} onClose={() => setCancelling(false)}>
          <Field label="Reason"><input className="input" value={reason} onChange={e => setReason(e.target.value)} autoFocus data-testid="cancel-reason" /></Field>
        </ConfirmDialog>
      )}
    </div>
  )
}

function ReturnDialog({ sale, onClose }: { sale: Sale; onClose: () => void }) {
  const { state } = useShop()
  const money = useMoney()
  const lines = useMemo(() => returnableLines(state, sale), [state, sale])
  const [qty, setQty] = useState<Record<number, number>>({})
  const [units, setUnits] = useState<Record<number, number[]>>({})
  const [restock, setRestock] = useState('RESTOCK_SELLABLE')
  const [refund, setRefund] = useState('')
  const [method, setMethod] = useState('CASH')
  const [reason, setReason] = useState('')
  const { run, busy, error, setError } = useAction()
  const walkIn = !(sale.customerId && sale.customerId > 0)
  const total = roundMoney(lines.reduce((a, l) => a + l.unitValue * (l.serialized ? (units[l.item.id]?.length ?? 0) : (qty[l.item.id] ?? 0)), 0))
  const refundValue = walkIn ? total : Math.min(Math.max(amountOf(refund), 0), total)

  const submit = async () => {
    const drafts: SalesReturnItemDraft[] = []
    for (const l of lines) {
      if (l.serialized) {
        for (const unitId of units[l.item.id] ?? []) {
          const u = state.get('product_serial_imei', unitId)
          drafts.push({
            saleItemId: l.item.id, productId: l.item.productId, productName: l.item.productName, variantId: l.item.variantId,
            returnQuantity: 1, serialUnitId: unitId, serialOrImei: u ? unitLabel(u) : '', restockOption: restock,
          })
        }
      } else if ((qty[l.item.id] ?? 0) > 0) {
        drafts.push({
          saleItemId: l.item.id, productId: l.item.productId, productName: l.item.productName, variantId: l.item.variantId,
          returnQuantity: qty[l.item.id], restockOption: restock,
        })
      }
    }
    if (drafts.length === 0) { setError('Please select at least one item to return'); return }
    const id = await run(tx => createSalesReturn(tx, {
      saleId: sale.id, returnDate: Date.now(), reason: reason.trim(), refundAmount: refundValue, refundPaymentMethod: method,
    }, drafts), 'Return saved')
    if (id !== undefined) onClose()
  }

  return (
    <Modal onClose={onClose} wide label="Return">
      <h2>Return from {sale.invoiceNumber}</h2>
      <div className="stack">
        {lines.map(l => (
          <div key={l.item.id} className="line-card">
            <div className="row">
              <div className="grow"><b>{l.item.productName}</b><div className="tiny muted">Sold {l.item.quantity}, returned {l.returned} · {money.format(l.unitValue)} each back</div></div>
              {!l.serialized && (
                <div className="qty">
                  <button onClick={() => setQty({ ...qty, [l.item.id]: Math.max((qty[l.item.id] ?? 0) - 1, 0) })}>−</button>
                  <span data-testid="return-qty">{qty[l.item.id] ?? 0}</span>
                  <button onClick={() => setQty({ ...qty, [l.item.id]: Math.min((qty[l.item.id] ?? 0) + 1, l.remaining) })} data-testid="return-plus">+</button>
                </div>
              )}
            </div>
            {l.serialized && (
              <div className="unit-list">
                {l.units.length === 0 && <div className="muted small" style={{ padding: 10 }}>All units of this line are back.</div>}
                {l.units.map(u => {
                  const picked = units[l.item.id] ?? []
                  return (
                    <label key={u.id}>
                      <input type="checkbox" checked={picked.includes(u.id)} data-testid="return-unit"
                        onChange={() => setUnits({ ...units, [l.item.id]: picked.includes(u.id) ? picked.filter(x => x !== u.id) : [...picked, u.id] })} />
                      <span className="mono">{unitLabel(u)}</span>
                    </label>
                  )
                })}
              </div>
            )}
          </div>
        ))}
        <div className="form-grid">
          <Field label="Condition">
            <select className="input" value={restock} onChange={e => setRestock(e.target.value)} data-testid="return-restock">
              <option value="RESTOCK_SELLABLE">Back on the shelf (sellable)</option>
              <option value="DEFECTIVE_DAMAGED">Defective / damaged (not sellable)</option>
            </select>
          </Field>
          <Field label="Reason"><input className="input" value={reason} onChange={e => setReason(e.target.value)} /></Field>
          <Field label="Cash refunded" hint={walkIn ? 'Walk-in customer: the whole value is handed back' : 'The rest is credited to the customer\'s account'}>
            <MoneyInput value={walkIn ? String(total) : refund} onChange={setRefund} disabled={walkIn} testId="return-refund" />
          </Field>
          <Field label="Refund method"><MethodSelect value={method} onChange={setMethod} /></Field>
        </div>
        <div className="k-v total"><span>Return value</span><span className="num" data-testid="return-total">{money.format(total)}</span></div>
        {!walkIn && <div className="k-v"><span className="k">Credit to the account</span><span className="num">{money.format(total - refundValue)}</span></div>}
      </div>
      <ErrorText error={error} />
      <div className="actions">
        <button className="btn text" onClick={onClose}>Cancel</button>
        <button className="btn" disabled={busy || total <= 0} onClick={submit} data-testid="return-save">{busy ? 'Saving…' : 'Save return'}</button>
      </div>
    </Modal>
  )
}
