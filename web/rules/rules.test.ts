// firestore.rules against the Firestore emulator. Run from the repo root:
//   npx firebase-tools emulators:exec --only firestore --project demo-electropos "npm --prefix web run test:rules"
import {
  assertFails, assertSucceeds, initializeTestEnvironment, type RulesTestContext, type RulesTestEnvironment,
} from '@firebase/rules-unit-testing'
import {
  collectionGroup, deleteDoc, doc, getDoc, getDocs, increment, query, serverTimestamp, setDoc, Timestamp, updateDoc, where,
  writeBatch,
} from 'firebase/firestore'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { afterAll, beforeAll, beforeEach, describe, it } from 'vitest'

const PROJECT = 'demo-electropos'
const S = 'shop1'
const APP_OWNER = 'ocpabdurrahim@gmail.com'

let env: RulesTestEnvironment

const user = (email: string, verified = true): RulesTestContext =>
  env.authenticatedContext(`uid-${email}`, { email, email_verified: verified })

const owner = () => user('owner@test.com')
const admin = () => user('admin@test.com')
const manager = () => user('manager@test.com')
const sales = () => user('sales@test.com')
const stranger = () => user('stranger@test.com')

const member = (email: string, role: string, active = true) => ({
  email, name: email.split('@')[0], role, active, addedBy: 'owner@test.com', createdAt: 1, _del: false, _ts: serverTimestamp(),
})

/** A synced row as a client writes it. */
const row = (id: number, fields: Record<string, unknown> = {}) => ({ id, ...fields, _del: false, _ts: serverTimestamp() })

beforeAll(async () => {
  env = await initializeTestEnvironment({
    projectId: PROJECT,
    firestore: {
      rules: readFileSync(resolve(__dirname, '../../firebase/firestore.rules'), 'utf8'),
      host: '127.0.0.1',
      port: 8085,
    },
  })
})

afterAll(async () => {
  await env?.cleanup()
})

/** A shop with one member per role, written with rules switched off. */
async function seed() {
  await env.clearFirestore()
  await env.withSecurityRulesDisabled(async ctx => {
    const db = ctx.firestore()
    await setDoc(doc(db, 'shops', S), { name: 'Masum Mobile', ownerUid: 'uid-owner@test.com', ownerEmail: 'owner@test.com', createdAt: 1, _ts: Timestamp.now() })
    await setDoc(doc(db, 'shops', S, 'meta', 'counters'), { nextBlock: 1, nextPhone: 1, _ts: Timestamp.now() })
    for (const [email, role, active] of [
      ['owner@test.com', 'OWNER', true], ['admin@test.com', 'ADMIN', true], ['manager@test.com', 'MANAGER', true],
      ['sales@test.com', 'SALESPERSON', true], ['gone@test.com', 'MANAGER', false],
    ] as const) {
      await setDoc(doc(db, 'shops', S, 'members', email), { ...member(email, role, active), _ts: Timestamp.now() })
    }
  })
}

/** The batch a phone sends when it puts a shop online. */
function createShopBatch(ctx: RulesTestContext, shopId: string, email: string) {
  const db = ctx.firestore()
  const b = writeBatch(db)
  b.set(doc(db, 'shops', shopId), { name: 'New Shop', ownerUid: `uid-${email}`, ownerEmail: email, createdAt: 1, _ts: serverTimestamp() })
  b.set(doc(db, 'shops', shopId, 'members', email), member(email, 'OWNER'))
  b.set(doc(db, 'shops', shopId, 'meta', 'counters'), { nextBlock: 1, nextPhone: 1, _ts: serverTimestamp() })
  b.set(doc(db, 'shops', shopId, 'devices', '0'), { block: 0, kind: 'PHONE', tillCode: 'A', email, label: 'Phone', createdAt: 1, _ts: serverTimestamp() })
  return b
}

