# ElectroPOS cloud data model and sync rules

The Android app (Room database, offline-first) and the web app (whole shop in
memory) share one Cloud Firestore database and follow these rules. Change both
apps and `firebase/firestore.rules` together.

## Shops, members and roles

Everything belongs to a **shop**. Whoever puts a shop online becomes its
**OWNER**. The owner (or an admin) adds staff by their Google email address.

| Role | Writes | Notes |
|---|---|---|
| `OWNER` | everything | exactly one, the creator; can't be removed |
| `ADMIN` | everything | manages members |
| `MANAGER` | everything except `users` and `members` | |
| `SALESPERSON` | selling side only (see "Write rights") | the web hides costs and profit, but the rules can't hide single fields |

Only `OWNER`, `ADMIN` and `MANAGER` members can link a **phone** to the shop
(phones are shop devices; staff work on them with their PIN accounts).
Every role can use the **web app** with their own Google account.

### Who may create a shop

The app owner is `ocpabdurrahim@gmail.com` (in `firestore.rules` `isAppAdmin`,
Android `AppOwner.EMAILS`, web `APP_OWNERS`; change all three together). The app
owner may always create shops; anyone else needs `creators/{lower-case email}`,
which only the app owner writes (web: Settings → Shop creators).

## Where the data lives

```
shops/{shopId}                         owner + name (ownerUid, ownerEmail, name, createdAt, _ts)
shops/{shopId}/members/{email}         email, name, role, active, addedBy, createdAt, _ts, _del
shops/{shopId}/meta/counters           nextBlock, nextPhone, web number counters (see "Numbers")
shops/{shopId}/devices/{block}         block, kind (PHONE|WEB), tillCode, email, label, createdAt, _ts
shops/{shopId}/{table}/{id}            one document per Room row (tables below)
creators/{email}                       email, name, approvedBy, approvedAt, _ts
```

A person finds their shops with a collection-group query on `members` where
`email == <their lower-case email>`; `_del: true` or `active: false` records are ignored.

### Synced tables (collection name = Room table name)

`shop_profile`, `users`, `categories`, `subcategories`, `brands`, `units`,
`suppliers`, `customers`, `products`, `product_variants`, `product_serial_imei`,
`product_price_history`, `purchases`, `purchase_items`, `purchase_returns`,
`purchase_return_items`, `supplier_payments`, `supplier_ledger_entries`, `sales`,
`sale_items`, `sales_returns`, `sales_return_items`, `customer_payments`,
`customer_ledger_entries`, `stock_transactions`, `payment_transactions`,
`expenses`, `expense_categories`, `cash_register_transactions`,
`warranty_records`, `audit_logs`.

## Documents

- A document holds **every column of the row** under the same name (Room field
  names), plus `_ts` (server timestamp, set on every write; the rules reject
  anything else) and `_del` (boolean tombstone).
- The document id is the row id as a decimal string, and the `id` field equals it.
  `shop_profile` has the single document `1`.
- Values are what Room stores, converted by the column's type in the entity:
  Kotlin `Boolean` → boolean (SQLite keeps 0/1), `Long`/`Int` → integer,
  `Double` → number (not rounded), `String` → string, `null` stays `null`.
  **Moments** (`createdAt`, `saleDate`…) are epoch milliseconds. Status and type
  fields are the same strings the Kotlin code uses (`"COMPLETED"`, `"PURCHASE"`…).
- A reader converts back by the column's type: Firestore may hand an integer for
  a `Double` column (the web writes `500`, not `500.0`) or a number for a
  `Long` column; booleans become 0/1 on the phone. A missing field takes the
  entity's default (older documents, newer columns).
- **Writes** are whole documents (`set`, every column). A delete is a merge-write
  of `{id, _del: true, _ts}` (the last fields stay in the document). Documents are
  never removed from Firestore. A writer never sends a row only because its cache
  columns or its `updatedAt` changed.
- **Last write wins** by the order writes reach the server (not edit time).

### Local-only columns (never sent, ignored when received)

