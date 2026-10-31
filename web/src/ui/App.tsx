import {
  getRedirectResult, GoogleAuthProvider, onAuthStateChanged, signInWithCredential, signInWithPopup, signInWithRedirect, signOut,
  type User,
} from 'firebase/auth'
import { createContext, lazy, Suspense, useContext, useEffect, useState } from 'react'
import { NavLink, Navigate, Route, Routes, useLocation } from 'react-router-dom'
import { closeSession, openSession, setActor } from '../data/actions'
import { findShops, isAppOwner, lastShop, rememberShop, type ShopChoice } from '../data/shops'
import { store, useShop } from '../data/store'
import { can, ROLE_LABELS, type Feature, type Role } from '../domain/roles'
import { initFirebase, type FirebaseHandles } from '../firebase'
import { message, Spinner, ToastProvider } from './common'
import { GoogleLogo, Icon, LogoMark, type IconName } from './icons'
import { useTheme } from './preferences'

const Dashboard = lazy(() => import('./pages/Dashboard'))
const Pos = lazy(() => import('./pages/Pos'))
const Sales = lazy(() => import('./pages/Sales'))
const SaleDetail = lazy(() => import('./pages/SaleDetail'))
const Customers = lazy(() => import('./pages/Customers'))
const CustomerDetail = lazy(() => import('./pages/CustomerDetail'))
const Products = lazy(() => import('./pages/Products'))
const ProductDetail = lazy(() => import('./pages/ProductDetail'))
const Catalog = lazy(() => import('./pages/Catalog'))
const Purchases = lazy(() => import('./pages/Purchases'))
const PurchaseEntry = lazy(() => import('./pages/PurchaseEntry'))
const PurchaseDetail = lazy(() => import('./pages/PurchaseDetail'))
const Suppliers = lazy(() => import('./pages/Suppliers'))
const SupplierDetail = lazy(() => import('./pages/SupplierDetail'))
const Expenses = lazy(() => import('./pages/Expenses'))
const CashBook = lazy(() => import('./pages/CashBook'))
const Reports = lazy(() => import('./pages/Reports'))
const Warranty = lazy(() => import('./pages/Warranty'))
const Settings = lazy(() => import('./pages/Settings'))
const Creators = lazy(() => import('./pages/Creators'))
const More = lazy(() => import('./pages/More'))
const PrintInvoice = lazy(() => import('./print/Invoice'))

const FirebaseContext = createContext<FirebaseHandles | null>(null)

export function useFirebase(): FirebaseHandles {
  const value = useContext(FirebaseContext)
  if (!value) throw new Error('Firebase is not ready')
  return value
}

/** The signed-in person and how to leave the current shop. */
const SessionContext = createContext<{ user: User; email: string; switchShop: () => void } | null>(null)
export function useSession() {
  const value = useContext(SessionContext)
  if (!value) throw new Error('No session')
  return value
}

export function App() {
  useTheme()
  const [firebase, setFirebase] = useState<FirebaseHandles | null>(null)
  const [error, setError] = useState<string | null>(null)
  useEffect(() => {
    initFirebase().then(setFirebase, e => setError(message(e)))
  }, [])
  if (error) return <div className="center-screen"><div className="signin"><h1>ElectroPOS</h1><p>{error}</p></div></div>
  if (!firebase) return <Spinner />
  return (
    <FirebaseContext.Provider value={firebase}>
      <ToastProvider>
        <AuthGate />
      </ToastProvider>
    </FirebaseContext.Provider>
  )
}

const emailOf = (user: User) => (user.email ?? '').toLowerCase()

function AuthGate() {
  const { auth } = useFirebase()
  const [user, setUser] = useState<User | null | undefined>(undefined)
  useEffect(() => onAuthStateChanged(auth, setUser), [auth])
  if (user === undefined) return <Spinner />
  if (!user) return <SignIn />
  return <ShopGate key={user.uid} user={user} />
}

