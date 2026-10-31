package com.example.sync

import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteDatabase
import androidx.room.withTransaction
import com.example.data.local.database.AppDatabase

/**
 * Writes cloud documents into Room (docs/data-model.md, "Reading"). From Room's point of view the
 * cloud is just another writer: one transaction per delivery, applying = 1 (so the triggers stay
 * quiet), update the row or insert it. A row this phone still has to upload is left alone (its own
 * upload wins next); a UNIQUE clash becomes a sync issue; a row whose parent hasn't arrived waits
 * in sync_pending_inbound and is retried after every delivery, and so does a deletion while rows on
 * this phone still point to it. Caches are recomputed before commit.
 *
 * Foreign keys stay immediate: every parent is checked before a row is written, and SQLite would
 * also defer ON DELETE RESTRICT, which would let a deletion from the cloud orphan this phone's rows.
 */
class SyncApplier(private val db: AppDatabase) {
    private val syncDao = db.syncDao()
    private val sql get() = db.openHelper.writableDatabase

    data class Result(val applied: Int, val skipped: Int, val parked: Int, val conflicts: Int)

    private enum class Outcome { APPLIED, SKIPPED, PARKED, CONFLICT }

    /**
     * Applies one collection's documents. Returns null (and changes nothing) when the phone is no
     * longer linked to [shopId], e.g. it was unlinked or reset while the delivery was on its way.
     * [recompute] = false leaves the caches for one [recomputeAll] at the end (downloading a whole shop).
     */
    suspend fun apply(shopId: String, table: SyncTable, docs: List<RemoteDoc>, recompute: Boolean = true): Result? =
        db.withTransaction {
            if (syncDao.flags()?.shopId != shopId) return@withTransaction null
            syncDao.setApplying(true)
            val affected = Affected()
            var applied = 0
            var skipped = 0
            var parked = 0
            var conflicts = 0
            var newest = syncDao.cursor(table.name) ?: 0L
            for (doc in docs) {
                if (doc.pendingWrite) {
                    skipped++
                    continue
                }
                when (applyOne(table, doc.id, doc.data, doc.ts ?: 0L, affected)) {
                    Outcome.APPLIED -> applied++
                    Outcome.SKIPPED -> skipped++
                    Outcome.PARKED -> parked++
                    Outcome.CONFLICT -> conflicts++
                }
                doc.ts?.let { if (it > newest) newest = it }
            }
            if (applied > 0) retryParked(affected)
            if (recompute) CacheRecompute(db).run(affected)
            syncDao.setCursor(SyncCursorEntity(table.name, newest))
            syncDao.setApplying(false)
            Result(applied, skipped, parked, conflicts)
        }

    /** Tries the waiting records again (e.g. a sale whose customer just arrived). */
    suspend fun retryParked(shopId: String): Int? = db.withTransaction {
        if (syncDao.flags()?.shopId != shopId) return@withTransaction null
        syncDao.setApplying(true)
        val affected = Affected()
        val n = retryParked(affected)
        CacheRecompute(db).run(affected)
        syncDao.setApplying(false)
        n
    }

    /** Works out every cache from the records (after the whole shop was downloaded). */
    suspend fun recomputeAll(shopId: String): Unit? = db.withTransaction {
        if (syncDao.flags()?.shopId != shopId) return@withTransaction null
        syncDao.setApplying(true)
        CacheRecompute(db).all()
        syncDao.setApplying(false)
    }

    private suspend fun retryParked(affected: Affected): Int {
        var done = 0
        for (p in syncDao.parked().sortedBy { SyncTables.order(it.tbl) }) {
            val table = SyncTables.find(p.tbl) ?: continue
            if (applyOne(table, p.rowId.toString(), SyncMapper.fromJson(p.data), p.ts, affected) == Outcome.APPLIED) done++
        }
        return done
    }

    private suspend fun applyOne(table: SyncTable, docId: String, data: Map<String, Any?>, ts: Long, affected: Affected): Outcome {
        val id = SyncMapper.idOf(data, docId) ?: return Outcome.SKIPPED
        if (table.name == SyncTables.SHOP_PROFILE && id != 1L) return Outcome.SKIPPED
        // This phone changed the row and hasn't uploaded it yet: its version goes up next and wins
        if (syncDao.hasPending(table.name, id)) return Outcome.SKIPPED

        val before = if (table.name in Affected.FEEDING) SyncMapper.readRow(sql, table, id) else null
        if (data["_del"] == true) {
            if (table.name == SyncTables.SHOP_PROFILE) return Outcome.SKIPPED
            // Rows here still point to it (their own deletions may follow, or they are this phone's): wait
            val child = childStillThere(table.name, id)
            if (child != null) {
                park(table, id, data, ts, "Still used by $child on this phone")
                return Outcome.PARKED
            }
            try {
                sql.delete(table.name, "id = ?", arrayOf<Any>(id))
            } catch (e: SQLiteConstraintException) {
                park(table, id, data, ts, "Still used by other records on this phone")
                return Outcome.PARKED
            }
            before?.let { affected.note(table.name, it) }
            syncDao.unpark(table.name, id)
            return Outcome.APPLIED
        }

        for ((column, parent) in table.parents) {
            val col = table.columns.first { it.name == column }
            val parentId = SyncMapper.sqlValue(col, data[column], table.defaults[column]) as? Long ?: continue
            if (!exists(parent, parentId)) {
                park(table, id, data, ts, "Waiting for $parent $parentId")
                return Outcome.PARKED
            }
        }

        try {
            val n = sql.update(table.name, SQLiteDatabase.CONFLICT_ABORT, SyncMapper.docToValues(table, data, withLocalDefaults = false), "id = ?", arrayOf<Any>(id))
            if (n == 0) sql.insert(table.name, SQLiteDatabase.CONFLICT_ABORT, SyncMapper.docToValues(table, data, withLocalDefaults = true))
        } catch (e: SQLiteConstraintException) {
            if (e.message.orEmpty().contains("FOREIGN KEY", ignoreCase = true)) {
                park(table, id, data, ts, "Waiting for a related record")
                return Outcome.PARKED
            }
            syncDao.addConflict(SyncConflictEntity(tbl = table.name, rowId = id, kind = KIND_DUPLICATE, message = duplicateMessage(table, data, e)))
            syncDao.unpark(table.name, id)
            return Outcome.CONFLICT
        }
        before?.let { affected.note(table.name, it) }
        affected.note(table.name, data)
        syncDao.unpark(table.name, id)
        syncDao.clearConflict(table.name, id, KIND_DUPLICATE)
        if (table.name in SyncTables.NAMED) checkSameName(table, id, data["name"] as? String)
        return Outcome.APPLIED
    }

