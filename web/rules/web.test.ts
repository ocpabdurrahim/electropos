// The web app's own writes against firestore.rules (run with rules.test.ts, same emulator).
import {
  assertFails, assertSucceeds, initializeTestEnvironment, type RulesTestContext, type RulesTestEnvironment,
} from '@firebase/rules-unit-testing'
import { deleteDoc, doc, runTransaction, serverTimestamp, setDoc, Timestamp, updateDoc, writeBatch } from 'firebase/firestore'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { afterAll, beforeAll, beforeEach, describe, it } from 'vitest'

const S = 'webshop'
let env: RulesTestEnvironment
const user = (email: string): RulesTestContext => env.authenticatedContext(`uid-${email}`, { email, email_verified: true })
const owner = () => user('owner@test.com')
const sales = () => user('sales@test.com')

beforeAll(async () => {
  env = await initializeTestEnvironment({
    projectId: 'demo-electropos',
    firestore: { rules: readFileSync(resolve(__dirname, '../../firebase/firestore.rules'), 'utf8'), host: '127.0.0.1', port: 8085 },
  })
})

afterAll(async () => {
  await env?.cleanup()
})

beforeEach(async () => {
  await env.clearFirestore()
  await env.withSecurityRulesDisabled(async ctx => {
    const db = ctx.firestore()
    await setDoc(doc(db, 'shops', S), { name: 'Web Shop', ownerUid: 'uid-owner@test.com', ownerEmail: 'owner@test.com', createdAt: 1, _ts: Timestamp.now() })
    await setDoc(doc(db, 'shops', S, 'meta', 'counters'), { nextBlock: 3, nextPhone: 2, inv: 4, _ts: Timestamp.now() })
    for (const [email, role] of [['owner@test.com', 'OWNER'], ['sales@test.com', 'SALESPERSON']]) {
      await setDoc(doc(db, 'shops', S, 'members', email), { email, name: email, role, active: true, addedBy: 'owner@test.com', createdAt: 1, _del: false, _ts: Timestamp.now() })
    }
    await setDoc(doc(db, 'shops', S, 'sales', '7'), { id: 7, invoiceNumber: 'INV-2026-A00007', _del: false, _ts: Timestamp.now() })
  })
})

const row = (id: number, f: Record<string, unknown> = {}) => ({ ...f, id, _del: false, _ts: serverTimestamp() })

describe('the web app', () => {
  it('claims its id block in a transaction (data/ids.ts)', async () => {
    const db = sales().firestore()
    await assertSucceeds(runTransaction(db, async t => {
      const counters = doc(db, 'shops', S, 'meta', 'counters')
      const snap = await t.get(counters)
      const block = snap.get('nextBlock') as number
      t.update(counters, { nextBlock: block + 1, _ts: serverTimestamp() })
      t.set(doc(db, 'shops', S, 'devices', String(block)), {
        block, kind: 'WEB', tillCode: 'W', email: 'sales@test.com', label: 'Chrome on Windows', createdAt: Date.now(), _ts: serverTimestamp(),
      })
    }))
  })

  it('takes several document numbers at once (data/actions.ts takeNumbers)', async () => {
    const db = sales().firestore()
    await assertSucceeds(updateDoc(doc(db, 'shops', S, 'meta', 'counters'), { inv: 5, cus: 1, _ts: serverTimestamp() }))
    await assertSucceeds(updateDoc(doc(db, 'shops', S, 'meta', 'counters'), { inv: 9, _ts: serverTimestamp() })) // skipped numbers already used
    await assertFails(updateDoc(doc(db, 'shops', S, 'meta', 'counters'), { inv: 8, _ts: serverTimestamp() }))
  })

  it('a salesperson writes a whole sale in one batch with big web ids, and tombstones by merge', async () => {
    const db = sales().firestore()
    const id = 3 * 2 ** 32 + 1
    const b = writeBatch(db)
    b.set(doc(db, 'shops', S, 'sales', String(id)), row(id, { invoiceNumber: 'INV-2026-W00005', totalAmount: 500, customerId: null }))
    b.set(doc(db, 'shops', S, 'sale_items', String(id + 1)), row(id + 1, { saleId: id, quantity: 1, unitPrice: 500 }))
    b.set(doc(db, 'shops', S, 'stock_transactions', String(id + 2)), row(id + 2, { productId: 1, quantityChange: -1 }))
    b.set(doc(db, 'shops', S, 'cash_register_transactions', String(id + 3)), row(id + 3, { type: 'SALE_CASH', amount: 500 }))
    b.set(doc(db, 'shops', S, 'audit_logs', String(id + 4)), row(id + 4, { action: 'SALE_COMPLETED' }))
    await assertSucceeds(b.commit())
    await assertSucceeds(setDoc(doc(db, 'shops', S, 'cash_register_transactions', String(id + 3)), { id: id + 3, _del: true, _ts: serverTimestamp() }, { merge: true }))
  })

  it('a salesperson may not write expenses or the shop profile even as a tombstone', async () => {
    const db = sales().firestore()
    await assertFails(setDoc(doc(db, 'shops', S, 'expenses', '5'), { id: 5, _del: true, _ts: serverTimestamp() }, { merge: true }))
    await assertFails(setDoc(doc(db, 'shops', S, 'shop_profile', '1'), row(1, { shopName: 'x' })))
  })

  it('the owner manages members (removal is a tombstone) and renames the shop document', async () => {
    const db = owner().firestore()
    await assertSucceeds(setDoc(doc(db, 'shops', S, 'members', 'new@test.com'), {
      email: 'new@test.com', name: 'New', role: 'MANAGER', active: true, addedBy: 'owner@test.com', createdAt: 1, _del: false, _ts: serverTimestamp(),
    }))
    await assertSucceeds(setDoc(doc(db, 'shops', S, 'members', 'new@test.com'), {
      email: 'new@test.com', name: 'New', role: 'MANAGER', active: false, addedBy: 'owner@test.com', createdAt: 1, _del: true, _ts: serverTimestamp(),
    }))
    await assertSucceeds(updateDoc(doc(db, 'shops', S), { name: 'Renamed', _ts: serverTimestamp() }))
    await assertFails(updateDoc(doc(sales().firestore(), 'shops', S), { name: 'Nope', _ts: serverTimestamp() }))
  })

  it('the app owner removes a shop creator', async () => {
    const appOwner = user('ocpabdurrahim@gmail.com').firestore()
    await assertSucceeds(setDoc(doc(appOwner, 'creators', 'friend@test.com'), { email: 'friend@test.com', name: 'F', approvedBy: 'ocpabdurrahim@gmail.com', approvedAt: 1, _ts: serverTimestamp() }))
    await assertFails(deleteDoc(doc(owner().firestore(), 'creators', 'friend@test.com')))
    await assertSucceeds(deleteDoc(doc(appOwner, 'creators', 'friend@test.com')))
  })
})
