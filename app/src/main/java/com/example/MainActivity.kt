package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.core.security.AccessControl
import com.example.core.security.Feature
import com.example.ui.screens.*
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.viewmodels.*
import kotlinx.coroutines.launch

/** Key under which the barcode-capture screen hands its result back to the screen that opened it. */
private const val SCANNED_CODE = "scanned_code"

/** Screens reachable without signing in. */
private val PUBLIC_ROUTES = setOf("setup", "login")

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val app = AppContainer.get(applicationContext)
        app.syncManager.start()
        setContent { ElectroPosRoot(app) }
    }

    override fun onStart() {
        super.onStart()
        (application as com.example.ElectroPosApp).updater.onAppForeground()
    }

    override fun onStop() {
        super.onStop()
        // A downloaded update installs once the app is out of the way
        if (!isChangingConfigurations) (application as com.example.ElectroPosApp).updater.onAppBackground()
    }
}

@Composable
private fun ElectroPosRoot(app: AppContainer) {
    val authRepository = app.authRepository
    val shopProfile by authRepository.shopProfile.collectAsState(initial = null)
    val isLoggedIn by authRepository.isLoggedIn.collectAsState()
    val currentUser by authRepository.currentUser.collectAsState()

    // Saved across rotation so the NavHost (and its restored back stack) keeps the same start destination
    var startRoute by rememberSaveable { mutableStateOf<String?>(null) }
    val coroutineScope = rememberCoroutineScope()
    val syncStatus by app.syncManager.status.collectAsState()

    // A joined shop finished downloading: start again at the PIN login with the shop's own users
    var wasDownloading by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(syncStatus.downloading) {
        if (syncStatus.downloading) {
            wasDownloading = true
        } else if (wasDownloading) {
            wasDownloading = false
            startRoute = if (authRepository.isSetupCompletedNow()) "login" else "setup"
        }
    }

    LaunchedEffect(Unit) {
        if (!app.startupDone) {
            // Ensure default units exist
            app.unitRepository.getAllUnitsSync()
            // Check auto-login if shop setup is completed
            authRepository.checkAutoLogin()
            app.startupDone = true
        }
        if (startRoute == null) {
            startRoute = when {
                // Joining a shop: its profile hasn't arrived yet (the download screen covers the app)
                app.database.syncDao().flags()?.downloading == true -> "login"
                !authRepository.isSetupCompletedNow() -> "setup"
                authRepository.isLoggedIn.value -> "dashboard"
                else -> "login"
            }
        }
    }

    val themeMode = shopProfile?.themeMode ?: "SYSTEM"

    MyApplicationTheme(themeMode = themeMode) {
        val start = startRoute
        if (start == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
            return@MyApplicationTheme
        }

        if (syncStatus.downloading) {
            DownloadingShopScreen(
                status = syncStatus,
                onSyncNow = app.syncManager::syncNow,
                onUnlink = { coroutineScope.launch { app.syncManager.unlink() } }
            )
            return@MyApplicationTheme
        }

        key(start) {
        val navController = rememberNavController()
        val backStackEntry by navController.currentBackStackEntryAsState()

        // Login guard: nobody stays on a business screen without a signed-in user
        // (covers logout, a restored screen stack after the app was killed, and deactivated users)
        LaunchedEffect(isLoggedIn, backStackEntry?.destination?.route) {
            val route = backStackEntry?.destination?.route ?: return@LaunchedEffect
            if (!isLoggedIn && route !in PUBLIC_ROUTES) {
                val target = if (authRepository.isSetupCompletedNow()) "login" else "setup"
                navController.navigate(target) { popUpTo(0) { inclusive = true } }
            }
        }

        val role = currentUser?.role

        /** Shows [content] only when the signed-in user's role allows [feature]. */
        @Composable
        fun Guarded(feature: Feature, content: @Composable () -> Unit) {
            if (AccessControl.can(role, feature)) content()
            else NoAccessScreen(onBack = { navController.popBackStack() })
        }

        NavHost(
            navController = navController,
            startDestination = start,
            modifier = Modifier.fillMaxSize()
        ) {
            composable("setup") {
                val setupViewModel = viewModel { SetupViewModel(authRepository, app.sampleDataRepository) }
                SetupWizardScreen(
                    viewModel = setupViewModel,
                    onSetupComplete = {
                        navController.navigate("dashboard") {
                            popUpTo("setup") { inclusive = true }
                        }
                    }
                )
            }

            composable("login") {
                val loginViewModel = viewModel { LoginViewModel(authRepository) }
                LoginScreen(
                    viewModel = loginViewModel,
                    onLoginSuccess = {
                        navController.navigate("dashboard") {
                            popUpTo("login") { inclusive = true }
                        }
                    }
                )
            }

            composable("dashboard") {
                val dashboardViewModel = viewModel { DashboardViewModel(app.dashboardRepository, authRepository) }
                DashboardScreen(
                    viewModel = dashboardViewModel,
                    onNavigateToSettings = { navController.navigate("settings") },
                    onNavigateToShopProfile = { navController.navigate("shop_profile") },
                    onNavigateToUserProfile = { navController.navigate("user_profile") },
                    onNavigateToProducts = { navController.navigate("products") },
                    onNavigateToAddProduct = { navController.navigate("product_add") },
                    onNavigateToSuppliers = { navController.navigate("suppliers") },
                    onNavigateToAddSupplier = { navController.navigate("supplier_add") },
                    onNavigateToPurchases = { navController.navigate("purchases") },
                    onNavigateToNewPurchase = { navController.navigate("purchase_entry") },
                    onNavigateToPOS = { navController.navigate("pos") },
                    onNavigateToSales = { navController.navigate("sales") },
                    onNavigateToCustomers = { navController.navigate("customers") },
                    onNavigateToAddCustomer = { navController.navigate("customer_add") },
                    onNavigateToCloudSync = { navController.navigate("cloud_sync") },
                    onNavigateToWarranty = { navController.navigate("warranty") },
                    onNavigateToExpenses = { navController.navigate("expenses") },
                    onNavigateToCashBook = { navController.navigate("cash_book") },
                    onNavigateToReports = { navController.navigate("reports") },
                    onLogoutClick = {
                        coroutineScope.launch {
                            authRepository.logout()
                            navController.navigate("login") {
                                popUpTo(0) { inclusive = true }
                            }
                        }
                    }
                )
            }

            // Product Master Routes
            composable("products") {
                Guarded(Feature.PRODUCTS_VIEW) {
                    val productListViewModel = viewModel { ProductListViewModel(app.productRepository, app.categoryRepository, app.brandRepository) }
                    ProductListScreen(
                        viewModel = productListViewModel,
                        onNavigateBack = { navController.popBackStack() },
                        onAddProductClick = { navController.navigate("product_add") },
                        onProductClick = { productId -> navController.navigate("product_details/$productId") },
                        onManageCategoriesClick = { navController.navigate("categories") },
                        onManageBrandsClick = { navController.navigate("brands") },
                        onScanBarcodeClick = { navController.navigate("barcode_scanner") }
                    )
                }
            }

            composable(
                route = "product_add?barcode={barcode}",
                arguments = listOf(navArgument("barcode") { type = NavType.StringType; nullable = true; defaultValue = null })
            ) { entry ->
                Guarded(Feature.PRODUCTS_EDIT) {
                    val productFormViewModel = viewModel {
                        ProductFormViewModel(app.productRepository, app.categoryRepository, app.brandRepository, app.unitRepository)
                    }
                    // A barcode passed from the scanner ("Create product with this barcode") fills the field once
                    val initialBarcode = entry.arguments?.getString("barcode")
                    LaunchedEffect(productFormViewModel) {
                        if (!initialBarcode.isNullOrBlank() && productFormViewModel.state.value.barcode.isBlank()) {
                            productFormViewModel.updateBarcode(initialBarcode)
                        }
                    }
                    ReceiveScannedCode(entry) { code -> productFormViewModel.updateBarcode(code) }
                    AddEditProductScreen(
                        productId = 0L,
                        viewModel = productFormViewModel,
                        onNavigateBack = { navController.popBackStack() },
                        onProductSaved = { navController.popBackStack() },
                        onScanBarcodeRequested = { navController.navigate("barcode_capture") }
                    )
                }
            }

            composable(
                route = "product_edit/{productId}",
                arguments = listOf(navArgument("productId") { type = NavType.LongType })
            ) { entry ->
                Guarded(Feature.PRODUCTS_EDIT) {
                    val productId = entry.arguments?.getLong("productId") ?: 0L
                    val productFormViewModel = viewModel {
                        ProductFormViewModel(app.productRepository, app.categoryRepository, app.brandRepository, app.unitRepository)
                    }
                    ReceiveScannedCode(entry) { code -> productFormViewModel.updateBarcode(code) }
                    AddEditProductScreen(
                        productId = productId,
                        viewModel = productFormViewModel,
                        onNavigateBack = { navController.popBackStack() },
                        onProductSaved = { navController.popBackStack() },
                        onScanBarcodeRequested = { navController.navigate("barcode_capture") }
                    )
                }
            }

            composable(
                route = "product_details/{productId}",
                arguments = listOf(navArgument("productId") { type = NavType.LongType })
            ) { entry ->
                Guarded(Feature.PRODUCTS_VIEW) {
                    val productId = entry.arguments?.getLong("productId") ?: 0L
                    val detailsViewModel = viewModel {
                        ProductDetailsViewModel(productId, app.productRepository, app.stockRepository, app::operatorName)
                    }
                    ProductDetailsScreen(
                        viewModel = detailsViewModel,
                        onNavigateBack = { navController.popBackStack() },
                        onEditProductClick = { id ->
                            if (AccessControl.can(role, Feature.PRODUCTS_EDIT)) navController.navigate("product_edit/$id")
                        },
                        canEdit = AccessControl.can(role, Feature.PRODUCTS_EDIT),
                        canSeeCost = AccessControl.can(role, Feature.VIEW_COSTS_AND_PROFIT)
                    )
                }
            }

            composable("categories") {
                Guarded(Feature.CATEGORIES_BRANDS) {
                    val categoryViewModel = viewModel { CategoryViewModel(app.categoryRepository) }
                    CategoryListScreen(
                        viewModel = categoryViewModel,
                        onNavigateBack = { navController.popBackStack() }
                    )
                }
            }

            composable("brands") {
                Guarded(Feature.CATEGORIES_BRANDS) {
                    val brandViewModel = viewModel { BrandViewModel(app.brandRepository) }
                    BrandListScreen(
                        viewModel = brandViewModel,
                        onNavigateBack = { navController.popBackStack() }
                    )
                }
            }

            // Scanner in lookup mode: opens the product that was scanned
            composable("barcode_scanner") {
                BarcodeScannerScreen(
                    productRepository = app.productRepository,
                    onNavigateBack = { navController.popBackStack() },
                    onProductFound = { productId ->
                        navController.navigate("product_details/$productId") {
                            popUpTo("barcode_scanner") { inclusive = true }
                        }
                    },
                    onCreateProductWithBarcode = { barcode ->
                        val target = if (AccessControl.can(role, Feature.PRODUCTS_EDIT)) {
                            "product_add?barcode=${android.net.Uri.encode(barcode)}"
                        } else null
                        if (target != null) {
                            navController.navigate(target) {
                                popUpTo("barcode_scanner") { inclusive = true }
                            }
                        }
                    }
                )
            }

            // Scanner in capture mode: hands the code back to the screen that opened it (POS, purchase, product form)
            composable("barcode_capture") {
                BarcodeScannerScreen(
                    productRepository = app.productRepository,
                    onNavigateBack = { navController.popBackStack() },
                    onProductFound = {},
                    onCreateProductWithBarcode = {},
                    onCodeCaptured = { code ->
                        navController.previousBackStackEntry?.savedStateHandle?.set(SCANNED_CODE, code)
                        navController.popBackStack()
                    }
                )
            }

            // Supplier Routes
            composable("suppliers") {
                Guarded(Feature.SUPPLIERS) {
                    val supplierListViewModel = viewModel { SupplierListViewModel(app.supplierRepository) }
                    SupplierListScreen(
                        viewModel = supplierListViewModel,
                        onNavigateBack = { navController.popBackStack() },
                        onAddSupplierClick = { navController.navigate("supplier_add") },
                        onSupplierClick = { id -> navController.navigate("supplier_details/$id") },
                        onPaySupplierClick = { id -> navController.navigate("supplier_pay/$id") }
                    )
                }
            }

            composable("supplier_add") {
                Guarded(Feature.SUPPLIERS) {
                    val addViewModel = viewModel { AddEditSupplierViewModel(app.supplierRepository, 0L) }
                    AddEditSupplierScreen(
                        viewModel = addViewModel,
                        onNavigateBack = { navController.popBackStack() },
                        onSupplierSaved = { navController.popBackStack() }
                    )
                }
            }

            composable(
                route = "supplier_edit/{supplierId}",
                arguments = listOf(navArgument("supplierId") { type = NavType.LongType })
            ) { entry ->
                Guarded(Feature.SUPPLIERS) {
                    val supplierId = entry.arguments?.getLong("supplierId") ?: 0L
                    val editViewModel = viewModel { AddEditSupplierViewModel(app.supplierRepository, supplierId) }
                    AddEditSupplierScreen(
                        viewModel = editViewModel,
                        onNavigateBack = { navController.popBackStack() },
                        onSupplierSaved = { navController.popBackStack() }
                    )
                }
            }

            composable(
                route = "supplier_details/{supplierId}",
                arguments = listOf(navArgument("supplierId") { type = NavType.LongType })
            ) { entry ->
                Guarded(Feature.SUPPLIERS) {
                    val supplierId = entry.arguments?.getLong("supplierId") ?: 0L
                    val detailsViewModel = viewModel { SupplierDetailsViewModel(supplierId, app.supplierRepository) }
                    // Loaded with one-shot queries: refresh whenever the screen is shown again (after a payment, edit...)
                    LaunchedEffect(Unit) { detailsViewModel.loadDetails() }
                    SupplierDetailsScreen(
                        viewModel = detailsViewModel,
                        onNavigateBack = { navController.popBackStack() },
                        onEditSupplierClick = { id -> navController.navigate("supplier_edit/$id") },
                        onNewPurchaseClick = { navController.navigate("purchase_entry") },
                        onPaySupplierClick = { id -> navController.navigate("supplier_pay/$id") },
                        onViewLedgerClick = { id -> navController.navigate("supplier_ledger/$id") }
                    )
                }
            }

            composable(
                route = "supplier_ledger/{supplierId}",
                arguments = listOf(navArgument("supplierId") { type = NavType.LongType })
            ) { entry ->
                Guarded(Feature.SUPPLIERS) {
                    val supplierId = entry.arguments?.getLong("supplierId") ?: 0L
                    SupplierLedgerScreen(
                        supplierId = supplierId,
                        supplierRepository = app.supplierRepository,
                        onNavigateBack = { navController.popBackStack() }
                    )
                }
            }

            composable(
                route = "supplier_pay/{supplierId}",
                arguments = listOf(navArgument("supplierId") { type = NavType.LongType })
            ) { entry ->
                Guarded(Feature.SUPPLIERS) {
                    val supplierId = entry.arguments?.getLong("supplierId") ?: 0L
                    val paymentViewModel = viewModel {
                        SupplierPaymentViewModel(supplierId, app.supplierRepository, app.supplierPaymentRepository)
                    }
                    SupplierPaymentScreen(
                        viewModel = paymentViewModel,
                        onNavigateBack = { navController.popBackStack() },
                        onPaymentSuccess = { navController.popBackStack() }
                    )
                }
            }

            // Purchase Routes
            composable("purchases") {
                Guarded(Feature.PURCHASES) {
                    val purchaseListViewModel = viewModel { PurchaseListViewModel(app.purchaseRepository) }
                    PurchaseListScreen(
                        viewModel = purchaseListViewModel,
                        onNavigateBack = { navController.popBackStack() },
                        onNewPurchaseClick = { navController.navigate("purchase_entry") },
                        onPurchaseClick = { id -> navController.navigate("purchase_details/$id") }
                    )
                }
            }

            composable("purchase_entry") { entry ->
                Guarded(Feature.PURCHASES) {
                    val purchaseEntryViewModel = viewModel {
                        PurchaseEntryViewModel(app.purchaseRepository, app.supplierRepository, app.productRepository)
                    }
                    ReceiveScannedCode(entry) { code -> purchaseEntryViewModel.onCodeScanned(code) }
                    PurchaseEntryScreen(
                        viewModel = purchaseEntryViewModel,
                        onNavigateBack = { navController.popBackStack() },
                        onPurchaseSaved = { navController.popBackStack() },
                        onScanBarcodeRequested = { navController.navigate("barcode_capture") }
                    )
                }
            }

            composable(
                route = "purchase_details/{purchaseId}",
                arguments = listOf(navArgument("purchaseId") { type = NavType.LongType })
            ) { entry ->
                Guarded(Feature.PURCHASES) {
                    val purchaseId = entry.arguments?.getLong("purchaseId") ?: 0L
                    val detailsViewModel = viewModel {
                        PurchaseDetailsViewModel(purchaseId, app.purchaseRepository, app.purchaseReturnRepository)
                    }
                    // Loaded with one-shot queries: refresh whenever the screen is shown again (after a return)
                    LaunchedEffect(Unit) { detailsViewModel.loadDetails() }
                    PurchaseDetailsScreen(
                        viewModel = detailsViewModel,
                        onNavigateBack = { navController.popBackStack() },
                        onReturnClick = { id -> navController.navigate("purchase_return/$id") }
                    )
                }
            }

            composable(
                route = "purchase_return/{purchaseId}",
                arguments = listOf(navArgument("purchaseId") { type = NavType.LongType })
            ) { entry ->
                Guarded(Feature.PURCHASES) {
                    val purchaseId = entry.arguments?.getLong("purchaseId") ?: 0L
                    val returnViewModel = viewModel {
                        PurchaseReturnViewModel(purchaseId, app.purchaseRepository, app.purchaseReturnRepository)
                    }
                    PurchaseReturnScreen(
                        viewModel = returnViewModel,
                        onNavigateBack = { navController.popBackStack() },
                        onReturnSuccess = { navController.popBackStack() }
                    )
                }
            }

            // POS & Sales Routes
            composable("pos") { entry ->
                Guarded(Feature.POS) {
                    val posViewModel = viewModel {
                        POSViewModel(app.saleRepository, app.productRepository, app.customerRepository, app::operatorName)
                    }
                    ReceiveScannedCode(entry) { code -> posViewModel.addByCode(code) }
                    POSScreen(
                        viewModel = posViewModel,
                        onNavigateBack = { navController.popBackStack() },
                        onScanBarcodeRequested = { navController.navigate("barcode_capture") },
                        onViewInvoiceRequested = { saleId ->
                            navController.navigate("sale_details/$saleId") {
                                popUpTo("pos") { inclusive = true }
                            }
                        }
                    )
                }
            }

            composable("sales") {
                Guarded(Feature.SALES_HISTORY) {
                    val salesHistoryViewModel = viewModel { SalesHistoryViewModel(app.saleRepository) }
                    SalesHistoryScreen(
                        viewModel = salesHistoryViewModel,
                        onNavigateBack = { navController.popBackStack() },
                        onNewSaleClick = { navController.navigate("pos") },
                        onSaleClick = { saleId -> navController.navigate("sale_details/$saleId") }
                    )
                }
            }

            composable(
                route = "sale_details/{saleId}",
                arguments = listOf(navArgument("saleId") { type = NavType.LongType })
            ) { entry ->
                Guarded(Feature.SALES_HISTORY) {
                    val saleId = entry.arguments?.getLong("saleId") ?: 0L
                    val detailsViewModel = viewModel { SaleDetailsViewModel(saleId, app.saleRepository, app.salesReturnRepository) }
                    SaleDetailsScreen(
                        viewModel = detailsViewModel,
                        onNavigateBack = { navController.popBackStack() },
                        onReturnClick = { id -> navController.navigate("sale_return/$id") },
                        invoiceExporter = app.invoiceExporter
                    )
                }
            }

            composable(
                route = "sale_return/{saleId}",
                arguments = listOf(navArgument("saleId") { type = NavType.LongType })
            ) { entry ->
                Guarded(Feature.SALES_RETURN) {
                    val saleId = entry.arguments?.getLong("saleId") ?: 0L
                    val returnViewModel = viewModel {
                        SalesReturnViewModel(saleId, app.saleRepository, app.salesReturnRepository, app.productRepository, app::operatorName)
                    }
                    SalesReturnScreen(
                        viewModel = returnViewModel,
                        onNavigateBack = { navController.popBackStack() },
                        onReturnSuccess = { navController.popBackStack() }
                    )
                }
            }

            // Customer Routes
            composable("customers") {
                Guarded(Feature.CUSTOMERS) {
                    val customerListViewModel = viewModel { CustomerListViewModel(app.customerRepository) }
                    CustomerListScreen(
                        viewModel = customerListViewModel,
                        onNavigateBack = { navController.popBackStack() },
                        onAddCustomerClick = { navController.navigate("customer_add") },
                        onCustomerClick = { customerId -> navController.navigate("customer_details/$customerId") },
                        onPayCustomerClick = { customerId -> navController.navigate("customer_pay/$customerId") }
                    )
                }
            }

            composable("customer_add") {
                Guarded(Feature.CUSTOMERS) {
                    val addViewModel = viewModel { AddEditCustomerViewModel(app.customerRepository, 0L) }
                    AddEditCustomerScreen(
                        viewModel = addViewModel,
                        onNavigateBack = { navController.popBackStack() },
                        onCustomerSaved = { navController.popBackStack() }
                    )
                }
            }

            composable(
                route = "customer_edit/{customerId}",
                arguments = listOf(navArgument("customerId") { type = NavType.LongType })
            ) { entry ->
                Guarded(Feature.CUSTOMERS) {
                    val customerId = entry.arguments?.getLong("customerId") ?: 0L
                    val editViewModel = viewModel { AddEditCustomerViewModel(app.customerRepository, customerId) }
                    AddEditCustomerScreen(
                        viewModel = editViewModel,
                        onNavigateBack = { navController.popBackStack() },
                        onCustomerSaved = { navController.popBackStack() }
                    )
                }
            }

            composable(
                route = "customer_details/{customerId}",
                arguments = listOf(navArgument("customerId") { type = NavType.LongType })
            ) { entry ->
                Guarded(Feature.CUSTOMERS) {
                    val customerId = entry.arguments?.getLong("customerId") ?: 0L
                    val detailsViewModel = viewModel { CustomerDetailsViewModel(customerId, app.customerRepository, app.saleRepository) }
                    CustomerDetailsScreen(
                        viewModel = detailsViewModel,
                        onNavigateBack = { navController.popBackStack() },
                        onEditCustomerClick = { id -> navController.navigate("customer_edit/$id") },
                        onPayCustomerClick = { id -> navController.navigate("customer_pay/$id") },
                        onViewLedgerClick = { id -> navController.navigate("customer_ledger/$id") },
                        onSaleClick = { id -> navController.navigate("sale_details/$id") }
                    )
                }
            }

            composable(
                route = "customer_ledger/{customerId}",
                arguments = listOf(navArgument("customerId") { type = NavType.LongType })
            ) { entry ->
                Guarded(Feature.CUSTOMERS) {
                    val customerId = entry.arguments?.getLong("customerId") ?: 0L
                    CustomerLedgerScreen(
                        customerId = customerId,
                        customerRepository = app.customerRepository,
                        onNavigateBack = { navController.popBackStack() }
                    )
                }
            }

            composable(
                route = "customer_pay/{customerId}",
                arguments = listOf(navArgument("customerId") { type = NavType.LongType })
            ) { entry ->
                Guarded(Feature.CUSTOMERS) {
                    val customerId = entry.arguments?.getLong("customerId") ?: 0L
                    val paymentViewModel = viewModel { CustomerPaymentViewModel(customerId, app.customerRepository) }
                    CustomerPaymentScreen(
                        viewModel = paymentViewModel,
                        onNavigateBack = { navController.popBackStack() },
                        onPaymentSuccess = { navController.popBackStack() }
                    )
                }
            }

            composable("shop_profile") {
                Guarded(Feature.SHOP_PROFILE) {
                    val shopProfileViewModel = viewModel { ShopProfileViewModel(authRepository) }
                    ShopProfileScreen(
                        viewModel = shopProfileViewModel,
                        onNavigateBack = { navController.popBackStack() }
                    )
                }
            }

            composable("settings") {
                Guarded(Feature.SETTINGS) {
                    val settingsViewModel = viewModel {
                        SettingsViewModel(authRepository) {
                            app.backupRepository.createSafetyCopy("Before reset").map { }
                        }
                    }
                    SettingsScreen(
                        viewModel = settingsViewModel,
                        onNavigateBack = { navController.popBackStack() },
                        onNavigateToShopProfile = { navController.navigate("shop_profile") },
                        onNavigateToCategories = { navController.navigate("categories") },
                        onNavigateToBrands = { navController.navigate("brands") },
                        onNavigateToChangePin = { navController.navigate("change_pin") },
                        onNavigateToUsers = { navController.navigate("users") },
                        onNavigateToBackup = { navController.navigate("backup") },
                        onLoggedOut = {
                            navController.navigate("login") {
                                popUpTo(0) { inclusive = true }
                            }
                        },
                        onAppResetCompleted = {
                            navController.navigate("setup") {
                                popUpTo(0) { inclusive = true }
                            }
                        }
                    )
                }
            }

            composable("change_pin") {
                val settingsViewModel = viewModel { SettingsViewModel(authRepository) }
                ChangePinScreen(
                    viewModel = settingsViewModel,
                    onNavigateBack = { navController.popBackStack() }
                )
            }

            composable("backup") {
                Guarded(Feature.BACKUP) {
                    val backupViewModel = viewModel { BackupViewModel(app.backupRepository) }
                    BackupRestoreScreen(
                        viewModel = backupViewModel,
                        onNavigateBack = { navController.popBackStack() },
                        canRestore = AccessControl.can(role, Feature.RESTORE)
                    )
                }
            }

            composable("expenses") {
                Guarded(Feature.EXPENSES) {
                    val expensesViewModel = viewModel { ExpensesViewModel(app.financeRepository, app::operatorName) }
                    ExpensesScreen(
                        viewModel = expensesViewModel,
                        onNavigateBack = { navController.popBackStack() },
                        canEdit = AccessControl.can(role, Feature.EXPENSES)
                    )
                }
            }

            composable("cash_book") {
                Guarded(Feature.CASH_BOOK) {
                    val cashBookViewModel = viewModel { CashBookViewModel(app.financeRepository, app::operatorName) }
                    CashBookScreen(
                        viewModel = cashBookViewModel,
                        onNavigateBack = { navController.popBackStack() },
                        canEdit = AccessControl.can(role, Feature.CASH_BOOK),
                        onOpenExpenses = { navController.navigate("expenses") }
                    )
                }
            }

            composable("reports") {
                // Shows profit and costs, so it needs both permissions
                Guarded(if (AccessControl.can(role, Feature.VIEW_COSTS_AND_PROFIT)) Feature.REPORTS else Feature.VIEW_COSTS_AND_PROFIT) {
                    val reportsViewModel = viewModel { ReportsViewModel(app.reportsRepository) }
                    ReportsScreen(
                        viewModel = reportsViewModel,
                        onNavigateBack = { navController.popBackStack() }
                    )
                }
            }

            composable("warranty") {
                Guarded(Feature.WARRANTY) {
                    val warrantyViewModel = viewModel { WarrantyViewModel(app.warrantyRepository) }
                    WarrantyScreen(
                        viewModel = warrantyViewModel,
                        onNavigateBack = { navController.popBackStack() },
                        onOpenSale = { saleId -> navController.navigate("sale_details/$saleId") }
                    )
                }
            }

            composable("users") {
                Guarded(Feature.USERS) {
                    val usersViewModel = viewModel { UserManagementViewModel(authRepository) }
                    UserManagementScreen(
                        viewModel = usersViewModel,
                        onNavigateBack = { navController.popBackStack() }
                    )
                }
            }

            composable("user_profile") {
                UserProfileScreen(
                    authRepository = authRepository,
                    onNavigateBack = { navController.popBackStack() }
                )
            }

            composable("cloud_sync") {
                Guarded(Feature.CLOUD_SYNC) {
                    val cloudViewModel = viewModel { CloudSyncViewModel(app.syncManager) }
                    CloudSyncScreen(
                        viewModel = cloudViewModel,
                        onNavigateBack = { navController.popBackStack() }
                    )
                }
            }
        }
        }
    }
}

/** Delivers a code from the "barcode_capture" screen to the screen that opened it, exactly once. */
@Composable
private fun ReceiveScannedCode(entry: NavBackStackEntry, onCode: (String) -> Unit) {
    val handle = entry.savedStateHandle
    val code by handle.getStateFlow<String?>(SCANNED_CODE, null).collectAsState()
    val latestOnCode by rememberUpdatedState(onCode)
    LaunchedEffect(code) {
        code?.let {
            handle[SCANNED_CODE] = null
            latestOnCode(it)
        }
    }
}

@Composable
private fun NoAccessScreen(onBack: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.outline)
            Text("Not available for your role", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                "Ask the shop admin if you need access to this screen.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(onClick = onBack) { Text("Go back") }
        }
    }
}
