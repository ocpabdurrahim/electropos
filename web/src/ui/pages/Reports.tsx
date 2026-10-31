import { useMemo, useState } from 'react'
import { useShop } from '../../data/store'
import { dateInput, fromDateInput } from '../../domain/dates'
import {
  buildCsv, csvBlob, customRange, forPeriod, loadReport, localeFirstDay, PERIOD_LABELS, rangeDateLabel, suggestedFileName, type ReportPeriod,
} from '../../domain/reports'
import { Chips, PageHead, Tile, useMoney } from '../common'
import { Icon } from '../icons'

const PERIODS = Object.keys(PERIOD_LABELS) as ReportPeriod[]

export default function Reports() {
  const { state } = useShop()
  const money = useMoney()
  const [period, setPeriod] = useState<ReportPeriod>('THIS_MONTH')
  const [from, setFrom] = useState(dateInput(Date.now()))
  const [to, setTo] = useState(dateInput(Date.now()))
  const range = useMemo(() => (period === 'CUSTOM'
    ? customRange(fromDateInput(from) ?? Date.now(), fromDateInput(to) ?? Date.now())
    : forPeriod(period, Date.now(), undefined, undefined, localeFirstDay())), [period, from, to])
  const r = useMemo(() => loadReport(state, range), [state, range])
  const max = Math.max(1, ...r.chart.map(c => Math.max(c.sales, c.profit, 0)))

  const download = () => {
    const url = URL.createObjectURL(csvBlob(buildCsv(r)))
    const a = document.createElement('a')
    a.href = url
    a.download = suggestedFileName(range)
    a.click()
    setTimeout(() => URL.revokeObjectURL(url), 1000)
  }

  return (
    <div className="page">
      <PageHead title="Reports" sub={`${PERIOD_LABELS[range.period]} · ${rangeDateLabel(range)}`}>
        <button className="btn tonal" onClick={download} data-testid="report-csv"><Icon name="download" size={18} />CSV</button>
      </PageHead>
      <div className="toolbar">
        <Chips options={PERIODS.map(p => [p, PERIOD_LABELS[p]] as [ReportPeriod, string])} value={period} onChange={setPeriod} />
        {period === 'CUSTOM' && <>
          <input className="input" type="date" value={from} onChange={e => setFrom(e.target.value)} />
          <span className="muted">to</span>
          <input className="input" type="date" value={to} onChange={e => setTo(e.target.value)} />
        </>}
      </div>
      <div className="tiles" style={{ marginBottom: 16 }}>
        <Tile label="Net sales" value={money.format(r.sales.netSales)} sub={`${r.sales.invoiceCount} invoices`} />
        <Tile label="Gross profit" value={money.format(r.profit.grossProfit)} />
        <Tile label="Expenses" value={money.format(r.profit.expenses)} />
        <Tile label="Net profit" value={money.format(r.profit.netProfit)} className={r.profit.netProfit < 0 ? 'danger' : 'success'} />
        <Tile label="Money received" value={money.format(r.sales.moneyReceived)} />
        <Tile label="Purchased" value={money.format(r.purchases.purchased)} sub={`${r.purchases.purchaseCount} purchases`} />
      </div>
      {r.chart.length > 0 && (
        <div className="card pad" style={{ marginBottom: 16 }}>
          <div className="row" style={{ marginBottom: 10 }}>
            <h2 className="grow" style={{ margin: 0 }}>Sales and profit</h2>
            <span className="legend"><i style={{ background: 'var(--primary)' }} />Net sales</span>
            <span className="legend"><i style={{ background: 'var(--success)' }} />Gross profit</span>
          </div>
          <div className="bars" style={{ overflowX: 'auto' }}>
            {r.chart.map(c => (
              <div key={c.start} className="group" title={`${c.fullLabel}: ${money.format(c.sales)} / ${money.format(c.profit)}`}>
                <div className="cols">
                  <div className="col" style={{ height: `${(Math.max(c.sales, 0) / max) * 100}%`, background: 'var(--primary)' }} />
                  <div className="col" style={{ height: `${(Math.max(c.profit, 0) / max) * 100}%`, background: 'var(--success)' }} />
                </div>
                <span className="tiny muted">{c.label}</span>
              </div>
            ))}
          </div>
        </div>
      )}
      <div className="grid-2">
        <div className="card pad">
          <h2>Sales</h2>
          <Row k="Gross sales (incl. tax)" v={money.format(r.sales.grossSales)} />
          <Row k={`Returns (${r.sales.returnCount})`} v={`−${money.format(r.sales.returns)}`} />
          <Row k="Net sales" v={money.format(r.sales.netSales)} bold />
          <Row k="Discounts given" v={money.format(r.sales.discounts)} />
          <Row k="Tax collected (after returns)" v={money.format(r.sales.taxCollected)} />
          <Row k="Due created" v={money.format(r.sales.dueCreated)} />
          <Row k="Still unpaid on these invoices" v={money.format(r.sales.stillUnpaid)} />
          <h2 style={{ marginTop: 16 }}>Money received</h2>
          {r.sales.receivedByMethod.map(m => <Row key={m.label} k={m.label} v={money.format(m.amount)} />)}
          <Row k="Total received" v={money.format(r.sales.moneyReceived)} bold />
          <Row k="Refunded to customers" v={`−${money.format(r.sales.refundsToCustomers)}`} />
          <Row k="Net money in" v={money.format(r.sales.netMoneyIn)} bold />
        </div>
        <div className="card pad">
          <h2>Profit</h2>
          <Row k="Profit on sales (excl. tax)" v={money.format(r.profit.saleProfit)} />
          <Row k="Given back by returns" v={`−${money.format(r.profit.returnedProfit)}`} />
          <Row k="Gross profit" v={money.format(r.profit.grossProfit)} bold />
          {r.profit.expensesByCategory.map(e => <Row key={e.label} k={e.label} v={`−${money.format(e.amount)}`} />)}
          <Row k="Net profit" v={money.format(r.profit.netProfit)} bold />
          <h2 style={{ marginTop: 16 }}>Purchases</h2>
          <Row k="Total purchased" v={money.format(r.purchases.purchased)} />
          <Row k="Purchase returns" v={money.format(r.purchases.returns)} />
          <Row k="Paid" v={money.format(r.purchases.paid)} />
          <Row k="Still owed" v={money.format(r.purchases.owed)} />
          <h2 style={{ marginTop: 16 }}>Now</h2>
          <Row k="Customers owe the shop" v={money.format(r.dues.customersOwe)} />
          <Row k="Shop owes suppliers" v={money.format(r.dues.youOweSuppliers)} />
          <Row k="Stock value at cost" v={money.format(r.stock.stockValue)} />
          <Row k="Low / out of stock" v={`${r.stock.lowStockCount} / ${r.stock.outOfStockCount}`} />
        </div>
        <div className="card list">
          <div className="card-head"><h2>Best sellers</h2></div>
          {r.topByQuantity.length === 0 && <div className="muted small" style={{ padding: '0 16px 14px' }}>No sales in this period.</div>}
          {r.topByQuantity.map(p => (
            <div key={p.productId} className="item"><div className="grow"><div className="title">{p.name}</div><div className="subtitle">{money.format(p.netSales)}</div></div><b>{p.quantity}</b></div>
          ))}
        </div>
        <div className="card list">
          <div className="card-head"><h2>Most profit</h2></div>
          {r.topByProfit.map(p => (
            <div key={p.productId} className="item"><div className="grow"><div className="title">{p.name}</div><div className="subtitle">{p.quantity} sold</div></div><b className="num">{money.format(p.profit)}</b></div>
          ))}
        </div>
      </div>
      <div className="card table-wrap" style={{ marginTop: 16 }}>
        <table className="table">
          <thead><tr><th>Date</th><th className="r">Invoices</th><th className="r">Net sales</th><th className="r">Gross profit</th><th className="r">Expenses</th><th className="r">Net profit</th></tr></thead>
          <tbody>
            {r.daily.filter(d => d.invoices || d.returns || d.expenses).map(d => (
              <tr key={d.dayStart}>
                <td>{new Date(d.dayStart).toLocaleDateString('en-GB', { day: '2-digit', month: 'short', year: 'numeric' })}</td>
                <td className="r">{d.invoices}</td><td className="r num">{money.plain(d.netSales)}</td><td className="r num">{money.plain(d.grossProfit)}</td>
                <td className="r num">{money.plain(d.expenses)}</td><td className="r num">{money.plain(d.netProfit)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  )
}

function Row({ k, v, bold = false }: { k: string; v: string; bold?: boolean }) {
  return <div className={`k-v ${bold ? 'bold' : ''}`}><span className="k">{k}</span><span className="num">{v}</span></div>
}
