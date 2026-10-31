import { useMemo, useState } from 'react'
import { useNavigate, useSearchParams } from 'react-router-dom'
import { useShop } from '../../data/store'
import { TrackingType } from '../../domain/common'
import { Chips, Empty, MoreButton, PageHead, SearchBox, matches, useCan, useLimit, useMoney } from '../common'
import { ProductDialog } from '../dialogs'
import { Icon } from '../icons'

type Filter = 'ALL' | 'LOW' | 'OUT' | 'INACTIVE'

export default function Products() {
  const { state } = useShop()
  const money = useMoney()
  const navigate = useNavigate()
  const allowed = useCan()
  const costs = allowed('VIEW_COSTS_AND_PROFIT')
  const [params] = useSearchParams()
  const initial = params.get('filter') === 'low' ? 'LOW' : params.get('filter') === 'out' ? 'OUT' : 'ALL'
  const [filter, setFilter] = useState<Filter>(initial)
  const [q, setQ] = useState('')
  const [category, setCategory] = useState('ALL')
  const [adding, setAdding] = useState(false)
  const [limit, more] = useLimit()
  const products = useMemo(() => [...state.list('products')].sort((a, b) => a.name.localeCompare(b.name)), [state])
  const categories = useMemo(() => [...new Set(products.map(p => p.categoryName).filter(Boolean))].sort(), [products])
  const imeiHit = (code: string) => {
    const c = code.trim()
    if (c.length < 4) return new Set<number>()
    return new Set(state.list('product_serial_imei').filter(u => matches(c, u.imei1, u.imei2, u.serialNumber)).map(u => u.productId))
  }
  const hits = imeiHit(q)
  const shown = products.filter(p => {
    if (filter === 'INACTIVE' ? p.isActive : !p.isActive) return false
    if (filter === 'LOW' && !(p.currentStock <= p.minStock && p.currentStock > 0)) return false
    if (filter === 'OUT' && p.currentStock > 0) return false
    if (category !== 'ALL' && p.categoryName !== category) return false
    return matches(q, p.name, p.sku, p.barcode, p.model, p.brandName, p.categoryName) || hits.has(p.id)
  })
  return (
    <div className="page">
      <PageHead title="Products" sub={`${products.filter(p => p.isActive).length} active products`}>
        {allowed('PRODUCTS_EDIT') && <button className="btn" onClick={() => setAdding(true)} data-testid="add-product"><Icon name="plus" size={18} />Product</button>}
      </PageHead>
      <div className="toolbar">
        <SearchBox value={q} onChange={setQ} placeholder="Name, SKU, barcode, IMEI" testId="product-search" />
        <Chips options={[['ALL', 'All'], ['LOW', 'Low stock'], ['OUT', 'Out of stock'], ['INACTIVE', 'Inactive']]} value={filter} onChange={setFilter} />
      </div>
      {categories.length > 0 && <div style={{ marginBottom: 12 }}><Chips options={[['ALL', 'All categories'], ...categories.map(c => [c, c] as [string, string])]} value={category} onChange={setCategory} /></div>}
      <div className="card list">
        {shown.length === 0 && <Empty emoji="📦" title="No products found" />}
        {shown.slice(0, limit).map(p => (
          <button key={p.id} className="item" onClick={() => navigate(`/products/${p.id}`)} data-testid="product-row">
            <div className="grow">
              <div className="title">{p.name}</div>
              <div className="subtitle">{[p.brandName, p.categoryName, p.sku, TrackingType.isSerialized(p.trackingType) ? p.trackingType.replace(/_/g, ' ') : ''].filter(Boolean).join(' · ')}</div>
            </div>
            <div className="end">
              <div className="bold num">{money.format(p.retailPrice)}</div>
              {costs && <div className="tiny muted num">cost {money.format(p.purchasePrice)}</div>}
              <span className={`pill ${p.currentStock <= 0 ? 'red' : p.currentStock <= p.minStock ? 'amber' : 'green'}`} data-testid="product-stock">{p.currentStock} {p.unit}</span>
            </div>
          </button>
        ))}
        <MoreButton shown={Math.min(limit, shown.length)} total={shown.length} onMore={more} />
      </div>
      {adding && <ProductDialog onClose={() => setAdding(false)} onSaved={id => navigate(`/products/${id}`)} />}
    </div>
  )
}
