// Numbers people see, web series (docs/data-model.md, "Numbers people see"): till W,
// counter fields in meta/counters, prefixes from shop_profile with the Kotlin fallbacks.
import type { ShopProfile } from '../data/schema'
import type { Series, ShopState } from './state'

export const WEB_TILL = 'W'

export const SERIES: Series[] = ['inv', 'pur', 'prt', 'srt', 'spay', 'cpay', 'cus', 'sup']

const pad = (n: number, width: number) => String(n).padStart(width, '0')
const prefix = (value: string, fallback: string) => (value.trim() === '' ? fallback : value)

/** The [n]th number of [series] for till [till] (W on the web). */
export function formatNumber(series: Series, n: number, profile: ShopProfile, year: number, till = WEB_TILL): string {
  switch (series) {
    case 'inv': return `${prefix(profile.invoicePrefix, 'INV-')}${year}-${till}${pad(n, 5)}`
    case 'pur': return `PUR-${year}-${till}${pad(n, 5)}`
    case 'prt': return `PRT-${year}-${till}${pad(n, 5)}`
    case 'srt': return `${prefix(profile.salesReturnPrefix, 'SRT-')}${year}-${till}${pad(n, 5)}`
    case 'spay': return `SPAY-${year}-${till}${pad(n, 5)}`
    case 'cpay': return `${prefix(profile.customerPaymentPrefix, 'CPAY-')}${till}${pad(n, 5)}`
    case 'cus': return `${prefix(profile.customerCodePrefix, 'CUS-')}${till}${pad(n, 4)}`
    case 'sup': return `SUP-${till}${pad(n, 4)}`
  }
}

/** True when [value] is already used by a row of that series. */
export function numberTaken(state: ShopState, series: Series, value: string): boolean {
  switch (series) {
    case 'inv': return state.where('sales', 'invoiceNumber', value).length > 0
    case 'pur': return state.where('purchases', 'purchaseNumber', value).length > 0
    case 'prt': return state.where('purchase_returns', 'returnNumber', value).length > 0
    case 'srt': return state.where('sales_returns', 'returnNumber', value).length > 0
    case 'spay': return state.where('supplier_payments', 'paymentNumber', value).length > 0
    case 'cpay': return state.where('customer_payments', 'paymentNumber', value).length > 0
    case 'cus': return state.where('customers', 'customerCode', value).length > 0
    case 'sup': return state.where('suppliers', 'supplierCode', value).length > 0
  }
}

/** Next free number after counter value [current]: counters start at 1 and skip numbers already seen. */
export function nextNumber(state: ShopState, series: Series, current: number, year: number, till = WEB_TILL): { n: number; text: string } {
  let n = Math.max(0, Math.trunc(current)) + 1
  let text = formatNumber(series, n, state.profile, year, till)
  while (numberTaken(state, series, text)) {
    n++
    text = formatNumber(series, n, state.profile, year, till)
  }
  return { n, text }
}
