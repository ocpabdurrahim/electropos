// PIN users of the phones (AuthRepository user management), hashed exactly like
// SecurityUtils: PBKDF2-HMAC-SHA256, 120 000 rounds, 256 bits, salt = the hex text's UTF-8 bytes.
import type { User } from '../data/schema'
import { audit, check, fail } from './common'
import type { Tx } from './state'

export const PIN_ROLES = ['ADMIN', 'MANAGER', 'SALESPERSON'] as const

export const PIN_ROLE_LABELS: Record<string, string> = {
  ADMIN: 'Admin (owner)',
  MANAGER: 'Manager',
  SALESPERSON: 'Salesperson',
}

const PBKDF2_PREFIX = 'pbkdf2$'
const ITERATIONS = 120_000

const hex = (bytes: Uint8Array) => [...bytes].map(b => b.toString(16).padStart(2, '0')).join('')

/** 16 random bytes as 32 hex characters (SecurityUtils.generateSalt). */
export function generateSalt(): string {
  const b = new Uint8Array(16)
  crypto.getRandomValues(b)
  return hex(b)
}

export async function hashPin(pin: string, salt: string): Promise<string> {
  const enc = new TextEncoder()
  const key = await crypto.subtle.importKey('raw', enc.encode(pin), 'PBKDF2', false, ['deriveBits'])
  const bits = await crypto.subtle.deriveBits({ name: 'PBKDF2', hash: 'SHA-256', salt: enc.encode(salt), iterations: ITERATIONS }, key, 256)
  return PBKDF2_PREFIX + hex(new Uint8Array(bits))
}

async function sha256(data: Uint8Array): Promise<Uint8Array> {
  return new Uint8Array(await crypto.subtle.digest('SHA-256', data))
}

/** The old salted SHA-256 ×1001 hash, only to verify PINs stored before PBKDF2. */
async function legacyHashPin(pin: string, salt: string): Promise<string> {
  let digest = await sha256(new TextEncoder().encode(`${salt}:${pin}:electropos_salt_key`))
  for (let i = 0; i < 1000; i++) digest = await sha256(digest)
  return hex(digest)
}

export async function verifyPin(pin: string, salt: string, expected: string): Promise<boolean> {
  const computed = expected.startsWith(PBKDF2_PREFIX) ? await hashPin(pin, salt) : await legacyHashPin(pin, salt)
  return computed === expected
}

export function validatePin(pin: string) {
  check(pin.length >= 4 && pin.length <= 12 && /^\d+$/.test(pin), 'PIN must be 4 to 12 digits.')
}

/** Who acts on the web: the signed-in member, shown like a PIN user in the audit log. */
export interface WebAdmin {
  username: string
  /** The audit role (OWNER and ADMIN members write ADMIN). */
  role: string
}

/** createUser; [pinHash]/[salt] come from hashPin (async, done before the action). */
export function createUser(tx: Tx, me: WebAdmin, input: { name: string; username: string; role: string; pinHash: string; salt: string }): number {
  const name = input.name.trim()
  const username = input.username.trim().toLowerCase()
  check(name !== '', 'Name is required.')
  check(/^[a-z0-9._-]{3,30}$/.test(username), 'Username: 3-30 letters, digits, dot, dash or underscore.')
  check((PIN_ROLES as readonly string[]).includes(input.role), 'Unknown role.')
  if (tx.where('users', 'username', username).length > 0) fail(`Username '${username}' is already taken.`)
  const user = tx.insert('users', {
    name, username, pinHash: input.pinHash, salt: input.salt, role: input.role, isActive: true, createdAt: tx.now, updatedAt: tx.now,
  })
  audit(tx, me.role, 'USER_CREATED', 'SECURITY', String(user.id), `${me.username} created user '${username}' (${input.role})`)
  return user.id
}

export function updateUser(tx: Tx, me: WebAdmin, userId: number, name: string, role: string, isActive: boolean) {
  const user: User = tx.get('users', userId) ?? fail('User not found.')
  check(name.trim() !== '', 'Name is required.')
  check((PIN_ROLES as readonly string[]).includes(role), 'Unknown role.')
  const losesAdmin = user.role === 'ADMIN' && user.isActive && (role !== 'ADMIN' || !isActive)
  if (losesAdmin && tx.all('users').filter(u => u.role === 'ADMIN' && u.isActive).length <= 1) fail('At least one active admin is needed.')
  tx.put('users', { ...user, name: name.trim(), role, isActive, updatedAt: tx.now })
  audit(tx, me.role, 'USER_UPDATED', 'SECURITY', String(userId),
    `${me.username} updated '${user.username}': role ${role}, ${isActive ? 'active' : 'inactive'}`)
}

export function resetUserPin(tx: Tx, me: WebAdmin, userId: number, pinHash: string, salt: string) {
  const user: User = tx.get('users', userId) ?? fail('User not found.')
  tx.put('users', { ...user, pinHash, salt, updatedAt: tx.now })
  audit(tx, me.role, 'USER_PIN_RESET', 'SECURITY', String(userId), `${me.username} reset the PIN of '${user.username}'`)
}
