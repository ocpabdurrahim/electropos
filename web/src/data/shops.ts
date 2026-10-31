// Finding the shops a person belongs to (docs/data-model.md, "Shops, members and roles"),
// managing members, and the app owner's list of people who may put shops online.
import {
  collection, collectionGroup, deleteDoc, doc, getDoc, getDocs, onSnapshot, query, serverTimestamp, setDoc, updateDoc, where,
  type Firestore, type Unsubscribe,
} from 'firebase/firestore'
import type { Role } from '../domain/roles'
import { parseMember, type Member } from './store'

export interface ShopChoice {
  id: string
  name: string
  role: Role
}

/** Shops where this email is an active member (records with _del or active false are ignored), by name. */
export async function findShops(db: Firestore, email: string): Promise<ShopChoice[]> {
  const snap = await getDocs(query(collectionGroup(db, 'members'), where('email', '==', email)))
  const out: ShopChoice[] = []
  for (const d of snap.docs) {
    const member = parseMember(d.id, d.data())
    const shopRef = d.ref.parent.parent
    if (!member.active || member.deleted || !shopRef) continue
    try {
      const s = await getDoc(doc(db, 'shops', shopRef.id))
      if (!s.exists()) continue
      out.push({ id: s.id, name: String(s.get('name') ?? '') || 'Shop', role: member.role })
    } catch {
      // Not readable right now (access just changed): leave it out.
    }
  }
  return out.sort((a, b) => a.name.localeCompare(b.name))
}

const LAST_KEY = 'electropos.lastShop'

export function lastShop(email: string): string | null {
  try {
    return localStorage.getItem(`${LAST_KEY}.${email}`)
  } catch {
    return null
  }
}

export function rememberShop(email: string, shopId: string | null) {
  try {
    if (shopId) localStorage.setItem(`${LAST_KEY}.${email}`, shopId)
    else localStorage.removeItem(`${LAST_KEY}.${email}`)
  } catch {
    // Storage blocked: the choice lasts for this visit.
  }
}

// ---- Members (OWNER and ADMIN) --------------------------------------------------------

export function saveMember(db: Firestore, shopId: string, m: { email: string; name: string; role: Role; active: boolean; createdAt?: number }, by: string) {
  const email = m.email.trim().toLowerCase()
  return setDoc(doc(db, 'shops', shopId, 'members', email), {
    email, name: m.name.trim(), role: m.role, active: m.active, addedBy: by, createdAt: m.createdAt ?? Date.now(), _del: false,
    _ts: serverTimestamp(),
  })
}

/** Takes someone out of the shop: the record stays as a tombstone (documents are never removed). */
export function removeMember(db: Firestore, shopId: string, m: Member, by: string) {
  return setDoc(doc(db, 'shops', shopId, 'members', m.email), {
    email: m.email, name: m.name, role: m.role, active: false, addedBy: by, createdAt: m.createdAt, _del: true, _ts: serverTimestamp(),
  })
}

/** The shop document's name follows the shop profile (OWNER / ADMIN only). */
export function renameShop(db: Firestore, shopId: string, name: string) {
  return updateDoc(doc(db, 'shops', shopId), { name: name.trim(), _ts: serverTimestamp() })
}

// ---- Who may put a shop online --------------------------------------------------------

/** Must match isAppAdmin() in firebase/firestore.rules and AppOwner.EMAILS in the Android app. */
export const APP_OWNERS = ['ocpabdurrahim@gmail.com']

export const isAppOwner = (email: string | null | undefined) => !!email && APP_OWNERS.includes(email.toLowerCase())

export interface Creator {
  email: string
  name: string
  approvedBy: string
  approvedAt: number
}

export function watchCreators(db: Firestore, onChange: (list: Creator[]) => void): Unsubscribe {
  return onSnapshot(collection(db, 'creators'), snap => {
    onChange(snap.docs.map(d => {
      const v = d.data()
      return {
        email: String(v.email ?? d.id), name: String(v.name ?? ''), approvedBy: String(v.approvedBy ?? ''),
        approvedAt: typeof v.approvedAt === 'number' ? v.approvedAt : 0,
      }
    }).sort((a, b) => b.approvedAt - a.approvedAt))
  }, () => onChange([]))
}

export function addCreator(db: Firestore, email: string, name: string, by: string) {
  const e = email.trim().toLowerCase()
  return setDoc(doc(db, 'creators', e), { email: e, name: name.trim(), approvedBy: by, approvedAt: Date.now(), _ts: serverTimestamp() })
}

export function removeCreator(db: Firestore, email: string) {
  return deleteDoc(doc(db, 'creators', email))
}
