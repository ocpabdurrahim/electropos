import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react'
import { runAction } from '../data/actions'
import { useShop } from '../data/store'
import { DomainError } from '../domain/common'
import { formatMoney, methodLabel, PAYMENT_METHODS, parseAmount, toInput } from '../domain/money'
import { can, type Feature } from '../domain/roles'
import type { Tx } from '../domain/state'
import { Icon } from './icons'

// ---- Money in the shop's currency ---------------------------------------------------

export function useMoney() {
  const { state } = useShop()
  const symbol = state.profile.currencySymbol.trim() || '৳'
  return useMemo(() => ({
    symbol,
    format: (amount: number | null | undefined) => formatMoney(amount ?? 0, symbol),
    /** Without the sign, for narrow table cells. */
    plain: (amount: number | null | undefined) => formatMoney(amount ?? 0, '').trim(),
  }), [symbol])
}

export function useCan() {
  const { role } = useShop()
  return useCallback((f: Feature) => can(role, f), [role])
}

export const message = (e: unknown) => (e instanceof DomainError || e instanceof Error ? e.message : String(e))

/** Runs a domain action with a busy flag and an error message for the form. */
export function useAction() {
  const toast = useToast()
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const run = useCallback(async <R,>(fn: (tx: Tx) => R, done?: string): Promise<R | undefined> => {
    setBusy(true)
    setError(null)
    try {
      const r = await runAction(fn)
      if (done) toast(done)
      return r
    } catch (e) {
      setError(message(e))
      return undefined
    } finally {
      setBusy(false)
    }
  }, [toast])
  return { run, busy, error, setError }
}

/** Case-insensitive search over a few fields. */
export function matches(q: string, ...fields: (string | number | null | undefined)[]): boolean {
  const needle = q.trim().toLowerCase()
  if (!needle) return true
  return fields.some(f => f !== null && f !== undefined && String(f).toLowerCase().includes(needle))
}

/** The time now, refreshed every minute and when the tab comes back. */
export function useNow(): number {
  const [now, setNow] = useState(() => Date.now())
  useEffect(() => {
    const tick = () => setNow(Date.now())
    const timer = setInterval(tick, 60_000)
    document.addEventListener('visibilitychange', tick)
    return () => {
      clearInterval(timer)
      document.removeEventListener('visibilitychange', tick)
    }
  }, [])
  return now
}

// ---- Toasts --------------------------------------------------------------------------

const ToastContext = createContext<(message: string) => void>(() => {})
export const useToast = () => useContext(ToastContext)

export function ToastProvider({ children }: { children: ReactNode }) {
  const [toasts, setToasts] = useState<{ id: number; message: string }[]>([])
  const show = useCallback((m: string) => {
    const id = Date.now() + Math.random()
    setToasts(t => [...t, { id, message: m }])
    setTimeout(() => setToasts(t => t.filter(x => x.id !== id)), 3500)
  }, [])
  return (
    <ToastContext.Provider value={show}>
      {children}
      <div className="toasts" role="status">
        {toasts.map(t => <div key={t.id} className="toast">{t.message}</div>)}
      </div>
    </ToastContext.Provider>
  )
}

// ---- Building blocks -----------------------------------------------------------------

export function Empty({ emoji, title, body, children }: { emoji: string; title: string; body?: string; children?: ReactNode }) {
  return (
    <div className="empty">
      <div className="e">{emoji}</div>
      <h3>{title}</h3>
      {body && <p>{body}</p>}
      {children && <div style={{ marginTop: 14 }}>{children}</div>}
    </div>
  )
}

export function Tile({ label, value, sub, className, onClick }: { label: string; value: string; sub?: string; className?: string; onClick?: () => void }) {
  const body = (
    <>
      <div className="k">{label}</div>
      <div className={`v num ${className ?? ''}`}>{value}</div>
      {sub && <div className="s">{sub}</div>}
    </>
  )
  return onClick ? <button className="tile" onClick={onClick}>{body}</button> : <div className="tile">{body}</div>
}

export function Tabs<T extends string>({ options, value, onChange }: { options: [T, string][]; value: T; onChange: (v: T) => void }) {
  return (
    <div className="tabs" role="tablist">
      {options.map(([v, label]) => (
        <button key={v} role="tab" aria-selected={v === value} className={v === value ? 'on' : ''} onClick={() => onChange(v)}>{label}</button>
      ))}
    </div>
  )
}

export function Chips<T extends string>({ options, value, onChange }: { options: [T, string][]; value: T; onChange: (v: T) => void }) {
  return (
    <div className="chips">
      {options.map(([v, label]) => (
        <button key={v} className={`chip ${v === value ? 'on' : ''}`} onClick={() => onChange(v)}>{label}</button>
      ))}
    </div>
  )
}

export function Field({ label, hint, children, full = false }: { label: string; hint?: ReactNode; children: ReactNode; full?: boolean }) {
  return (
    <label className={`field ${full ? 'full' : ''}`}>
      <span>{label}</span>
      {children}
      {hint && <span className="hint">{hint}</span>}
    </label>
  )
}

