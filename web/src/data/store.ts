// The web app's copy of one shop, kept in step with Firestore (docs/data-model.md, "Reading").
//
// Each of the 31 collections is read from the persistent cache first (instant), then a
// listener asks only for documents with _ts >= the newest cached one. Deletes are merge
// tombstones (_del: true), so they arrive through the same listener. Writes change memory at
// once and are sent in batches; caches are recomputed in memory (domain/caches.ts).
import {
  collection, doc, getDocFromCache, getDocFromServer, getDocsFromCache, onSnapshot, query, serverTimestamp, Timestamp, where,
  writeBatch, type DocumentSnapshot, type Firestore, type Unsubscribe,
} from 'firebase/firestore'
import { useSyncExternalStore } from 'react'
import { finalRows, ShopState, type AnyRow, type Change } from '../domain/state'
import { readsUsers, type Role } from '../domain/roles'
import { fromDoc, TABLES, toFields, type TableName } from './schema'

export interface Member {
  email: string
  name: string
  role: Role
  active: boolean
  addedBy: string
  createdAt: number
  deleted: boolean
}

export interface Device {
  block: number
  kind: 'PHONE' | 'WEB'
  tillCode: string
  email: string
  label: string
  createdAt: number
}

export interface ShopDoc {
  id: string
  name: string
  ownerUid: string
  ownerEmail: string
  createdAt: number
}

export interface ShopData {
  shopId: string
  /** False until every readable collection has come out of the cache (or the server). */
  ready: boolean
  /** True once the server has answered for every collection this role reads. */
  synced: boolean
  /** Write batches the server has not confirmed yet. */
  pending: number
  error: string | null
  /** This person is no longer an active member of the shop. */
  revoked: boolean
  shop: ShopDoc | null
  me: Member | null
  role: Role
  email: string
  members: Member[]
  devices: Device[]
  counters: Record<string, number> | null
  /** The PIN users could not be read (role, or rules). */
  usersDenied: boolean
  state: ShopState
  loadedCount: number
  tableCount: number
}

const BLOCK = 2 ** 32
const isPermissionError = (err: unknown) => (err as { code?: string })?.code === 'permission-denied'
const str = (v: unknown) => (typeof v === 'string' ? v : '')
const num = (v: unknown) => (typeof v === 'number' && Number.isFinite(v) ? v : 0)
const millis = (v: unknown) => (v instanceof Timestamp ? v.toMillis() : 0)
const asRole = (v: unknown): Role => (v === 'OWNER' || v === 'ADMIN' || v === 'MANAGER' ? v : 'SALESPERSON')

export function parseMember(id: string, d: Record<string, unknown>): Member {
  return {
    email: str(d.email) || id, name: str(d.name), role: asRole(d.role), active: d.active === true, addedBy: str(d.addedBy),
    createdAt: num(d.createdAt), deleted: d._del === true,
  }
}

function parseDevice(id: string, d: Record<string, unknown>): Device {
  return {
    block: typeof d.block === 'number' ? d.block : Number(id), kind: d.kind === 'PHONE' ? 'PHONE' : 'WEB', tillCode: str(d.tillCode),
    email: str(d.email), label: str(d.label), createdAt: num(d.createdAt),
  }
}

const key = (table: TableName, id: number) => `${table}/${id}`
const roleRank = (r: Role) => ['OWNER', 'ADMIN', 'MANAGER', 'SALESPERSON'].indexOf(r)

class ShopStore {
  private db: Firestore | null = null
  private shopId = ''
  private email = ''
  private initialRole: Role = 'SALESPERSON'
  private generation = 0
  private baseUnsubs: Unsubscribe[] = []
  private tableUnsubs = new Map<TableName, Unsubscribe>()
  private state = ShopState.empty()
  private incoming: Change[] = []
  private flushTimer: ReturnType<typeof setTimeout> | null = null
  private cacheLoaded = new Set<TableName>()
  private serverLoaded = new Set<TableName>()
  /** This browser has opened the shop before (its profile is cached), so the cache can be shown before the server answers. */
  private profileCached = false
  private inFlight = new Map<string, number>()
  /** Highest id seen per id block (deleted rows included): new ids never reuse one. */
  private maxLow = new Map<number, number>()
  private members = new Map<string, Member>()
  private membersFromServer = false
  private devices: Device[] = []
  private counters: Record<string, number> | null = null
  private shop: ShopDoc | null = null
  private usersDenied = false
  private pending = 0
  private error: string | null = null
  private revoked = false
  private listeners = new Set<() => void>()
  private cached: ShopData | null = null

