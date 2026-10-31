import { signOut } from 'firebase/auth'
import { useEffect, useState } from 'react'
import type { User } from '../../data/schema'
import { isAppOwner, removeMember, renameShop, saveMember } from '../../data/shops'
import { useShop, type Member } from '../../data/store'
import { saveShopProfile } from '../../domain/catalog'
import { formatDate, formatDateTime } from '../../domain/dates'
import { auditRole, can, ROLE_HINTS, ROLE_LABELS, type Role } from '../../domain/roles'
import { createUser, generateSalt, hashPin, PIN_ROLE_LABELS, PIN_ROLES, resetUserPin, updateUser, validatePin } from '../../domain/users'
import { useFirebase, useSession } from '../App'
import { ConfirmDialog, ErrorText, Field, message, Modal, PageHead, Tabs, useAction, useToast } from '../common'
import { Icon } from '../icons'
import { THEMES, useTheme } from '../preferences'
import Creators from './Creators'

type Tab = 'SHOP' | 'MEMBERS' | 'USERS' | 'DEVICES' | 'CREATORS' | 'ME'

export default function Settings() {
  const data = useShop()
  const { email } = useSession()
  const tabs: [Tab, string][] = []
  if (can(data.role, 'SHOP_PROFILE')) tabs.push(['SHOP', 'Shop profile'])
  if (can(data.role, 'MEMBERS')) tabs.push(['MEMBERS', 'Members & roles'])
  if (can(data.role, 'USERS')) tabs.push(['USERS', 'PIN users'])
  if (can(data.role, 'SHOP_PROFILE')) tabs.push(['DEVICES', 'Devices'])
  if (isAppOwner(email)) tabs.push(['CREATORS', 'Shop creators'])
  tabs.push(['ME', 'Me & this browser'])
  const [tab, setTab] = useState<Tab>(tabs[0][0])
  return (
    <div className="page">
      <PageHead title="Settings" sub={data.shop?.name} />
      <Tabs options={tabs} value={tab} onChange={setTab} />
      {tab === 'SHOP' && <ShopProfileForm />}
      {tab === 'MEMBERS' && <Members />}
      {tab === 'USERS' && <PinUsers />}
      {tab === 'DEVICES' && <Devices />}
      {tab === 'CREATORS' && <Creators me={email} />}
      {tab === 'ME' && <Me />}
    </div>
  )
}

