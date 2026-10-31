package com.example

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.database.AppDatabase
import com.example.data.local.entities.CustomerEntity
import com.example.data.local.entities.ProductEntity
import com.example.data.local.entities.PurchaseEntity
import com.example.data.local.entities.SaleEntity
import com.example.data.local.entities.ShopProfileEntity
import com.example.data.local.entities.SupplierEntity
import com.example.data.repository.BackupInspection
import com.example.data.repository.BackupManifest
import com.example.data.repository.BackupRepository
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Backup / staged restore / safety copies / CSV export against a real file database
 * (the same "electro_pos.db" name the app uses).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackupRestoreTest {

    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var repository: BackupRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        wipeAppFiles()
        BackupRepository.resetPendingRestoreGuardForTests()
        database = openAppDatabase()
        repository = BackupRepository(context, database)
    }

    @After
    fun tearDown() {
        runCatching { database.close() }
        wipeAppFiles()
    }

    // ------------------------------------------------------------------ full backup

    @Test
    fun fullBackupContainsManifestAndStandaloneDatabase() {
        seedShop("Masum Electronics", listOf("Charger", "Cable", "Earphone"))

        val (bytes, manifest) = backupBytes()
        val entries = unzip(bytes)

        assertTrue(entries.containsKey("manifest.json"))
        assertTrue(entries.containsKey("electro_pos.db"))

        val parsed = BackupManifest.fromJson(String(entries.getValue("manifest.json"), Charsets.UTF_8))
        assertEquals(BackupRepository.BACKUP_FORMAT, parsed.format)
        assertEquals(context.packageName, parsed.appId)
        assertEquals(currentVersion(), parsed.dbSchemaVersion)
        assertEquals(manifest.dbSha256, parsed.dbSha256)
        assertEquals("Masum Electronics", parsed.shopName)
        assertEquals(3L, parsed.counts["products"])
        assertEquals(1L, parsed.counts["customers"])
        assertEquals(1L, parsed.counts["suppliers"])
        assertEquals(1L, parsed.counts["sales"])
        assertEquals(1L, parsed.counts["purchases"])

        // The zipped database is one self-contained SQLite file (no -wal needed).
        val dbFile = writeTemp("zipped-check.db", entries.getValue("electro_pos.db"))
        SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT COUNT(*) FROM products", null).use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(3, c.getInt(0))
            }
        }
    }

    // ------------------------------------------------------------------ restore

    @Test
    fun restoreRoundTripReplacesDataAfterRestartHook() {
        seedShop("Original Shop", listOf("A", "B", "C"))
        val (bytes, _) = backupBytes()

        // Work done after the backup was made.
        runBlocking {
            database.productDao().insertProduct(ProductEntity(name = "D"))
            database.productDao().insertProduct(ProductEntity(name = "E"))
            database.settingsAndAuditDao().saveShopProfile(ShopProfileEntity(shopName = "Changed Shop"))
        }
        assertEquals(5L, count("products"))

        val inspection = inspect(bytes, "test-backup.zip").getOrThrow()
        assertEquals("test-backup.zip", inspection.sourceLabel)
        assertEquals(3L, inspection.counts["products"])
        assertEquals("Original Shop", inspection.shopName)
        assertFalse(inspection.needsUpgrade)
        assertEquals(5L, count("products")) // checking changes nothing

        runBlocking { repository.stageRestore(inspection) }.getOrThrow()
        assertTrue(BackupRepository.hasPendingRestore(context))

        // A safety copy of the data being replaced was saved first.
        val copies = runBlocking { repository.listSafetyCopies() }
        assertEquals(1, copies.size)
        assertEquals("Before restore", copies[0].reason)
        assertEquals(5L, copies[0].counts["products"])

        // Simulate the restart: Room closed, startup hook, Room opened again.
        database.close()
        assertTrue(BackupRepository.applyPendingRestore(context))
        assertFalse(BackupRepository.hasPendingRestore(context))
        assertFalse("hook must run only once per process", BackupRepository.applyPendingRestore(context))
        reopen()

        assertEquals(3L, count("products"))
        assertEquals("Original Shop", shopName())
        val info = repository.lastRestoreInfo()
        assertNotNull(info)
        assertEquals("test-backup.zip", info!!.source)
        assertNull(info.error)
    }

    @Test
    fun hookDoesNothingWithoutPendingRestore() {
        seedShop("Shop", listOf("A"))
        database.close()
        assertFalse(BackupRepository.applyPendingRestore(context))
        reopen()
        assertEquals(1L, count("products"))
    }

    @Test
    fun badFilesAreRejectedWithoutTouchingData() {
        seedShop("Shop", listOf("A"))
        val entries = unzip(backupBytes().first)
        val manifestNoSha = stripSha(entries.getValue("manifest.json"))

        val garbage = ByteArray(4096) { (it * 31 % 251).toByte() }
        val noManifest = zipOf(mapOf("hello.txt" to "hi".toByteArray()))
        val notSqlite = zipOf(mapOf("manifest.json" to manifestNoSha, "electro_pos.db" to ByteArray(8192) { 7.toByte() }))
        val tamperedDb = entries.getValue("electro_pos.db").copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        val checksumMismatch = zipOf(mapOf("manifest.json" to entries.getValue("manifest.json"), "electro_pos.db" to tamperedDb))
        val foreignManifest = zipOf(
            mapOf(
                "manifest.json" to """{"format":"something-else"}""".toByteArray(),
                "electro_pos.db" to entries.getValue("electro_pos.db")
            )
        )

        for ((name, file) in listOf(
            "garbage" to garbage,
            "no manifest" to noManifest,
            "not sqlite" to notSqlite,
            "checksum mismatch" to checksumMismatch,
            "foreign manifest" to foreignManifest
        )) {
            val result = inspect(file, name)
            assertTrue("$name should be rejected", result.isFailure)
            assertFalse("$name should have a message", result.exceptionOrNull()?.message.isNullOrBlank())
        }

        assertEquals(1L, count("products"))
        assertFalse(context.getDatabasePath(BackupRepository.CANDIDATE_DB_NAME).exists())
        assertFalse(BackupRepository.hasPendingRestore(context))
    }

    @Test
    fun backupFromNewerAppIsRejected() {
        seedShop("Shop", listOf("A"))
        val entries = unzip(backupBytes().first)
        val newer = withUserVersion(entries.getValue("electro_pos.db"), 99)

        val result = inspect(zipOf(mapOf("manifest.json" to stripSha(entries.getValue("manifest.json")), "electro_pos.db" to newer)))

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("newer"))
    }

    @Test
    fun backupFromOlderSchemaIsUpgradedDuringCheck() {
        seedShop("Old Shop", listOf("A", "B"))
        val current = currentVersion()
        val entries = unzip(backupBytes().first)
        val older = withUserVersion(entries.getValue("electro_pos.db"), current - 1)

        val inspection = inspect(zipOf(mapOf("manifest.json" to stripSha(entries.getValue("manifest.json")), "electro_pos.db" to older)))
            .getOrThrow()

        assertEquals(current - 1, inspection.schemaVersion)
        assertTrue(inspection.needsUpgrade)
        assertEquals(2L, inspection.counts["products"])
        SQLiteDatabase.openDatabase(inspection.candidatePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            assertEquals(current, db.version)
        }

        runBlocking { repository.discardInspection(inspection) }
        assertFalse(File(inspection.candidatePath).exists())
    }

    // ------------------------------------------------------------------ safety copies

    @Test
    fun safetyCopiesKeepTheNewestFive() {
        seedShop("Shop", listOf("A"))
        repeat(7) { i -> runBlocking { repository.createSafetyCopy("Copy $i") }.getOrThrow() }

        val copies = runBlocking { repository.listSafetyCopies() }

        assertEquals(BackupRepository.MAX_SAFETY_COPIES, copies.size)
        assertEquals("Copy 6", copies.first().reason)
        assertEquals("Copy 2", copies.last().reason)
        assertTrue(copies.all { it.counts["products"] == 1L })
    }

    @Test
    fun safetyCopyBeforeResetCanBeRestored() {
        seedShop("Before Reset Shop", listOf("A", "B"))
        val copy = runBlocking { repository.createSafetyCopy("Before reset") }.getOrThrow()

        database.clearAllTables() // what "Reset Application" does
        assertEquals(0L, count("products"))

        val inspection = runBlocking { repository.inspectSafetyCopy(copy.path) }.getOrThrow()
        assertTrue(inspection.isSafetyCopy)
        assertEquals(2L, inspection.counts["products"])

        runBlocking { repository.stageRestore(inspection) }.getOrThrow()
        database.close()
        assertTrue(BackupRepository.applyPendingRestore(context))
        reopen()

        assertEquals(2L, count("products"))
        assertEquals("Before Reset Shop", shopName())
    }

    @Test
    fun safetyCopyOutsideItsFolderIsRefused() {
        val outside = writeTemp("not-a-safety-copy.zip", ByteArray(10))
        val result = runBlocking { repository.inspectSafetyCopy(outside.path) }
        assertTrue(result.isFailure)
    }

    // ------------------------------------------------------------------ CSV export

    @Test
    fun csvExportWritesBomAndEscapesValues() {
        seedShop("Shop", listOf("স্যামসাং চার্জার", "Cable, \"Type-C\" 1m"))

        val out = ByteArrayOutputStream()
        val counts = runBlocking { repository.writeCsvExportTo(out) }
        val entries = unzip(out.toByteArray())

        assertEquals(setOf("products.csv", "customers.csv", "suppliers.csv", "sales.csv", "purchases.csv"), entries.keys)
        assertEquals(2, counts["products"])
        assertEquals(1, counts["customers"])
        assertEquals(1, counts["sales"])
        for ((name, data) in entries) {
            assertTrue(
                "$name must start with a UTF-8 BOM",
                data.size >= 3 && data[0] == 0xEF.toByte() && data[1] == 0xBB.toByte() && data[2] == 0xBF.toByte()
            )
        }

        val products = csv(entries, "products.csv")
        assertTrue(products.startsWith("Name,SKU,Barcode,Category,Brand,Stock,Purchase Price,Retail Price,Active\r\n"))
        assertTrue(products.contains("স্যামসাং চার্জার"))
        assertTrue(products.contains("\"Cable, \"\"Type-C\"\" 1m\""))

        val customers = csv(entries, "customers.csv")
        assertTrue(customers.contains("রহিম টেলিকম"))
        assertTrue("phone keeps its leading zero in Excel", customers.contains("\"=\"\"01711000000\"\"\""))
        assertTrue(customers.contains("1500.00"))

        assertTrue(csv(entries, "suppliers.csv").contains("\"Dhaka Gadgets, Ltd\""))
        assertTrue(csv(entries, "sales.csv").contains("INV-1001"))
        assertTrue(csv(entries, "purchases.csv").contains("PUR-0001"))
    }

    @Test
    fun csvEscapingRules() {
        assertEquals("plain", BackupRepository.csvEscape("plain"))
        assertEquals("\"a,b\"", BackupRepository.csvEscape("a,b"))
        assertEquals("\"say \"\"hi\"\"\"", BackupRepository.csvEscape("say \"hi\""))
        assertEquals("\"line1\nline2\"", BackupRepository.csvEscape("line1\nline2"))
        assertEquals("=\"01711000000\"", BackupRepository.excelSafeId("01711000000"))
        assertEquals("=\"8901234567890\"", BackupRepository.excelSafeId("8901234567890"))
        assertEquals("12345", BackupRepository.excelSafeId("12345"))
        assertEquals("INV-1001", BackupRepository.excelSafeId("INV-1001"))
        assertEquals("'=SUM(A1)", BackupRepository.excelSafeText("=SUM(A1)"))
        assertEquals("Walk-in Customer", BackupRepository.excelSafeText("Walk-in Customer"))
    }

    @Test
    fun everySchemaStepHasAMigrationForOldBackups() {
        val migrations = BackupRepository.discoverMigrations()
        val current = currentVersion()
        for (v in 1 until current) {
            assertTrue("missing migration $v -> ${v + 1}", migrations.any { it.startVersion == v && it.endVersion == v + 1 })
        }
    }

    @Test
    fun defaultFileNamesLookRight() {
        assertTrue(BackupRepository.defaultBackupFileName().matches(Regex("ElectroPOS-backup-\\d{4}-\\d{2}-\\d{2}-\\d{4}\\.zip")))
        assertTrue(BackupRepository.defaultExportFileName().matches(Regex("ElectroPOS-export-\\d{4}-\\d{2}-\\d{2}-\\d{4}\\.zip")))
    }

    // ------------------------------------------------------------------ helpers

    private fun openAppDatabase(): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, BackupRepository.DB_NAME)
            .addMigrations(*BackupRepository.discoverMigrations())
            .allowMainThreadQueries()
            .build()

    private fun reopen() {
        database = openAppDatabase()
        repository = BackupRepository(context, database)
    }

    private fun wipeAppFiles() {
        context.deleteDatabase(BackupRepository.DB_NAME)
        context.deleteDatabase(BackupRepository.CANDIDATE_DB_NAME)
        File(context.noBackupFilesDir, "restore").deleteRecursively()
        File(context.noBackupFilesDir, "safety_copies").deleteRecursively()
        File(context.cacheDir, "backup_work").deleteRecursively()
        File(context.cacheDir, "backup_test").deleteRecursively()
        context.getSharedPreferences("electropos_backup", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun seedShop(shopName: String, productNames: List<String>) {
        runBlocking {
            database.settingsAndAuditDao().saveShopProfile(ShopProfileEntity(shopName = shopName, setupCompleted = true))
            productNames.forEachIndexed { i, name ->
                database.productDao().insertProduct(
                    ProductEntity(
                        name = name,
                        sku = "SKU-${i + 1}",
                        barcode = "89012345678${i}0",
                        categoryName = "Accessories",
                        brandName = "Generic",
                        currentStock = 10 + i,
                        purchasePrice = 100.0 * (i + 1),
                        retailPrice = 150.0 * (i + 1)
                    )
                )
            }
            database.customerDao().insertCustomer(
                CustomerEntity(customerCode = "CUS-0001", name = "রহিম টেলিকম", phone = "01711000000", currentDue = 1500.0)
            )
            val supplierId = database.supplierDao().insertSupplier(
                SupplierEntity(supplierCode = "SUP-0001", name = "Dhaka Gadgets, Ltd", phone = "01811000000", currentDue = 2000.0)
            )
            database.saleDao().insertSale(
                SaleEntity(invoiceNumber = "INV-1001", customerName = "Walk-in Customer", totalAmount = 1200.0, paidAmount = 1200.0)
            )
            database.purchaseDao().insertPurchase(
                PurchaseEntity(
                    purchaseNumber = "PUR-0001",
                    supplierId = supplierId,
                    supplierName = "Dhaka Gadgets, Ltd",
                    totalAmount = 5000.0,
                    paidAmount = 3000.0,
                    dueAmount = 2000.0
                )
            )
        }
    }

    private fun currentVersion(): Int = database.openHelper.readableDatabase.version

    private fun count(table: String): Long =
        database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $table").use { c ->
            c.moveToFirst()
            c.getLong(0)
        }

    private fun shopName(): String =
        database.openHelper.readableDatabase.query("SELECT shopName FROM shop_profile LIMIT 1").use { c ->
            if (c.moveToFirst()) c.getString(0) else ""
        }

    private fun backupBytes(): Pair<ByteArray, BackupManifest> = runBlocking {
        val out = ByteArrayOutputStream()
        val manifest = repository.writeFullBackupTo(out)
        out.toByteArray() to manifest
    }

    private fun inspect(bytes: ByteArray, label: String = "test.zip"): Result<BackupInspection> = runBlocking {
        repository.inspectBackupStream(label) { ByteArrayInputStream(bytes) }
    }

    private fun unzip(bytes: ByteArray): Map<String, ByteArray> {
        val result = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                result[entry.name] = zip.readBytes()
                zip.closeEntry()
            }
        }
        return result
    }

    private fun zipOf(entries: Map<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, data) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(data)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun stripSha(manifest: ByteArray): ByteArray {
        val json = JSONObject(String(manifest, Charsets.UTF_8))
        json.remove("dbSha256")
        return json.toString().toByteArray(Charsets.UTF_8)
    }

    private fun withUserVersion(dbBytes: ByteArray, version: Int): ByteArray {
        val file = writeTemp("version-${System.nanoTime()}.db", dbBytes)
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.version = version
        }
        listOf("-wal", "-shm", "-journal").forEach { File(file.path + it).delete() }
        return file.readBytes()
    }

    private fun writeTemp(name: String, data: ByteArray): File {
        val dir = File(context.cacheDir, "backup_test").apply { mkdirs() }
        return File(dir, name).apply { writeBytes(data) }
    }

    private fun csv(entries: Map<String, ByteArray>, name: String): String =
        String(entries.getValue(name), Charsets.UTF_8).removePrefix("﻿")
}