  subscribe = (listener: () => void) => {
    this.listeners.add(listener)
    return () => { this.listeners.delete(listener) }
  }

  getSnapshot = (): ShopData => {
    if (!this.cached) {
      const readable = this.readable()
      const me = this.members.get(this.email) ?? null
      this.cached = {
        shopId: this.shopId,
        ready: readable.every(t => this.loaded(t)),
        synced: readable.every(t => this.serverLoaded.has(t)) && this.shop !== null,
        pending: this.pending,
        error: this.error,
        revoked: this.revoked,
        shop: this.shop,
        me,
        role: this.role(),
        email: this.email,
        members: [...this.members.values()].filter(m => !m.deleted)
          .sort((a, b) => roleRank(a.role) - roleRank(b.role) || a.email.localeCompare(b.email)),
        devices: [...this.devices].sort((a, b) => a.block - b.block),
        counters: this.counters,
        usersDenied: this.usersDenied || !readsUsers(this.role()),
        state: this.state,
        loadedCount: readable.filter(t => this.loaded(t)).length,
        tableCount: readable.length,
      }
    }
    return this.cached
  }

  /** The shop as it is in memory now (for actions). */
  current(): ShopState {
    this.flush()
    return this.state
  }

  get firestore(): Firestore | null {
    return this.db
  }

  get id(): string {
    return this.shopId
  }

  private changed() {
    this.cached = null
    this.listeners.forEach(l => l())
  }

  private role(): Role {
    const me = this.members.get(this.email)
    return me && !me.deleted ? me.role : this.initialRole
  }

  private readable(): TableName[] {
    return readsUsers(this.role()) && !this.usersDenied ? TABLES : TABLES.filter(t => t !== 'users')
  }

  start(db: Firestore, shopId: string, email: string, role: Role) {
    this.stop()
    this.db = db
    this.shopId = shopId
    this.email = email
    this.initialRole = role
    const gen = ++this.generation
    const shopRef = doc(db, 'shops', shopId)
    this.baseUnsubs.push(onSnapshot(shopRef, snap => {
      const d = snap.data({ serverTimestamps: 'estimate' })
      if (d) this.shop = { id: snap.id, name: str(d.name), ownerUid: str(d.ownerUid), ownerEmail: str(d.ownerEmail), createdAt: num(d.createdAt) }
      this.changed()
    }, err => this.fail(err, true)))
    this.baseUnsubs.push(onSnapshot(collection(db, 'shops', shopId, 'members'), { includeMetadataChanges: true }, snap => {
      if (gen !== this.generation) return
      snap.docChanges().forEach(c => {
        const d = c.doc.data()
        if (c.type === 'removed') this.members.delete(c.doc.id)
        else this.members.set(c.doc.id, parseMember(c.doc.id, d))
      })
      if (!snap.metadata.fromCache) this.membersFromServer = true
      const me = this.members.get(this.email)
      if (this.membersFromServer && (!me || !me.active || me.deleted)) this.revoked = true
      this.syncListeners()
      this.changed()
    }, err => this.fail(err, true)))
    this.baseUnsubs.push(onSnapshot(collection(db, 'shops', shopId, 'devices'), snap => {
      this.devices = snap.docs.map(d => parseDevice(d.id, d.data()))
      this.changed()
    }, () => {}))
    this.baseUnsubs.push(onSnapshot(doc(db, 'shops', shopId, 'meta', 'counters'), snap => {
      const d = snap.data()
      this.counters = d ? Object.fromEntries(Object.entries(d).filter(([, v]) => typeof v === 'number')) as Record<string, number> : null
      this.changed()
    }, () => {}))
    this.syncListeners()
  }

