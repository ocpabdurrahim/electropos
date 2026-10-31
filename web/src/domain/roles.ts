// What each member role may do: the phone's AccessControl (core/security/AccessControl.kt) for
// the screens, and firestore.rules for the writes. OWNER and ADMIN have the phone's ADMIN rights.

export type Role = 'OWNER' | 'ADMIN' | 'MANAGER' | 'SALESPERSON'
export const ROLES: Role[] = ['OWNER', 'ADMIN', 'MANAGER', 'SALESPERSON']

export const ROLE_LABELS: Record<Role, string> = {
  OWNER: 'Owner',
  ADMIN: 'Admin',
  MANAGER: 'Manager',
  SALESPERSON: 'Salesperson',
}

export const ROLE_HINTS: Record<Role, string> = {
  OWNER: 'Everything; the shop belongs to them',
  ADMIN: 'Everything, including staff and PIN users',
  MANAGER: 'Sales, purchases, suppliers, products, reports. Not staff or PIN users',
  SALESPERSON: 'POS, sales, returns and customers. No purchases, costs or profit',
}

export type Feature =
  | 'POS' | 'SALES_HISTORY' | 'SALES_RETURN' | 'CANCEL_SALE' | 'CUSTOMERS' | 'PRODUCTS_VIEW' | 'PRODUCTS_EDIT'
  | 'CATEGORIES_BRANDS' | 'PURCHASES' | 'SUPPLIERS' | 'VIEW_COSTS_AND_PROFIT' | 'SHOP_PROFILE' | 'SETTINGS' | 'USERS'
  | 'EXPENSES' | 'CASH_BOOK' | 'REPORTS' | 'WARRANTY' | 'MEMBERS'

const SALESPERSON: Feature[] = ['POS', 'SALES_HISTORY', 'SALES_RETURN', 'CUSTOMERS', 'PRODUCTS_VIEW', 'SETTINGS', 'WARRANTY']

export function can(role: Role | null | undefined, feature: Feature): boolean {
  switch (role) {
    case 'OWNER': case 'ADMIN': return true
    case 'MANAGER': return feature !== 'USERS' && feature !== 'MEMBERS'
    case 'SALESPERSON': return SALESPERSON.includes(feature)
    default: return false
  }
}

/** The role written as userRole in audit rows by the web (the phone writes its PIN user's role or name). */
export const auditRole = (role: Role) => (role === 'OWNER' || role === 'ADMIN' ? 'ADMIN' : role)

/** Roles that may read the PIN users (firestore.rules canRead 'users'). */
export const readsUsers = (role: Role | null | undefined) => role === 'OWNER' || role === 'ADMIN' || role === 'MANAGER'
