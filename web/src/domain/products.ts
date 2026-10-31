// ProductRepository + StockRepository.adjustStock: products, variants, units and stock corrections.
import { blank, type Product, type SerialUnit, type Variant } from '../data/schema'
import { audit, check, fail, StockMovementType, TrackingType } from './common'
import type { Reader, ShopState, Tx } from './state'

const activeProducts = (r: Reader, field: 'sku' | 'barcode', value: string) =>
  r.where('products', field, value).filter(p => p.isActive)

/** SKU used by another active product, or by any variant. */
export function skuExists(r: Reader, sku: string, excludeProductId = 0): boolean {
  if (sku.trim() === '') return false
  const v = sku.trim()
  return activeProducts(r, 'sku', v).some(p => p.id !== excludeProductId) || r.where('product_variants', 'sku', v).length > 0
}

/** Barcode used by another active product, or by any variant. */
export function barcodeExists(r: Reader, barcode: string, excludeProductId = 0): boolean {
  if (barcode.trim() === '') return false
  const v = barcode.trim()
  return activeProducts(r, 'barcode', v).some(p => p.id !== excludeProductId) || r.where('product_variants', 'barcode', v).length > 0
}

export type ProductInput = Partial<Product> & Pick<Product, 'name'>

/** saveProduct: new (id 0) with its opening stock, or changes (stock never changes here). Returns the id. */
export function saveProduct(tx: Tx, input: ProductInput): number {
  const operator = tx.ctx.actor
  const product = { ...blank('products'), ...input } as Product
  check(product.name.trim() !== '', 'Product name cannot be empty.')
  check(!(product.retailPrice < 0 || product.purchasePrice < 0), 'Prices cannot be negative.')
  check(!(product.minStock < 0 || product.currentStock < 0), 'Stock cannot be negative.')
  check(product.maxStock >= product.minStock, 'Maximum stock cannot be less than minimum stock.')
  if (product.sku.trim() !== '' && skuExists(tx, product.sku, product.id)) fail(`SKU '${product.sku}' already exists.`)
  if (product.barcode.trim() !== '' && barcodeExists(tx, product.barcode, product.id)) fail(`Barcode '${product.barcode}' already exists.`)
  const serialized = TrackingType.requiresSerial(product.trackingType) || TrackingType.requiresImei(product.trackingType)

  if (product.id === 0) {
    if (serialized && product.currentStock > 0) {
      fail('For serial/IMEI products leave opening stock at 0 and add the units through a purchase, so each unit\'s IMEI/serial is recorded.')
    }
    const { id: _ignored, ...fields } = product
    void _ignored
    const created = tx.insert('products', {
      ...fields, openingStock: product.currentStock, createdAt: product.createdAt || tx.now, updatedAt: product.updatedAt || tx.now,
    })
    tx.insert('product_price_history', {
      productId: created.id, purchasePrice: product.purchasePrice, wholesalePrice: product.wholesalePrice,
      retailPrice: product.retailPrice, minSellingPrice: product.minSellingPrice, changedAt: tx.now, changedBy: operator,
      note: 'Initial product price',
    })
    if (product.currentStock > 0) {
      tx.insert('stock_transactions', {
        productId: created.id, productName: product.name, transactionType: StockMovementType.OPENING_STOCK,
        quantityChange: product.currentStock, previousStock: 0, newStock: product.currentStock, unitCost: product.purchasePrice,
        referenceId: created.id, referenceType: 'PRODUCT', notes: 'Initial opening stock', createdAt: tx.now,
      })
    }
    audit(tx, 'ADMIN', 'PRODUCT_CREATED', 'PRODUCTS', String(created.id),
      `Created product '${product.name}' (SKU: ${product.sku}) by ${operator}`)
    return created.id
  }

  const existing = tx.product(product.id)
  if (existing && (existing.purchasePrice !== product.purchasePrice || existing.retailPrice !== product.retailPrice ||
    existing.wholesalePrice !== product.wholesalePrice || existing.minSellingPrice !== product.minSellingPrice)) {
    tx.insert('product_price_history', {
      productId: product.id, purchasePrice: product.purchasePrice, wholesalePrice: product.wholesalePrice,
      retailPrice: product.retailPrice, minSellingPrice: product.minSellingPrice, changedAt: tx.now, changedBy: operator,
      note: 'Updated prices',
    })
  }
  if (existing && existing.trackingType !== product.trackingType &&
    (existing.currentStock > 0 || tx.where('product_serial_imei', 'productId', existing.id).length > 0)) {
    fail('Tracking type can only be changed while the product has no stock and no serial/IMEI units.')
  }
  tx.put('products', {
    ...product,
    currentStock: existing?.currentStock ?? product.currentStock,
    openingStock: existing?.openingStock ?? product.openingStock,
    createdAt: existing?.createdAt ?? product.createdAt,
    updatedAt: tx.now,
  })
  audit(tx, 'ADMIN', 'PRODUCT_UPDATED', 'PRODUCTS', String(product.id), `Updated product '${product.name}' by ${operator}`)
  return product.id
}

