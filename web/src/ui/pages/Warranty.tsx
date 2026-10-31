import { useMemo, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import type { Warranty as WarrantyRow } from '../../data/schema'
import { useShop } from '../../data/store'
import { recordWarrantyClaim, WARRANTY_FILTERS, warrantyMatches, type WarrantyFilter } from '../../domain/catalog'
import { formatDate } from '../../domain/dates'
import { Chips, Empty, ErrorText, Field, Modal, PageHead, SearchBox, StatusPill, matches, useAction, useNow } from '../common'

const FILTER_LABELS: Record<WarrantyFilter, string> = { ALL: 'All', ACTIVE: 'Active', EXPIRING: 'Expiring in 30 days', EXPIRED: 'Expired', CLAIMS: 'Claims' }

export default function Warranty() {
  const { state } = useShop()
  const navigate = useNavigate()
  const now = useNow()
  const [q, setQ] = useState('')
  const [filter, setFilter] = useState<WarrantyFilter>('ACTIVE')
  const [selected, setSelected] = useState<WarrantyRow | null>(null)
  const rows = useMemo(() => [...state.list('warranty_records')].sort((a, b) => a.warrantyEndDate - b.warrantyEndDate), [state])
  const shown = rows.filter(w => warrantyMatches(w, filter, now) && matches(q, w.serialOrImei, w.customerPhone, w.customerName, w.invoiceNumber, w.productName))
  return (
    <div className="page">
      <PageHead title="Warranty" sub={`${rows.filter(w => warrantyMatches(w, 'ACTIVE', now)).length} active`} />
      <div className="toolbar">
        <SearchBox value={q} onChange={setQ} placeholder="IMEI, phone, customer or invoice" testId="warranty-search" />
        <Chips options={WARRANTY_FILTERS.map(f => [f, FILTER_LABELS[f]] as [WarrantyFilter, string])} value={filter} onChange={setFilter} />
      </div>
      <div className="card list">
        {shown.length === 0 && <Empty emoji="🛡️" title="Nothing here" />}
        {shown.map(w => (
          <button key={w.id} className="item" onClick={() => setSelected(w)}>
            <div className="grow">
              <div className="title">{w.productName}</div>
              <div className="subtitle">{w.serialOrImei || 'No serial'} · {w.customerName}{w.customerPhone ? ` (${w.customerPhone})` : ''} · {w.invoiceNumber}</div>
            </div>
            <div className="end">
              <div className={`small ${w.warrantyEndDate < now ? 'danger' : ''}`}>until {formatDate(w.warrantyEndDate)}</div>
              <StatusPill status={w.status === 'ACTIVE' && w.warrantyEndDate < now ? 'EXPIRED' : w.status} />
            </div>
          </button>
        ))}
      </div>
      {selected && <ClaimDialog warranty={selected} onOpenSale={() => navigate(`/sales/${selected.saleId}`)} onClose={() => setSelected(null)} />}
    </div>
  )
}

function ClaimDialog({ warranty, onOpenSale, onClose }: { warranty: WarrantyRow; onOpenSale: () => void; onClose: () => void }) {
  const [status, setStatus] = useState('CLAIMED')
  const [note, setNote] = useState('')
  const { run, busy, error } = useAction()
  const canClaim = warranty.status !== 'RETURNED' && warranty.status !== 'CANCELLED'
  const save = async () => {
    const ok = await run(tx => { recordWarrantyClaim(tx, warranty.id, status, note); return true }, 'Warranty updated')
    if (ok) onClose()
  }
  return (
    <Modal onClose={onClose} narrow label="Warranty">
      <h2>{warranty.productName}</h2>
      <div className="stack">
        <div className="small">
          <div><b>{warranty.serialOrImei || 'No serial'}</b></div>
          <div className="muted">{warranty.customerName} {warranty.customerPhone} · {warranty.invoiceNumber}</div>
          <div className="muted">{formatDate(warranty.warrantyStartDate)} → {formatDate(warranty.warrantyEndDate)} · {warranty.warrantyPeriodMonths} months</div>
          <div>Status: <StatusPill status={warranty.status} /></div>
        </div>
        {warranty.claimNotes && <pre className="small" style={{ whiteSpace: 'pre-wrap', margin: 0, fontFamily: 'inherit' }}>{warranty.claimNotes}</pre>}
        {canClaim ? (
          <>
            <Chips options={[['CLAIMED', 'Received'], ['REPAIRED', 'Repaired'], ['REPLACED', 'Replaced'], ['ACTIVE', 'Back to active']]} value={status} onChange={setStatus} />
            <Field label="Note"><input className="input" value={note} onChange={e => setNote(e.target.value)} /></Field>
          </>
        ) : <div className="notice small">The sale was {warranty.status.toLowerCase()}; this warranty no longer applies.</div>}
      </div>
      <ErrorText error={error} />
      <div className="actions">
        <button className="btn text" onClick={onOpenSale}>Open sale</button>
        <button className="btn text" onClick={onClose}>Close</button>
        {canClaim && <button className="btn" disabled={busy} onClick={save}>Save</button>}
      </div>
    </Modal>
  )
}
