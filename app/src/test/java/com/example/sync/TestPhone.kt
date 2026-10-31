package com.example.sync

import android.content.Context
import com.example.core.utils.SecurityUtils
import com.example.data.local.database.AppDatabase
import com.example.data.local.entities.*
import com.example.data.repository.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking

/** One phone in a sync test: its own database, its view of the fake cloud, its sync engine. */
class TestPhone(context: Context, cloud: FakeCloud, email: String, label: String) {
    val db: AppDatabase = syncDatabase(context)
    val auth = FakeAuth(email)
    val store = FakeRemoteStore(cloud, auth)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    var safetyCopies = 0
    var downloads = 0

    val sync = SyncManager(
        db = db, remote = store, auth = auth, scope = scope, deviceLabel = label,
        safetyCopy = { safetyCopies++; Result.success(Unit) },
        onShopDownloaded = { downloads++ },
        retryDelayMs = 200
    )

    val products = ProductRepository(db)
    val customers = CustomerRepository(db)
    val suppliers = SupplierRepository(db)
    val sales = SaleRepository(db)
    val purchases = PurchaseRepository(db)

    fun flags(): SyncFlagsEntity = runBlocking { db.syncDao().flags()!! }

    fun pending(): Int = runBlocking { db.syncDao().pendingCount() }

    fun conflicts(): List<SyncConflictEntity> = runBlocking { db.syncDao().conflicts() }

    /** A shop as the setup wizard leaves it, plus a product with opening stock and a customer. */
    fun seedShop(shopName: String): Seed = runBlocking {
        db.settingsAndAuditDao().saveShopProfile(ShopProfileEntity(shopName = shopName, setupCompleted = true))
        val salt = SecurityUtils.generateSalt()
        db.userDao().insertUser(UserEntity(name = "Owner", username = "owner", pinHash = SecurityUtils.hashPin("1234", salt), salt = salt))
        val productId = products.saveProduct(ProductEntity(name = "Charger 20W", purchasePrice = 300.0, retailPrice = 500.0, currentStock = 10)).getOrThrow()
        val customerId = customers.saveCustomer(CustomerEntity(name = "Rahim Telecom", phone = "01711000000")).getOrThrow()
        Seed(productId, customerId)
    }

    data class Seed(val productId: Long, val customerId: Long)

    fun close() {
        scope.cancel()
        db.close()
    }
}
