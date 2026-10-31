import { useMemo, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { useShop } from '../../data/store'
import { formatDateTime } from '../../domain/dates'
import { Chips, Empty, MoreButton, PageHead, SearchBox, StatusPill, matches, useLimit, useMoney } from '../common'
import { Icon } from '../icons'

type Filter = 'ALL' | 'DUE' | 'CANCELLED' | 'RETURNS'

export default function Purchases() {
  const { state } = useShop()
  const money = useMoney()
  const navigate = useNavigate()
  const [q, setQ] = useState('')
  const [filter, setFilter] = useState<Filter>('ALL')
  const [limit, more] = useLimit()
  const purchases = useMemo(() => [...state.list('purchases')].sort((a, b) => b.purchaseDate - a.purchaseDate || b.id - a.id), [state])
  const returns = useMemo(() => [...state.list('purchase_returns')].sort((a, b) => b.returnDate - a.returnDate), [state])
  const shown = purchases.filter(p => matches(q, p.purchaseNumber, p.supplierName, p.supplierInvoiceNumber, p.referenceNumber) &&
    (filter === 'ALL' || (filter === 'DUE' && p.status !== 'CANCELLED' && p.dueAmount > 0.005) || (filter === 'CANCELLED' && p.status === 'CANCELLED')))
  return (
    <div className="page">
      <PageHead title="Purchases" sub={`${purchases.length} purchases`}>
        <button className="btn" onClick={() => navigate('/purchases/new')} data-testid="new-purchase"><Icon name="plus" size={18} />Purchase</button>
      </PageHead>
      <div className="toolbar">
        <SearchBox value={q} onChange={setQ} placeholder="Number, supplier, invoice" />
        <Chips options={[['ALL', 'All'], ['DUE', 'With due'], ['CANCELLED', 'Cancelled'], ['RETURNS', 'Returns']]} value={filter} onChange={setFilter} />
      </div>
      {filter === 'RETURNS' ? (
        <div className="card list">
          {returns.length === 0 && <Empty emoji="↩️" title="No purchase returns" />}
          {returns.filter(r => matches(q, r.returnNumber, r.supplierName)).map(r => (
            <button key={r.id} className="item" onClick={() => navigate(`/purchases/${r.purchaseId}`)}>
              <div className="grow"><div className="title">{r.returnNumber}</div><div className="subtitle">{r.supplierName} · {formatDateTime(r.returnDate)}</div></div>
              <b className="num">−{money.format(r.totalAmount)}</b>
            </button>
          ))}
        </div>
      ) : (
        <div className="card list">
          {shown.length === 0 && <Empty emoji="🛒" title="No purchases found" />}
          {shown.slice(0, limit).map(p => (
            <button key={p.id} className="item" onClick={() => navigate(`/purchases/${p.id}`)} data-testid="purchase-row">
              <div className="grow"><div className="title">{p.purchaseNumber}</div><div className="subtitle">{p.supplierName} · {formatDateTime(p.purchaseDate)}</div></div>
              <div className="end"><div className="bold num">{money.format(p.totalAmount)}</div>{p.status === 'CANCELLED' ? <StatusPill status="CANCELLED" /> : <StatusPill status={p.paymentStatus} />}</div>
            </button>
          ))}
          <MoreButton shown={Math.min(limit, shown.length)} total={shown.length} onMore={more} />
        </div>
      )}
    </div>
  )
}