function ShopProfileForm() {
  const { state, role, shop, shopId } = useShop()
  const { db } = useFirebase()
  const p = state.profile
  const fromProfile = () => ({
    shopName: p.shopName, slogan: p.slogan, address: p.address, phone: p.phone, alternativePhone: p.alternativePhone, email: p.email,
    website: p.website, currencySymbol: p.currencySymbol, currencyCode: p.currencyCode, invoicePrefix: p.invoicePrefix,
    salesReturnPrefix: p.salesReturnPrefix, customerPaymentPrefix: p.customerPaymentPrefix, customerCodePrefix: p.customerCodePrefix,
    defaultTaxRate: String(p.defaultTaxRate), lowStockAlertThreshold: String(p.lowStockAlertThreshold),
  })
  const [f, setF] = useState(fromProfile)
  const [dirty, setDirty] = useState(false)
  // A change from another device shows up here until you start typing
  useEffect(() => { if (!dirty) setF(fromProfile()) }, [p])
  const { run, busy, error } = useAction()
  const set = (k: keyof typeof f) => (e: { target: { value: string } }) => { setDirty(true); setF({ ...f, [k]: e.target.value }) }
  const save = async () => {
    const ok = await run(tx => {
      saveShopProfile(tx, {
        ...f, shopName: f.shopName.trim(), defaultTaxRate: Number(f.defaultTaxRate) || 0, lowStockAlertThreshold: Math.trunc(Number(f.lowStockAlertThreshold) || 0),
      })
      return true
    }, 'Shop profile saved')
    if (ok) setDirty(false)
    if (ok && (role === 'OWNER' || role === 'ADMIN') && shop && shop.name !== f.shopName.trim()) {
      await renameShop(db, shopId, f.shopName).catch(() => {})
    }
  }
  return (
    <div className="card pad">
      <div className="form-grid">
        <Field label="Shop name *"><input className="input" value={f.shopName} onChange={set('shopName')} data-testid="shop-name" /></Field>
        <Field label="Slogan"><input className="input" value={f.slogan} onChange={set('slogan')} /></Field>
        <Field label="Address" full><input className="input" value={f.address} onChange={set('address')} /></Field>
        <Field label="Phone"><input className="input" value={f.phone} onChange={set('phone')} /></Field>
        <Field label="Other phone"><input className="input" value={f.alternativePhone} onChange={set('alternativePhone')} /></Field>
        <Field label="Email"><input className="input" value={f.email} onChange={set('email')} /></Field>
        <Field label="Website"><input className="input" value={f.website} onChange={set('website')} /></Field>
        <Field label="Currency symbol"><input className="input" value={f.currencySymbol} onChange={set('currencySymbol')} /></Field>
        <Field label="Currency code"><input className="input" value={f.currencyCode} onChange={set('currencyCode')} /></Field>
        <Field label="Invoice prefix" hint="Web invoices: prefix + year + W00001"><input className="input" value={f.invoicePrefix} onChange={set('invoicePrefix')} /></Field>
        <Field label="Sales return prefix"><input className="input" value={f.salesReturnPrefix} onChange={set('salesReturnPrefix')} /></Field>
        <Field label="Customer payment prefix"><input className="input" value={f.customerPaymentPrefix} onChange={set('customerPaymentPrefix')} /></Field>
        <Field label="Customer code prefix"><input className="input" value={f.customerCodePrefix} onChange={set('customerCodePrefix')} /></Field>
        <Field label="Default tax %"><input className="input num" value={f.defaultTaxRate} onChange={set('defaultTaxRate')} /></Field>
        <Field label="Low-stock alert for new products"><input className="input num" value={f.lowStockAlertThreshold} onChange={set('lowStockAlertThreshold')} /></Field>
      </div>
      <ErrorText error={error} />
      <div className="actions"><button className="btn" disabled={busy} onClick={save} data-testid="shop-save">{busy ? 'Saving…' : 'Save'}</button></div>
    </div>
  )
}

function Members() {
  const { members, role, shopId } = useShop()
  const { db } = useFirebase()
  const { email } = useSession()
  const toast = useToast()
  const [editing, setEditing] = useState<Member | 'new' | null>(null)
  const [removing, setRemoving] = useState<Member | null>(null)
  const [error, setError] = useState<string | null>(null)
  return (
    <div className="stack">
      <div className="card list">
        {members.map(m => (
          <div key={m.email} className="item" data-testid="member-row">
            <span className="avatar" style={{ width: 36, height: 36 }}>{(m.name || m.email).charAt(0).toUpperCase()}</span>
            <div className="grow"><div className="title">{m.name || m.email}{m.email === email ? ' (you)' : ''}</div><div className="subtitle">{m.email}</div></div>
            <span className={`pill ${m.active ? 'indigo' : ''}`}>{ROLE_LABELS[m.role]}{m.active ? '' : ' · inactive'}</span>
            {m.role !== 'OWNER' && <button className="icon-btn" onClick={() => setEditing(m)} aria-label="Edit"><Icon name="edit" size={18} /></button>}
            {m.role !== 'OWNER' && <button className="icon-btn" onClick={() => setRemoving(m)} aria-label="Remove"><Icon name="trash" size={18} /></button>}
          </div>
        ))}
      </div>
      <div><button className="btn" onClick={() => setEditing('new')} data-testid="add-member"><Icon name="plus" size={18} />Add member</button></div>
      <p className="small muted">Members sign in to the web with their Google account. Owners, admins and managers can also link a phone to the shop.</p>
      <ErrorText error={error} />
      {editing && <MemberDialog member={editing === 'new' ? undefined : editing} myRole={role} onClose={() => setEditing(null)} />}
      {removing && (
        <ConfirmDialog title="Remove this member?" danger confirm="Remove" text={`${removing.email} loses access to the shop.`}
          onConfirm={() => void removeMember(db, shopId, removing, email).then(() => toast('Member removed'), e => setError(message(e)))} onClose={() => setRemoving(null)} />
      )}
    </div>
  )
}

