// Mirrors ReportsTest.kt.
import { describe, expect, it } from 'vitest'
import { recordCustomerPayment } from './customers'
import { localDate } from './dates'
import { buildChart, buildCsv, buildDailyRows, customRange, dayStarts, forPeriod, loadReport } from './reports'
import { cancelSale } from './sales'
import { createSalesReturn } from './salesReturns'
import { buy, customer, product, sell, supplier, TestShop } from './testkit'

const at = localDate
const day = (y: number, m: number, d: number) => forPeriod('TODAY', at(y, m, d, 13))

function returnOne(shop: TestShop, saleId: number, productId: number, date: number, restock = 'RESTOCK_SELLABLE') {
  const item = shop.state.where('sale_items', 'saleId', saleId)[0]
  return shop.run(tx => createSalesReturn(tx, { saleId, returnDate: date }, [{
    saleItemId: item.id, productId, productName: item.productName, returnQuantity: 1, restockOption: restock,
  }]))
}

const expense = (shop: TestShop, category: string, amount: number, date: number) =>
  shop.insert('expenses', { categoryName: category, title: category, amount, expenseDate: date })

describe('reports', () => {
  it('net profit covers tax, discount, a return and expenses', () => {
    const shop = new TestShop()
    const s = supplier(shop)
    const speaker = product(shop, 'BT speaker', { price: 1000 })
    const cable = product(shop, 'USB cable', { price: 500 })
    buy(shop, s, speaker, 10, 600, { paid: 6000, date: at(2026, 10, 15, 9) })
    buy(shop, s, cable, 10, 200, { paid: 0, date: at(2026, 10, 15, 9) })
    const rahim = customer(shop, 'Rahim', '01811000001')
    const first = sell(shop, speaker, 2, 1000, { lineDiscount: 100, lineTax: 150, invoiceDiscount: 50, date: at(2026, 10, 15, 11) })
    sell(shop, cable, 3, 500, { customerId: rahim, paid: 500, date: at(2026, 10, 15, 12) })
    returnOne(shop, first, speaker, at(2026, 10, 15, 15))
    expense(shop, 'Rent', 300, at(2026, 10, 15, 18))
    expense(shop, 'Electricity', 100, at(2026, 10, 15, 19))

    const report = loadReport(shop.state, day(2026, 10, 15))
    const sales = report.sales
    expect(sales.invoiceCount).toBe(2)
    expect(sales.grossSales).toBeCloseTo(3500, 2)
    expect(sales.returnCount).toBe(1)
    expect(sales.returns).toBeCloseTo(1000, 2)
    expect(sales.netSales).toBeCloseTo(2500, 2)
    expect(sales.discounts).toBeCloseTo(150, 2)
    expect(sales.taxOnSales).toBeCloseTo(150, 2)
    expect(sales.taxReturned).toBeCloseTo(73.17, 2)
    expect(sales.taxCollected).toBeCloseTo(76.83, 2)
    expect(sales.dueCreated).toBeCloseTo(1000, 2)
    expect(sales.stillUnpaid).toBeCloseTo(1000, 2)
    expect(sales.refundsToCustomers).toBeCloseTo(1000, 2)

    const profit = report.profit
    expect(profit.saleProfit).toBeCloseTo(1550, 2)
    expect(profit.returnedProfit).toBeCloseTo(326.83, 2)
    expect(profit.grossProfit).toBeCloseTo(1223.17, 2)
    expect(profit.expenses).toBeCloseTo(400, 2)
    expect(profit.netProfit).toBeCloseTo(823.17, 2)
    expect(profit.expensesByCategory.map(e => e.label)).toEqual(['Rent', 'Electricity'])

    expect(report.purchases.purchaseCount).toBe(2)
    expect(report.purchases.purchased).toBeCloseTo(8000, 2)
    expect(report.purchases.paid).toBeCloseTo(6000, 2)
    expect(report.purchases.owed).toBeCloseTo(2000, 2)

    const [row] = report.daily
    expect(report.daily).toHaveLength(1)
    expect(row.invoices).toBe(2)
    expect(row.netSales).toBeCloseTo(2500, 2)
    expect(row.tax).toBeCloseTo(76.83, 2)
    expect(row.grossProfit).toBeCloseTo(1223.17, 2)
    expect(row.netProfit).toBeCloseTo(823.17, 2)
    expect(report.chart).toHaveLength(0)

    expect(report.topByQuantity.map(p => [p.name, p.quantity])).toEqual([['USB cable', 3], ['BT speaker', 1]])
    expect(report.topByProfit.map(p => p.name)).toEqual(['USB cable', 'BT speaker'])
    const speakerRow = report.topByProfit.find(p => p.productId === speaker)!
    expect(speakerRow.netSales).toBeCloseTo(1000, 2)
    expect(speakerRow.profit).toBeCloseTo(650 - 326.83, 2)
    expect(report.topByProfit.find(p => p.productId === cable)!.profit).toBeCloseTo(900, 2)
  })

  it('a damaged return gives back the whole value excl. tax', () => {
    const shop = new TestShop()
    const s = supplier(shop)
    const charger = product(shop, 'Charger', { price: 1000 })
    buy(shop, s, charger, 5, 600, { paid: 3000, date: at(2026, 5, 2, 9) })
    const saleId = sell(shop, charger, 1, 1000, { date: at(2026, 5, 2, 10) })
    returnOne(shop, saleId, charger, at(2026, 5, 2, 11), 'DEFECTIVE_DAMAGED')
    const report = loadReport(shop.state, day(2026, 5, 2))
    expect(report.profit.returnedProfit).toBeCloseTo(1000, 2)
    expect(report.profit.grossProfit).toBeCloseTo(-600, 2)
    expect(report.sales.netSales).toBeCloseTo(0, 2)
    expect(report.topByProfit[0].profit).toBeCloseTo(-600, 2)
    expect(report.topByQuantity).toHaveLength(0)
  })

  it('a cancelled sale is left out', () => {
    const shop = new TestShop()
    const s = supplier(shop)
    const ssd = product(shop, 'SSD 512GB', { price: 1000 })
    buy(shop, s, ssd, 5, 600, { paid: 3000, date: at(2026, 7, 1, 9) })
    sell(shop, ssd, 1, 1000, { date: at(2026, 7, 1, 10) })
    const cancelled = sell(shop, ssd, 2, 1000, { date: at(2026, 7, 1, 11) })
    shop.run(tx => cancelSale(tx, cancelled, 'Entered by mistake'))
    const report = loadReport(shop.state, day(2026, 7, 1))
    expect(report.sales.invoiceCount).toBe(1)
    expect(report.sales.grossSales).toBeCloseTo(1000, 2)
    expect(report.profit.grossProfit).toBeCloseTo(400, 2)
    expect(report.topByQuantity[0].quantity).toBe(1)
    expect(report.daily[0].invoices).toBe(1)
  })

  it('date boundaries are inclusive whole days', () => {
    const shop = new TestShop()
    const s = supplier(shop)
    const cover = product(shop, 'Phone cover', { price: 100 })
    buy(shop, s, cover, 10, 50, { paid: 500, date: at(2026, 3, 1, 9) })
    sell(shop, cover, 1, 100, { date: at(2026, 3, 9, 23, 59, 59, 999) })
    sell(shop, cover, 1, 100, { date: at(2026, 3, 10, 0, 0, 0, 0) })
    sell(shop, cover, 1, 100, { date: at(2026, 3, 10, 23, 59, 59, 999) })
    sell(shop, cover, 1, 100, { date: at(2026, 3, 11, 0, 0, 0, 0) })
    expense(shop, 'Tea', 10, at(2026, 3, 10))
    expense(shop, 'Tea', 20, at(2026, 3, 11))

    const yesterday = forPeriod('YESTERDAY', at(2026, 3, 11, 8))
    expect(yesterday.start).toBe(at(2026, 3, 10))
    expect(yesterday.end).toBe(at(2026, 3, 10, 23, 59, 59, 999))
    const r = customRange(at(2026, 3, 10, 5), at(2026, 3, 10, 6))
    expect([r.start, r.end]).toEqual([yesterday.start, yesterday.end])

    const oneDay = loadReport(shop.state, yesterday)
    expect(oneDay.sales.invoiceCount).toBe(2)
    expect(oneDay.sales.grossSales).toBeCloseTo(200, 2)
    expect(oneDay.profit.expenses).toBeCloseTo(10, 2)
    expect(oneDay.profit.netProfit).toBeCloseTo(90, 2)

    const three = loadReport(shop.state, customRange(at(2026, 3, 11, 12), at(2026, 3, 9, 12)))
    expect(three.daily.map(d => d.invoices)).toEqual([1, 2, 1])
    expect(three.sales.invoiceCount).toBe(4)
    expect(three.range.grouping).toBe('DAY')
    expect(three.chart).toHaveLength(3)

    const month = loadReport(shop.state, forPeriod('THIS_MONTH', at(2026, 3, 20, 10)))
    expect(month.daily).toHaveLength(31)
    expect(month.chart).toHaveLength(31)
    expect(month.sales.invoiceCount).toBe(4)
    expect(month.profit.expenses).toBeCloseTo(30, 2)
    expect(month.chart.reduce((a, c) => a + c.sales, 0)).toBeCloseTo(month.sales.netSales, 2)
    expect(month.chart.reduce((a, c) => a + c.profit, 0)).toBeCloseTo(month.profit.grossProfit, 2)
    expect(month.daily.reduce((a, d) => a + d.netProfit, 0)).toBeCloseTo(month.profit.netProfit, 2)
    expect(month.purchases.purchased).toBeCloseTo(500, 2)
    expect(oneDay.purchases.purchased).toBeCloseTo(0, 2)
  })

  it('periods cover whole weeks, months and years', () => {
    const now = at(2026, 10, 31, 14)
    for (const firstDay of [6, 0, 1]) {
      const week = forPeriod('THIS_WEEK', now, undefined, undefined, firstDay)
      expect(new Date(week.start).getDay()).toBe(firstDay)
      expect(now >= week.start && now <= week.end).toBe(true)
      expect(dayStarts(week.start, week.end)).toHaveLength(7)
    }
    const lastMonth = forPeriod('LAST_MONTH', at(2026, 3, 31, 10))
    expect(lastMonth.start).toBe(at(2026, 2, 1))
    expect(lastMonth.end).toBe(at(2026, 2, 28, 23, 59, 59, 999))
    const december = forPeriod('LAST_MONTH', at(2026, 1, 15, 10))
    expect(december.start).toBe(at(2025, 12, 1))
    expect(december.end).toBe(at(2025, 12, 31, 23, 59, 59, 999))
    const year = forPeriod('THIS_YEAR', now)
    expect(year.start).toBe(at(2026, 1, 1))
    expect(year.end).toBe(at(2026, 12, 31, 23, 59, 59, 999))
    expect(year.grouping).toBe('MONTH')
    const daily = buildDailyRows(year, [], [], [])
    expect(daily).toHaveLength(365)
    expect(buildChart(year, daily)).toHaveLength(12)
    expect(forPeriod('TODAY', now).grouping).toBe('NONE')
    expect(customRange(now, now).grouping).toBe('NONE')
    expect(customRange(at(2026, 1, 1), at(2026, 6, 30)).grouping).toBe('MONTH')
  })

  it('money received, dues and stock', () => {
    const shop = new TestShop()
    const s = supplier(shop, 'Vendor A', '01700000001')
    const s2 = supplier(shop, 'Vendor B', '01700000002')
    const phone = product(shop, 'Phone', { price: 1000 })
    const cable = product(shop, 'Cable', { price: 200 })
    product(shop, 'Earphone')
    buy(shop, s, phone, 10, 600, { paid: 6000 })
    buy(shop, s2, cable, 5, 100, { paid: 0 })
    const karim = customer(shop, 'Karim', '01811000002')
    sell(shop, phone, 1, 1000)
    sell(shop, phone, 2, 1000, { customerId: karim, paid: 500, method: 'BKASH' })
    shop.run(tx => recordCustomerPayment(tx, { customerId: karim, amount: 300, paymentMethod: 'NAGAD' }))

    const report = loadReport(shop.state, forPeriod('TODAY'))
    const received = Object.fromEntries(report.sales.receivedByMethod.map(r => [r.label, r.amount]))
    expect(received.Cash).toBeCloseTo(1000, 2)
    expect(received.bKash).toBeCloseTo(500, 2)
    expect(received.Nagad).toBeCloseTo(300, 2)
    expect(report.sales.moneyReceived).toBeCloseTo(1800, 2)
    expect(report.sales.receivedAtSale).toBeCloseTo(1500, 2)
    expect(report.sales.dueCollected).toBeCloseTo(300, 2)
    expect(report.sales.dueCreated).toBeCloseTo(1500, 2)
    expect(report.sales.stillUnpaid).toBeCloseTo(1200, 2)
    expect(report.dues.customersOwe).toBeCloseTo(1200, 2)
    expect(report.dues.customersOwingCount).toBe(1)
    expect(report.dues.topCustomers[0].name).toBe('Karim')
    expect(report.dues.youOweSuppliers).toBeCloseTo(500, 2)
    expect(report.dues.topSuppliers[0].name).toBe('Vendor B')
    expect(report.stock.stockValue).toBeCloseTo(4700, 2)
    expect(report.stock.lowStockCount).toBe(1)
    expect(report.stock.outOfStockCount).toBe(1)
    expect(report.stock.productCount).toBe(3)
  })

  it('CSV has the summary and daily rows', () => {
    const shop = new TestShop()
    const s = supplier(shop)
    const cover = product(shop, 'Cover', { price: 100 })
    buy(shop, s, cover, 10, 50, { paid: 500, date: at(2026, 4, 1, 9) })
    sell(shop, cover, 2, 100, { date: at(2026, 4, 2, 10) })
    expense(shop, 'ভাড়া', 30, at(2026, 4, 2, 11))
    expense(shop, 'Tea, snacks', 5, at(2026, 4, 3, 11))
    expense(shop, '=HYPERLINK(1)', 1, at(2026, 4, 3, 12))
    const csv = buildCsv(loadReport(shop.state, customRange(at(2026, 4, 1), at(2026, 4, 3))))
    const lines = csv.split('\r\n')
    expect(csv).toContain('Expenses,ভাড়া,30.00')
    expect(csv).toContain('Expenses,"Tea, snacks",5.00')
    expect(csv).toContain("Expenses,'=HYPERLINK(1),1.00")
    expect(csv).toContain('Profit,Net profit,64.00')
    expect(csv).toContain('Sales,Net sales,200.00')
    const header = lines.findIndex(l => l.startsWith('Date,Invoices,'))
    expect(header).toBeGreaterThan(0)
    expect(lines[header + 1]).toBe('2026-04-01,0,0.00,0.00,0.00,0.00,0.00,0.00,0.00,0.00')
    expect(lines[header + 2]).toBe('2026-04-02,1,200.00,0.00,200.00,0.00,0.00,100.00,30.00,70.00')
    expect(lines[header + 3]).toBe('2026-04-03,0,0.00,0.00,0.00,0.00,0.00,0.00,6.00,-6.00')
    expect(lines[header + 4].startsWith('Total,1,200.00,')).toBe(true)
  })
})
