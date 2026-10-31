// CategoryRepository, BrandRepository, UnitRepository and the warranty claim screen.
import { blank, type Brand, type Category, type Subcategory, type Unit, type Warranty } from '../data/schema'
import { audit, check, fail } from './common'
import { formatDateTime } from './dates'
import type { Tx } from './state'

const byNameNoCase = <T extends { id: number; name: string }>(rows: T[], name: string) =>
  rows.sort((a, b) => a.id - b.id).find(r => r.name.toLowerCase() === name.toLowerCase())

/** saveCategory. A hidden category of the same name comes back instead of a duplicate. Returns the id. */
export function saveCategory(tx: Tx, input: Partial<Category> & Pick<Category, 'name'>): number {
  const user = tx.ctx.actor
  const category = { ...blank('categories', { createdAt: tx.now, updatedAt: tx.now }), ...input } as Category
  check(category.name.trim() !== '', 'Category name cannot be empty.')
  const same = byNameNoCase(tx.all('categories'), category.name.trim())
  if (same && same.id !== category.id) {
    if (!same.isActive && category.id === 0) {
      tx.put('categories', { ...same, isActive: true, updatedAt: tx.now })
      return same.id
    }
    fail(`Category '${same.name}' already exists.`)
  }
  if (category.id === 0) {
    const { id: _ignored, ...fields } = category
    void _ignored
    const created = tx.insert('categories', fields)
    audit(tx, 'ADMIN', 'CATEGORY_CREATED', 'CATEGORIES', String(created.id), `Created category '${category.name}' by ${user}`)
    return created.id
  }
  const name = category.name.trim()
  tx.put('categories', { ...category, name, updatedAt: tx.now })
  // Products keep a copy of the category name for lists and invoices
  for (const p of tx.where('products', 'categoryId', category.id)) if (p.categoryName !== name) tx.put('products', { ...p, categoryName: name })
  audit(tx, 'ADMIN', 'CATEGORY_UPDATED', 'CATEGORIES', String(category.id), `Updated category '${category.name}' by ${user}`)
  return category.id
}

/** Deletes an unused category; one with products is deactivated and the message says so. */
export function deleteCategoryIfUnused(tx: Tx, id: number): string | null {
  const user = tx.ctx.actor
  const category = tx.get('categories', id) ?? fail('Category not found.')
  const count = tx.where('products', 'categoryId', id).filter(p => p.isActive).length
  if (count > 0) {
    tx.put('categories', { ...category, isActive: false })
    audit(tx, 'ADMIN', 'CATEGORY_DEACTIVATED', 'CATEGORIES', String(id),
      `Category '${category.name}' deactivated (contains ${count} products) by ${user}`)
    return `Category contains ${count} products. It has been deactivated instead of permanently deleted.`
  }
  // subcategories.categoryId is ON DELETE RESTRICT
  check(tx.where('subcategories', 'categoryId', id).length === 0, 'Delete the subcategories of this category first.')
  tx.remove('categories', id)
  audit(tx, 'ADMIN', 'CATEGORY_DELETED', 'CATEGORIES', String(id), `Permanently deleted unused category '${category.name}' by ${user}`)
  return null
}

export function saveSubcategory(tx: Tx, input: Partial<Subcategory> & Pick<Subcategory, 'name' | 'categoryId'>): number {
  const user = tx.ctx.actor
  const sub = { ...blank('subcategories', { createdAt: tx.now, updatedAt: tx.now }), ...input } as Subcategory
  check(sub.name.trim() !== '', 'Subcategory name cannot be empty.')
  check(sub.categoryId > 0, 'Valid parent category must be selected.')
  const existing = tx.where('subcategories', 'categoryId', sub.categoryId).sort((a, b) => a.id - b.id).find(s => s.name === sub.name.trim())
  if (existing && existing.id !== sub.id) fail('A subcategory with this name already exists in this category.')
  if (sub.id === 0) {
    const { id: _ignored, ...fields } = sub
    void _ignored
    const created = tx.insert('subcategories', fields)
    audit(tx, 'ADMIN', 'SUBCATEGORY_CREATED', 'CATEGORIES', String(created.id), `Created subcategory '${sub.name}' by ${user}`)
    return created.id
  }
  const name = sub.name.trim()
  tx.put('subcategories', { ...sub, name, updatedAt: tx.now })
  for (const p of tx.where('products', 'subcategoryId', sub.id)) if (p.subcategoryName !== name) tx.put('products', { ...p, subcategoryName: name })
  return sub.id
}

export function deleteSubcategoryIfUnused(tx: Tx, id: number): string | null {
  const sub = tx.get('subcategories', id) ?? fail('Subcategory not found.')
  const count = tx.where('products', 'subcategoryId', id).filter(p => p.isActive).length
  if (count > 0) {
    tx.put('subcategories', { ...sub, isActive: false })
    return `Subcategory contains ${count} products. It has been deactivated instead of deleted.`
  }
  tx.remove('subcategories', id)
  return null
}