    /** The first table with a row pointing to [table] [id] through a foreign key, or null. */
    private fun childStillThere(table: String, id: Long): String? {
        for ((child, column) in CHILDREN[table].orEmpty()) {
            val found = sql.query("SELECT 1 FROM $child WHERE $column = ? LIMIT 1", arrayOf<Any>(id)).use { it.moveToFirst() }
            if (found) return child
        }
        return null
    }

    private fun exists(table: String, id: Long): Boolean =
        sql.query("SELECT 1 FROM $table WHERE id = ? LIMIT 1", arrayOf<Any>(id)).use { it.moveToFirst() }

    private suspend fun park(table: SyncTable, id: Long, data: Map<String, Any?>, ts: Long, reason: String) {
        syncDao.park(SyncPendingInboundEntity(tbl = table.name, rowId = id, data = SyncMapper.toJson(data), ts = ts, reason = reason))
    }

    /** Offline, two phones can add a category (brand, unit, expense category) with the same name. */
    private suspend fun checkSameName(table: SyncTable, id: Long, name: String?) {
        if (name.isNullOrBlank()) return
        val other = sql.query("SELECT id FROM ${table.name} WHERE name = ? COLLATE NOCASE AND id != ? LIMIT 1", arrayOf<Any>(name, id))
            .use { it.moveToFirst() }
        if (other) {
            syncDao.addConflict(
                SyncConflictEntity(tbl = table.name, rowId = id, kind = KIND_SAME_NAME, message = "Two ${label(table.name)} entries are called \"$name\". Rename or remove one of them.")
            )
        } else {
            syncDao.clearConflict(table.name, id, KIND_SAME_NAME)
        }
    }

    private fun duplicateMessage(table: SyncTable, data: Map<String, Any?>, e: SQLiteConstraintException): String {
        val column = Regex("UNIQUE constraint failed: \\w+\\.(\\w+)").find(e.message.orEmpty())?.groupValues?.get(1)
        val value = column?.let { data[it] }
        val what = if (column != null) "${columnLabel(column)} \"$value\"" else "the same unique value"
        return "A ${label(table.name)} from the cloud has $what, which another record on this phone already uses, " +
            "so it was not added here. Fix one of the two (for example cancel or return one sale)."
    }

    companion object {
        /** parent table -> (child table, foreign key column) */
        private val CHILDREN: Map<String, List<Pair<String, String>>> = SyncTables.ALL
            .flatMap { child -> child.parents.map { (column, parent) -> parent to (child.name to column) } }
            .groupBy({ it.first }, { it.second })

        const val KIND_DUPLICATE = "DUPLICATE"
        const val KIND_SAME_NAME = "SAME_NAME"
        const val KIND_REJECTED = "REJECTED"

        fun label(table: String): String = when (table) {
            "product_serial_imei" -> "phone/serial unit"
            "sales" -> "sale"
            "purchases" -> "purchase"
            "purchase_returns" -> "purchase return"
            "sales_returns" -> "sales return"
            "customer_payments" -> "customer payment"
            "supplier_payments" -> "supplier payment"
            "customers" -> "customer"
            "suppliers" -> "supplier"
            "users" -> "PIN user"
            "categories" -> "category"
            "brands" -> "brand"
            "units" -> "unit"
            "expense_categories" -> "expense category"
            else -> table.replace('_', ' ').removeSuffix("s")
        }

        private fun columnLabel(column: String): String = when (column) {
            "imei1" -> "IMEI 1"
            "imei2" -> "IMEI 2"
            "serialNumber" -> "serial number"
            "invoiceNumber" -> "invoice number"
            "purchaseNumber" -> "purchase number"
            "returnNumber" -> "return number"
            "paymentNumber" -> "payment number"
            "customerCode" -> "customer code"
            "supplierCode" -> "supplier code"
            "username" -> "username"
            else -> column
        }
    }
}
