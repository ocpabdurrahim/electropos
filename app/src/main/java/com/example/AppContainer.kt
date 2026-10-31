package com.example

import android.content.Context
import android.os.Build
import com.example.data.local.database.AppDatabase
import com.example.data.repository.*
import com.example.sync.FirebaseSyncAuth
import com.example.sync.FirestoreRemoteStore
import com.example.sync.NoAuth
import com.example.sync.NoRemote
import com.example.sync.SyncManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * One set of repositories per process. Screens and ViewModels share it, so the signed-in user,
 * caches and database handle survive screen rotation (the Activity is recreated, this is not).
 */
class AppContainer private constructor(context: Context) {
    val database: AppDatabase = AppDatabase.getInstance(context)
    val authRepository = AuthRepository(database)
    val sampleDataRepository = SampleDataAndBackupRepository(database)
    val dashboardRepository = DashboardRepository(database)
    val categoryRepository = CategoryRepository(database.masterDao(), database.subcategoryDao(), database.settingsAndAuditDao())
    val brandRepository = BrandRepository(database.masterDao(), database.settingsAndAuditDao())
    val unitRepository = UnitRepository(database.unitDao(), database.settingsAndAuditDao())
    val productRepository = ProductRepository(
        database.productDao(),
        database.stockDao(),
        database.productVariantDao(),
        database.productPriceHistoryDao(),
        database.settingsAndAuditDao()
    )
    val supplierRepository = SupplierRepository(database)
    val purchaseRepository = PurchaseRepository(database)
    val purchaseReturnRepository = PurchaseReturnRepository(database)
    val supplierPaymentRepository = SupplierPaymentRepository(database)
    val customerRepository = CustomerRepository(database)
    val saleRepository = SaleRepository(database)
    val salesReturnRepository = SalesReturnRepository(database)
    val backupRepository = BackupRepository(context, database)
    val stockRepository = StockRepository(database)
    val financeRepository = FinanceRepository(database)
    val reportsRepository = ReportsRepository(database)
    val invoiceExporter by lazy { com.example.core.invoice.InvoiceExporter(context, database) }
    val warrantyRepository = WarrantyRepository(database.warrantyDao())

    /** Two-way cloud sync; without a Firebase project in the build it simply stays offline. */
    val syncManager: SyncManager by lazy {
        val firebase = runCatching { com.google.firebase.FirebaseApp.getApps(context).isNotEmpty() }.getOrDefault(false)
        SyncManager(
            db = database,
            remote = if (firebase) FirestoreRemoteStore() else NoRemote,
            auth = if (firebase) FirebaseSyncAuth() else NoAuth,
            scope = (context as? ElectroPosApp)?.appScope ?: CoroutineScope(SupervisorJob() + Dispatchers.Default),
            deviceLabel = listOfNotNull(Build.MANUFACTURER, Build.MODEL).joinToString(" ").trim().ifBlank { "Phone" },
            safetyCopy = { reason -> backupRepository.createSafetyCopy(reason).map { } },
            // The joined shop's PIN users apply from now on
            onShopDownloaded = { authRepository.logout() }
        )
    }

    /** Startup work (default units, auto-login check) runs once per process, not on every rotation. */
    @Volatile var startupDone: Boolean = false

    /** Name stamped on sales, returns and payments. */
    fun operatorName(): String = authRepository.currentUser.value?.name?.ifBlank { null } ?: "Admin"

    companion object {
        @Volatile private var instance: AppContainer? = null

        fun get(context: Context): AppContainer =
            instance ?: synchronized(this) {
                instance ?: AppContainer(context.applicationContext).also { instance = it }
            }
    }
}