/** A text box for an amount; [value] is the text, read it with amountOf. */
export function MoneyInput({ value, onChange, autoFocus, placeholder, testId, disabled }: {
  value: string
  onChange: (v: string) => void
  autoFocus?: boolean
  placeholder?: string
  testId?: string
  disabled?: boolean
}) {
  const money = useMoney()
  return (
    <div className="row" style={{ gap: 0 }}>
      <span className="muted" style={{ marginRight: 6, fontWeight: 650 }}>{money.symbol}</span>
      <input className="input num" inputMode="decimal" value={value} autoFocus={autoFocus} placeholder={placeholder ?? '0'} disabled={disabled}
        data-testid={testId} onChange={e => { if (/^[\d০-৯.,]*$/.test(e.target.value)) onChange(e.target.value) }} />
    </div>
  )
}

export const amountOf = (s: string) => parseAmount(s) ?? 0
export const amountText = (v: number) => (v ? toInput(v) : '')

export function MethodSelect({ value, onChange, testId }: { value: string; onChange: (m: string) => void; testId?: string }) {
  return (
    <select className="input" value={value} onChange={e => onChange(e.target.value)} data-testid={testId}>
      {PAYMENT_METHODS.map(m => <option key={m} value={m}>{methodLabel(m)}</option>)}
    </select>
  )
}

export function Modal({ children, onClose, narrow = false, wide = false, sheet = true, label }: {
  children: ReactNode
  onClose: () => void
  narrow?: boolean
  wide?: boolean
  sheet?: boolean
  label: string
}) {
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && onClose()
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onClose])
  return (
    <div className={`backdrop ${sheet ? 'sheet' : ''}`} onMouseDown={e => e.target === e.currentTarget && onClose()}>
      <div className={`dialog ${narrow ? 'narrow' : ''} ${wide ? 'wide' : ''}`} role="dialog" aria-modal="true" aria-label={label}>
        {children}
      </div>
    </div>
  )
}

export function ConfirmDialog({ title, text, confirm, danger = false, onConfirm, onClose, children }: {
  title: string
  text: string
  confirm: string
  danger?: boolean
  onConfirm: () => void
  onClose: () => void
  children?: ReactNode
}) {
  return (
    <Modal onClose={onClose} narrow sheet={false} label={title}>
      <h2>{title}</h2>
      <p className="muted">{text}</p>
      {children}
      <div className="actions">
        <button className="btn text" onClick={onClose}>Cancel</button>
        <button className={`btn ${danger ? 'danger' : ''}`} onClick={() => { onConfirm(); onClose() }} data-testid="confirm">{confirm}</button>
      </div>
    </Modal>
  )
}

export function ErrorText({ error }: { error: string | null }) {
  return error ? <div className="notice err small" role="alert" data-testid="form-error">{error}</div> : null
}

export function Spinner() {
  return <div className="center-screen"><div className="spinner" aria-label="Loading" /></div>
}

export function PageHead({ title, sub, children, back }: { title: string; sub?: ReactNode; children?: ReactNode; back?: () => void }) {
  return (
    <div className="page-head">
      {back && <button className="icon-btn" onClick={back} aria-label="Back"><Icon name="back" /></button>}
      <div className="grow" style={{ minWidth: 160 }}>
        <h1 style={{ margin: 0 }}>{title}</h1>
        {sub && <div className="sub">{sub}</div>}
      </div>
      {children}
    </div>
  )
}

export function SearchBox({ value, onChange, placeholder, autoFocus, onEnter, testId }: {
  value: string
  onChange: (v: string) => void
  placeholder?: string
  autoFocus?: boolean
  onEnter?: () => void
  testId?: string
}) {
  return (
    <div className="search">
      <Icon name="search" size={18} />
      <input value={value} placeholder={placeholder ?? 'Search'} autoFocus={autoFocus} data-testid={testId}
        onChange={e => onChange(e.target.value)} onKeyDown={e => { if (e.key === 'Enter' && onEnter) { e.preventDefault(); onEnter() } }} />
      {value && <button className="icon-btn" style={{ width: 28, height: 28 }} onClick={() => onChange('')} aria-label="Clear"><Icon name="close" size={16} /></button>}
    </div>
  )
}

const STATUS_PILL: Record<string, string> = {
  PAID: 'green', PARTIAL: 'amber', DUE: 'red', COMPLETED: 'green', CANCELLED: 'red', RETURNED: 'amber', IN_STOCK: 'green',
  SOLD: 'blue', DAMAGED: 'red', LOST: 'red', ACTIVE: 'green', EXPIRED: 'red', CLAIMED: 'amber', REPAIRED: 'blue', REPLACED: 'blue',
}

export function StatusPill({ status }: { status: string }) {
  const text = status.charAt(0) + status.slice(1).toLowerCase().replace(/_/g, ' ')
  return <span className={`pill ${STATUS_PILL[status] ?? ''}`}>{text}</span>
}

/** Pages show long lists a page at a time. */
export function useLimit(step = 60): [number, () => void, (n: number) => void] {
  const [limit, setLimit] = useState(step)
  return [limit, () => setLimit(l => l + step), setLimit]
}

export function MoreButton({ shown, total, onMore }: { shown: number; total: number; onMore: () => void }) {
  if (shown >= total) return null
  return <div style={{ textAlign: 'center', padding: 12 }}><button className="btn tonal small" onClick={onMore}>Show more ({total - shown} left)</button></div>
}
