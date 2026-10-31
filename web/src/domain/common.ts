// Small pieces shared by the repository ports.
import type { Product } from '../data/schema'
import { addDays, addMonths, addYears } from './dates'
import type { Tx } from './state'

/** A refused action; the message is shown to the person as it is (the Kotlin messages). */
export class DomainError extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'DomainError'
  }
}

export function check(condition: unknown, message: string): asserts condition {
  if (!condition) throw new DomainError(message)
}

export const fail = (message: string): never => {
  throw new DomainError(message)
}

export const TrackingType = {
  QUANTITY: 'QUANTITY',
  SERIAL: 'SERIAL',
  IMEI: 'IMEI',
  SERIAL_AND_IMEI: 'SERIAL_AND_IMEI',
  requiresSerial: (t: string) => t === 'SERIAL' || t === 'SERIAL_AND_IMEI',
  requiresImei: (t: string) => t === 'IMEI' || t === 'SERIAL_AND_IMEI',
  isSerialized: (t: string) => t === 'SERIAL' || t === 'IMEI' || t === 'SERIAL_AND_IMEI',
}

export const InventoryStatus = {
  IN_STOCK: 'IN_STOCK',
  SOLD: 'SOLD',
  RETURNED: 'RETURNED',
  DAMAGED: 'DAMAGED',
  LOST: 'LOST',
  CANCELLED: 'CANCELLED',
}

export const StockMovementType = {
  PURCHASE: 'PURCHASE',
  PURCHASE_CANCELLED: 'PURCHASE_CANCELLED',
  PURCHASE_RETURN: 'PURCHASE_RETURN',
  OPENING_STOCK: 'OPENING_STOCK',
  ADJUSTMENT_MANUAL: 'ADJUSTMENT_MANUAL',
  ADJUSTMENT_FOUND: 'ADJUSTMENT_FOUND',
  ADJUSTMENT_DAMAGE: 'ADJUSTMENT_DAMAGE',
  ADJUSTMENT_LOST: 'ADJUSTMENT_LOST',
  INTERNAL_USE: 'INTERNAL_USE',
  SALE: 'SALE',
  SALE_RETURN: 'SALE_RETURN',
}

export const STOCK_MOVEMENT_LABELS: Record<string, string> = {
  PURCHASE: 'Purchase',
  PURCHASE_CANCELLED: 'Purchase cancelled',
  PURCHASE_RETURN: 'Returned to supplier',
  OPENING_STOCK: 'Opening stock',
  ADJUSTMENT_MANUAL: 'Manual correction',
  ADJUSTMENT_FOUND: 'Found',
  ADJUSTMENT_DAMAGE: 'Damaged',
  ADJUSTMENT_LOST: 'Lost / stolen',
  INTERNAL_USE: 'Internal use',
  SALE: 'Sale',
  SALE_RETURN: 'Customer return',
}

/** WarrantyUtils.endDate: calendar days/months/years from [start]; 0 when the product has none. */
export function warrantyEndDate(product: Product, start: number): number {
  if (product.warrantyPeriodMonths <= 0 && product.warrantyPeriod <= 0) return 0
  if (product.warrantyPeriod > 0 && product.warrantyUnit === 'DAYS') return addDays(start, product.warrantyPeriod)
  if (product.warrantyPeriod > 0 && product.warrantyUnit === 'YEARS') return addYears(start, product.warrantyPeriod)
  if (product.warrantyPeriod > 0) return addMonths(start, product.warrantyPeriod)
  return addMonths(start, product.warrantyPeriodMonths)
}

export function audit(tx: Tx, userRole: string, action: string, module: string, recordId: string, description: string) {
  tx.insert('audit_logs', { userRole, action, module, recordId, description, timestamp: tx.now })
}

/** Label of a physical unit: IMEI 1, else serial, else "SN-id" (POS / returns). */
export function unitLabel(u: { id: number; imei1: string | null; serialNumber: string | null }): string {
  return (u.imei1 && u.imei1.trim() !== '' ? u.imei1 : null) ?? u.serialNumber ?? `SN-${u.id}`
}

/** Labels stored in serialImeisJson (a JSON array; tolerates older plain text). */
export function parseSerials(json: string): string[] {
  const raw = json.trim()
  if (raw === '' || raw === '[]') return []
  try {
    const arr = JSON.parse(raw)
    if (Array.isArray(arr)) {
      return arr.map(x => (typeof x === 'string' ? x : x && typeof x === 'object'
        ? [x.imei1, x.serial, x.imei2].find((v: unknown) => typeof v === 'string' && v.trim() !== '') ?? '' : String(x)).trim())
        .filter(Boolean)
    }
  } catch {
    // older plain text
  }
  return raw.replace(/^\[/, '').replace(/\]$/, '').split(',').map(s => s.trim().replace(/^"|"$/g, '').trim()).filter(Boolean)
}