  stop() {
    this.generation++
    this.baseUnsubs.forEach(u => u())
    this.baseUnsubs = []
    this.tableUnsubs.forEach(u => u())
    this.tableUnsubs.clear()
    if (this.flushTimer) clearTimeout(this.flushTimer)
    this.flushTimer = null
    this.incoming = []
    this.state = ShopState.empty()
    this.cacheLoaded.clear()
    this.serverLoaded.clear()
    this.profileCached = false
    this.inFlight.clear()
    this.maxLow.clear()
    this.members.clear()
    this.membersFromServer = false
    this.devices = []
    this.counters = null
    this.shop = null
    this.usersDenied = false
    this.pending = 0
    this.error = null
    this.revoked = false
    this.db = null
    this.shopId = ''
    this.changed()
  }

  /** Listens to what the current role may read, and drops what it may not. */
  private syncListeners() {
    if (!this.db) return
    const readable = new Set(this.readable())
    for (const table of TABLES) {
      const listening = this.tableUnsubs.has(table)
      if (readable.has(table) && !listening) {
        this.tableUnsubs.set(table, () => {})
        void this.listen(table, this.generation)
      } else if (!readable.has(table) && listening) {
        this.tableUnsubs.get(table)!()
        this.tableUnsubs.delete(table)
        this.cacheLoaded.delete(table)
        this.serverLoaded.delete(table)
        const ids = [...this.state.raw[table].keys()]
        this.state = this.state.with(ids.map(id => ({ table, id, del: true as const })))
      }
    }
  }

  /** An empty cache for a shop this browser never opened would show a blank shop, so then only the server counts. */
  private loaded(table: TableName): boolean {
    return this.serverLoaded.has(table) || (this.cacheLoaded.has(table) && this.profileCached)
  }

  private async listen(table: TableName, gen: number) {
    const col = collection(this.db!, 'shops', this.shopId, table)
    let newest = 0
    try {
      const cached = await getDocsFromCache(col)
      if (gen !== this.generation || !this.tableUnsubs.has(table)) return
      cached.forEach(d => {
        this.applyDoc(table, d)
        if (!d.metadata.hasPendingWrites) newest = Math.max(newest, millis(d.get('_ts')))
      })
      if (table === 'shop_profile' && !cached.empty) {
        this.profileCached = true
        this.changed()
      }
    } catch {
      // Nothing cached yet: the listener below downloads everything.
    }
    if (gen !== this.generation || !this.tableUnsubs.has(table)) return
    this.cacheLoaded.add(table)
    this.scheduleFlush()
    const changes = query(col, where('_ts', '>=', Timestamp.fromMillis(newest)))
    const unsub = onSnapshot(changes, { includeMetadataChanges: true }, snap => {
      if (gen !== this.generation) return
      // 'removed' only means the document left this query (e.g. a pending server time): rows go away by tombstone.
      snap.docChanges().forEach(c => { if (c.type !== 'removed') this.applyDoc(table, c.doc) })
      if (!snap.metadata.fromCache && !this.serverLoaded.has(table)) {
        this.serverLoaded.add(table)
        this.changed()
      }
      this.scheduleFlush()
    }, err => {
      if (table === 'users' && isPermissionError(err)) {
        // Not this role's to read: the rest of the shop carries on.
        this.usersDenied = true
        this.tableUnsubs.delete(table)
        this.changed()
        return
      }
      this.fail(err, false)
    })
    if (this.tableUnsubs.has(table)) this.tableUnsubs.set(table, unsub)
    else unsub()
  }

  private applyDoc(table: TableName, d: DocumentSnapshot) {
    const data = d.data({ serverTimestamps: 'estimate' })
    if (!data) return
    const id = Number(d.id)
    if (!Number.isSafeInteger(id)) return
    this.seeId(id)
    if (this.inFlight.has(key(table, id)) && !d.metadata.hasPendingWrites) return
    if (data._del === true) this.incoming.push({ table, id, del: true })
    else this.incoming.push({ table, id, row: fromDoc(table, d.id, data) as unknown as AnyRow })
  }

  private seeId(id: number) {
    const block = Math.floor(id / BLOCK)
    const low = id - block * BLOCK
    if (low > (this.maxLow.get(block) ?? 0)) this.maxLow.set(block, low)
  }

  /** Highest id number used in [block] that this tab has seen. */
  maxUsedInBlock(block: number): number {
    return this.maxLow.get(block) ?? 0
  }

  private scheduleFlush() {
    if (this.flushTimer) return
    this.flushTimer = setTimeout(() => {
      this.flushTimer = null
      this.flush()
      this.changed()
    }, 16)
  }

