import { useState } from 'react'
import type { Brand, Category, Subcategory, Unit } from '../../data/schema'
import { useShop } from '../../data/store'
import {
  deactivateUnit, deleteBrandIfUnused, deleteCategoryIfUnused, deleteSubcategoryIfUnused, saveBrand, saveCategory, saveSubcategory, saveUnit,
} from '../../domain/catalog'
import { Empty, ErrorText, Field, Modal, PageHead, Tabs, useAction, useToast } from '../common'
import { Icon } from '../icons'

type Tab = 'CATEGORIES' | 'BRANDS' | 'UNITS'
type Editing =
  | { kind: 'category'; row?: Category }
  | { kind: 'subcategory'; categoryId: number; row?: Subcategory }
  | { kind: 'brand'; row?: Brand }
  | { kind: 'unit'; row?: Unit }

export default function Catalog() {
  const { state } = useShop()
  const toast = useToast()
  const [tab, setTab] = useState<Tab>('CATEGORIES')
  const [editing, setEditing] = useState<Editing | null>(null)
  const [open, setOpen] = useState<number | null>(null)
  const { run, error } = useAction()
  const count = (field: 'categoryId' | 'brandId' | 'subcategoryId', id: number) => state.where('products', field, id).filter(p => p.isActive).length
  const categories = state.list('categories').filter(c => c.isActive).sort((a, b) => a.name.localeCompare(b.name))
  const brands = state.list('brands').filter(b => b.isActive).sort((a, b) => a.name.localeCompare(b.name))
  const units = state.list('units').filter(u => u.isActive).sort((a, b) => a.name.localeCompare(b.name))

  const remove = async (fn: Parameters<typeof run>[0], done: string) => {
    const msg = await run(fn)
    if (msg === null) toast(done)
    else if (typeof msg === 'string') toast(msg)
  }

  return (
    <div className="page">
      <PageHead title="Categories & brands">
        <button className="btn" data-testid="catalog-add" onClick={() => setEditing(tab === 'CATEGORIES' ? { kind: 'category' } : tab === 'BRANDS' ? { kind: 'brand' } : { kind: 'unit' })}>
          <Icon name="plus" size={18} />{tab === 'CATEGORIES' ? 'Category' : tab === 'BRANDS' ? 'Brand' : 'Unit'}
        </button>
      </PageHead>
      <Tabs options={[['CATEGORIES', 'Categories'], ['BRANDS', 'Brands'], ['UNITS', 'Units']]} value={tab} onChange={setTab} />
      <ErrorText error={error} />
      {tab === 'CATEGORIES' && (
        <div className="card list">
          {categories.length === 0 && <Empty emoji="🗂️" title="No categories" />}
          {categories.map(c => {
            const subs = state.where('subcategories', 'categoryId', c.id).filter(s => s.isActive).sort((a, b) => a.name.localeCompare(b.name))
            return (
              <div key={c.id}>
                <div className="item">
                  <button className="icon-btn" onClick={() => setOpen(open === c.id ? null : c.id)} aria-label="Subcategories"><Icon name={open === c.id ? 'left' : 'right'} size={18} /></button>
                  <div className="grow"><div className="title">{c.name}</div><div className="subtitle">{count('categoryId', c.id)} products · {subs.length} subcategories</div></div>
                  <button className="icon-btn" onClick={() => setEditing({ kind: 'category', row: c })} aria-label="Edit"><Icon name="edit" size={18} /></button>
                  <button className="icon-btn" onClick={() => void remove(tx => deleteCategoryIfUnused(tx, c.id), 'Category deleted')} aria-label="Delete"><Icon name="trash" size={18} /></button>
                </div>
                {open === c.id && (
                  <div style={{ paddingLeft: 48 }}>
                    {subs.map(s => (
                      <div key={s.id} className="item">
                        <div className="grow"><div className="title">{s.name}</div><div className="subtitle">{count('subcategoryId', s.id)} products</div></div>
                        <button className="icon-btn" onClick={() => setEditing({ kind: 'subcategory', categoryId: c.id, row: s })} aria-label="Edit"><Icon name="edit" size={18} /></button>
                        <button className="icon-btn" onClick={() => void remove(tx => deleteSubcategoryIfUnused(tx, s.id), 'Subcategory deleted')} aria-label="Delete"><Icon name="trash" size={18} /></button>
                      </div>
                    ))}
                    <div style={{ padding: '6px 16px 12px' }}><button className="btn tonal small" onClick={() => setEditing({ kind: 'subcategory', categoryId: c.id })}><Icon name="plus" size={16} />Subcategory</button></div>
                  </div>
                )}
              </div>
            )
          })}
        </div>
      )}
      {tab === 'BRANDS' && (
        <div className="card list">
          {brands.length === 0 && <Empty emoji="🏷️" title="No brands" />}
          {brands.map(b => (
            <div key={b.id} className="item">
              <div className="grow"><div className="title">{b.name}</div><div className="subtitle">{[b.originCountry, `${count('brandId', b.id)} products`].filter(Boolean).join(' · ')}</div></div>
              <button className="icon-btn" onClick={() => setEditing({ kind: 'brand', row: b })} aria-label="Edit"><Icon name="edit" size={18} /></button>
              <button className="icon-btn" onClick={() => void remove(tx => deleteBrandIfUnused(tx, b.id), 'Brand deleted')} aria-label="Delete"><Icon name="trash" size={18} /></button>
            </div>
          ))}
        </div>
      )}
      {tab === 'UNITS' && (
        <div className="card list">
          {units.length === 0 && <Empty emoji="📏" title="No units" />}
          {units.map(u => (
            <div key={u.id} className="item">
              <div className="grow"><div className="title">{u.name}</div><div className="subtitle">{u.code}</div></div>
              <button className="icon-btn" onClick={() => setEditing({ kind: 'unit', row: u })} aria-label="Edit"><Icon name="edit" size={18} /></button>
              <button className="icon-btn" onClick={() => void run(tx => { deactivateUnit(tx, u.id); return true }, 'Unit removed')} aria-label="Remove"><Icon name="trash" size={18} /></button>
            </div>
          ))}
        </div>
      )}
      {editing && <EditDialog editing={editing} onClose={() => setEditing(null)} />}
    </div>
  )
}