| Table | Columns |
|---|---|
| `shop_profile` | `themeMode`, `rememberLogin`, `lastActiveUserId`, `logoUri`, and every `*NextNumber` counter |
| `users` | `lastLoginAt` |
| `brands` | `logoUri` |
| `products` | `imageUri` |

### Cache columns (sent, but every client recomputes them)

| Table | Columns | Recomputed from |
|---|---|---|
| `products` | `currentStock` | Σ `stock_transactions.quantityChange` |
| `products` | `purchasePrice` | weighted-average replay of purchase stock movements (below) |
| `product_variants` | `currentStock`, `purchasePrice` | same, per variant |

`purchasePrice` is the entered cost until the product (or variant) has a
`PURCHASE` movement; until then a change to it alone is sent like a real column.
| `customers` | `currentDue` | Σ `customer_ledger_entries.signedAmount` |
| `suppliers` | `currentDue` | Σ `supplier_ledger_entries.signedAmount` |
| `customer_ledger_entries`, `supplier_ledger_entries` | `runningBalance` | running Σ ordered by `createdAt`, `id` |
| `sales` | `paidAmount`, `dueAmount`, `paymentStatus` | customer invoice settlement (below) |
| `purchases` | `paidAmount`, `dueAmount`, `paymentStatus` | supplier invoice settlement (below) |

**Weighted average cost** (Android `WeightedCost.replay`, web `domain/caches.ts`):
walk a product's (or variant's) `stock_transactions` by `createdAt, id`, starting
with stock 0 and the average at the first movement's `unitCost`.
- `PURCHASE` of q at `unitCost` c: with s = max(stock, 0), the average becomes
  `(s·avg + q·c)/(s + q)`, or c when s + q ≤ 0; stock += q.