export function saveBrand(tx: Tx, input: Partial<Brand> & Pick<Brand, 'name'>): number {
  const user = tx.ctx.actor
  const brand = { ...blank('brands', { createdAt: tx.now, updatedAt: tx.now }), ...input } as Brand
  check(brand.name.trim() !== '', 'Brand name cannot be empty.')
  const same = byNameNoCase(tx.all('brands'), brand.name.trim())
  if (same && same.id !== brand.id) {
    if (!same.isActive && brand.id === 0) {
      tx.put('brands', { ...same, isActive: true, updatedAt: tx.now })
      return same.id
    }
    fail(`Brand '${same.name}' already exists.`)
  }
  if (brand.id === 0) {
    const { id: _ignored, ...fields } = brand
    void _ignored
    const created = tx.insert('brands', fields)
    audit(tx, 'ADMIN', 'BRAND_CREATED', 'BRANDS', String(created.id), `Created brand '${brand.name}' by ${user}`)
    return created.id
  }
  const name = brand.name.trim()
  tx.put('brands', { ...brand, name, updatedAt: tx.now })
  for (const p of tx.where('products', 'brandId', brand.id)) if (p.brandName !== name) tx.put('products', { ...p, brandName: name })
  audit(tx, 'ADMIN', 'BRAND_UPDATED', 'BRANDS', String(brand.id), `Updated brand '${brand.name}' by ${user}`)
  return brand.id
}

export function deleteBrandIfUnused(tx: Tx, id: number): string | null {
  const user = tx.ctx.actor
  const brand = tx.get('brands', id) ?? fail('Brand not found.')
  const count = tx.where('products', 'brandId', id).filter(p => p.isActive).length
  if (count > 0) {
    tx.put('brands', { ...brand, isActive: false })
    return `Brand is used by ${count} products. It has been deactivated instead of deleted.`
  }
  tx.remove('brands', id)
  audit(tx, 'ADMIN', 'BRAND_DELETED', 'BRANDS', String(id), `Deleted unused brand '${brand.name}' by ${user}`)
  return null
}

export function saveUnit(tx: Tx, input: Partial<Unit> & Pick<Unit, 'name'>): number {
  const unit = { ...blank('units', { createdAt: tx.now }), ...input } as Unit
  check(unit.name.trim() !== '', 'Unit name cannot be empty.')
  const clash = tx.all('units').find(u => u.id !== unit.id && u.isActive && u.name.trim().toLowerCase() === unit.name.trim().toLowerCase())
  if (clash) fail(`Unit '${clash.name}' already exists.`)
  if (unit.id === 0) {
    const { id: _ignored, ...fields } = unit
    void _ignored
    const created = tx.insert('units', fields)
    audit(tx, 'ADMIN', 'UNIT_CREATED', 'UNITS', String(created.id), `Created measurement unit '${unit.name}'`)
    return created.id
  }
  tx.put('units', unit)
  return unit.id
}

export function deactivateUnit(tx: Tx, id: number) {
  const unit = tx.get('units', id) ?? fail('Unit not found.')
  tx.put('units', { ...unit, isActive: false })
}

/** Records a claim step (received, repaired, replaced) and keeps a dated note trail (WarrantyScreen.recordClaim). */
export function recordWarrantyClaim(tx: Tx, warrantyId: number, status: string, note: string) {
  const w: Warranty = tx.get('warranty_records', warrantyId) ?? fail('Warranty not found.')
  check(w.status !== 'RETURNED' && w.status !== 'CANCELLED', 'This sale was returned or cancelled; its warranty no longer applies.')
  const line = `${formatDateTime(tx.now)}: ${status}${note.trim() !== '' ? ` - ${note.trim()}` : ''}`
  const notes = [w.claimNotes, line].filter(s => s.trim() !== '').join('\n')
  tx.put('warranty_records', { ...w, status, claimNotes: notes })
}

export const WARRANTY_FILTERS = ['ALL', 'ACTIVE', 'EXPIRING', 'EXPIRED', 'CLAIMS'] as const
export type WarrantyFilter = typeof WARRANTY_FILTERS[number]

export function warrantyMatches(w: Warranty, filter: WarrantyFilter, now: number): boolean {
  const DAY = 24 * 60 * 60 * 1000
  switch (filter) {
    case 'ALL': return true
    case 'ACTIVE': return w.status === 'ACTIVE' && w.warrantyEndDate >= now
    case 'EXPIRING': return w.status === 'ACTIVE' && w.warrantyEndDate >= now && w.warrantyEndDate <= now + 30 * DAY
    case 'EXPIRED': return (w.status === 'ACTIVE' && w.warrantyEndDate < now) || w.status === 'EXPIRED'
    case 'CLAIMS': return ['CLAIMED', 'REPAIRED', 'REPLACED'].includes(w.status)
  }
}

/** SettingsRepository.updateShopProfile: the one shop_profile row (local-only columns are never sent). */
export function saveShopProfile(tx: Tx, fields: Partial<import('../data/schema').ShopProfile>) {
  check((fields.shopName ?? tx.base.profile.shopName).trim() !== '', 'Shop name is required.')
  const current = tx.get('shop_profile', 1) ?? blank('shop_profile', { id: 1 })
  tx.put('shop_profile', { ...current, ...fields, id: 1, updatedAt: tx.now })
}