/**
 * deleteProductIfUnused: a product with history is only deactivated (the message says so); an unused one is
 * removed together with the rows Room deletes with it (ON DELETE CASCADE: variants, units, price history).
 * Returns the message to show when it was deactivated instead.
 */
export function deleteProductIfUnused(tx: Tx, productId: number): string | null {
  const operator = tx.ctx.actor
  const product = tx.get('products', productId) ?? fail('Product not found.')
  const total = tx.where('stock_transactions', 'productId', productId).length + tx.where('sale_items', 'productId', productId).length +
    tx.where('purchase_items', 'productId', productId).length
  if (total > 0) {
    tx.put('products', { ...product, isActive: false })
    audit(tx, 'ADMIN', 'PRODUCT_DEACTIVATED', 'PRODUCTS', String(product.id),
      `Product '${product.name}' deactivated (has ${total} transactions) by ${operator}`)
    return `This product has ${total} historical transactions and cannot be deleted. It has been deactivated instead.`
  }
  for (const v of tx.where('product_variants', 'productId', productId)) tx.remove('product_variants', v.id)
  for (const u of tx.where('product_serial_imei', 'productId', productId)) tx.remove('product_serial_imei', u.id)
  for (const h of tx.where('product_price_history', 'productId', productId)) tx.remove('product_price_history', h.id)
  tx.remove('products', productId)
  audit(tx, 'ADMIN', 'PRODUCT_DELETED', 'PRODUCTS', String(product.id), `Permanently deleted unused product '${product.name}' by ${operator}`)
  return null
}

export type VariantInput = Partial<Variant> & Pick<Variant, 'productId' | 'variantName'>

/** saveVariant: new variants start empty; stock and cost only change through purchases, sales and returns. */
export function saveVariant(tx: Tx, input: VariantInput): number {
  const operator = tx.ctx.actor
  const raw = { ...blank('product_variants'), ...input } as Variant
  const variant: Variant = { ...raw, variantName: raw.variantName.trim(), sku: raw.sku.trim(), barcode: raw.barcode.trim() }
  check(variant.variantName !== '', 'Variant name cannot be empty.')
  check(!(variant.retailPrice < 0 || variant.purchasePrice < 0 || variant.minSellingPrice < 0), 'Prices cannot be negative.')
  if (variant.sku !== '' && (tx.where('product_variants', 'sku', variant.sku).some(v => v.id !== variant.id) ||
    activeProducts(tx, 'sku', variant.sku).length > 0)) fail(`SKU '${variant.sku}' is already used.`)
  if (variant.barcode !== '' && (tx.where('product_variants', 'barcode', variant.barcode).some(v => v.id !== variant.id) ||
    activeProducts(tx, 'barcode', variant.barcode).length > 0)) fail(`Barcode '${variant.barcode}' is already used.`)
  const same = tx.where('product_variants', 'productId', variant.productId).filter(v => v.isActive).sort((a, b) => a.id - b.id)
    .find(v => v.id !== variant.id && v.variantName.toLowerCase() === variant.variantName.toLowerCase())
  if (same) fail(`This product already has a variant called '${same.variantName}'.`)

  if (variant.id === 0) {
    const { id: _ignored, ...fields } = variant
    void _ignored
    const created = tx.insert('product_variants', {
      ...fields, currentStock: 0, createdAt: variant.createdAt || tx.now, updatedAt: variant.updatedAt || tx.now,
    })
    const product = tx.get('products', variant.productId)
    if (product && !product.hasVariants) tx.put('products', { ...product, hasVariants: true, updatedAt: tx.now })
    audit(tx, 'ADMIN', 'VARIANT_CREATED', 'PRODUCTS', String(created.id), `Created variant '${variant.variantName}' by ${operator}`)
    return created.id
  }
  const existing = tx.variant(variant.id) ?? fail('Variant not found.')
  tx.put('product_variants', {
    ...variant, productId: existing.productId, currentStock: existing.currentStock, createdAt: existing.createdAt, updatedAt: tx.now,
  })
  return variant.id
}

export function deactivateVariant(tx: Tx, variantId: number) {
  const variant = tx.variant(variantId) ?? fail('Variant not found.')
  check(variant.currentStock <= 0, `'${variant.variantName}' still has ${variant.currentStock} in stock. Sell or return them first.`)
  tx.put('product_variants', { ...tx.get('product_variants', variantId)!, isActive: false, updatedAt: tx.now })
}

