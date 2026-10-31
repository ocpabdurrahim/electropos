// Local-time dates, the way the phone's Calendar and SimpleDateFormat give them.

export const DAY_MS = 24 * 60 * 60 * 1000

export function startOfDay(ts: number = Date.now()): number {
  const d = new Date(ts)
  d.setHours(0, 0, 0, 0)
  return d.getTime()
}

export function endOfDay(ts: number = Date.now()): number {
  const d = new Date(ts)
  d.setHours(23, 59, 59, 999)
  return d.getTime()
}

/** Local midnight of a calendar date (month 1-12). */
export function localDate(year: number, month: number, day: number, h = 0, m = 0, s = 0, ms = 0): number {
  return new Date(year, month - 1, day, h, m, s, ms).getTime()
}

const daysIn = (year: number, monthIndex: number) => new Date(year, monthIndex + 1, 0).getDate()

/** Calendar.add(MONTH, n): the day of month is clamped to the target month's length. */
export function addMonths(ts: number, n: number): number {
  const d = new Date(ts)
  const day = d.getDate()
  d.setDate(1)
  d.setMonth(d.getMonth() + n)
  d.setDate(Math.min(day, daysIn(d.getFullYear(), d.getMonth())))
  return d.getTime()
}

export function addDays(ts: number, n: number): number {
  const d = new Date(ts)
  d.setDate(d.getDate() + n)
  return d.getTime()
}

export const addYears = (ts: number, n: number) => addMonths(ts, 12 * n)

const MONTHS = ['January', 'February', 'March', 'April', 'May', 'June', 'July', 'August', 'September', 'October', 'November', 'December']
const DAYS = ['Sunday', 'Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday']
const p2 = (n: number) => String(n).padStart(2, '0')

/**
 * SimpleDateFormat-style patterns used by the app: yyyy, yy, MMMM, MMM, MM, M, dd, d, EEEE, EEE,
 * HH, hh, mm, ss, a. Anything else is copied.
 */
export function formatDate(ts: number, pattern = 'dd MMM yyyy'): string {
  if (!ts || ts <= 0) return 'N/A'
  const d = new Date(ts)
  const tokens: Record<string, () => string> = {
    yyyy: () => String(d.getFullYear()),
    yy: () => p2(d.getFullYear() % 100),
    MMMM: () => MONTHS[d.getMonth()],
    MMM: () => MONTHS[d.getMonth()].slice(0, 3),
    MM: () => p2(d.getMonth() + 1),
    M: () => String(d.getMonth() + 1),
    dd: () => p2(d.getDate()),
    d: () => String(d.getDate()),
    EEEE: () => DAYS[d.getDay()],
    EEE: () => DAYS[d.getDay()].slice(0, 3),
    HH: () => p2(d.getHours()),
    hh: () => p2(d.getHours() % 12 === 0 ? 12 : d.getHours() % 12),
    mm: () => p2(d.getMinutes()),
    ss: () => p2(d.getSeconds()),
    a: () => (d.getHours() < 12 ? 'AM' : 'PM'),
  }
  return pattern.replace(/yyyy|yy|MMMM|MMM|MM|M|dd|d|EEEE|EEE|HH|hh|mm|ss|a/g, t => tokens[t]())
}

export const formatDateTime = (ts: number) => formatDate(ts, 'dd MMM yyyy, hh:mm a')

/** yyyy-MM-dd of a moment, for <input type="date">. */
export const dateInput = (ts: number) => formatDate(ts, 'yyyy-MM-dd')

/** Local midnight of an <input type="date"> value. */
export function fromDateInput(value: string): number | null {
  const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(value)
  return m ? localDate(Number(m[1]), Number(m[2]), Number(m[3])) : null
}

/** The chosen day with the time of day of [timeOf] (FinanceDates.onDayWithTimeOf). */
export function onDayWithTimeOf(day: number, timeOf: number = Date.now()): number {
  return startOfDay(day) + (timeOf - startOfDay(timeOf))
}
