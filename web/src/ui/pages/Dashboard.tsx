import { useMemo } from 'react'
import { useNavigate } from 'react-router-dom'
import { useShop } from '../../data/store'
import { endOfDay, formatDateTime, startOfDay } from '../../domain/dates'
import { methodLabel } from '../../domain/money'
import { stockSummary } from '../../domain/reports'
import { balancesByMethod } from '../../domain/finance'
import { Empty, PageHead, StatusPill, Tile, useCan, useMoney, useNow } from '../common'
import { Icon } from '../icons'

/** DashboardRepository: today's figures net of today's returns, dues, stock. */
export default function Dashboard() {
  const { state, shop } = useShop()
  const money = useMoney()
  const allowed = useCan()
  const navigate = useNavigate()
  const now = useNow()
  const costs = allowed('VIEW_COSTS_AND_PROFIT')

  const d = useMemo(() => {
    const start = startOfDay(now)
    const end = endOfDay(now)
    const within = (t: number) => t >= start && t <= end
    const sales = state.list('sales').filter(s => within(s.saleDate) && s.status !== 'CANCELLED' && s.status !== 'VOID')
    const returns = state.list('sales_returns').filter(r => within(r.returnDate) && r.status === 'COMPLETED')
    let returnedProfit = 0
    for (const r of returns) {
      for (const i of state.where('sales_return_items', 'salesReturnId', r.id)) {
        returnedProfit += i.totalPrice - (i.restockOption === 'RESTOCK_SELLABLE' ? i.unitCostAtSale * i.quantity : 0)
      }
      returnedProfit -= r.taxAmount
    }
    const todaySales = sales.reduce((a, s) => a + s.totalAmount, 0) - returns.reduce((a, r) => a + r.totalAmount, 0)
    const todayProfit = sales.reduce((a, s) => a + s.totalProfit, 0) - returnedProfit
    const purchases = state.list('purchases').filter(p => within(p.purchaseDate) && p.status !== 'CANCELLED').reduce((a, p) => a + p.totalAmount, 0)
    const expenses = state.list('expenses').filter(e => within(e.expenseDate)).reduce((a, e) => a + e.amount, 0)
    const customerDue = state.list('customers').filter(c => c.isActive).reduce((a, c) => a + c.currentDue, 0)
    const supplierDue = state.list('suppliers').filter(s => s.isActive).reduce((a, s) => a + s.currentDue, 0)
    const stock = stockSummary(state)
    const low = state.list('products').filter(p => p.isActive && p.currentStock <= p.minStock).sort((a, b) => a.currentStock - b.currentStock).slice(0, 8)
    const recent = [...state.list('sales')].sort((a, b) => b.saleDate - a.saleDate || b.id - a.id).slice(0, 8)
    const cash = balancesByMethod(state).filter(b => Math.abs(b.balance) > 0.004)
    const returned = returns.reduce((a, r) => a + r.totalAmount, 0)
    return { todaySales, todayProfit, returned, count: sales.length, purchases, expenses, customerDue, supplierDue, stock, low, recent, cash }
  }, [state, now])

  return (
    <div className="page">
      <PageHead title={shop?.name ?? 'Dashboard'} sub={state.profile.slogan}>
        {allowed('POS') && <button className="btn" onClick={() => navigate('/pos')} data-testid="new-sale"><Icon name="pos" size={18} />New sale</button>}
      </PageHead>
      <div className="hero" style={{ marginBottom: 16 }}>
        <div className="label">Today's sales</div>
        <div className="big num" data-testid="today-sales">{money.format(d.todaySales)}</div>
        <div className="stats">
          <div><div className="k">Invoices</div><div className="v">{d.count}</div></div>
          {costs ? <div><div className="k">Profit</div><div className="v">{money.format(d.todayProfit)}</div></div>
            : <div><div className="k">Returned</div><div className="v">{money.format(d.returned)}</div></div>}
          {costs && <div><div className="k">Expenses</div><div className="v">{money.format(d.expenses)}</div></div>}
        </div>
      </div>
      <div className="tiles" style={{ marginBottom: 16 }}>
        <Tile label="Customers owe" value={money.format(d.customerDue)} onClick={() => navigate('/customers')} />
        {allowed('SUPPLIERS') && <Tile label="You owe suppliers" value={money.format(d.supplierDue)} onClick={() => navigate('/suppliers')} />}
        {allowed('PURCHASES') && <Tile label="Purchases today" value={money.format(d.purchases)} onClick={() => navigate('/purchases')} />}
        {costs && <Tile label="Stock value (cost)" value={money.format(d.stock.stockValue)} sub={`${d.stock.productCount} products`} />}
        <Tile label="Low stock" value={String(d.stock.lowStockCount)} className={d.stock.lowStockCount ? 'warning' : ''} onClick={() => navigate('/products?filter=low')} />
        <Tile label="Out of stock" value={String(d.stock.outOfStockCount)} className={d.stock.outOfStockCount ? 'danger' : ''} onClick={() => navigate('/products?filter=out')} />
      </div>
      <div className="grid-2">
        <div className="card list">
          <div className="card-head"><h2>Recent sales</h2>{allowed('SALES_HISTORY') && <button className="btn text small" onClick={() => navigate('/sales')}>All sales</button>}</div>
          {d.recent.length === 0 && <Empty emoji="🧾" title="No sales yet" body="Sales made here or on the phone appear in this list." />}
          {d.recent.map(s => (
            <button key={s.id} className="item" onClick={() => navigate(`/sales/${s.id}`)}>
              <div className="grow">
                <div className="title">{s.invoiceNumber}</div>
                <div className="subtitle">{s.customerName} · {formatDateTime(s.saleDate)}</div>
              </div>
              <div className="end"><div className="bold num">{money.format(s.totalAmount)}</div><StatusPill status={s.status === 'CANCELLED' ? 'CANCELLED' : s.paymentStatus} /></div>
            </button>
          ))}
        </div>
        <div className="stack">
          <div className="card list">
            <div className="card-head"><h2>Low stock</h2></div>
            {d.low.length === 0 && <div className="pad muted small" style={{ padding: '4px 16px 14px' }}>Everything is above its alert level.</div>}
            {d.low.map(p => (
              <button key={p.id} className="item" onClick={() => navigate(`/products/${p.id}`)}>
                <div className="grow"><div className="title">{p.name}</div><div className="subtitle">Alert at {p.minStock}</div></div>
                <span className={`pill ${p.currentStock <= 0 ? 'red' : 'amber'}`}>{p.currentStock} left</span>
              </button>
            ))}
          </div>
          {allowed('CASH_BOOK') && d.cash.length > 0 && (
            <div className="card pad">
              <h2>Money in hand</h2>
              {d.cash.map(b => (
                <div key={b.method} className="k-v"><span className="k">{b.method === 'CASH' ? 'Cash in drawer' : methodLabel(b.method)}</span><b className="num">{money.format(b.balance)}</b></div>
              ))}
            </div>
          )}
        </div>
      </div>
    </div>
  )
}
