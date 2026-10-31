import { useMemo, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { useShop } from '../../data/store'
import { formatDateTime } from '../../domain/dates'
import { Chips, Empty, MoreButton, PageHead, SearchBox, StatusPill, matches, useCan, useLimit, useMoney } from '../common'
import { Icon } from '../icons'

type Filter = 'ALL' | 'DUE' | 'CANCELLED' | 'RETURNS'

export default function Sales() {
  const { state } = useShop()
  const money = useMoney()
  const navigate = useNavigate()
  const allowed = useCan()
  const [q, setQ] = useState('')
  const [filter, setFilter] = useState<Filter>('ALL')
  const [limit, more] = useLimit()

  const sales = useMemo(() => [...state.list('sales')].sort((a, b) => b.saleDate - a.saleDate || b.id - a.id), [state])
  const returns = useMemo(() => [...state.list('sales_returns')].sort((a, b) => b.returnDate - a.returnDate || b.id - a.id), [state])
  const shown = sales.filter(s => matches(q, s.invoiceNumber, s.customerName, s.customerPhone) &&
    (filter === 'ALL' || (filter === 'DUE' && s.status !== 'CANCELLED' && s.dueAmount > 0.005) || (filter === 'CANCELLED' && s.status === 'CANCELLED')))
  const shownReturns = returns.filter(r => matches(q, r.returnNumber, r.invoiceNumber, r.customerName, r.customerPhone))

  return (
    <div className="page">
      <PageHead title="Sales" sub={`${sales.length} invoices`}>
        {allowed('POS') && <button className="btn" onClick={() => navigate('/pos')}><Icon name="plus" size={18} />New sale</button>}
      </PageHead>
      <div className="toolbar">
        <SearchBox value={q} onChange={setQ} placeholder="Invoice, customer or phone" testId="sales-search" />
        <Chips options={[['ALL', 'All'], ['DUE', 'With due'], ['CANCELLED', 'Cancelled'], ['RETURNS', 'Returns']]} value={filter} onChange={setFilter} />
      </div>
      {filter === 'RETURNS' ? (
        <div className="card list">
          {shownReturns.length === 0 && <Empty emoji="↩️" title="No returns" />}
          {shownReturns.slice(0, limit).map(r => (
            <button key={r.id} className="item" onClick={() => navigate(`/sales/${r.saleId}`)}>
              <div className="grow"><div className="title">{r.returnNumber}</div><div className="subtitle">{r.invoiceNumber} · {r.customerName} · {formatDateTime(r.returnDate)}</div></div>
              <div className="end"><div className="bold num">−{money.format(r.totalAmount)}</div>{r.refundAmount > 0 && <div className="tiny muted">refund {money.format(r.refundAmount)}</div>}</div>
            </button>
          ))}
          <MoreButton shown={Math.min(limit, shownReturns.length)} total={shownReturns.length} onMore={more} />
        </div>
      ) : (
        <div className="card list">
          {shown.length === 0 && <Empty emoji="🧾" title="No sales found" />}
          {shown.slice(0, limit).map(s => (
            <button key={s.id} className="item" onClick={() => navigate(`/sales/${s.id}`)} data-testid="sale-row">
              <div className="grow">
                <div className="title">{s.invoiceNumber}</div>
                <div className="subtitle">{s.customerName}{s.customerPhone ? ` · ${s.customerPhone}` : ''} · {formatDateTime(s.saleDate)}</div>
              </div>
              <div className="end">
                <div className="bold num">{money.format(s.totalAmount)}</div>
                {s.status === 'CANCELLED' ? <StatusPill status="CANCELLED" /> : <StatusPill status={s.paymentStatus} />}
              </div>
            </button>
          ))}
          <MoreButton shown={Math.min(limit, shown.length)} total={shown.length} onMore={more} />
        </div>
      )}
    </div>
  )
}