function SignIn() {
  const { auth, emulator } = useFirebase()
  const [error, setError] = useState<string | null>(null)
  const [testEmail, setTestEmail] = useState('owner@electropos.test')
  useEffect(() => {
    getRedirectResult(auth).catch(e => setError(message(e)))
  }, [auth])
  const signIn = async () => {
    setError(null)
    const provider = new GoogleAuthProvider()
    provider.setCustomParameters({ prompt: 'select_account' })
    try {
      await signInWithPopup(auth, provider)
    } catch (e) {
      const code = (e as { code?: string }).code
      if (code === 'auth/popup-blocked' || code === 'auth/operation-not-supported-in-this-environment') await signInWithRedirect(auth, provider)
      else if (code !== 'auth/popup-closed-by-user' && code !== 'auth/cancelled-popup-request') setError(message(e))
    }
  }
  const testSignIn = () => {
    const email = testEmail.trim().toLowerCase()
    // The auth emulator accepts an unsigned Google token, so tests sign in without a popup.
    signInWithCredential(auth, GoogleAuthProvider.credential(JSON.stringify({
      sub: `test-${email}`, email, email_verified: true, name: email.split('@')[0],
    }))).catch(e => setError(message(e)))
  }
  return (
    <div className="center-screen">
      <div className="signin">
        <LogoMark size={72} />
        <h1>ElectroPOS</h1>
        <p>Sell, buy, track stock, dues and cash for your shop — the same data as the ElectroPOS app on your phone.</p>
        <button className="google-btn" onClick={signIn}><GoogleLogo />Continue with Google</button>
        {emulator && (
          <div className="card pad" style={{ marginTop: 18, textAlign: 'left' }}>
            <div className="small bold" style={{ marginBottom: 8 }}>Test mode (local Firebase emulator)</div>
            <div className="row">
              <input className="input" value={testEmail} onChange={e => setTestEmail(e.target.value)} data-testid="test-email" />
              <button className="btn" onClick={testSignIn} data-testid="test-signin">Sign in</button>
            </div>
          </div>
        )}
        {error && <p className="error-text small" style={{ marginTop: 16 }}>{error}</p>}
        <p className="small" style={{ marginTop: 26 }}>Staff sign in with the Google account their shop's owner added.</p>
      </div>
    </div>
  )
}

function ShopGate({ user }: { user: User }) {
  const { db, auth } = useFirebase()
  const email = emailOf(user)
  const [choices, setChoices] = useState<ShopChoice[] | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [current, setCurrent] = useState<{ id: string; role: Role } | null>(null)
  const [reload, setReload] = useState(0)
  const [creators, setCreators] = useState(false)
  const appOwner = isAppOwner(email)

  useEffect(() => {
    let live = true
    setError(null)
    findShops(db, email).then(list => {
      if (!live) return
      setChoices(list)
      const last = lastShop(email)
      const pick = list.find(c => c.id === last) ?? (list.length === 1 ? list[0] : undefined)
      if (pick && reload === 0) setCurrent({ id: pick.id, role: pick.role })
    }, e => live && setError(message(e)))
    return () => { live = false }
  }, [db, email, reload])

  useEffect(() => {
    if (!current) return
    store.start(db, current.id, email, current.role)
    openSession(db, current.id, email, user.displayName ?? '')
    rememberShop(email, current.id)
    return () => {
      closeSession()
      store.stop()
    }
  }, [db, email, current, user.displayName])

  const switchShop = () => {
    rememberShop(email, null)
    setCurrent(null)
    setReload(n => n + 1)
  }

  if (current) {
    return (
      <SessionContext.Provider value={{ user, email, switchShop }}>
        <Shell />
      </SessionContext.Provider>
    )
  }
  if (error) {
    return (
      <div className="center-screen"><div className="signin">
        <h1>Can't reach ElectroPOS</h1><p>{error}</p>
        <button className="btn" onClick={() => setReload(n => n + 1)}>Try again</button>{' '}
        <button className="btn text" onClick={() => signOut(auth)}>Sign out</button>
      </div></div>
    )
  }
  if (!choices) return <Spinner />
  if (creators) {
    return (
      <div className="page" style={{ maxWidth: 760 }}>
        <Suspense fallback={<Spinner />}><Creators me={email} onBack={() => setCreators(false)} /></Suspense>
      </div>
    )
  }
  return (
    <div className="center-screen">
      <div className="picker stack">
        <div className="row"><LogoMark /><h1 style={{ margin: 0, fontSize: 24 }} className="grow">ElectroPOS</h1>
          <button className="btn text" onClick={() => signOut(auth)}><Icon name="logout" size={18} />Sign out</button></div>
        <div className="muted small">Signed in as {email}</div>
        {choices.length > 0 && (
          <div className="card list">
            <div className="card-head"><h2>Your shops</h2></div>
            {choices.map(c => (
              <button key={c.id} className="item" onClick={() => setCurrent({ id: c.id, role: c.role })} data-testid="shop-choice">
                <LogoMark size={36} />
                <div className="grow"><div className="title">{c.name}</div></div>
                <span className="pill indigo">{ROLE_LABELS[c.role]}</span>
              </button>
            ))}
          </div>
        )}
        {choices.length === 0 && (
          <div className="card pad stack" data-testid="no-shop">
            <h2 style={{ margin: 0 }}>No shop yet</h2>
            <p className="muted" style={{ margin: 0 }}>
              A shop is put online from the <b>ElectroPOS app on the shop's phone</b>: open <b>Cloud</b>, sign in with Google and
              tap <b>Put this shop online</b>. The shop's data then appears here.
            </p>
            <p className="muted" style={{ margin: 0 }}>
              If your shop is already online, ask its owner to add <b>{email}</b> under Cloud → Members (or Settings → Members & roles
              on the web), then <button className="btn text small" onClick={() => setReload(n => n + 1)}>check again</button>.
            </p>
          </div>
        )}
        {appOwner && (
          <button className="btn tonal" onClick={() => setCreators(true)} data-testid="open-creators">
            <Icon name="key" size={18} />Shop creators
          </button>
        )}
      </div>
    </div>
  )
}