  private flush() {
    if (this.incoming.length === 0) return
    const batch = this.incoming
    this.incoming = []
    this.state = this.state.with(batch)
  }

  private fail(err: unknown, membership: boolean) {
    if (isPermissionError(err) && membership) this.revoked = true
    this.error = err instanceof Error ? err.message : String(err)
    this.changed()
  }

  clearError() {
    this.error = null
    this.changed()
  }

  // ---- Writes ----------------------------------------------------------------------

  /**
   * Applies one action's rows in memory and sends them: whole documents with _ts and _del,
   * tombstones as merge writes, at most 500 writes per batch. Resolves when the server has them.
   */
  commit(changes: readonly Change[]): Promise<void> {
    if (!this.db || changes.length === 0) return Promise.resolve()
    this.flush()
    const after = this.state.with(changes)
    const rows = finalRows(changes, after)
    this.state = after
    for (const c of rows) {
      this.seeId(c.id)
      this.inFlight.set(key(c.table, c.id), (this.inFlight.get(key(c.table, c.id)) ?? 0) + 1)
    }
    this.pending++
    this.changed()
    const db = this.db
    const shopId = this.shopId
    const gen = this.generation
    const commits: Promise<void>[] = []
    for (let i = 0; i < rows.length; i += 500) {
      const batch = writeBatch(db)
      for (const c of rows.slice(i, i + 500)) {
        const ref = doc(db, 'shops', shopId, c.table, String(c.id))
        if (c.del) batch.set(ref, { id: c.id, _del: true, _ts: serverTimestamp() }, { merge: true })
        else batch.set(ref, { ...toFields(c.table, c.row as never), id: c.id, _del: false, _ts: serverTimestamp() })
      }
      commits.push(batch.commit())
    }
    const done = Promise.all(commits).then(() => undefined)
    done.catch(async err => {
      if (gen !== this.generation) return
      this.error = `Not saved: ${err instanceof Error ? err.message : String(err)}`
      await this.reload(rows)
    }).finally(() => {
      if (gen !== this.generation) return
      const settled: Change[] = []
      for (const c of rows) {
        const k = key(c.table, c.id)
        const n = (this.inFlight.get(k) ?? 1) - 1
        if (n <= 0) {
          this.inFlight.delete(k)
          settled.push(c)
        } else this.inFlight.set(k, n)
      }
      this.pending--
      this.changed()
      // Whatever arrived for these documents while they were in flight is in the cache now.
      void this.refreshFromCache(settled, gen)
    })
    return done
  }

  private async refreshFromCache(rows: readonly Change[], gen: number) {
    if (!this.db || rows.length === 0) return
    const fixes: Change[] = []
    for (const c of rows) {
      try {
        const snap = await getDocFromCache(doc(this.db, 'shops', this.shopId, c.table, String(c.id)))
        const data = snap.data({ serverTimestamps: 'estimate' })
        if (!data) continue
        if (data._del === true) fixes.push({ table: c.table, id: c.id, del: true })
        else fixes.push({ table: c.table, id: c.id, row: fromDoc(c.table, String(c.id), data) as unknown as AnyRow })
      } catch {
        // not cached
      }
      if (gen !== this.generation) return
    }
    if (fixes.length && gen === this.generation) {
      this.incoming.push(...fixes)
      this.scheduleFlush()
    }
  }

  /** After a refused write: the rows as the server has them. */
  private async reload(rows: readonly Change[]) {
    if (!this.db) return
    const fixes: Change[] = []
    for (const c of rows) {
      const ref = doc(this.db, 'shops', this.shopId, c.table, String(c.id))
      let snap: DocumentSnapshot | null = null
      try {
        snap = await getDocFromServer(ref)
      } catch {
        try { snap = await getDocFromCache(ref) } catch { snap = null }
      }
      const data = snap?.data()
      if (!data || data._del === true) fixes.push({ table: c.table, id: c.id, del: true })
      else fixes.push({ table: c.table, id: c.id, row: fromDoc(c.table, String(c.id), data) as unknown as AnyRow })
    }
    this.state = this.state.with(fixes)
    this.changed()
  }
}

export const store = new ShopStore()

export function useShop(): ShopData {
  return useSyncExternalStore(store.subscribe, store.getSnapshot)
}
