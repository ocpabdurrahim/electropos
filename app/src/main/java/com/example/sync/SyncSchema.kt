package com.example.sync

import android.util.Log
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import java.io.File

/**
 * The database side of sync: the sync tables (migration 8 -> 9) and the triggers that record
 * every local change in sync_outbox. The triggers are generated from [SyncTables] and checked on
 * every open, so a new column, a migration or a restored backup always gets the current ones.
 */
object SyncSchema {
    private const val TAG = "SyncSchema"
    private const val PREFIX = "sync_"

    /** Recording is on and the change is local (not one being applied from the cloud). */
    private const val GATE =
        "(SELECT tracking FROM sync_flags WHERE id = 1) = 1 AND (SELECT applying FROM sync_flags WHERE id = 1) = 0"

    private const val BLOCK = "COALESCE((SELECT block FROM sync_flags WHERE id = 1), 0)"

    val MIGRATION_8_9 = object : Migration(8, 9) {
        override fun migrate(db: SupportSQLiteDatabase) {
            for (sql in CREATE_SYNC_TABLES) db.execSQL(sql)
            // Names are no longer unique: two phones may add the same name while offline
            for (table in SyncTables.NAMED) {
                db.execSQL("DROP INDEX IF EXISTS `index_${table}_name`")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_${table}_name` ON `$table` (`name`)")
            }
        }
    }

    /** Room's own CREATE statements for the sync entities (app/schemas/.../9.json). */
    private val CREATE_SYNC_TABLES = listOf(
        "CREATE TABLE IF NOT EXISTS `sync_flags` (`id` INTEGER NOT NULL, `tracking` INTEGER NOT NULL, `applying` INTEGER NOT NULL, `block` INTEGER NOT NULL, `tillCode` TEXT, `shopId` TEXT, `shopName` TEXT, `email` TEXT, `role` TEXT, `downloading` INTEGER NOT NULL, `linkedAt` INTEGER NOT NULL, `lastSyncAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
        "CREATE TABLE IF NOT EXISTS `sync_outbox` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `tbl` TEXT NOT NULL, `rowId` INTEGER NOT NULL)",
        "CREATE INDEX IF NOT EXISTS `index_sync_outbox_tbl_rowId` ON `sync_outbox` (`tbl`, `rowId`)",
        "CREATE TABLE IF NOT EXISTS `sync_idgen` (`tbl` TEXT NOT NULL, `next` INTEGER NOT NULL, PRIMARY KEY(`tbl`))",
        "CREATE TABLE IF NOT EXISTS `sync_conflicts` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `tbl` TEXT NOT NULL, `rowId` INTEGER NOT NULL, `kind` TEXT NOT NULL, `message` TEXT NOT NULL, `createdAt` INTEGER NOT NULL)",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_sync_conflicts_tbl_rowId_kind` ON `sync_conflicts` (`tbl`, `rowId`, `kind`)",
        "CREATE TABLE IF NOT EXISTS `sync_pending_inbound` (`tbl` TEXT NOT NULL, `rowId` INTEGER NOT NULL, `data` TEXT NOT NULL, `ts` INTEGER NOT NULL, `reason` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`tbl`, `rowId`))",
        "CREATE TABLE IF NOT EXISTS `sync_counters` (`series` TEXT NOT NULL, `next` INTEGER NOT NULL, PRIMARY KEY(`series`))",
        "CREATE TABLE IF NOT EXISTS `sync_cursors` (`tbl` TEXT NOT NULL, `ts` INTEGER NOT NULL, PRIMARY KEY(`tbl`))"
    )

    /** Every trigger by name, generated from [SyncTables]. */
    val triggers: Map<String, String> by lazy {
        val all = LinkedHashMap<String, String>()
        for (t in SyncTables.ALL) {
            val n = t.name
            if (n != SyncTables.SHOP_PROFILE) {
                // A missed insert path (an id left to SQLite) or a wrong block fails loudly here.
                all["${PREFIX}guard_$n"] = "CREATE TRIGGER ${PREFIX}guard_$n BEFORE INSERT ON $n " +
                    "WHEN COALESCE((SELECT applying FROM sync_flags WHERE id = 1), 0) = 0 " +
                    "AND (NEW.id IS NULL OR NEW.id < $BLOCK * ${SyncIds.BLOCK_SIZE} + 1 OR NEW.id > $BLOCK * ${SyncIds.BLOCK_SIZE} + ${SyncIds.BLOCK_SIZE - 1}) " +
                    "BEGIN SELECT RAISE(ABORT, 'sync: new $n id outside this device''s id block'); END"
            }
            all["${PREFIX}ins_$n"] = "CREATE TRIGGER ${PREFIX}ins_$n AFTER INSERT ON $n WHEN $GATE " +
                "BEGIN INSERT INTO sync_outbox (tbl, rowId) VALUES ('$n', NEW.id); END"
            val changes = (listOf("id") + t.watched.map { it.name }).map { "OLD.$it IS NOT NEW.$it" }.toMutableList()
            t.costOwnerColumn?.let { owner ->
                // The entered cost (no purchase yet) is a real change; after a purchase it is the weighted average
                changes += "(OLD.purchasePrice IS NOT NEW.purchasePrice AND NOT EXISTS (SELECT 1 FROM stock_transactions " +
                    "WHERE $owner = NEW.id AND transactionType = 'PURCHASE'))"
            }
            all["${PREFIX}upd_$n"] = "CREATE TRIGGER ${PREFIX}upd_$n AFTER UPDATE ON $n WHEN $GATE AND (${changes.joinToString(" OR ")}) " +
                "BEGIN INSERT INTO sync_outbox (tbl, rowId) VALUES ('$n', NEW.id); " +
                "INSERT INTO sync_outbox (tbl, rowId) SELECT '$n', OLD.id WHERE OLD.id IS NOT NEW.id; END"
            all["${PREFIX}del_$n"] = "CREATE TRIGGER ${PREFIX}del_$n AFTER DELETE ON $n WHEN $GATE " +
                "BEGIN INSERT INTO sync_outbox (tbl, rowId) VALUES ('$n', OLD.id); END"
        }
        all
    }

    /** Builder hook: migrations stay in AppDatabase; this adds the open-time setup. */
    class Callback(private val restoreMarker: File? = null) : RoomDatabase.Callback() {
        override fun onOpen(db: SupportSQLiteDatabase) {
            prepare(db, restoreMarker)
        }
    }

    /** Runs on every open: connection settings, the flags row, the triggers, and the post-restore unlink. */
    fun prepare(db: SupportSQLiteDatabase, restoreMarker: File? = null) {
        db.query("PRAGMA recursive_triggers = ON").use { it.moveToFirst() }
        db.execSQL(
            "INSERT OR IGNORE INTO sync_flags (id, tracking, applying, block, downloading, linkedAt, lastSyncAt) " +
                "VALUES (1, 0, 0, 0, 0, 0, 0)"
        )
        // An apply that was cut off rolled back with its transaction; this only guards against a stuck flag
        db.execSQL("UPDATE sync_flags SET applying = 0 WHERE id = 1 AND applying != 0")
        installTriggers(db)
        if (restoreMarker != null && restoreMarker.exists()) {
            forgetLink(db)
            restoreMarker.delete()
        }
    }

    /** (Re)creates the triggers when the installed ones differ from [triggers]. Returns true if it did. */
    fun installTriggers(db: SupportSQLiteDatabase): Boolean {
        val installed = HashMap<String, String>()
        db.query("SELECT name, sql FROM sqlite_master WHERE type = 'trigger' AND name LIKE 'sync\\_%' ESCAPE '\\'").use { c ->
            while (c.moveToNext()) installed[c.getString(0)] = c.getString(1) ?: ""
        }
        if (installed == triggers) return false
        db.beginTransaction()
        try {
            installed.keys.forEach { db.execSQL("DROP TRIGGER IF EXISTS `$it`") }
            triggers.values.forEach { db.execSQL(it) }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        Log.i(TAG, "Installed ${triggers.size} sync triggers")
        return true
    }

    /**
     * Forgets the cloud link without touching the shop's records and without sending anything:
     * after a restore (the file may come from another phone) the phone works offline again and
     * gets a fresh id block when it joins.
     */
    fun forgetLink(db: SupportSQLiteDatabase) {
        db.beginTransaction()
        try {
            db.execSQL(
                "UPDATE sync_flags SET tracking = 0, applying = 0, block = 0, tillCode = NULL, shopId = NULL, " +
                    "shopName = NULL, email = NULL, role = NULL, downloading = 0, linkedAt = 0 WHERE id = 1"
            )
            for (t in listOf("sync_outbox", "sync_pending_inbound", "sync_conflicts", "sync_cursors", "sync_counters", "sync_idgen")) {
                db.execSQL("DELETE FROM $t")
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** File left by a restore: the next open forgets the link (see BackupRepository.applyPendingRestore). */
    fun restoreMarker(noBackupDir: File): File = File(noBackupDir, "sync_forget_link")
}
