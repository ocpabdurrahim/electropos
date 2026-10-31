// Row ids (docs/data-model.md, "Ids"): every tab owns one block of 2^32 ids, claimed in a
// transaction that moves meta/counters.nextBlock and creates devices/{block} (kind WEB, till W).
// The block and the next id live in sessionStorage; a Web Lock keeps a duplicated tab (which
// copies sessionStorage) from using the same block.
import { doc, runTransaction, serverTimestamp, type Firestore } from 'firebase/firestore'

export const BLOCK_SIZE = 2 ** 32
/** nextBlock stays below 2^21, so ids stay below 2^53 (firestore.rules). */
export const MAX_BLOCKS = 2 ** 21

interface Stored {
  block: number
  next: number
}

const storageKey = (shopId: string, email: string) => `electropos.idblock.${shopId}.${email}`
const lockName = (shopId: string, block: number) => `electropos-idblock-${shopId}-${block}`

function readStored(shopId: string, email: string): Stored | null {
  try {
    const raw = sessionStorage.getItem(storageKey(shopId, email))
    if (!raw) return null
    const v = JSON.parse(raw) as Stored
    return Number.isSafeInteger(v.block) && v.block > 0 && Number.isSafeInteger(v.next) && v.next > 0 ? v : null
  } catch {
    return null
  }
}

function writeStored(shopId: string, email: string, v: Stored) {
  try {
    sessionStorage.setItem(storageKey(shopId, email), JSON.stringify(v))
  } catch {
    // Storage blocked: the block lasts while the page stays open.
  }
}

/** Holds a lock for the rest of the tab's life; false when another tab holds it. */
function hold(name: string): Promise<boolean> {
  const locks = (navigator as Navigator & { locks?: LockManager }).locks
  if (!locks) return Promise.resolve(true)
  return new Promise(resolve => {
    void locks.request(name, { ifAvailable: true }, lock => {
      if (!lock) {
        resolve(false)
        return undefined
      }
      resolve(true)
      return new Promise<void>(() => {})
    })
  })
}

export function deviceLabel(): string {
  const ua = navigator.userAgent
  const browser = /Edg\//.test(ua) ? 'Edge' : /OPR\//.test(ua) ? 'Opera' : /Firefox\//.test(ua) ? 'Firefox'
    : /Chrome\//.test(ua) ? 'Chrome' : /Safari\//.test(ua) ? 'Safari' : 'Browser'
  const os = /Windows/.test(ua) ? 'Windows' : /Android/.test(ua) ? 'Android' : /iPhone|iPad/.test(ua) ? 'iOS'
    : /Mac OS X/.test(ua) ? 'macOS' : /Linux/.test(ua) ? 'Linux' : ''
  return os ? `${browser} on ${os}` : browser
}

export class IdBlock {
  private block = 0
  private nextLow = 0
  private claiming: Promise<void> | null = null

  constructor(private db: Firestore, private shopId: string, private email: string, private maxUsed: (block: number) => number) {}

  get current(): number {
    return this.block
  }

  /** Makes sure this tab owns a block (claims one the first time it writes). Needs a connection. */
  ensure(): Promise<void> {
    if (this.block > 0) return Promise.resolve()
    this.claiming ??= this.open().finally(() => { this.claiming = null })
    return this.claiming
  }

  private async open() {
    const stored = readStored(this.shopId, this.email)
    if (stored && (await hold(lockName(this.shopId, stored.block)))) {
      this.use(stored.block, stored.next)
      return
    }
    for (let attempt = 0; attempt < 3; attempt++) {
      const block = await this.claim()
      if (await hold(lockName(this.shopId, block))) {
        this.use(block, 1)
        return
      }
    }
    throw new Error('Could not reserve record numbers for this tab. Close other ElectroPOS tabs and try again.')
  }

  private use(block: number, next: number) {
    this.block = block
    this.nextLow = Math.max(next, this.maxUsed(block) + 1)
    writeStored(this.shopId, this.email, { block, next: this.nextLow })
  }

  private claim(): Promise<number> {
    const countersRef = doc(this.db, 'shops', this.shopId, 'meta', 'counters')
    return runTransaction(this.db, async t => {
      const snap = await t.get(countersRef)
      if (!snap.exists()) throw new Error('This shop is not fully online yet (no counters). Open the Cloud screen on the phone that put it online.')
      const block = Number(snap.get('nextBlock') ?? 1)
      if (!Number.isSafeInteger(block) || block < 1 || block + 1 >= MAX_BLOCKS) throw new Error('No more id blocks are free for this shop.')
      t.update(countersRef, { nextBlock: block + 1, _ts: serverTimestamp() })
      t.set(doc(this.db, 'shops', this.shopId, 'devices', String(block)), {
        block, kind: 'WEB', tillCode: 'W', email: this.email, label: deviceLabel(), createdAt: Date.now(), _ts: serverTimestamp(),
      })
      return block
    })
  }

  /** The next free id of this tab's block: block·2^32 + n. */
  next(): number {
    if (this.block <= 0) throw new Error('No id block yet')
    const low = this.nextLow++
    if (low >= BLOCK_SIZE) throw new Error('This tab used up its id block; reload the page.')
    writeStored(this.shopId, this.email, { block: this.block, next: this.nextLow })
    return this.block * BLOCK_SIZE + low
  }
}
