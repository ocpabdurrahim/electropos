// Money helpers with the Kotlin app's rounding and text formats.

/** Kotlin roundMoney: (value * 100).roundToLong() / 100 (half rounds up). */
export function roundMoney(value: number): number {
  return Math.round(value * 100) / 100
}

export const coerceIn = (v: number, lo: number, hi: number) => Math.min(Math.max(v, lo), hi)
export const sum = <T>(list: readonly T[], f: (x: T) => number) => list.reduce((a, x) => a + f(x), 0)

/**
 * Java's Double.toString, which Kotlin string templates use ("৳${amount}" in audit texts):
 * "500.0", "1363.95", "1.0E7".
 */
export function kotlinDouble(v: number): string {
  if (!Number.isFinite(v)) return Number.isNaN(v) ? 'NaN' : v > 0 ? 'Infinity' : '-Infinity'
  if (v === 0) return Object.is(v, -0) ? '-0.0' : '0.0'
  const abs = Math.abs(v)
  if (abs >= 1e-3 && abs < 1e7) {
    const s = String(v)
    return s.includes('.') ? s : `${s}.0`
  }
  const [mantissa, exp] = v.toExponential().split('e')
  const m = mantissa.includes('.') ? mantissa : `${mantissa}.0`
  return `${m}E${Number(exp)}`
}

/** Kotlin Int text of a Double (`toInt()` truncates toward zero). */
export const kotlinToInt = (v: number) => Math.trunc(v)

const twoDecimals = new Intl.NumberFormat('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 })

/** CurrencyUtils.format: "৳ 1,500.00". */
export function formatMoney(amount: number | null | undefined, symbol = '৳'): string {
  return `${symbol} ${twoDecimals.format(amount ?? 0)}`
}

/** CurrencyUtils.toInput: "1500" for whole amounts, "1363.95" otherwise. */
export function toInput(amount: number): string {
  const r = roundMoney(amount)
  return Number.isInteger(r) ? String(r) : r.toFixed(2)
}

const BANGLA_DIGITS = '০১২৩৪৫৬৭৮৯'

/** Parses typed amounts: "1,500", "250.5", Bangla digits. Null when it is not a number. */
export function parseAmount(text: string): number | null {
  const western = text.trim().replace(/[০-৯]/g, d => String(BANGLA_DIGITS.indexOf(d))).replace(/[,\s]/g, '')
  if (!western) return null
  if (!/^-?\d*\.?\d*$/.test(western) || western === '.' || western === '-') return null
  const v = Number(western)
  return Number.isFinite(v) ? v : null
}

/** Payment methods money is kept in (PaymentMethods.ALL), upper-case in the paymentMethod columns. */
export const PAYMENT_METHODS = ['CASH', 'BKASH', 'NAGAD', 'ROCKET', 'CARD', 'BANK'] as const

/** Upper-case, and blank (old rows) counts as cash. */
export const normalizeMethod = (m: string | null | undefined) => (m ?? '').trim().toUpperCase() || 'CASH'

export function methodLabel(m: string | null | undefined): string {
  const v = normalizeMethod(m)
  switch (v) {
    case 'CASH': return 'Cash'
    case 'BKASH': return 'bKash'
    case 'NAGAD': return 'Nagad'
    case 'ROCKET': return 'Rocket'
    case 'CARD': return 'Card'
    case 'BANK': return 'Bank'
    case 'MULTIPLE': return 'Multiple'
    case 'OTHER': return 'Other'
    default: return v.charAt(0) + v.slice(1).toLowerCase()
  }
}