interface NavItem { to: string; label: string; icon: IconName; feature?: Feature; group?: string }

export const NAV: NavItem[] = [
  { to: '/', label: 'Dashboard', icon: 'home' },
  { to: '/pos', label: 'POS', icon: 'pos', feature: 'POS' },
  { to: '/sales', label: 'Sales', icon: 'sales', feature: 'SALES_HISTORY' },
  { to: '/customers', label: 'Customers', icon: 'customers', feature: 'CUSTOMERS' },
  { to: '/products', label: 'Products', icon: 'products', feature: 'PRODUCTS_VIEW', group: 'Stock' },
  { to: '/catalog', label: 'Categories & brands', icon: 'tag', feature: 'CATEGORIES_BRANDS' },
  { to: '/purchases', label: 'Purchases', icon: 'purchases', feature: 'PURCHASES' },
  { to: '/suppliers', label: 'Suppliers', icon: 'suppliers', feature: 'SUPPLIERS' },
  { to: '/expenses', label: 'Expenses', icon: 'expenses', feature: 'EXPENSES', group: 'Money' },
  { to: '/cash', label: 'Cash book', icon: 'cash', feature: 'CASH_BOOK' },
  { to: '/reports', label: 'Reports', icon: 'reports', feature: 'REPORTS' },
  { to: '/warranty', label: 'Warranty', icon: 'warranty', feature: 'WARRANTY' },
  { to: '/settings', label: 'Settings', icon: 'settings', feature: 'SETTINGS', group: 'Shop' },
]