describe('putting a shop online', () => {
  beforeEach(() => env.clearFirestore())

  it('the app owner may create a shop with its owner record, counters and device 0', async () => {
    await assertSucceeds(createShopBatch(user(APP_OWNER), 'newShop', APP_OWNER).commit())
  })

  it('someone not approved may not', async () => {
    await assertFails(createShopBatch(stranger(), 'newShop', 'stranger@test.com').commit())
  })

  it('an approved creator may', async () => {
    await env.withSecurityRulesDisabled(async ctx => {
      await setDoc(doc(ctx.firestore(), 'creators', 'friend@test.com'), { email: 'friend@test.com', name: 'Friend', approvedBy: APP_OWNER, approvedAt: 1, _ts: Timestamp.now() })
    })
    await assertSucceeds(createShopBatch(user('friend@test.com'), 'friendShop', 'friend@test.com').commit())
  })

  it('an unverified email may not', async () => {
    await assertFails(createShopBatch(user(APP_OWNER, false), 'newShop', APP_OWNER).commit())
  })

  it('only the app owner manages creators', async () => {
    await assertSucceeds(setDoc(doc(user(APP_OWNER).firestore(), 'creators', 'a@test.com'), { email: 'a@test.com', name: 'A', approvedBy: APP_OWNER, approvedAt: 1, _ts: serverTimestamp() }))
    await assertFails(setDoc(doc(stranger().firestore(), 'creators', 'stranger@test.com'), { email: 'stranger@test.com', name: 'S', approvedBy: 'x', approvedAt: 1, _ts: serverTimestamp() }))
  })
})

describe('reading', () => {
  beforeEach(seed)

  it('members read the shop and its rows; strangers and inactive members do not', async () => {
    await assertSucceeds(getDoc(doc(sales().firestore(), 'shops', S, 'products', '1')))
    await assertFails(getDoc(doc(stranger().firestore(), 'shops', S, 'products', '1')))
    await assertFails(getDoc(doc(user('gone@test.com').firestore(), 'shops', S, 'products', '1')))
  })

  it('PIN users are visible to owner, admin and manager only', async () => {
    await assertSucceeds(getDoc(doc(manager().firestore(), 'shops', S, 'users', '1')))
    await assertFails(getDoc(doc(sales().firestore(), 'shops', S, 'users', '1')))
  })

  it('anyone signed in finds the shops they belong to', async () => {
    const db = sales().firestore()
    await assertSucceeds(getDocs(query(collectionGroup(db, 'members'), where('email', '==', 'sales@test.com'))))
    await assertFails(getDocs(query(collectionGroup(db, 'members'), where('email', '==', 'owner@test.com'))))
  })
})

describe('writing rows', () => {
  beforeEach(seed)

  it('a salesperson writes the selling side', async () => {
    const db = sales().firestore()
    await assertSucceeds(setDoc(doc(db, 'shops', S, 'sales', '4294967297'), row(4294967297, { invoiceNumber: 'INV-2026-W00001', totalAmount: 500 })))
    await assertSucceeds(setDoc(doc(db, 'shops', S, 'customers', '7'), row(7, { name: 'Rahim' })))
  })

  it('a salesperson may not write purchases, products, suppliers or expenses', async () => {
    const db = sales().firestore()
    for (const col of ['purchases', 'products', 'suppliers', 'expenses', 'shop_profile']) {
      await assertFails(setDoc(doc(db, 'shops', S, col, '9'), row(9)))
    }
  })

  it('a manager writes purchases and products but not PIN users', async () => {
    const db = manager().firestore()
    await assertSucceeds(setDoc(doc(db, 'shops', S, 'purchases', '9'), row(9)))
    await assertSucceeds(setDoc(doc(db, 'shops', S, 'products', '9'), row(9)))
    await assertFails(setDoc(doc(db, 'shops', S, 'users', '9'), row(9)))
    await assertSucceeds(setDoc(doc(admin().firestore(), 'shops', S, 'users', '9'), row(9)))
  })

  it('rows must carry their own id, the tombstone flag and the server time', async () => {
    const db = owner().firestore()
    await assertFails(setDoc(doc(db, 'shops', S, 'sales', '10'), row(11)))
    await assertFails(setDoc(doc(db, 'shops', S, 'sales', '10'), { id: 10, _ts: serverTimestamp() }))
    await assertFails(setDoc(doc(db, 'shops', S, 'sales', '10'), { id: 10, _del: false, _ts: Timestamp.fromMillis(5) }))
    await assertFails(setDoc(doc(db, 'shops', S, 'sales', 'abc'), row(0)))
    await assertFails(setDoc(doc(db, 'shops', S, 'not_a_table', '10'), row(10)))
  })

  it('nothing is ever deleted', async () => {
    await env.withSecurityRulesDisabled(async ctx => {
      await setDoc(doc(ctx.firestore(), 'shops', S, 'sales', '12'), { id: 12, _del: false, _ts: Timestamp.now() })
    })
    await assertFails(deleteDoc(doc(owner().firestore(), 'shops', S, 'sales', '12')))
    await assertSucceeds(setDoc(doc(owner().firestore(), 'shops', S, 'sales', '12'), { ...row(12), _del: true }))
  })
})

