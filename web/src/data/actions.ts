// Runs a domain action against the live shop: validate first (no number or id used),
// then claim this tab's id block if needed, take the document numbers from meta/counters
// in one transaction, build the rows again with them and send them in one batch.
import { doc, runTransaction, serverTimestamp, type Firestore } from 'firebase/firestore'
import { nextNumber } from '../domain/numbers'
import { act, type Ctx, type Series, type ShopState, type Tx } from '../domain/state'
import { IdBlock } from './ids'
import { store } from './store'

let blocks: IdBlock | null = null
let actorName = 'Admin'

/** Called when a shop opens: who acts (written to createdBy / salespersonName) and where ids come from. */
export function openSession(db: Firestore, shopId: string, email: string, name: string) {
  blocks = new IdBlock(db, shopId, email, b => store.maxUsedInBlock(b))
  actorName = name.trim() || email.split('@')[0]
}

/** The member's name, once the members list has loaded (the shop's own name for them wins). */
export function setActor(name: string) {
  if (name.trim()) actorName = name.trim()
}

export function closeSession() {
  blocks = null
}

export const currentActor = () => actorName

async function takeNumbers(db: Firestore, shopId: string, series: Series[], state: ShopState, year: number): Promise<Map<Series, string>> {
  const ref = doc(db, 'shops', shopId, 'meta', 'counters')
  return runTransaction(db, async t => {
    const snap = await t.get(ref)
    if (!snap.exists()) throw new Error('This shop has no number counters yet. Put it online again from the phone.')
    const out = new Map<Series, string>()
    const update: Record<string, number | ReturnType<typeof serverTimestamp>> = { _ts: serverTimestamp() }
    for (const s of series) {
      const current = Number(snap.get(s) ?? 0)
      const { n, text } = nextNumber(state, s, Number.isFinite(current) ? current : 0, year)
      out.set(s, text)
      update[s] = n
    }
    t.update(ref, update)
    return out
  })
}

const timeout = (ms: number) => new Promise<'timeout'>(resolve => setTimeout(() => resolve('timeout'), ms))

/**
 * Runs [fn] (a domain function on a working copy) and saves its rows. Domain errors are thrown
 * before anything is written. Resolves with fn's result once the server has the rows (or after a
 * few seconds, when they are still queued).
 */
export async function runAction<R>(fn: (tx: Tx) => R): Promise<R> {
  const db = store.firestore
  if (!db || !blocks) throw new Error('No shop is open')
  const year = new Date().getFullYear()

  // 1. A dry run finds the mistakes and the number series the action needs.
  const wanted: Series[] = []
  let fake = -1
  const dry: Ctx = {
    now: Date.now(), actor: actorName, year, newId: () => fake--,
    number: s => {
      if (!wanted.includes(s)) wanted.push(s)
      return `…${s}`
    },
  }
  act(store.current(), dry, fn)

  // 2. Ids and numbers (both need the server).
  await blocks.ensure()
  const numbers = wanted.length ? await takeNumbers(db, store.id, wanted, store.current(), year) : new Map<Series, string>()

  // 3. The real rows.
  const ids = blocks
  const ctx: Ctx = {
    now: Date.now(), actor: actorName, year, newId: () => ids.next(),
    number: s => {
      const v = numbers.get(s)
      if (!v) throw new Error('The shop changed while saving; please try again.')
      return v
    },
  }
  const { result, changes } = act(store.current(), ctx, fn)
  await Promise.race([store.commit(changes), timeout(5000)])
  return result
}
