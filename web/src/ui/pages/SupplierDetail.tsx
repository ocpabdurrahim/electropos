import { useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { useShop } from '../../data/store'
import { byTime } from '../../domain/caches'
import { formatDateTime } from '../../domain/dates'
import { methodLabel } from '../../domain/money'
import { supplierSummary } from '../../domain/suppliers'
import { Empty, PageHead, StatusPill, Tabs, Tile, useMoney } from '../common'
import { SupplierDialog, SupplierPaymentDialog } from '../dialogs'
import { Icon } from '../icons'

type Tab = 'LEDGER' | 'PURCHASES' | 'PAYMENTS'

export default function SupplierDetail() {
  const { id } = useParams()
  const { state } = useShop()
  const money = useMoney()
  const navigate = useNavigate()
  const [tab, setTab] = useState<Tab>('LEDGER')
  const [editing, setEditing] = useState(false)
  const [paying, setPaying] = useState(false)
  const s = state.get('suppliers', Number(id))
  if (!s) return <div className="page"><Empty emoji="🔎" title="Supplier not found" /></div>
  const ledger = [...state.where('supplier_ledger_entries', 'supplierId', s.id)].sort(byTime)
  const summary = supplierSummary(ledger)
  const purchases = [...state.where('purchases', 'supplierId', s.id)].sort((a, b) => b.purchaseDate - a.purchaseDate)
  const payments = [...state.where('supplier_payments', 'supplierId', s.id)].sort((a, b) => b.paymentDate - a.paymentDate)
  return (
    <div className="page">
      <PageHead title={s.name} sub={[s.supplierCode, s.company, s.phone, s.address].filter(Boolean).join(' · ')} back={() => navigate(-1)}>
        <button className="btn tonal" onClick={() => setEditing(true)}><Icon name="edit" size={18} />Edit</button>
        <button className="btn tonal" onClick={() => navigate(`/purchases/new?supplier=${s.id}`)}><Icon name="purchases" size={18} />Purchase</button>
        <button className="btn" onClick={() => setPaying(true)} data-testid="supplier-pay"><Icon name="cash" size={18} />Pay</button>
      </PageHead>
      <div className="tiles" style={{ marginBottom: 14 }}>
        <Tile label={s.currentDue < 0 ? 'Advance paid' : 'You owe'} value={money.format(Math.abs(s.currentDue))} className={s.currentDue > 0.005 ? 'danger' : 'success'} />
        <Tile label="Purchases" value={money.format(summary.totalPurchases)} />
        <Tile label="Returns" value={money.format(summary.totalReturns)} />
        <Tile label="Paid" value={money.format(summary.totalPaid)} />
      </div>
      <Tabs options={[['LEDGER', 'Ledger'], ['PURCHASES', 'Purchases'], ['PAYMENTS', 'Payments']]} value={tab} onChange={setTab} />
      {tab === 'LEDGER' && (
        <div className="card table-wrap">
          {ledger.length === 0 ? <Empty emoji="📒" title="No entries yet" /> : (
            <table className="table">
              <thead><tr><th>Date</th><th>Entry</th><th className="r">Amount</th><th className="r">Balance</th></tr></thead>
              <tbody>
                {ledger.map(e => (
                  <tr key={e.id}>
                    <td className="small">{formatDateTime(e.createdAt)}</td>
                    <td><div>{e.description}</div><div className="tiny muted">{e.transactionType.replace(/_/g, ' ')} · {e.referenceNumber}</div></td>
                    <td className={`r num ${e.signedAmount < 0 ? 'success' : ''}`}>{e.signedAmount < 0 ? '−' : '+'}{money.plain(e.amount)}</td>
                    <td className="r num">{money.plain(e.runningBalance)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </div>
      )}
      {tab === 'PURCHASES' && (
        <div className="card list">
          {purchases.length === 0 && <Empty emoji="🛒" title="No purchases" />}
          {purchases.map(p => (
            <button key={p.id} className="item" onClick={() => navigate(`/purchases/${p.id}`)}>
              <div className="grow"><div className="title">{p.purchaseNumber}</div><div className="subtitle">{formatDateTime(p.purchaseDate)}{p.supplierInvoiceNumber ? ` · inv ${p.supplierInvoiceNumber}` : ''}</div></div>
              <div className="end"><div className="bold num">{money.format(p.totalAmount)}</div>{p.status === 'CANCELLED' ? <StatusPill status="CANCELLED" /> : <StatusPill status={p.paymentStatus} />}</div>
            </button>
          ))}
        </div>
      )}
      {tab === 'PAYMENTS' && (
        <div className="card list">
          {payments.length === 0 && <Empty emoji="💵" title="No payments" />}
          {payments.map(p => (
            <div key={p.id} className="item">
              <div className="grow"><div className="title">{p.paymentNumber}</div><div className="subtitle">{formatDateTime(p.paymentDate)} · {methodLabel(p.paymentMethod)}{p.notes ? ` · ${p.notes}` : ''}</div></div>
              <b className="num">{money.format(p.amount)}</b>
            </div>
          ))}
        </div>
      )}
      {editing && <SupplierDialog supplier={s} onClose={() => setEditing(false)} />}
      {paying && <SupplierPaymentDialog supplierId={s.id} onClose={() => setPaying(false)} />}
    </div>
  )
}
