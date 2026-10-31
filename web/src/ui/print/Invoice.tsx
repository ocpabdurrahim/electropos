// Printable sale invoice in the phone's two main formats (core/invoice): A4 and an 80 mm roll.
import { useEffect } from 'react'
import { useNavigate, useParams, useSearchParams } from 'react-router-dom'
import type { Product, Sale } from '../../data/schema'
import { useShop } from '../../data/store'
import { parseSerials, warrantyEndDate } from '../../domain/common'
import { formatDate, formatDateTime } from '../../domain/dates'
import { formatMoney, methodLabel } from '../../domain/money'
import { Icon } from '../icons'

const THANK_YOU = 'Thank you for shopping with us!'

/** "12 months service, until 31 Oct 2027" (InvoiceText.warrantyLine). */
function warrantyLine(product: Product | undefined, saleDate: number): string | null {
  if (!product || product.warrantyPeriodMonths <= 0) return null
  const [count, unit] = product.warrantyPeriod > 0
    ? [product.warrantyPeriod, product.warrantyUnit.toUpperCase() === 'DAYS' ? 'day' : product.warrantyUnit.toUpperCase() === 'YEARS' ? 'year' : 'month']
    : [product.warrantyPeriodMonths, 'month']
  const type = product.warrantyType.toUpperCase() === 'SERVICE' ? ' service' : product.warrantyType.toUpperCase() === 'REPLACEMENT' ? ' replacement' : ''
  const end = warrantyEndDate(product, saleDate)
  return `${count} ${unit}${count === 1 ? '' : 's'}${type}${end > 0 ? `, until ${formatDate(end, 'dd MMM yyyy')}` : ''}`
}

const paymentLine = (s: Sale) => {
  const method = s.paymentMethod.trim() || 'CASH'
  return s.paymentStatus.trim() ? `${methodLabel(method)} (${s.paymentStatus})` : methodLabel(method)
}

