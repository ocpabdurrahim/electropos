import { useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { useShop } from '../../data/store'
import { byTime } from '../../domain/caches'
import { deactivateCustomer } from '../../domain/customers'
import { formatDateTime } from '../../domain/dates'
import { methodLabel } from '../../domain/money'
import { ConfirmDialog, Empty, ErrorText, PageHead, StatusPill, Tabs, Tile, useAction, useMoney } from '../common'
import { CustomerDialog, CustomerPaymentDialog } from '../dialogs'
import { Icon } from '../icons'

type Tab = 'LEDGER' | 'SALES' | 'PAYMENTS'

export default function CustomerDetail() {
  const { id } = useParams()
  const { state } = useShop()
  const money = useMoney()
  const navigate = useNavigate()
  const [tab, setTab] = useState<Tab>('LEDGER')
  const [editing, setEditing] = useState(false)
  const [paying, setPaying] = useState(false)
  const [deactivating, setDeactivating] = useState(false)
  const { run, error } = useAction()
  const c = state.get('customers', Number(id))
  if (!c) return <div className="page"><Empty emoji="🔎" title="Customer not found" /></div>
  const ledger = [...state.where('customer_ledger_entries', 'customerId', c.id)].sort(byTime)
  const sales = [...state.where('sales', 'customerId', c.id)].sort((a, b) => b.saleDate - a.saleDate)
  const payments = [...state.where('customer_payments', 'customerId', c.id)].sort((a, b) => b.paymentDate - a.paymentDate)
  const active = sales.filter(s => s.status !== 'CANCELLED')

  return (
    <div className="page">
      <PageHead title={c.name} sub={`${c.customerCode} · ${c.phone}${c.address ? ` · ${c.address}` : ''}`} back={() => navigate(-1)}>
        <button className="btn tonal" onClick={() => setEditing(true)}><Icon name="edit" size={18} />Edit</button>
        <button className="btn" onClick={() => setPaying(true)} data-testid="customer-pay"><Icon name="cash" size={18} />Payment</button>
      </PageHead>
      {!c.isActive && <div className="notice warn" style={{ marginBottom: 12 }}>This customer is deactivated.</div>}
      <div className="tiles" style={{ marginBottom: 14 }}>
        <Tile label={c.currentDue < 0 ? 'Advance (credit)' : 'Due'} value={money.format(Math.abs(c.currentDue))} className={c.currentDue > 0.005 ? 'danger' : 'success'} />
        <Tile label="Invoices" value={String(active.length)} sub={money.format(active.reduce((a, s) => a + s.totalAmount, 0))} />
        <Tile label="Payments" value={String(payments.length)} sub={money.format(payments.reduce((a, p) => a + p.amount, 0))} />
        {c.creditLimit > 0 && <Tile label="Credit limit" value={money.format(c.creditLimit)} />}
      </div>
      <Tabs options={[['LEDGER', 'Ledger'], ['SALES', 'Invoices'], ['PAYMENTS', 'Payments']]} value={tab} onChange={setTab} />
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
                    <td className="r num" data-testid="ledger-balance">{money.plain(e.runningBalance)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </div>
      )}
      {tab === 'SALES' && (
        <div className="card list">
          {sales.length === 0 && <Empty emoji="🧾" title="No invoices" />}
          {sales.map(s => (
            <button key={s.id} className="item" onClick={() => navigate(`/sales/${s.id}`)}>
              <div className="grow"><div className="title">{s.invoiceNumber}</div><div className="subtitle">{formatDateTime(s.saleDate)}</div></div>
              <div className="end"><div className="bold num">{money.format(s.totalAmount)}</div>
                {s.status === 'CANCELLED' ? <StatusPill status="CANCELLED" /> : <span className="tiny">{s.dueAmount > 0.005 ? `due ${money.format(s.dueAmount)}` : 'paid'}</span>}</div>
            </button>
          ))}
        </div>
      )}
      {tab === 'PAYMENTS' && (
        <div className="card list">
          {payments.length === 0 && <Empty emoji="💵" title="No payments" />}
          {payments.map(p => (
            <div key={p.id} className="item">
              <div className="grow"><div className="title">{p.paymentNumber}</div><div className="subtitle">{formatDateTime(p.paymentDate)} · {methodLabel(p.paymentMethod)}{p.referenceNumber ? ` · ${p.referenceNumber}` : ''}{p.notes ? ` · ${p.notes}` : ''}</div></div>
              <b className="num success">{money.format(p.amount)}</b>
            </div>
          ))}
        </div>
      )}
      {c.isActive && <div style={{ marginTop: 16 }}><button className="btn danger" onClick={() => setDeactivating(true)}>Deactivate customer</button></div>}
      <ErrorText error={error} />
      {editing && <CustomerDialog customer={c} onClose={() => setEditing(false)} />}
      {paying && <CustomerPaymentDialog customerId={c.id} onClose={() => setPaying(false)} />}
      {deactivating && (
        <ConfirmDialog title="Deactivate this customer?" text="They stay in the records but can no longer buy on credit." confirm="Deactivate" danger
          onConfirm={() => void run(tx => deactivateCustomer(tx, c.id), 'Customer deactivated')} onClose={() => setDeactivating(false)} />
      )}
    </div>
  )
}
