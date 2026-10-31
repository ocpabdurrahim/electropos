package com.example.sync

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.database.AppDatabase
import com.example.data.local.entities.*
import com.example.data.repository.ProductRepository
import com.example.data.repository.PurchaseItemDraft
import com.example.data.repository.PurchaseRepository
import com.example.data.repository.SupplierRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Id blocks, the guard trigger, and which local changes the triggers record. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SyncSchemaTest {
    private lateinit var db: AppDatabase
    private val sql get() = db.openHelper.writableDatabase

    @Before
    fun setUp() {
        db = syncDatabase(ApplicationProvider.getApplicationContext<Context>())
    }

    @After
    fun tearDown() = db.close()

    private fun setFlags(block: Long = 0, tracking: Boolean = false, applying: Boolean = false) = runBlocking {
        db.syncDao().saveFlags(SyncFlagsEntity(block = block, tracking = tracking, applying = applying, shopId = if (tracking) "shop" else null))
    }

    private fun outbox(): List<Pair<String, Long>> = sql.query("SELECT tbl, rowId FROM sync_outbox ORDER BY id").use { c ->
        val out = ArrayList<Pair<String, Long>>()
        while (c.moveToNext()) out.add(c.getString(0) to c.getLong(1))
        out
    }

    private fun clearOutbox() = sql.execSQL("DELETE FROM sync_outbox")

    // ------------------------------------------------------------------ ids

    @Test
    fun newRowsTakeIdsFromThisDevicesBlock() = runBlocking {
        val first = db.categoryInsert("Phones")
        assertEquals(1L, first)

        setFlags(block = 3)
        val inBlock = db.categoryInsert("Chargers")
        assertEquals(3 * SyncIds.BLOCK_SIZE + 1, inBlock)
        assertEquals(3 * SyncIds.BLOCK_SIZE + 2, db.categoryInsert("Cables"))

        // Lists get consecutive ids
        val saleId = db.saleDao().insertSale(SaleEntity(invoiceNumber = "X-1"))
        val productId = db.productDao().insertProduct(ProductEntity(name = "P"))
        val items = db.saleDao().insertSaleItems(
            List(3) { SaleItemEntity(saleId = saleId, productId = productId, productName = "P", quantity = 1, unitPrice = 1.0, unitCostAtSale = 0.0, totalPrice = 1.0) }
        )
        assertEquals(listOf(1L, 2L, 3L).map { 3 * SyncIds.BLOCK_SIZE + it }, items)
    }

    @Test
    fun idsAreNeverReusedAndSkipRowsThatArrivedWithAnId() = runBlocking {
        val a = db.categoryInsert("A")
        val b = db.categoryInsert("B")
        db.masterDao().deleteCategory(db.masterDao().getCategoryById(b)!!)
        assertEquals(b + 1, db.categoryInsert("C")) // the deleted id stays retired
        // A row that came with an explicit id of this block (e.g. from a restore)
        db.masterDao().insertCategory(CategoryEntity(id = 50, name = "Explicit"))
        assertEquals(51L, db.categoryInsert("D"))
        assertEquals(1L, a)
    }

    @Test
    fun guardRefusesIdsOutsideTheBlockAndIdsLeftToSqlite() = runBlocking {
        setFlags(block = 2)
        // A missed insert path: SQLite would pick the id itself
        assertThrows(SQLiteConstraintException::class.java) {
            runBlocking { db.masterDao().insertCategoryRaw(CategoryEntity(name = "No id")) }
        }
        // Another device's id
        assertThrows(SQLiteConstraintException::class.java) {
            runBlocking { db.masterDao().insertCategory(CategoryEntity(id = 7, name = "Block 0 id")) }
        }
        assertEquals(0, count("categories"))
        // Rows applied from the cloud may carry any device's id
        setFlags(block = 2, applying = true)
        db.masterDao().insertCategory(CategoryEntity(id = 7, name = "From the cloud"))
        assertEquals(1, count("categories"))
        // The shop profile is one shared row (id 1) and has no guard
        setFlags(block = 2)
        db.settingsAndAuditDao().saveShopProfile(ShopProfileEntity(shopName = "Shop"))
        assertEquals(1, count("shop_profile"))
    }

    // ------------------------------------------------------------------ what the triggers record

    @Test
    fun nothingIsRecordedWhileTrackingIsOffOrWhileApplying() = runBlocking {
        db.categoryInsert("Offline")
        assertTrue(outbox().isEmpty())
        setFlags(tracking = true, applying = true)
        db.masterDao().insertCategory(CategoryEntity(id = SyncIds.BLOCK_SIZE * 9 + 1, name = "From cloud"))
        assertTrue(outbox().isEmpty())
    }

    @Test
    fun realChangesAreRecordedButCacheLocalAndTimestampOnlyChangesAreNot() = runBlocking {
        setFlags(tracking = true)
        val customerId = db.customerDao().insertCustomer(CustomerEntity(name = "Rahim", phone = "017"))
        val productId = db.productDao().insertProduct(ProductEntity(name = "Cable", purchasePrice = 100.0))
        db.settingsAndAuditDao().saveShopProfile(ShopProfileEntity(shopName = "Shop"))
        val userId = db.userDao().insertUser(UserEntity(name = "A", username = "a", pinHash = "h", salt = "s"))
        assertEquals(4, outbox().size) // every insert
        clearOutbox()

        // Caches and the edit time alone: not sent
        db.customerDao().setCustomerBalance(customerId, 500.0)
        db.productDao().setStockQuantity(productId, 12)
        db.customerDao().updateCustomer(db.customerDao().getCustomerById(customerId)!!.copy(updatedAt = 99))
        // Phone-only columns: not sent
        db.settingsAndAuditDao().setThemeMode("DARK")
        db.settingsAndAuditDao().setLastActiveUserId(userId)
        db.settingsAndAuditDao().incrementInvoiceNumber()
        db.userDao().updateLastLogin(userId)
        assertEquals(emptyList<Pair<String, Long>>(), outbox())

        // Real changes: sent
        db.customerDao().updateCustomer(db.customerDao().getCustomerById(customerId)!!.copy(name = "Rahim Telecom"))
        db.settingsAndAuditDao().saveShopProfile(db.settingsAndAuditDao().getShopProfileSync()!!.copy(phone = "019"))
        db.userDao().updatePin(userId, "h2", "s2")
        assertEquals(listOf("customers" to customerId, "shop_profile" to 1L, "users" to userId), outbox())
        clearOutbox()

        // Deletions are recorded too
        db.productDao().deleteProduct(db.productDao().getProductById(productId)!!)
        assertEquals(listOf("products" to productId), outbox())
    }

    @Test
    fun enteredCostIsSentUntilTheFirstPurchaseThenItIsTheWeightedAverage() = runBlocking {
        val supplierId = SupplierRepository(db).saveSupplier(SupplierEntity(name = "Vendor", phone = "018")).getOrThrow()
        val productId = ProductRepository(db).saveProduct(ProductEntity(name = "Earphone", purchasePrice = 100.0, retailPrice = 150.0)).getOrThrow()
        setFlags(tracking = true)

        db.productDao().updatePurchaseCost(productId, 110.0) // entered cost, no purchase yet
        assertEquals(listOf("products" to productId), outbox())
        clearOutbox()

        val buy = suspend {
            PurchaseRepository(db).createPurchase(
                PurchaseEntity(supplierId = supplierId),
                listOf(PurchaseItemDraft(productId = productId, productName = "Earphone", quantity = 2, unitCost = 130.0))
            ).getOrThrow()
        }
        buy() // the first purchase may still carry the row once (its cost moves before its stock movement is written)
        clearOutbox()
        db.productDao().updatePurchaseCost(productId, 125.0)
        assertTrue(outbox().isEmpty())
        buy()
        assertFalse("later purchases move the cost, not the product row", outbox().contains("products" to productId))
        assertTrue(outbox().contains("stock_transactions" to db.stockDao().getTransactionsForProductSync(productId).last().id))
    }

    // ------------------------------------------------------------------ outbox

    @Test
    fun outboxIsReadOncePerRowAndANewerChangeSurvivesTheCleanup() = runBlocking {
        setFlags(tracking = true)
        val id = db.customerDao().insertCustomer(CustomerEntity(customerCode = "C1", name = "A", phone = "1"))
        repeat(3) { db.customerDao().updateCustomer(db.customerDao().getCustomerById(id)!!.copy(name = "A$it")) }
        val other = db.customerDao().insertCustomer(CustomerEntity(customerCode = "C2", name = "B", phone = "2"))

        val batch = db.syncDao().pendingBatch(400)
        assertEquals(listOf("customers" to id, "customers" to other), batch.map { it.tbl to it.rowId })
        assertEquals(2, db.syncDao().pendingCount())

        // The row changes again after it was read for upload, then the upload is cleaned up
        db.customerDao().updateCustomer(db.customerDao().getCustomerById(id)!!.copy(name = "Changed again"))
        batch.forEach { db.syncDao().clearSent(it.tbl, it.rowId, it.lastSeq) }
        assertEquals(listOf("customers" to id), db.syncDao().pendingBatch(400).map { it.tbl to it.rowId })
    }

    // ------------------------------------------------------------------ triggers installed once

    @Test
    fun triggersAreInstalledOnceAndRepairedWhenTheyDiffer() {
        assertFalse(SyncSchema.installTriggers(sql))
        sql.execSQL("DROP TRIGGER sync_ins_products")
        assertTrue(SyncSchema.installTriggers(sql))
        val count = sql.query("SELECT COUNT(*) FROM sqlite_master WHERE type = 'trigger' AND name LIKE 'sync\\_%' ESCAPE '\\'").use { it.moveToFirst(); it.getInt(0) }
        assertEquals(SyncSchema.triggers.size, count)
        assertEquals(SyncTables.ALL.size * 4 - 1, count) // no id guard on the shop profile
    }

    private fun count(table: String): Int = sql.query("SELECT COUNT(*) FROM $table").use { it.moveToFirst(); it.getInt(0) }

    private suspend fun AppDatabase.categoryInsert(name: String): Long = masterDao().insertCategory(CategoryEntity(name = name))
}
