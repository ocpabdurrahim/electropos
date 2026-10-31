package com.example.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.database.AppDatabase
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.Modifier

/** The SyncTables config must match the entities, so a new column or table can't be forgotten. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SyncTablesTest {
    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        db = syncDatabase(ApplicationProvider.getApplicationContext<Context>())
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun everyColumnOfEveryEntityIsListedWithItsType() {
        for (table in SyncTables.ALL) {
            val fields = table.entityClass.declaredFields.filter { !Modifier.isStatic(it.modifiers) }
            assertEquals("columns of ${table.name}", fields.map { it.name }.sorted(), table.columnNames.sorted())
            // Room makes a column NOT NULL exactly when the Kotlin type is not nullable
            val nullable = db.openHelper.readableDatabase.query("PRAGMA table_info(${table.name})").use { c ->
                val out = HashMap<String, Boolean>()
                while (c.moveToNext()) out[c.getString(1)] = c.getInt(3) == 0
                out
            }
            for (col in table.columns) {
                val type = fields.first { it.name == col.name }.type
                val expected = when (type) {
                    java.lang.Boolean.TYPE, java.lang.Boolean::class.java -> ColType.BOOLEAN
                    java.lang.Long.TYPE, java.lang.Long::class.java, Integer.TYPE, Integer::class.java -> ColType.LONG
                    java.lang.Double.TYPE, java.lang.Double::class.java -> ColType.DOUBLE
                    String::class.java -> ColType.STRING
                    else -> error("${table.name}.${col.name}: unexpected type $type")
                }
                assertEquals("type of ${table.name}.${col.name}", expected, col.type)
                assertEquals("nullability of ${table.name}.${col.name}", nullable.getValue(col.name), col.nullable)
            }
        }
    }

    @Test
    fun everyRoomTableIsSyncedExceptTheSyncBookkeeping() {
        val tables = query("SELECT name FROM sqlite_master WHERE type = 'table'")
            .filter { !it.startsWith("sqlite_") && !it.startsWith("room_") && it != "android_metadata" && !it.startsWith("sync_") }
        assertEquals(tables.sorted(), SyncTables.ALL.map { it.name }.sorted())
    }

    @Test
    fun columnsAndForeignKeysMatchTheDatabase() {
        for (table in SyncTables.ALL) {
            assertEquals("db columns of ${table.name}", query("PRAGMA table_info(${table.name})", column = 1).sorted(), table.columnNames.sorted())
            val fks = db.openHelper.readableDatabase.query("PRAGMA foreign_key_list(${table.name})").use { c ->
                val out = HashMap<String, String>()
                while (c.moveToNext()) out[c.getString(c.getColumnIndexOrThrow("from"))] = c.getString(c.getColumnIndexOrThrow("table"))
                out
            }
            assertEquals("foreign keys of ${table.name}", fks, table.parents)
            // Parents are applied first
            for (parent in table.parents.values) assertTrue("$parent before ${table.name}", SyncTables.order(parent) < SyncTables.order(table.name))
        }
    }

    @Test
    fun contractColumnsAreClassifiedAsTheContractSays() {
        assertEquals(
            setOf("themeMode", "rememberLogin", "lastActiveUserId", "logoUri", "invoiceNextNumber", "purchaseNextNumber",
                "purchaseReturnNextNumber", "salesReturnNextNumber", "customerPaymentNextNumber", "customerNextNumber"),
            SyncTables.get("shop_profile").localOnly
        )
        assertEquals(setOf("currentStock", "purchasePrice"), SyncTables.get("products").cache)
        assertEquals(setOf("paidAmount", "dueAmount", "paymentStatus"), SyncTables.get("sales").cache)
        assertEquals(setOf("runningBalance"), SyncTables.get("customer_ledger_entries").cache)
        // The contract's collection list, in its order
        assertEquals(
            listOf("shop_profile", "users", "categories", "subcategories", "brands", "units", "suppliers", "customers", "products",
                "product_variants", "product_serial_imei", "product_price_history", "purchases", "purchase_items", "purchase_returns",
                "purchase_return_items", "supplier_payments", "supplier_ledger_entries", "sales", "sale_items", "sales_returns",
                "sales_return_items", "customer_payments", "customer_ledger_entries", "stock_transactions", "payment_transactions",
                "expenses", "expense_categories", "cash_register_transactions", "warranty_records", "audit_logs"),
            SyncTables.ALL.map { it.name }
        )
    }

    private fun query(sql: String, column: Int = 0): List<String> = db.openHelper.readableDatabase.query(sql).use { c ->
        val out = ArrayList<String>()
        while (c.moveToNext()) out.add(c.getString(column))
        out
    }
}