describe('members', () => {
  beforeEach(seed)

  it('owner and admin add staff; nobody else becomes an owner', async () => {
    await assertSucceeds(setDoc(doc(admin().firestore(), 'shops', S, 'members', 'new@test.com'), member('new@test.com', 'SALESPERSON')))
    await assertFails(setDoc(doc(admin().firestore(), 'shops', S, 'members', 'boss@test.com'), member('boss@test.com', 'OWNER')))
    await assertFails(setDoc(doc(manager().firestore(), 'shops', S, 'members', 'new2@test.com'), member('new2@test.com', 'SALESPERSON')))
  })

  it('the owner record cannot be changed by an admin', async () => {
    await assertFails(setDoc(doc(admin().firestore(), 'shops', S, 'members', 'owner@test.com'), member('owner@test.com', 'ADMIN')))
  })
})

describe('id blocks and numbers', () => {
  beforeEach(seed)

  it('a member claims the next block together with the counter', async () => {
    const db = sales().firestore()
    const b = writeBatch(db)
    b.update(doc(db, 'shops', S, 'meta', 'counters'), { nextBlock: 2, _ts: serverTimestamp() })
    b.set(doc(db, 'shops', S, 'devices', '1'), { block: 1, kind: 'WEB', tillCode: 'W', email: 'sales@test.com', label: 'Chrome', createdAt: 1, _ts: serverTimestamp() })
    await assertSucceeds(b.commit())
  })

  it('a salesperson may not link a phone, and blocks cannot be skipped or reused', async () => {
    const db = sales().firestore()
    const phone = writeBatch(db)
    phone.update(doc(db, 'shops', S, 'meta', 'counters'), { nextBlock: 2, nextPhone: 2, _ts: serverTimestamp() })
    phone.set(doc(db, 'shops', S, 'devices', '1'), { block: 1, kind: 'PHONE', tillCode: 'B', email: 'sales@test.com', label: 'Phone', createdAt: 1, _ts: serverTimestamp() })
    await assertFails(phone.commit())

    const mdb = manager().firestore()
    const skip = writeBatch(mdb)
    skip.update(doc(mdb, 'shops', S, 'meta', 'counters'), { nextBlock: 3, _ts: serverTimestamp() })
    skip.set(doc(mdb, 'shops', S, 'devices', '2'), { block: 2, kind: 'WEB', tillCode: 'W', email: 'manager@test.com', label: 'x', createdAt: 1, _ts: serverTimestamp() })
    await assertFails(skip.commit())
  })

  it('web number counters only grow', async () => {
    const db = sales().firestore()
    await assertSucceeds(updateDoc(doc(db, 'shops', S, 'meta', 'counters'), { inv: increment(1), _ts: serverTimestamp() }))
    await assertFails(updateDoc(doc(db, 'shops', S, 'meta', 'counters'), { inv: 0, _ts: serverTimestamp() }))
    await assertFails(updateDoc(doc(db, 'shops', S, 'meta', 'counters'), { hacked: 1, _ts: serverTimestamp() }))
  })
})
