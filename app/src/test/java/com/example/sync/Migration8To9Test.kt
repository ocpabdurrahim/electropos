package com.example.sync

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import com.example.data.local.database.AppDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Room's own schema check for 8 -> 9 (sync tables, names no longer unique), then the app's open-time setup. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Migration8To9Test {
    private val dbName = "migration-test.db"

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java)

    @Test
    fun migratesAShopFromVersion8() {
        helper.createDatabase(dbName, 8).use { db ->
            db.insert("categories", SQLiteDatabase.CONFLICT_ABORT, ContentValues().apply {
                put("id", 1); put("name", "Phones"); put("description", ""); put("iconName", "phone"); put("isActive", 1)
                put("createdAt", 1L); put("updatedAt", 1L)
            })
            db.insert("units", SQLiteDatabase.CONFLICT_ABORT, ContentValues().apply {
                put("id", 1); put("name", "Piece"); put("code", "Pcs"); put("isActive", 1); put("createdAt", 1L)
            })
        }

        val db = helper.runMigrationsAndValidate(dbName, 9, true, AppDatabase.MIGRATION_8_9)
        // The shop's rows survive; a second "Phones" is now allowed (it shows under Sync issues instead)
        db.insert("categories", SQLiteDatabase.CONFLICT_ABORT, ContentValues().apply {
            put("id", 2); put("name", "Phones"); put("description", ""); put("iconName", "phone"); put("isActive", 1)
            put("createdAt", 2L); put("updatedAt", 2L)
        })
        db.query("SELECT COUNT(*) FROM categories WHERE name = 'Phones'").use { it.moveToFirst(); assertEquals(2, it.getInt(0)) }
        db.query("SELECT COUNT(*) FROM sync_outbox").use { it.moveToFirst(); assertEquals(0, it.getInt(0)) }
        db.close()

        // Opened like the app opens it: the triggers and the flags row arrive, nothing is recorded yet
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val room = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(*AppDatabase.ALL_MIGRATIONS)
            .addCallback(SyncSchema.Callback())
            .allowMainThreadQueries()
            .build()
        try {
            val flags = runBlocking { room.syncDao().flags() }
            assertNotNull(flags)
            assertFalse(flags!!.tracking)
            assertEquals(0L, flags.block)
            assertFalse(SyncSchema.installTriggers(room.openHelper.writableDatabase))
            assertEquals("Phones", runBlocking { room.masterDao().getCategoryById(1) }!!.name)
            // New rows continue after the old ids
            assertEquals(3L, runBlocking { room.masterDao().insertCategory(com.example.data.local.entities.CategoryEntity(name = "Chargers")) })
        } finally {
            room.close()
        }
    }
}