- `PURCHASE_CANCELLED` of q (= −`quantityChange`): c is the `unitCost` of that
  purchase's own `PURCHASE` movement (same `referenceId` and `variantId`, the one
  with the same quantity first, each matched once). For **products only**, if
  stock − q > 0 and r = `(stock·avg − q·c)/(stock − q)` > 0, the average becomes r.
  Variants only change stock here (the phone never restored a variant's cost).
  stock −= q.
- Every other movement only changes the stock.
- A product or variant with no `PURCHASE` movement keeps its stored cost.

**Invoice settlement** (same as `CustomerRepository.reallocateSalePayments` /
`SupplierRepository.reallocatePurchasePayments`): money taken against an invoice
(its counter payment `PAY-<number>` and payments recorded for it), less cash
refunded on its returns, settles that invoice first; the rest of the account's
outstanding ledger balance sits on the newest unpaid invoices. It applies to a
customer's sales (`customerId` > 0) that are not `CANCELLED`/`VOID`, and to
purchases that are not `CANCELLED`; walk-in and cancelled invoices keep the
paid/due/status they were written with.

## Ids

Rows keep the app's Long ids. To keep them unique across devices, each client
takes an **id block** of 2^32 ids: block `b` owns ids `b·2^32 + 1 … (b+1)·2^32 − 1`.

- `meta/counters.nextBlock` hands out blocks; a client claims one in a
  transaction that also creates `devices/{block}`. `nextBlock` stays below 2^21
  (ids below 2^53, safe in JavaScript).
- The phone that puts the shop online is **block 0** (its existing ids stay).
- A phone gets a fresh block every time it joins (also after a restore). A web
  tab claims its block on its first write; the block lives in sessionStorage per
  shop and signed-in email, and a Web Lock gives a duplicated tab a new block.
- New rows take the next free id inside the block; the phone never uses SQLite's
  own sequence for synced tables (a trigger refuses any insert outside the block).

## Numbers people see

Invoice, purchase, return and payment numbers and customer/supplier codes must
not clash between devices:

- each phone has a **till code**, and its new numbers carry it; its counters live
  on that phone only. Till codes run `A … V, X, Y, Z, AA, AB, …` (single `W` is
  the web's and is skipped); `meta/counters.nextPhone` is the index of the next
  code in that list (0 = `A`, taken by the phone that puts the shop online), and a
  phone claims it in the same transaction as its block;
- the web takes numbers from `meta/counters` in a transaction (till `W`; field
  per series below, read + increment by 1, the new value is the number), so it
  needs a connection to sell;
- while a phone is **not** online it keeps the old formats (left column).

| Series | Offline (old) | Online, till T, counter n | Web counter |
|---|---|---|---|
| Invoice | `{invoicePrefix}{year}-{000000}` | `{invoicePrefix}{year}-T{00000}` → `INV-2026-B00042` | `inv` |
| Purchase | `PUR-{year}-{000000}` | `PUR-{year}-T{00000}` | `pur` |
| Purchase return | `PRT-{year}-{000000}` | `PRT-{year}-T{00000}` | `prt` |
| Sales return | `{salesReturnPrefix}{year}-{000000}` | `{salesReturnPrefix}{year}-T{00000}` | `srt` |
| Supplier payment | `SPAY-{year}-{000000}` | `SPAY-{year}-T{00000}` | `spay` |
| Customer payment | `{customerPaymentPrefix}{000000}` | `{customerPaymentPrefix}T{00000}` | `cpay` |
| Customer code | `{customerCodePrefix}{0000}` | `{customerCodePrefix}T{0000}` → `CUS-B0007` | `cus` |
| Supplier code | `SUP-{0000}` | `SUP-T{0000}` | `sup` |

`{year}` is the device's current year; prefixes come from `shop_profile` with the
same blank fallbacks as the Kotlin generators. Counters start at 1; if a number is
already taken (seen in the data) the generator moves on to the next one.

## Reading

**Phone:** per collection, a listener on `_ts >= newest applied` (from Room's
point of view the cloud is just another writer). Each snapshot is applied in
one Room transaction: update the row, or insert it if missing. Rows the phone
still has to upload are skipped (its own upload wins next). Every parent is
checked first: a row whose parent hasn't arrived yet is parked and retried, and
a deletion is parked while rows on the phone still point to it. Caches are
recomputed in the same transaction.

**Sync issues** (listed on the phone's Cloud screen): a row that breaks a UNIQUE
index (IMEI, document number, username) is not applied; the same name twice in
categories, brands, units or expense categories is applied and listed; a change
the server refuses is listed and dropped (if the account is no longer an active
member, changes wait instead).

**Web:** keeps the whole shop in memory: read each collection from the
persistent cache, then listen to `_ts >= newest`. Firestore's cache must be
**unlimited** (an evicted document would never come back).

## Writing

**Phone:** database triggers record every inserted, changed (real columns only,
not caches) or deleted row in an outbox; the uploader sends them in batches of
400 and clears what it sent.

**Web:** applies the same rules as the phone (`web/src/domain/*` mirrors the
Kotlin repositories) and writes all rows of one action in one batch.

## Joining, restore and reset

- **Put this shop online** (phone): claims block 0 and till `A`, creates the
  shop + owner member, then uploads every row.
- **Join** (phone): needs an OWNER/ADMIN/MANAGER member account; a safety copy
  is made, every synced table is emptied, then the whole shop downloads before
  the app can be used.
- **Restore / Reset** on a linked phone unlinks it first (no tombstones are
  sent); linking again downloads the cloud copy (the cloud wins).
- Sample data can't be loaded on a linked phone.

## Write rights

| Collections | Roles |
|---|---|
| `members` | OWNER, ADMIN (nobody else becomes OWNER) |
| `users` | OWNER, ADMIN (read: OWNER, ADMIN, MANAGER) |
| `shop_profile`, `categories`, `subcategories`, `brands`, `units`, `suppliers`, `products`, `product_variants`, `product_price_history`, `purchases`, `purchase_items`, `purchase_returns`, `purchase_return_items`, `supplier_payments`, `supplier_ledger_entries`, `expenses`, `expense_categories` | OWNER, ADMIN, MANAGER |
| `sales`, `sale_items`, `sales_returns`, `sales_return_items`, `customers`, `customer_payments`, `customer_ledger_entries`, `stock_transactions`, `product_serial_imei`, `payment_transactions`, `cash_register_transactions`, `warranty_records`, `audit_logs` | every role |
| `devices`, `meta/counters` | members claiming a block / a number (shape checked) |
