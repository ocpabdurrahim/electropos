import { useMemo, useState } from 'react'
import { useShop } from '../../data/store'
import { dateInput, endOfDay, formatDateTime, fromDateInput, onDayWithTimeOf, startOfDay } from '../../domain/dates'
import {
  addManualCashEntry, balancesByMethod, cashBook, deleteManualCashEntry, isManualCash, methodsWithOpening, moveMoney, setOpeningBalance,
} from '../../domain/finance'
import { methodLabel, PAYMENT_METHODS } from '../../domain/money'
import { amountOf, ConfirmDialog, Empty, ErrorText, Field, MethodSelect, Modal, MoneyInput, PageHead, Tile, useAction, useMoney } from '../common'
import { Icon } from '../icons'

type Entry = 'IN' | 'OUT' | 'MOVE' | 'OPENING'

export default function CashBook() {
  const { state } = useShop()
  const money = useMoney()
  const [method, setMethod] = useState<string>('CASH')
  const [from, setFrom] = useState(dateInput(Date.now()))
  const [to, setTo] = useState(dateInput(Date.now()))
  const [entry, setEntry] = useState<Entry | null>(null)
  const [deleting, setDeleting] = useState<number | null>(null)
  const { run, error } = useAction()
  const start = startOfDay(fromDateInput(from) ?? Date.now())
  const end = endOfDay(fromDateInput(to) ?? Date.now())
  const page = useMemo(() => cashBook(state, method === 'ALL' ? null : method, Math.min(start, end), Math.max(start, end)), [state, method, start, end])
  const balances = balancesByMethod(state)

  return (
    <div className="page">
      <PageHead title="Cash book">
        <button className="btn tonal" onClick={() => setEntry('MOVE')}><Icon name="swap" size={18} />Move money</button>
        <button className="btn tonal" onClick={() => setEntry('OUT')} data-testid="cash-out">Cash out</button>
        <button className="btn" onClick={() => setEntry('IN')} data-testid="cash-in"><Icon name="plus" size={18} />Cash in</button>
      </PageHead>
      <div className="tiles" style={{ marginBottom: 14 }}>
        {balances.filter(b => b.method === 'CASH' || Math.abs(b.balance) > 0.004).map(b => (
          <Tile key={b.method} label={b.method === 'CASH' ? 'Cash in drawer' : methodLabel(b.method)} value={money.format(b.balance)} onClick={() => setMethod(b.method)} />
        ))}
        <Tile label="All money" value={money.format(balances.reduce((a, b) => a + b.balance, 0))} onClick={() => setMethod('ALL')} />
      </div>
      <div className="toolbar">
        <select className="input" value={method} onChange={e => setMethod(e.target.value)} data-testid="cash-method">
          <option value="ALL">All methods</option>
          {[...new Set([...PAYMENT_METHODS, ...balances.map(b => b.method)])].map(m => <option key={m} value={m}>{methodLabel(m)}</option>)}
        </select>
        <input className="input" type="date" value={from} onChange={e => setFrom(e.target.value)} />
        <span className="muted">to</span>
        <input className="input" type="date" value={to} onChange={e => setTo(e.target.value)} />
        {!methodsWithOpening(state).has(method === 'ALL' ? 'CASH' : method) && <button className="btn text small" onClick={() => setEntry('OPENING')}>Set opening balance</button>}
      </div>
      <ErrorText error={error} />
      <div className="card table-wrap">
        <table className="table">
          <thead><tr><th>Time</th><th>Entry</th>{method === 'ALL' && <th>Method</th>}<th className="r">In</th><th className="r">Out</th><th className="r">Balance</th><th /></tr></thead>
          <tbody>
            <tr><td colSpan={method === 'ALL' ? 5 : 4} className="muted">Opening balance</td><td className="r num bold">{money.plain(page.openingBalance)}</td><td /></tr>
            {page.lines.map(l => (
              <tr key={l.entry.id}>
                <td className="small">{formatDateTime(l.entry.date)}</td>
                <td><div>{l.label}</div><div className="tiny muted">{[l.entry.referenceText, l.entry.notes].filter(Boolean).join(' · ')}</div></td>
                {method === 'ALL' && <td className="small">{methodLabel(l.entry.paymentMethod)}</td>}
                <td className="r num success">{l.signedAmount > 0 ? money.plain(l.signedAmount) : ''}</td>
                <td className="r num danger">{l.signedAmount < 0 ? money.plain(-l.signedAmount) : ''}</td>
                <td className="r num">{money.plain(l.runningBalance)}</td>
                <td>{isManualCash(l.entry.type) && <button className="icon-btn" onClick={() => setDeleting(l.entry.id)} aria-label="Delete"><Icon name="trash" size={16} /></button>}</td>
              </tr>
            ))}
          </tbody>
          <tfoot><tr><td colSpan={method === 'ALL' ? 3 : 2}>Closing</td><td className="r num">{money.plain(page.totalIn)}</td><td className="r num">{money.plain(page.totalOut)}</td><td className="r num" data-testid="cash-closing">{money.plain(page.closingBalance)}</td><td /></tr></tfoot>
        </table>
        {page.lines.length === 0 && <Empty emoji="💵" title="Nothing in this period" />}
      </div>
      {entry && <EntryDialog kind={entry} method={method === 'ALL' ? 'CASH' : method} onClose={() => setEntry(null)} />}
      {deleting !== null && (
        <ConfirmDialog title="Delete this entry?" danger confirm="Delete" text="Both halves of a money move are removed together."
          onConfirm={() => void run(tx => { deleteManualCashEntry(tx, deleting); return true }, 'Entry deleted')} onClose={() => setDeleting(null)} />
      )}
    </div>
  )
}

