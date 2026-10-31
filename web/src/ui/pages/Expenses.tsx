import { useMemo, useState } from 'react'
import type { Expense, ExpenseCategory } from '../../data/schema'
import { useShop } from '../../data/store'
import { dateInput, formatDate, fromDateInput, onDayWithTimeOf } from '../../domain/dates'
import {
  addExpense, addExpenseCategory, deleteExpense, deleteExpenseCategory, renameExpenseCategory, updateExpense,
} from '../../domain/finance'
import { methodLabel } from '../../domain/money'
import {
  amountOf, amountText, ConfirmDialog, Empty, ErrorText, Field, MethodSelect, Modal, MoneyInput, PageHead, Tabs, Tile, useAction, useMoney,
} from '../common'
import { Icon } from '../icons'

const monthKey = (t: number) => { const d = new Date(t); return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}` }

export default function Expenses() {
  const { state } = useShop()
  const money = useMoney()
  const [tab, setTab] = useState<'LIST' | 'CATEGORIES'>('LIST')
  const [month, setMonth] = useState(monthKey(Date.now()))
  const [editing, setEditing] = useState<Expense | 'new' | null>(null)
  const [category, setCategory] = useState<ExpenseCategory | 'new' | null>(null)
  const [deleting, setDeleting] = useState<Expense | null>(null)
  const { run, error } = useAction()
  const expenses = useMemo(() => [...state.list('expenses')].sort((a, b) => b.expenseDate - a.expenseDate || b.id - a.id), [state])
  const months = [...new Set([monthKey(Date.now()), ...expenses.map(e => monthKey(e.expenseDate))])].sort().reverse()
  const shown = expenses.filter(e => monthKey(e.expenseDate) === month)
  const byCategory = new Map<string, number>()
  for (const e of shown) byCategory.set(e.categoryName, (byCategory.get(e.categoryName) ?? 0) + e.amount)
  const categories = state.list('expense_categories').sort((a, b) => a.name.localeCompare(b.name))
  const usage = (name: string) => state.where('expenses', 'categoryName', name).length

  return (
    <div className="page">
      <PageHead title="Expenses">
        <button className="btn" onClick={() => (tab === 'LIST' ? setEditing('new') : setCategory('new'))} data-testid="add-expense">
          <Icon name="plus" size={18} />{tab === 'LIST' ? 'Expense' : 'Category'}
        </button>
      </PageHead>
      <Tabs options={[['LIST', 'Expenses'], ['CATEGORIES', 'Categories']]} value={tab} onChange={setTab} />
      <ErrorText error={error} />
      {tab === 'LIST' ? (
        <>
          <div className="toolbar">
            <select className="input" value={month} onChange={e => setMonth(e.target.value)} style={{ width: 'auto' }}>
              {months.map(m => <option key={m} value={m}>{formatDate(new Date(`${m}-01T00:00:00`).getTime(), 'MMMM yyyy')}</option>)}
            </select>
          </div>
          <div className="tiles" style={{ marginBottom: 14 }}>
            <Tile label="This month" value={money.format(shown.reduce((a, e) => a + e.amount, 0))} sub={`${shown.length} expenses`} />
            {[...byCategory.entries()].sort((a, b) => b[1] - a[1]).slice(0, 3).map(([k, v]) => <Tile key={k} label={k} value={money.format(v)} />)}
          </div>
          <div className="card list">
            {shown.length === 0 && <Empty emoji="💸" title="No expenses this month" />}
            {shown.map(e => (
              <div key={e.id} className="item" data-testid="expense-row">
                <div className="grow"><div className="title">{e.title}</div><div className="subtitle">{e.categoryName} · {formatDate(e.expenseDate)} · {methodLabel(e.paymentMethod)}{e.notes ? ` · ${e.notes}` : ''}</div></div>
                <b className="num">{money.format(e.amount)}</b>
                <button className="icon-btn" onClick={() => setEditing(e)} aria-label="Edit"><Icon name="edit" size={18} /></button>
                <button className="icon-btn" onClick={() => setDeleting(e)} aria-label="Delete"><Icon name="trash" size={18} /></button>
              </div>
            ))}
          </div>
        </>
      ) : (
        <div className="card list">
          {categories.length === 0 && <Empty emoji="🗂️" title="No categories yet" body="New categories are added when you type one on an expense." />}
          {categories.map(c => (
            <div key={c.id} className="item">
              <div className="grow"><div className="title">{c.name}</div><div className="subtitle">{usage(c.name)} expenses</div></div>
              <button className="icon-btn" onClick={() => setCategory(c)} aria-label="Rename"><Icon name="edit" size={18} /></button>
              <button className="icon-btn" onClick={() => void run(tx => { deleteExpenseCategory(tx, c.id); return true }, 'Category deleted')} aria-label="Delete"><Icon name="trash" size={18} /></button>
            </div>
          ))}
        </div>
      )}
      {editing && <ExpenseDialog expense={editing === 'new' ? undefined : editing} onClose={() => setEditing(null)} />}
      {category && <CategoryDialog category={category === 'new' ? undefined : category} onClose={() => setCategory(null)} />}
      {deleting && (
        <ConfirmDialog title="Delete this expense?" danger confirm="Delete" text={`${deleting.title}: ${money.format(deleting.amount)}. Its cash book line goes too.`}
          onConfirm={() => void run(tx => { deleteExpense(tx, deleting.id); return true }, 'Expense deleted')} onClose={() => setDeleting(null)} />
      )}
    </div>
  )
}

function ExpenseDialog({ expense, onClose }: { expense?: Expense; onClose: () => void }) {
  const { state } = useShop()
  const [f, setF] = useState({
    categoryName: expense?.categoryName ?? '', title: expense?.title ?? '', amount: amountText(expense?.amount ?? 0),
    paymentMethod: expense?.paymentMethod ?? 'CASH', date: dateInput(expense?.expenseDate ?? Date.now()), referenceNo: expense?.referenceNo ?? '',
    notes: expense?.notes ?? '',
  })
  const { run, busy, error } = useAction()
  const names = state.list('expense_categories').map(c => c.name).sort()
  const save = async () => {
    const day = fromDateInput(f.date) ?? Date.now()
    const input = {
      categoryName: f.categoryName, title: f.title, amount: amountOf(f.amount), paymentMethod: f.paymentMethod,
      expenseDate: expense && dateInput(expense.expenseDate) === f.date ? expense.expenseDate : onDayWithTimeOf(day), referenceNo: f.referenceNo, notes: f.notes,
    }
    const ok = await run(tx => (expense ? (updateExpense(tx, expense.id, input), expense.id) : addExpense(tx, input)), expense ? 'Expense saved' : 'Expense recorded')
    if (ok !== undefined) onClose()
  }
  return (
    <Modal onClose={onClose} narrow label="Expense">
      <h2>{expense ? 'Edit expense' : 'New expense'}</h2>
      <div className="stack">
        <Field label="Category *">
          <input className="input" list="expense-categories" value={f.categoryName} onChange={e => setF({ ...f, categoryName: e.target.value })} autoFocus data-testid="expense-category" />
          <datalist id="expense-categories">{names.map(n => <option key={n} value={n} />)}</datalist>
        </Field>
        <Field label="What for"><input className="input" value={f.title} onChange={e => setF({ ...f, title: e.target.value })} data-testid="expense-title" /></Field>
        <Field label="Amount *"><MoneyInput value={f.amount} onChange={v => setF({ ...f, amount: v })} testId="expense-amount" /></Field>
        <div className="pos-row">
          <Field label="Paid from"><MethodSelect value={f.paymentMethod} onChange={m => setF({ ...f, paymentMethod: m })} /></Field>
          <Field label="Date"><input className="input" type="date" value={f.date} onChange={e => setF({ ...f, date: e.target.value })} /></Field>
        </div>
        <Field label="Reference"><input className="input" value={f.referenceNo} onChange={e => setF({ ...f, referenceNo: e.target.value })} /></Field>
        <Field label="Notes"><input className="input" value={f.notes} onChange={e => setF({ ...f, notes: e.target.value })} /></Field>
      </div>
      <ErrorText error={error} />
      <div className="actions">
        <button className="btn text" onClick={onClose}>Cancel</button>
        <button className="btn" disabled={busy} onClick={save} data-testid="expense-save">{busy ? 'Saving…' : 'Save'}</button>
      </div>
    </Modal>
  )
}

function CategoryDialog({ category, onClose }: { category?: ExpenseCategory; onClose: () => void }) {
  const [name, setName] = useState(category?.name ?? '')
  const { run, busy, error } = useAction()
  const save = async () => {
    const ok = await run(tx => (category ? (renameExpenseCategory(tx, category.id, name), category.id) : addExpenseCategory(tx, name)), 'Category saved')
    if (ok !== undefined) onClose()
  }
  return (
    <Modal onClose={onClose} narrow sheet={false} label="Category">
      <h2>{category ? 'Rename category' : 'New category'}</h2>
      <Field label="Name"><input className="input" autoFocus value={name} onChange={e => setName(e.target.value)} /></Field>
      {category && <p className="small muted">Every expense filed under it is renamed too.</p>}
      <ErrorText error={error} />
      <div className="actions"><button className="btn text" onClick={onClose}>Cancel</button><button className="btn" disabled={busy} onClick={save}>Save</button></div>
    </Modal>
  )
}
