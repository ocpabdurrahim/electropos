package com.example.sync

import android.content.ContentValues
import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteDatabase
import org.json.JSONObject

/**
 * Rows <-> documents for any synced table, typed by [SyncTable] (docs/data-model.md, "Documents"):
 * booleans travel as booleans, integers as integers, numbers as numbers; a reader accepts an
 * integer for a number column and a number for an integer column.
 */
object SyncMapper {

    /** The row with this id as document fields (local-only columns left out), or null if there is none. */
    fun readRow(db: SupportSQLiteDatabase, table: SyncTable, id: Long): Map<String, Any?>? =
        db.query("SELECT * FROM ${table.name} WHERE id = ?", arrayOf<Any>(id)).use { c ->
            if (c.moveToFirst()) rowToDoc(table, c) else null
        }

    fun rowToDoc(table: SyncTable, c: Cursor): Map<String, Any?> {
        val doc = LinkedHashMap<String, Any?>()
        for (col in table.synced) {
            val i = c.getColumnIndexOrThrow(col.name)
            doc[col.name] = if (c.isNull(i)) null else when (col.type) {
                ColType.LONG -> c.getLong(i)
                ColType.DOUBLE -> c.getDouble(i)
                ColType.STRING -> c.getString(i)
                ColType.BOOLEAN -> c.getLong(i) != 0L
            }
        }
        return doc
    }

    /**
     * SQLite values for the row a document describes. A missing field takes the entity's default;
     * local-only columns are included only when [withLocalDefaults] (a new row needs them).
     */
    fun docToValues(table: SyncTable, doc: Map<String, Any?>, withLocalDefaults: Boolean): ContentValues {
        val values = ContentValues()
        for (col in table.columns) {
            if (col.name in table.localOnly && !withLocalDefaults) continue
            val default = table.defaults[col.name]
            val value = if (col.name in table.localOnly || !doc.containsKey(col.name)) default
            else sqlValue(col, doc[col.name], default)
            when (value) {
                null -> values.putNull(col.name)
                is Long -> values.put(col.name, value)
                is Double -> values.put(col.name, value)
                is String -> values.put(col.name, value)
                else -> values.put(col.name, value.toString())
            }
        }
        return values
    }

    /** One field as SQLite stores it (booleans 0/1), converted by the column's type. */
    fun sqlValue(col: SyncColumn, raw: Any?, default: Any?): Any? {
        if (raw == null) return if (col.nullable) null else default
        return when (col.type) {
            ColType.LONG -> when (raw) {
                is Number -> raw.toLong()
                is Boolean -> if (raw) 1L else 0L
                is String -> raw.toLongOrNull() ?: default
                else -> default
            }
            ColType.DOUBLE -> when (raw) {
                is Number -> raw.toDouble()
                is String -> raw.toDoubleOrNull() ?: default
                else -> default
            }
            ColType.BOOLEAN -> when (raw) {
                is Boolean -> if (raw) 1L else 0L
                is Number -> if (raw.toDouble() != 0.0) 1L else 0L
                else -> default
            }
            ColType.STRING -> raw as? String ?: raw.toString()
        }
    }

    /** The document's id field as a row id (documents are named by it too). */
    fun idOf(doc: Map<String, Any?>, docId: String): Long? =
        (doc["id"] as? Number)?.toLong() ?: docId.toLongOrNull()

    fun toJson(doc: Map<String, Any?>): String {
        val o = JSONObject()
        for ((k, v) in doc) o.put(k, v ?: JSONObject.NULL)
        return o.toString()
    }

    fun fromJson(text: String): Map<String, Any?> {
        val o = JSONObject(text)
        val out = LinkedHashMap<String, Any?>()
        val keys = o.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val v = o.get(k)
            out[k] = if (v == JSONObject.NULL) null else v
        }
        return out
    }
}