function MemberDialog({ member, myRole, onClose }: { member?: Member; myRole: Role; onClose: () => void }) {
  const { shopId } = useShop()
  const { db } = useFirebase()
  const { email: me } = useSession()
  const toast = useToast()
  const [f, setF] = useState({ email: member?.email ?? '', name: member?.name ?? '', role: (member?.role ?? 'SALESPERSON') as Role, active: member?.active ?? true })
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const roles: Role[] = ['ADMIN', 'MANAGER', 'SALESPERSON']
  void myRole
  const save = async () => {
    setError(null)
    const e = f.email.trim().toLowerCase()
    if (!/^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(e)) { setError('Type the Google email address they sign in with.'); return }
    setBusy(true)
    try {
      await saveMember(db, shopId, { email: e, name: f.name, role: f.role, active: f.active, createdAt: member?.createdAt }, member?.addedBy || me)
      toast(member ? 'Member saved' : 'Member added')
      onClose()
    } catch (err) {
      setError(message(err))
    }
    setBusy(false)
  }
  return (
    <Modal onClose={onClose} narrow label="Member">
      <h2>{member ? 'Edit member' : 'Add member'}</h2>
      <div className="stack">
        <Field label="Google email *"><input className="input" type="email" value={f.email} disabled={!!member} onChange={e => setF({ ...f, email: e.target.value })} data-testid="member-email" /></Field>
        <Field label="Name"><input className="input" value={f.name} onChange={e => setF({ ...f, name: e.target.value })} /></Field>
        <Field label="Role" hint={ROLE_HINTS[f.role]}>
          <select className="input" value={f.role} onChange={e => setF({ ...f, role: e.target.value as Role })} data-testid="member-role">
            {roles.map(r => <option key={r} value={r}>{ROLE_LABELS[r]}</option>)}
          </select>
        </Field>
        {member && <label className="row"><input type="checkbox" checked={f.active} onChange={e => setF({ ...f, active: e.target.checked })} />Active</label>}
      </div>
      <ErrorText error={error} />
      <div className="actions"><button className="btn text" onClick={onClose}>Cancel</button><button className="btn" disabled={busy} onClick={save} data-testid="member-save">Save</button></div>
    </Modal>
  )
}

function PinUsers() {
  const { state, usersDenied, role, me } = useShop()
  const { email } = useSession()
  const [editing, setEditing] = useState<User | 'new' | null>(null)
  if (usersDenied) return <div className="notice">PIN users are visible to owners, admins and managers only.</div>
  const users = state.list('users').sort((a, b) => a.name.localeCompare(b.name))
  const admin = { username: me?.name || email, role: auditRole(role) }
  return (
    <div className="stack">
      <p className="small muted" style={{ margin: 0 }}>The phones' counter logins. They sync to every phone of the shop, so the same PIN works everywhere.</p>
      <div className="card list">
        {users.map(u => (
          <div key={u.id} className="item">
            <div className="grow"><div className="title">{u.name} <span className="muted">@{u.username}</span></div><div className="subtitle">{PIN_ROLE_LABELS[u.role] ?? u.role} · since {formatDate(u.createdAt)}</div></div>
            {!u.isActive && <span className="pill">inactive</span>}
            <button className="icon-btn" onClick={() => setEditing(u)} aria-label="Edit"><Icon name="edit" size={18} /></button>
          </div>
        ))}
      </div>
      <div><button className="btn" onClick={() => setEditing('new')}><Icon name="plus" size={18} />PIN user</button></div>
      {editing && <PinUserDialog user={editing === 'new' ? undefined : editing} admin={admin} onClose={() => setEditing(null)} />}
    </div>
  )
}

