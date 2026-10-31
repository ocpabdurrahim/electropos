package com.example.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.database.AppDatabase
import com.example.data.local.entities.*
import com.example.data.repository.AuthRepository
import com.example.data.repository.BackupRepository
import com.example.data.repository.SampleDataAndBackupRepository
import com.example.data.repository.UnitRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

/** Put online, join, unlink, reset and restore: what each sends (or must not send) and leaves behind. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SyncLifecycleTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var cloud: FakeCloud
    private val phones = ArrayList<TestPhone>()

    @Before
    fun setUp() {
        cloud = FakeCloud()
    }

    @After
    fun tearDown() {
        phones.forEach { it.close() }
    }

    private fun phone(email: String, label: String = email): TestPhone = TestPhone(context, cloud, email, label).also { phones.add(it) }

    private fun tombstones(): List<String> = cloud.docs.filter { it.value["_del"] == true }.keys.toList()

    @Test
    fun puttingTheShopOnlineSendsTheCreationBatchThenEveryRow() = runBlocking {
        val a = phone("ocpabdurrahim@gmail.com", "Phone A")
        val seed = a.seedShop("Masum Electronics")
        a.sync.start()
        a.sync.putShopOnline()

        val creation = cloud.commits.first()
        val shopId = a.flags().shopId!!
        assertEquals(
            listOf("shops/$shopId", "shops/$shopId/members/ocpabdurrahim@gmail.com", "shops/$shopId/meta/counters", "shops/$shopId/devices/0"),
            creation.map { it.path }
        )
        assertTrue(creation.all { it is RemoteWrite.Set && it.data["_ts"] === ServerTime })
        val shop = cloud.doc("shops/$shopId")!!
        assertEquals("uid-ocpabdurrahim@gmail.com", shop["ownerUid"])
        assertEquals("ocpabdurrahim@gmail.com", shop["ownerEmail"])
        assertEquals("Masum Electronics", shop["name"])
        val owner = cloud.doc("shops/$shopId/members/ocpabdurrahim@gmail.com")!!
        assertEquals(listOf("OWNER", true, false), listOf(owner["role"], owner["active"], owner["_del"]))
        assertEquals(1L, cloud.doc("shops/$shopId/meta/counters")!!["nextBlock"])
        assertEquals(1L, cloud.doc("shops/$shopId/meta/counters")!!["nextPhone"])
        assertEquals(listOf(0L, "PHONE", "A"), cloud.doc("shops/$shopId/devices/0")!!.let { listOf(it["block"], it["kind"], it["tillCode"]) })

        assertEquals(0L, a.flags().block)
        assertEquals("A", a.flags().tillCode)
        assertTrue(a.flags().tracking)
        eventually("every row went up") { a.pending() == 0 }
        val product = cloud.doc("shops/$shopId/products/${seed.productId}")!!
        assertEquals(seed.productId, product["id"])
        assertEquals(false, product["_del"])
        assertFalse(product.containsKey("imageUri"))
        assertNotNull(cloud.doc("shops/$shopId/shop_profile/1"))
        assertNotNull(cloud.doc("shops/$shopId/stock_transactions/1"))
        // Later batches are rows only, at most 400 writes each
        assertTrue(cloud.commits.drop(1).all { batch -> batch.size <= SyncManager.BATCH_SIZE && batch.all { it.path.split('/').size == 4 } })
        assertTrue(tombstones().isEmpty())
    }

    @Test
    fun onlyTheAppOwnerOrAnApprovedCreatorMayPutAShopOnline() = runBlocking {
        val p = phone("someone@shop.com")
        p.seedShop("Shop")
        val refused = runCatching { p.sync.putShopOnline() }
        assertTrue(refused.exceptionOrNull()?.message.orEmpty().contains("app owner"))
        assertTrue(cloud.docs.isEmpty())

        cloud.put("creators/someone@shop.com", mapOf("email" to "someone@shop.com", "_ts" to ServerTime))
        p.sync.putShopOnline()
        assertEquals("OWNER", cloud.doc("shops/${p.flags().shopId}/members/someone@shop.com")!!["role"])
    }

    @Test
    fun aPhoneWithAnotherShopsRecordsCantCreateANewShop() = runBlocking {
        val p = phone("ocpabdurrahim@gmail.com")
        p.seedShop("Shop")
        // e.g. restored from a phone that had joined a shop: rows with block 4 ids
        p.db.syncDao().saveFlags(SyncFlagsEntity(applying = true))
        p.db.masterDao().insertCategory(CategoryEntity(id = SyncIds.firstId(4), name = "From another phone"))
        p.db.syncDao().saveFlags(SyncFlagsEntity())
        assertTrue(runCatching { p.sync.putShopOnline() }.exceptionOrNull()?.message.orEmpty().contains("Join that shop"))
        assertFalse(p.flags().isLinked)
    }

    @Test
    fun onlyOwnersAdminsAndManagersCanLinkAPhone() = runBlocking {
        val a = phone("ocpabdurrahim@gmail.com")
        a.seedShop("Shop")
        a.sync.start()
        a.sync.putShopOnline()
        a.sync.addMember("sales@shop.com", ShopRoles.SALESPERSON)
        val s = phone("sales@shop.com")
        s.seedShop("Own data")
        val shop = s.sync.findMyShops().single()
        assertEquals(ShopRoles.SALESPERSON, shop.role)
        assertTrue(runCatching { s.sync.joinShop(shop) }.isFailure)
        assertFalse(s.flags().isLinked)
        assertEquals(0, s.safetyCopies)
        assertEquals(1L, cloud.doc("shops/${shop.id}/meta/counters")!!["nextBlock"])
    }

    @Test
    fun unlinkingStopsRecordingAndSendsNothing() = runBlocking {
        val a = phone("ocpabdurrahim@gmail.com")
        val seed = a.seedShop("Shop")
        a.sync.start()
        a.sync.putShopOnline()
        eventually("uploaded") { a.pending() == 0 }
        val before = cloud.commits.size

        a.sync.unlink()
        assertFalse(a.flags().isLinked)
        assertFalse(a.flags().tracking)
        assertEquals(0L, a.flags().block) // the block stays this phone's
        a.db.productDao().deleteProduct(a.db.productDao().getProductById(seed.productId)!!.copy())
        a.db.masterDao().insertCategory(CategoryEntity(name = "Offline again"))
        assertEquals(0, a.pending())
        Thread.sleep(300)
        assertEquals(before, cloud.commits.size)
        assertTrue(tombstones().isEmpty())
        // Back to the old number formats
        assertEquals(null, DocNumbers.till(a.db))
    }

    @Test
    fun resetOnALinkedPhoneUnlinksFirstAndSendsNoDeletions() = runBlocking {
        val a = phone("ocpabdurrahim@gmail.com")
        a.seedShop("Shop")
        a.sync.start()
        a.sync.putShopOnline()
        eventually("uploaded") { a.pending() == 0 }
        val docsBefore = cloud.docs.size

        AuthRepository(a.db).resetApplication().getOrThrow()
        Thread.sleep(300)
        assertTrue(tombstones().isEmpty())
        assertEquals(docsBefore, cloud.docs.size)
        assertFalse(runBlocking { SyncLocal.isLinked(a.db) })
        assertEquals(0, a.db.productDao().getAllProductsSync().size)
    }

    @Test
    fun aLinkedPhoneRefusesSampleDataAndDoesNotSeedDefaults() = runBlocking {
        val a = phone("ocpabdurrahim@gmail.com")
        a.seedShop("Shop")
        a.sync.putShopOnline()
        val sample = SampleDataAndBackupRepository(a.db)
        assertTrue(runCatching { sample.seedSampleElectronicsData() }.isFailure)
        sample.initializeDefaultDataIfEmpty()
        assertEquals(0, a.db.financeDao().countExpenseCategoriesNamed("Shop Rent"))
        assertTrue(UnitRepository(a.db.unitDao(), a.db.settingsAndAuditDao()).getAllUnitsSync().isEmpty())
    }

    @Test
    fun restoredFileForgetsItsCloudLinkWithoutSendingAnything() {
        context.deleteDatabase(BackupRepository.DB_NAME)
        val marker = SyncSchema.restoreMarker(context.noBackupFilesDir)
        marker.delete()
        BackupRepository.resetPendingRestoreGuardForTests()
        fun open() = Room.databaseBuilder(context, AppDatabase::class.java, BackupRepository.DB_NAME)
            .addMigrations(*AppDatabase.ALL_MIGRATIONS)
            .addCallback(SyncSchema.Callback(marker))
            .allowMainThreadQueries()
            .build()

        // A linked phone (block 3, till C) with changes waiting, backed up
        var db = open()
        runBlocking {
            db.syncDao().saveFlags(SyncFlagsEntity(tracking = true, block = 3, tillCode = "C", shopId = "shop-1", shopName = "Shop"))
            db.settingsAndAuditDao().saveShopProfile(ShopProfileEntity(shopName = "Backed up shop", setupCompleted = true))
            db.masterDao().insertCategory(CategoryEntity(name = "Phones"))
        }
        assertTrue(runBlocking { db.syncDao().pendingCount() } > 0)
        val backup = BackupRepository(context, db)
        val bytes = ByteArrayOutputStream().also { runBlocking { backup.writeFullBackupTo(it) } }.toByteArray()
        val inspection = runBlocking { backup.inspectBackupStream("test.zip") { ByteArrayInputStream(bytes) } }.getOrThrow()

        // Staging the restore unlinks the current data at once (nothing more is sent from it)
        runBlocking { backup.stageRestore(inspection) }.getOrThrow()
        assertFalse(runBlocking { db.syncDao().flags() }!!.isLinked)
        db.close()

        // Next start: the file is swapped in and opened; it forgets the shop it was linked to
        assertTrue(BackupRepository.applyPendingRestore(context))
        assertTrue(marker.exists())
        db = open()
        try {
            val flags = runBlocking { db.syncDao().flags() }!!
            assertFalse(flags.isLinked)
            assertFalse(flags.tracking)
            assertEquals(0L, flags.block)
            assertEquals(null, flags.tillCode)
            assertEquals(0, runBlocking { db.syncDao().pendingCount() })
            assertFalse(marker.exists())
            assertEquals("Backed up shop", runBlocking { db.settingsAndAuditDao().getShopProfileSync() }!!.shopName)
            // Its rows keep their ids (block 3); new ones continue in block 0 until the phone joins again
            assertEquals(SyncIds.firstId(3), runBlocking { db.masterDao().getCategoryByName("Phones") }!!.id)
            assertEquals(1L, runBlocking { db.masterDao().insertCategory(CategoryEntity(name = "After restore")) })
        } finally {
            db.close()
            context.deleteDatabase(BackupRepository.DB_NAME)
            File(context.noBackupFilesDir, "restore").deleteRecursively()
            File(context.noBackupFilesDir, "safety_copies").deleteRecursively()
        }
    }

    @Test
    fun aChildThatArrivesBeforeItsParentWaitsAndIsAppliedLater() = runBlocking {
        val p = phone("ocpabdurrahim@gmail.com")
        p.db.syncDao().saveFlags(SyncFlagsEntity(tracking = true, block = 1, tillCode = "B", shopId = "s"))
        val applier = SyncApplier(p.db)
        val customerId = SyncIds.firstId(5) + 6
        val entry = mapOf(
            "id" to SyncIds.firstId(5) + 9, "customerId" to customerId, "transactionType" to "OPENING_BALANCE",
            "referenceNumber" to "OPENING", "amount" to 250, "signedAmount" to 250, "runningBalance" to 0, "createdAt" to 10L
        )
        val r1 = applier.apply("s", SyncTables.get("customer_ledger_entries"), listOf(RemoteDoc("${entry["id"]}", entry, 5L)))!!
        assertEquals(1, r1.parked)
        assertEquals(0, p.db.customerLedgerDao().getAllLedgerEntriesSync().size)

        val customer = mapOf("id" to customerId, "customerCode" to "CUS-W0001", "name" to "Web customer", "phone" to "019", "currentDue" to 0)
        applier.apply("s", SyncTables.get("customers"), listOf(RemoteDoc("$customerId", customer, 6L)))!!
        assertEquals(1, p.db.customerLedgerDao().getAllLedgerEntriesSync().size)
        assertEquals(250.0, p.db.customerDao().getCustomerById(customerId)!!.currentDue, 0.0)
        assertEquals(250.0, p.db.customerLedgerDao().getAllLedgerEntriesSync().single().runningBalance, 0.0)
        assertEquals(0, p.db.syncDao().parked().size)
        assertEquals("applied rows are not sent back", 0, p.pending())
        assertEquals(6L, p.db.syncDao().cursor("customers"))

        // The customer's tombstone can't go while its ledger is here (RESTRICT): it waits too
        val r3 = applier.apply("s", SyncTables.get("customers"), listOf(RemoteDoc("$customerId", customer + ("_del" to true), 7L)))!!
        assertEquals(1, r3.parked)
        assertNotNull(p.db.customerDao().getCustomerById(customerId))
    }

    @Test
    fun aRowWithChangesNotUploadedYetIsNotOverwritten() = runBlocking {
        val p = phone("ocpabdurrahim@gmail.com")
        p.db.syncDao().saveFlags(SyncFlagsEntity(tracking = true, shopId = "s"))
        val id = p.db.masterDao().insertCategory(CategoryEntity(name = "Mine"))
        val cloudVersion = mapOf("id" to id, "name" to "Theirs", "isActive" to true)
        val r = SyncApplier(p.db).apply("s", SyncTables.get("categories"), listOf(RemoteDoc("$id", cloudVersion, 9L), RemoteDoc("77", mapOf("id" to 77L, "name" to "Pending"), null, pendingWrite = true)))!!
        assertEquals(2, r.skipped)
        assertEquals("Mine", p.db.masterDao().getCategoryById(id)!!.name)
        assertNull(p.db.masterDao().getCategoryById(77))
    }
}
