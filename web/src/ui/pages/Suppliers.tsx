import { useMemo, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { useShop } from '../../data/store'
import { Empty, PageHead, SearchBox, Tile, matches, useMoney } from '../common'
import { SupplierDialog } from '../dialogs'
import { Icon } from '../icons'

export default function Suppliers() {
  const { state } = useShop()
  const money = useMoney()
  const navigate = useNavigate()
  const [q, setQ] = useState('')
  const [adding, setAdding] = useState(false)
  const suppliers = useMemo(() => state.list('suppliers').filter(s => s.isActive).sort((a, b) => a.name.localeCompare(b.name)), [state])
  const shown = suppliers.filter(s => matches(q, s.name, s.company, s.phone, s.supplierCode))
  return (
    <div className="page">
      <PageHead title="Suppliers" sub={`${suppliers.length} suppliers`}>
        <button className="btn" onClick={() => setAdding(true)} data-testid="add-supplier"><Icon name="plus" size={18} />Supplier</button>
      </PageHead>
      <div className="tiles" style={{ marginBottom: 14 }}>
        <Tile label="You owe suppliers" value={money.format(suppliers.reduce((a, s) => a + Math.max(s.currentDue, 0), 0))} />
      </div>
      <div className="toolbar"><SearchBox value={q} onChange={setQ} placeholder="Name, company, phone or code" /></div>
      <div className="card list">
        {shown.length === 0 && <Empty emoji="🚚" title="No suppliers" />}
        {shown.map(s => (
          <button key={s.id} className="item" onClick={() => navigate(`/suppliers/${s.id}`)} data-testid="supplier-row">
            <span className="avatar" style={{ width: 38, height: 38 }}>{s.name.charAt(0).toUpperCase()}</span>
            <div className="grow"><div className="title">{s.name}</div><div className="subtitle">{[s.company, s.phone, s.supplierCode].filter(Boolean).join(' · ')}</div></div>
            <div className="end">
              {s.currentDue > 0.005 ? <span className="pill red num">{money.format(s.currentDue)}</span>
                : s.currentDue < -0.005 ? <span className="pill green num">Advance {money.format(-s.currentDue)}</span> : <span className="tiny muted">Settled</span>}
            </div>
          </button>
        ))}
      </div>
      {adding && <SupplierDialog onClose={() => setAdding(false)} onSaved={id => navigate(`/suppliers/${id}`)} />}
    </div>
  )
}
