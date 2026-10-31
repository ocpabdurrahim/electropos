import { useMemo, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { useShop } from '../../data/store'
import { Chips, Empty, MoreButton, PageHead, SearchBox, Tile, matches, useLimit, useMoney } from '../common'
import { CustomerDialog } from '../dialogs'
import { Icon } from '../icons'

type Filter = 'ACTIVE' | 'DUE' | 'ALL'

export default function Customers() {
  const { state } = useShop()
  const money = useMoney()
  const navigate = useNavigate()
  const [q, setQ] = useState('')
  const [filter, setFilter] = useState<Filter>('ACTIVE')
  const [adding, setAdding] = useState(false)
  const [limit, more] = useLimit()
  const customers = useMemo(() => [...state.list('customers')].sort((a, b) => a.name.localeCompare(b.name)), [state])
  const shown = customers.filter(c => matches(q, c.name, c.phone, c.customerCode, c.address) &&
    (filter === 'ALL' || (filter === 'ACTIVE' && c.isActive) || (filter === 'DUE' && c.currentDue > 0.005)))
  const owed = customers.reduce((a, c) => a + Math.max(c.currentDue, 0), 0)

  return (
    <div className="page">
      <PageHead title="Customers" sub={`${customers.filter(c => c.isActive).length} active`}>
        <button className="btn" onClick={() => setAdding(true)} data-testid="add-customer"><Icon name="plus" size={18} />Customer</button>
      </PageHead>
      <div className="tiles" style={{ marginBottom: 14 }}>
        <Tile label="Customers owe the shop" value={money.format(owed)} sub={`${customers.filter(c => c.currentDue > 0.005).length} with due`} />
      </div>
      <div className="toolbar">
        <SearchBox value={q} onChange={setQ} placeholder="Name, phone or code" testId="customer-search" />
        <Chips options={[['ACTIVE', 'Active'], ['DUE', 'With due'], ['ALL', 'All']]} value={filter} onChange={setFilter} />
      </div>
      <div className="card list">
        {shown.length === 0 && <Empty emoji="👥" title="No customers found" />}
        {shown.slice(0, limit).map(c => (
          <button key={c.id} className="item" onClick={() => navigate(`/customers/${c.id}`)} data-testid="customer-row">
            <span className="avatar" style={{ width: 38, height: 38 }}>{c.name.charAt(0).toUpperCase()}</span>
            <div className="grow">
              <div className="title">{c.name}{!c.isActive && <span className="pill" style={{ marginLeft: 6 }}>inactive</span>}</div>
              <div className="subtitle">{c.phone} · {c.customerCode}</div>
            </div>
            <div className="end">
              {c.currentDue > 0.005 ? <span className="pill red num">{money.format(c.currentDue)}</span>
                : c.currentDue < -0.005 ? <span className="pill green num">Advance {money.format(-c.currentDue)}</span> : <span className="tiny muted">No due</span>}
            </div>
          </button>
        ))}
        <MoreButton shown={Math.min(limit, shown.length)} total={shown.length} onMore={more} />
      </div>
      {adding && <CustomerDialog onClose={() => setAdding(false)} onSaved={id => navigate(`/customers/${id}`)} />}
    </div>
  )
}