function Shell() {
  const data = useShop()
  const { email, switchShop } = useSession()
  const location = useLocation()
  const ok = (f?: Feature) => !f || can(data.role, f)
  const nav = NAV.filter(n => ok(n.feature))
  const bottom: NavItem[] = [NAV[0], NAV[1], NAV[2], NAV[4], { to: '/more', label: 'More', icon: 'more' }]
  const myName = data.me?.name ?? ''
  useEffect(() => setActor(myName), [myName])
  const syncLabel = data.error && !data.revoked ? 'Sync problem' : data.pending > 0 ? 'Saving…' : data.synced ? 'Synced' : 'Connecting…'
  const syncColor = data.error ? 'var(--error)' : data.pending > 0 || !data.synced ? 'var(--warning)' : 'var(--success)'

  if (data.revoked) {
    return (
      <div className="center-screen"><div className="signin">
        <LogoMark size={64} />
        <h1>No access</h1>
        <p>{email} is no longer an active member of this shop. Ask the owner to add you again.</p>
        <button className="btn" onClick={switchShop}>Choose another shop</button>
      </div></div>
    )
  }
  const loading = !data.ready || !data.shop
  if (location.pathname.startsWith('/print/')) {
    return loading ? <Spinner /> : (
      <Suspense fallback={<Spinner />}><Routes><Route path="/print/invoice/:id" element={<PrintInvoice />} /></Routes></Suspense>
    )
  }

  return (
    <div className="app">
      <nav className="sidebar" aria-label="Main">
        <div className="brand"><LogoMark />ElectroPOS</div>
        <div className="brand-center" title={data.shop?.name}>{data.shop?.name ?? '…'} · {ROLE_LABELS[data.role]}</div>
        {nav.map((n, i) => (
          <div key={n.to}>
            {n.group && nav.findIndex(x => x.group === n.group) === i && <div className="nav-group">{n.group}</div>}
            <NavLink to={n.to} end={n.to === '/'} className={({ isActive }) => `nav-link ${isActive ? 'active' : ''}`}>
              <Icon name={n.icon} size={20} />{n.label}
            </NavLink>
          </div>
        ))}
        <div className="sidebar-foot">
          <span className="sync-dot" style={{ background: syncColor }} /> {syncLabel}
          <div className="ellipsis">{email}</div>
        </div>
      </nav>

      <main className="main">
        {data.error && !data.revoked && (
          <div className="notice err small" style={{ margin: '12px 16px 0' }}>
            {data.error} <button className="btn text small" onClick={() => store.clearError()}>Dismiss</button>
          </div>
        )}
        {loading ? (
          <div className="center-screen" style={{ minHeight: '60vh' }}>
            <div className="stack" style={{ alignItems: 'center' }}>
              <div className="spinner" aria-label="Loading" />
              <div className="muted small">Loading the shop… {data.loadedCount}/{data.tableCount}</div>
            </div>
          </div>
        ) : (
          <Suspense fallback={<Spinner />}>
            <Routes>
              <Route path="/" element={<Dashboard />} />
              {ok('POS') && <Route path="/pos" element={<Pos />} />}
              {ok('SALES_HISTORY') && <Route path="/sales" element={<Sales />} />}
              {ok('SALES_HISTORY') && <Route path="/sales/:id" element={<SaleDetail />} />}
              {ok('CUSTOMERS') && <Route path="/customers" element={<Customers />} />}
              {ok('CUSTOMERS') && <Route path="/customers/:id" element={<CustomerDetail />} />}
              {ok('PRODUCTS_VIEW') && <Route path="/products" element={<Products />} />}
              {ok('PRODUCTS_VIEW') && <Route path="/products/:id" element={<ProductDetail />} />}
              {ok('CATEGORIES_BRANDS') && <Route path="/catalog" element={<Catalog />} />}
              {ok('PURCHASES') && <Route path="/purchases" element={<Purchases />} />}
              {ok('PURCHASES') && <Route path="/purchases/new" element={<PurchaseEntry />} />}
              {ok('PURCHASES') && <Route path="/purchases/:id" element={<PurchaseDetail />} />}
              {ok('SUPPLIERS') && <Route path="/suppliers" element={<Suppliers />} />}
              {ok('SUPPLIERS') && <Route path="/suppliers/:id" element={<SupplierDetail />} />}
              {ok('EXPENSES') && <Route path="/expenses" element={<Expenses />} />}
              {ok('CASH_BOOK') && <Route path="/cash" element={<CashBook />} />}
              {ok('REPORTS') && <Route path="/reports" element={<Reports />} />}
              {ok('WARRANTY') && <Route path="/warranty" element={<Warranty />} />}
              <Route path="/settings" element={<Settings />} />
              <Route path="/more" element={<More />} />
              <Route path="*" element={<Navigate to="/" replace />} />
            </Routes>
          </Suspense>
        )}
      </main>

      <nav className="bottom-nav" aria-label="Main">
        {bottom.filter(n => ok(n.feature)).map(n => (
          <NavLink key={n.to} to={n.to} end={n.to === '/'} className={({ isActive }) => (isActive ? 'active' : '')}>
            <span className="navpill"><Icon name={n.icon} /></span>{n.label}
          </NavLink>
        ))}
      </nav>
    </div>
  )
}