/** saveSerialImei: edits one physical unit's numbers; serial and IMEIs stay unique. */
export function saveSerialUnit(tx: Tx, unit: SerialUnit): number {
  const clean = (v: string | null) => (v ?? '').trim() || null
  const serialNumber = clean(unit.serialNumber)
  const imei1 = clean(unit.imei1)
  const imei2 = clean(unit.imei2)
  if (imei1 && imei2) check(imei1 !== imei2, `IMEI 1 and IMEI 2 cannot be identical (${imei1})`)
  const imeiUsed = (imei: string) => [...tx.where('product_serial_imei', 'imei1', imei), ...tx.where('product_serial_imei', 'imei2', imei)]
    .some(u => unit.id === 0 || u.id !== unit.id)
  if (imei1 && imeiUsed(imei1)) fail(`IMEI '${imei1}' already exists in inventory.`)
  if (imei2 && imeiUsed(imei2)) fail(`IMEI '${imei2}' already exists in inventory.`)
  if (serialNumber && tx.where('product_serial_imei', 'serialNumber', serialNumber).some(u => unit.id === 0 || u.id !== unit.id)) {
    fail(`Serial number '${serialNumber}' already exists in inventory.`)
  }
  const cleaned = { ...unit, serialNumber, imei1, imei2, updatedAt: tx.now }
  if (unit.id === 0) {
    const { id: _ignored, ...fields } = cleaned
    void _ignored
    return tx.insert('product_serial_imei', fields).id
  }
  tx.put('product_serial_imei', cleaned)
  return unit.id
}

export interface CodeMatch {
  product: Product
  variant?: Variant
  unit?: SerialUnit
}

/** findByCode: product barcode, product SKU, variant barcode/SKU, or one unit's IMEI / serial. */
export function findByCode(state: ShopState, rawCode: string): CodeMatch | null {
  const code = rawCode.trim()
  if (code === '') return null
  const first = <T extends { id: number }>(l: T[]) => [...l].sort((a, b) => a.id - b.id)[0]
  const byBarcode = first(activeProducts(state, 'barcode', code))
  if (byBarcode) return { product: byBarcode }
  const bySku = first(activeProducts(state, 'sku', code))
  if (bySku) return { product: bySku }
  const variant = first(state.where('product_variants', 'barcode', code)) ?? first(state.where('product_variants', 'sku', code))
  if (variant && variant.isActive) {
    const p = state.get('products', variant.productId)
    if (p && p.isActive) return { product: p, variant }
  }
  const unit = first([...state.where('product_serial_imei', 'imei1', code), ...state.where('product_serial_imei', 'imei2', code)]) ??
    first(state.where('product_serial_imei', 'serialNumber', code))
  if (unit) {
    const p = state.get('products', unit.productId)
    if (p) return { product: p, variant: unit.variantId > 0 ? state.get('product_variants', unit.variantId) : undefined, unit }
  }
  return null
}

export const ADJUSTMENT_REASONS = [
  StockMovementType.ADJUSTMENT_MANUAL, StockMovementType.ADJUSTMENT_FOUND, StockMovementType.ADJUSTMENT_DAMAGE,
  StockMovementType.ADJUSTMENT_LOST, StockMovementType.INTERNAL_USE,
]

/** StockRepository.adjustStock: one movement, never below zero. */
export function adjustStock(tx: Tx, productId: number, quantityDelta: number, reason: string, notes: string, variantId = 0) {
  const createdBy = tx.ctx.actor
  const product = tx.product(productId) ?? fail('Product not found')
  const previousStock = product.currentStock
  const newStock = previousStock + quantityDelta
  if (newStock < 0) fail(`Stock cannot become negative. Current: ${previousStock}, Delta: ${quantityDelta}`)
  if (variantId > 0) {
    const variant = tx.variant(variantId) ?? fail(`Variant ${variantId} not found for product ${productId}`)
    if (variant.productId !== productId) fail(`Variant ${variantId} does not belong to product ${productId}`)
    if (variant.currentStock + quantityDelta < 0) {
      fail(`Variant stock cannot become negative. Current: ${variant.currentStock}, Delta: ${quantityDelta}`)
    }
  }
  tx.insert('stock_transactions', {
    productId, productName: product.name, variantId, transactionType: reason, quantityChange: quantityDelta, previousStock, newStock,
    unitCost: product.purchasePrice, referenceType: 'MANUAL_ADJUSTMENT', createdBy, notes, createdAt: tx.now,
  })
  audit(tx, createdBy, 'STOCK_ADJUSTED', 'INVENTORY', String(productId),
    `Adjusted ${product.name} stock by ${quantityDelta} (${reason}). Notes: ${notes}`)
}

/** Stock not assigned to a variant (bought before the product got variants; sold as "no variant"). */
export function unassignedStock(state: ShopState, product: Product): number {
  const variants = state.where('product_variants', 'productId', product.id).filter(v => v.isActive)
  return Math.max(product.currentStock - variants.reduce((a, v) => a + v.currentStock, 0), 0)
}

/** True when the product's cost comes from its purchases (the entered cost no longer counts). */
export const costFromPurchases = (state: ShopState, productId: number) => state.derived.productCost.has(productId)