function EntryDialog({ kind, method: initial, onClose }: { kind: Entry; method: string; onClose: () => void }) {
  const [amount, setAmount] = useState('')
  const [method, setMethod] = useState(initial)
  const [toMethod, setToMethod] = useState(initial === 'BANK' ? 'CASH' : 'BANK')
  const [note, setNote] = useState('')
  const [date, setDate] = useState(dateInput(Date.now()))
  const { run, busy, error } = useAction()
  const title = { IN: 'Cash in', OUT: 'Cash out', MOVE: 'Move money', OPENING: 'Opening balance' }[kind]
  const save = async () => {
    const when = onDayWithTimeOf(fromDateInput(date) ?? Date.now())
    const ok = await run(tx => {
      if (kind === 'MOVE') return moveMoney(tx, method, toMethod, amountOf(amount), note, when)
      if (kind === 'OPENING') return setOpeningBalance(tx, amountOf(amount), method, note)
      return addManualCashEntry(tx, kind, method, amountOf(amount), note, when)
    }, `${title} saved`)
    if (ok !== undefined) onClose()
  }
  return (
    <Modal onClose={onClose} narrow label={title}>
      <h2>{title}</h2>
      <div className="stack">
        <Field label="Amount *"><MoneyInput value={amount} onChange={setAmount} autoFocus testId="entry-amount" /></Field>
        <Field label={kind === 'MOVE' ? 'From' : 'Method'}><MethodSelect value={method} onChange={setMethod} /></Field>
        {kind === 'MOVE' && <Field label="To"><MethodSelect value={toMethod} onChange={setToMethod} /></Field>}
        {kind !== 'OPENING' && <Field label="Date"><input className="input" type="date" value={date} onChange={e => setDate(e.target.value)} /></Field>}
        <Field label="Note"><input className="input" value={note} onChange={e => setNote(e.target.value)} /></Field>
        {kind === 'OPENING' && <p className="small muted" style={{ margin: 0 }}>Money this method held when the shop started using ElectroPOS; it can be set once.</p>}
      </div>
      <ErrorText error={error} />
      <div className="actions"><button className="btn text" onClick={onClose}>Cancel</button><button className="btn" disabled={busy} onClick={save} data-testid="entry-save">Save</button></div>
    </Modal>
  )
}