export default function PrintInvoice() {
  const { id } = useParams()
  const [params] = useSearchParams()
  const format = params.get('f') === '80' ? '80' : 'a4'
  const navigate = useNavigate()
  const { state } = useShop()
  const sale = state.get('sales', Number(id))
  const shop = state.profile
  const symbol = shop.currencySymbol.trim() || '৳'
  const money = (v: number) => formatMoney(v, symbol)
  const plain = (v: number) => formatMoney(v, '').trim()

  useEffect(() => {
    const before = document.title
    if (sale) document.title = `Invoice ${sale.invoiceNumber}`
    return () => { document.title = before }
  }, [sale])

  if (!sale) return <div className="page"><p>This invoice was not found.</p><button className="btn" onClick={() => navigate(-1)}>Back</button></div>
  const lines = state.where('sale_items', 'saleId', sale.id).sort((a, b) => a.id - b.id).map(i => ({
    item: i,
    serials: parseSerials(i.serialImeisJson),
    serialLabel: i.trackingType.toUpperCase().includes('IMEI') ? 'IMEI' : 'S/N',
    warranty: warrantyLine(state.get('products', i.productId), sale.saleDate),
  }))
  const returns = state.where('sales_returns', 'saleId', sale.id).filter(r => r.status === 'COMPLETED').sort((a, b) => a.returnDate - b.returnDate)
  const returnsTotal = returns.reduce((a, r) => a + r.totalAmount, 0)
  const customer = sale.customerId ? state.get('customers', sale.customerId) : undefined
  const phones = [shop.phone, shop.alternativePhone].map(p => p.trim()).filter(Boolean)
  const shopName = shop.shopName.trim() || 'ElectroPOS'
  const otherDiscount = Math.max(sale.discountAmount - sale.itemDiscount - sale.invoiceDiscount, 0)

  return (
    <div>
      <style>{format === '80' ? '@page { size: 80mm auto; margin: 0; }' : '@page { size: A4 portrait; margin: 0; }'}</style>
      <div className="print-bar">
        <button className="btn text" onClick={() => navigate(-1)}><Icon name="back" size={18} />Back</button>
        <b className="grow">Invoice {sale.invoiceNumber}</b>
        <div className="segmented">
          <button className={format === 'a4' ? 'on' : ''} onClick={() => navigate(`?f=a4`, { replace: true })}>A4</button>
          <button className={format === '80' ? 'on' : ''} onClick={() => navigate(`?f=80`, { replace: true })}>80 mm</button>
        </div>
        <button className="btn" onClick={() => window.print()} data-testid="print-now"><Icon name="printer" size={18} />Print / Save PDF</button>
      </div>
      <div className="print-area">
        {format === 'a4' ? (
          <div className="paper a4" data-testid="invoice-a4">
            <div className="inv-head">
              <div>
                <div className="shop">{shopName}</div>
                {shop.slogan && <div className="muted">{shop.slogan}</div>}
                {shop.address && <div>{shop.address}</div>}
                {phones.length > 0 && <div>Phone: {phones.join(', ')}</div>}
                {(shop.email || shop.website) && <div className="muted">{[shop.email, shop.website].filter(Boolean).join('  •  ')}</div>}
              </div>
              <div style={{ textAlign: 'right' }}>
                <div className="title">INVOICE</div>
                <div><b>Invoice No: {sale.invoiceNumber}</b></div>
                <div>Date: {formatDateTime(sale.saleDate)}</div>
                <div>Payment: {paymentLine(sale)}</div>
                {sale.salespersonName && <div>Served by: {sale.salespersonName}</div>}
                {sale.status !== 'COMPLETED' && <div className="status-stamp">{sale.status}</div>}
              </div>
            </div>
            <div style={{ marginBottom: 10 }}>
              <div className="muted" style={{ fontSize: 11, fontWeight: 700, letterSpacing: '.06em' }}>BILL TO</div>
              <div><b>{sale.customerName.trim() || 'Walk-in Customer'}</b></div>
              {sale.customerPhone && <div>{sale.customerPhone}</div>}
              {customer?.address && <div>{customer.address}</div>}
            </div>
            <table>
              <thead><tr><th className="c">#</th><th>Item</th><th className="c">Qty</th><th className="r">Price</th><th className="r">Discount</th><th className="r">Total</th></tr></thead>
              <tbody>
                {lines.map(({ item, serials, serialLabel, warranty }, i) => (
                  <tr key={item.id}>
                    <td className="c">{i + 1}</td>
                    <td>
                      <div><b>{item.productName}</b></div>
                      {serials.length > 0 && <div className="serials">{serialLabel}: {serials.join(', ')}</div>}
                      {item.tax > 0.005 && <div className="serials">Incl. tax {plain(item.tax)}</div>}
                      {warranty && <div className="serials">Warranty: {warranty}</div>}
                    </td>
                    <td className="c">{item.quantity}</td>
                    <td className="r">{plain(item.unitPrice)}</td>
                    <td className="r">{item.discount > 0.005 ? plain(item.discount) : '—'}</td>
                    <td className="r">{plain(item.totalPrice)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
            <div className="totals">
              <div className="k-v"><span className="k">Subtotal</span><span>{money(sale.subTotal)}</span></div>
              {sale.itemDiscount > 0.005 && <div className="k-v"><span className="k">Item discounts</span><span>−{money(sale.itemDiscount)}</span></div>}
              {sale.invoiceDiscount > 0.005 && <div className="k-v"><span className="k">Invoice discount</span><span>−{money(sale.invoiceDiscount)}</span></div>}
              {otherDiscount > 0.005 && <div className="k-v"><span className="k">Discount</span><span>−{money(otherDiscount)}</span></div>}
              {sale.taxAmount > 0.005 && <div className="k-v"><span className="k">Tax</span><span>{money(sale.taxAmount)}</span></div>}
              <div className="k-v total"><span>Grand Total</span><span>{money(sale.totalAmount)}</span></div>
              <div className="k-v"><span className="k">Paid</span><span>{money(sale.paidAmount)}</span></div>
              {sale.dueAmount > 0.005 && <div className="k-v"><b>Due</b><b>{money(sale.dueAmount)}</b></div>}
            </div>
            {returns.length > 0 && (
              <div className="totals" style={{ marginTop: 12 }}>
                <div className="bold" style={{ fontSize: 13 }}>Returns</div>
                {returns.map(r => <div key={r.id} className="k-v"><span className="k">{r.returnNumber} ({formatDate(r.returnDate, 'dd MMM yyyy')})</span><span>−{money(r.totalAmount)}</span></div>)}
                <div className="k-v"><b>Net after returns</b><b>{money(Math.max(sale.totalAmount - returnsTotal, 0))}</b></div>
              </div>
            )}
            <div style={{ textAlign: 'center', marginTop: 24 }}>{THANK_YOU}</div>
            <div className="sig-row"><div className="sig">Customer</div><div className="sig">Authorised signature</div></div>
          </div>
        ) : (
          <div className="paper r80" data-testid="invoice-80">
            <div className="c shop">{shopName}</div>
            {shop.slogan && <div className="c small2">{shop.slogan}</div>}
            {shop.address && <div className="c small2">{shop.address}</div>}
            {phones.length > 0 && <div className="c small2">{phones.join(', ')}</div>}
            <hr />
            <div className="ln"><span>Invoice</span><b>{sale.invoiceNumber}</b></div>
            <div className="ln"><span>Date</span><span>{formatDateTime(sale.saleDate)}</span></div>
            <div className="ln"><span>Customer</span><span>{sale.customerName.trim() || 'Walk-in Customer'}</span></div>
            {sale.customerPhone && <div className="ln"><span>Phone</span><span>{sale.customerPhone}</span></div>}
            <div className="ln"><span>Payment</span><span>{paymentLine(sale)}</span></div>
            {sale.status !== 'COMPLETED' && <div className="c"><b>{sale.status}</b></div>}
            <hr />
            {lines.map(({ item, serials, serialLabel, warranty }) => (
              <div key={item.id} className="it">
                <div className="nm">{item.productName}</div>
                <div className="ln"><span>{item.quantity} × {plain(item.unitPrice)}{item.discount > 0.005 ? ` − ${plain(item.discount)}` : ''}</span><span>{plain(item.totalPrice)}</span></div>
                {serials.length > 0 && <div className="small2">{serialLabel}: {serials.join(', ')}</div>}
                {warranty && <div className="small2">Warranty: {warranty}</div>}
              </div>
            ))}
            <hr />
            <div className="ln"><span>Subtotal</span><span>{plain(sale.subTotal)}</span></div>
            {sale.itemDiscount > 0.005 && <div className="ln"><span>Item discounts</span><span>−{plain(sale.itemDiscount)}</span></div>}
            {sale.invoiceDiscount > 0.005 && <div className="ln"><span>Invoice discount</span><span>−{plain(sale.invoiceDiscount)}</span></div>}
            {sale.taxAmount > 0.005 && <div className="ln"><span>Tax</span><span>{plain(sale.taxAmount)}</span></div>}
            <div className="ln big"><span>TOTAL</span><span>{money(sale.totalAmount)}</span></div>
            <div className="ln"><span>Paid</span><span>{plain(sale.paidAmount)}</span></div>
            {sale.dueAmount > 0.005 && <div className="ln"><b>Due</b><b>{plain(sale.dueAmount)}</b></div>}
            {returns.length > 0 && <>
              <hr />
              {returns.map(r => <div key={r.id} className="ln"><span>Return {r.returnNumber}</span><span>−{plain(r.totalAmount)}</span></div>)}
              <div className="ln"><b>Net</b><b>{plain(Math.max(sale.totalAmount - returnsTotal, 0))}</b></div>
            </>}
            <hr />
            <div className="c">{THANK_YOU}</div>
            {sale.salespersonName && <div className="c small2">Served by {sale.salespersonName}</div>}
          </div>
        )}
      </div>
    </div>
  )
}