function EditDialog({ editing, onClose }: { editing: Editing; onClose: () => void }) {
  const row = editing.row as (Partial<Category & Brand & Unit & Subcategory>) | undefined
  const [name, setName] = useState(row?.name ?? '')
  const [extra, setExtra] = useState(editing.kind === 'unit' ? (row?.code ?? '') : editing.kind === 'brand' ? (row?.originCountry ?? '') : (row?.description ?? ''))
  const { run, busy, error } = useAction()
  const title = { category: 'Category', subcategory: 'Subcategory', brand: 'Brand', unit: 'Unit' }[editing.kind]
  const save = async () => {
    const id = await run(tx => {
      switch (editing.kind) {
        case 'category': return saveCategory(tx, { ...(editing.row ?? {}), name, description: extra })
        case 'subcategory': return saveSubcategory(tx, { ...(editing.row ?? {}), categoryId: editing.categoryId, name, description: extra })
        case 'brand': return saveBrand(tx, { ...(editing.row ?? {}), name, originCountry: extra })
        case 'unit': return saveUnit(tx, { ...(editing.row ?? {}), name, code: extra.trim() || name.trim() })
      }
    }, `${title} saved`)
    if (id !== undefined) onClose()
  }
  return (
    <Modal onClose={onClose} narrow sheet={false} label={title}>
      <h2>{row ? `Edit ${title.toLowerCase()}` : `New ${title.toLowerCase()}`}</h2>
      <div className="stack">
        <Field label="Name *"><input className="input" autoFocus value={name} onChange={e => setName(e.target.value)} data-testid="catalog-name" /></Field>
        <Field label={editing.kind === 'unit' ? 'Short code (Pcs, Box…)' : editing.kind === 'brand' ? 'Country of origin' : 'Description'}>
          <input className="input" value={extra} onChange={e => setExtra(e.target.value)} />
        </Field>
      </div>
      <ErrorText error={error} />
      <div className="actions">
        <button className="btn text" onClick={onClose}>Cancel</button>
        <button className="btn" disabled={busy || !name.trim()} onClick={save} data-testid="catalog-save">Save</button>
      </div>
    </Modal>
  )
}