function PinUserDialog({ user, admin, onClose }: { user?: User; admin: { username: string; role: string }; onClose: () => void }) {
  const [f, setF] = useState({ name: user?.name ?? '', username: user?.username ?? '', role: user?.role ?? 'SALESPERSON', active: user?.isActive ?? true, pin: '' })
  const { run, busy, error, setError } = useAction()
  const save = async () => {
    try {
      if (!user || f.pin) validatePin(f.pin)
    } catch (e) {
      setError(message(e))
      return
    }
    const salt = f.pin ? generateSalt() : ''
    const hash = f.pin ? await hashPin(f.pin, salt) : ''
    const ok = await run(tx => {
      if (!user) return createUser(tx, admin, { name: f.name, username: f.username, role: f.role, pinHash: hash, salt })
      updateUser(tx, admin, user.id, f.name, f.role, f.active)
      if (f.pin) resetUserPin(tx, admin, user.id, hash, salt)
      return user.id
    }, user ? 'User saved' : 'User added')
    if (ok !== undefined) onClose()
  }
  return (
    <Modal onClose={onClose} narrow label="PIN user">
      <h2>{user ? 'Edit PIN user' : 'New PIN user'}</h2>
      <div className="stack">
        <Field label="Name *"><input className="input" value={f.name} onChange={e => setF({ ...f, name: e.target.value })} /></Field>
        <Field label="Username *"><input className="input" value={f.username} disabled={!!user} onChange={e => setF({ ...f, username: e.target.value })} /></Field>
        <Field label="Role">
          <select className="input" value={f.role} onChange={e => setF({ ...f, role: e.target.value })}>
            {PIN_ROLES.map(r => <option key={r} value={r}>{PIN_ROLE_LABELS[r]}</option>)}
          </select>
        </Field>
        <Field label={user ? 'New PIN (leave empty to keep)' : 'PIN *'} hint="4 to 12 digits">
          <input className="input" type="password" inputMode="numeric" value={f.pin} onChange={e => setF({ ...f, pin: e.target.value.replace(/\D/g, '') })} />
        </Field>
        {user && <label className="row"><input type="checkbox" checked={f.active} onChange={e => setF({ ...f, active: e.target.checked })} />Active</label>}
      </div>
      <ErrorText error={error} />
      <div className="actions"><button className="btn text" onClick={onClose}>Cancel</button><button className="btn" disabled={busy} onClick={save}>{busy ? 'Saving…' : 'Save'}</button></div>
    </Modal>
  )
}

function Devices() {
  const { devices, counters } = useShop()
  return (
    <div className="stack">
      <div className="card list">
        {devices.map(d => (
          <div key={d.block} className="item">
            <Icon name={d.kind === 'PHONE' ? 'phone' : 'cloud'} />
            <div className="grow"><div className="title">{d.label || (d.kind === 'PHONE' ? 'Phone' : 'Browser')} <span className="muted">· till {d.tillCode}</span></div>
              <div className="subtitle">{d.email} · id block {d.block} · {formatDateTime(d.createdAt)}</div></div>
            <span className="pill">{d.kind === 'PHONE' ? 'Phone' : 'Web'}</span>
          </div>
        ))}
      </div>
      {counters && <p className="small muted">Next id block: {counters.nextBlock ?? '—'} · web invoices so far: {counters.inv ?? 0}</p>}
    </div>
  )
}

function Me() {
  const { auth } = useFirebase()
  const { email, switchShop } = useSession()
  const { role, me } = useShop()
  const [theme, setTheme] = useTheme()
  return (
    <div className="card pad stack">
      <div><b>{me?.name || email}</b><div className="muted small">{email} · {ROLE_LABELS[role]}</div></div>
      <Field label="Theme (this browser)">
        <select className="input" value={theme} onChange={e => setTheme(e.target.value as typeof theme)} style={{ maxWidth: 260 }}>
          {THEMES.map(t => <option key={t.value} value={t.value}>{t.label}</option>)}
        </select>
      </Field>
      <div className="row wrap">
        <button className="btn tonal" onClick={switchShop}><Icon name="swap" size={18} />Switch shop</button>
        <button className="btn text" onClick={() => signOut(auth)} data-testid="sign-out"><Icon name="logout" size={18} />Sign out</button>
      </div>
    </div>
  )
}
