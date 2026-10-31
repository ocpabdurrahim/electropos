package com.example.ui.screens

import com.example.core.security.AccessControl
import com.example.core.security.Feature
import com.example.core.security.UserRoles
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.core.utils.CurrencyUtils
import com.example.core.utils.DateTimeUtils
import com.example.data.local.entities.ProductEntity
import com.example.ui.components.EmptyStateCard
import com.example.ui.components.SectionHeader
import com.example.ui.components.StatCard
import com.example.ui.theme.*
import com.example.ui.viewmodels.DashboardViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    viewModel: DashboardViewModel,
    onNavigateToSettings: () -> Unit,
    onNavigateToShopProfile: () -> Unit,
    onNavigateToUserProfile: () -> Unit,
    onNavigateToProducts: () -> Unit,
    onNavigateToAddProduct: () -> Unit,
    onNavigateToSuppliers: () -> Unit = {},
    onNavigateToAddSupplier: () -> Unit = {},
    onNavigateToPurchases: () -> Unit = {},
    onNavigateToNewPurchase: () -> Unit = {},
    onNavigateToPOS: () -> Unit = {},
    onNavigateToSales: () -> Unit = {},
    onNavigateToCustomers: () -> Unit = {},
    onNavigateToAddCustomer: () -> Unit = {},
    onNavigateToCloudSync: () -> Unit = {},
    onNavigateToWarranty: () -> Unit = {},
    onNavigateToExpenses: () -> Unit = {},
    onNavigateToCashBook: () -> Unit = {},
    onNavigateToReports: () -> Unit = {},
    onLogoutClick: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val role = uiState.currentUser?.role
    val canBuy = AccessControl.can(role, Feature.PURCHASES)
    val canSeeMoney = AccessControl.can(role, Feature.VIEW_COSTS_AND_PROFIT)
    var infoSnackbarMessage by remember { mutableStateOf<String?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(infoSnackbarMessage) {
        infoSnackbarMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            infoSnackbarMessage = null
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = uiState.shopProfile?.shopName?.ifBlank { "ElectroPOS Terminal" } ?: "ElectroPOS Terminal",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                        )
                        Text(
                            text = DateTimeUtils.formatDate(System.currentTimeMillis(), "EEEE, dd MMMM yyyy"),
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        )
                    }
                },
                actions = {
                    // Profile button
                    IconButton(
                        onClick = onNavigateToUserProfile,
                        modifier = Modifier.testTag("dashboard_profile_button")
                    ) {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primaryContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = (uiState.currentUser?.name?.take(1) ?: "A").uppercase(),
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            )
                        }
                    }

                    // Cloud Sync shortcut
                    if (AccessControl.can(role, Feature.CLOUD_SYNC)) {
                        IconButton(
                            onClick = onNavigateToCloudSync,
                            modifier = Modifier.testTag("dashboard_cloud_sync_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.CloudSync,
                                contentDescription = "Cloud Sync & Web Access",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                    // Settings shortcut
                    IconButton(
                        onClick = onNavigateToSettings,
                        modifier = Modifier.testTag("dashboard_settings_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Settings"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        if (uiState.isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(bottom = 32.dp)
            ) {
                // User Banner
                item {
                    UserWelcomeHeader(
                        userName = uiState.currentUser?.name ?: "Admin",
                        userRole = uiState.currentUser?.role ?: "ADMIN"
                    )
                }

                // Quick Actions Carousel
                item {
                    SectionHeader(title = "Quick Actions")
                    QuickActionsRow(
                        role = role,
                        onNavigateToPOS = onNavigateToPOS,
                        onNavigateToSales = onNavigateToSales,
                        onNavigateToCustomers = onNavigateToCustomers,
                        onNavigateToAddCustomer = onNavigateToAddCustomer,
                        onNavigateToProducts = onNavigateToProducts,
                        onNavigateToAddProduct = onNavigateToAddProduct,
                        onNavigateToSuppliers = onNavigateToSuppliers,
                        onNavigateToAddSupplier = onNavigateToAddSupplier,
                        onNavigateToPurchases = onNavigateToPurchases,
                        onNavigateToNewPurchase = onNavigateToNewPurchase,
                        onShopProfileClick = onNavigateToShopProfile,
                        onSettingsClick = onNavigateToSettings,
                        onNavigateToCloudSync = onNavigateToCloudSync,
                        onNavigateToWarranty = onNavigateToWarranty,
                        onNavigateToExpenses = onNavigateToExpenses,
                        onNavigateToCashBook = onNavigateToCashBook,
                        onNavigateToReports = onNavigateToReports
                    )
                }

                // Financial & Operations KPI Grid
                item {
                    SectionHeader(title = "Today's Business Summary")
                    val sym = uiState.summary.currencySymbol

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // Row 1: Sales & Purchases
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            StatCard(
                                title = "Today's Sales",
                                value = CurrencyUtils.format(uiState.summary.todaySales, sym),
                                icon = Icons.Default.PointOfSale,
                                iconTint = PrimaryCyan,
                                subtitle = "Completed sales",
                                modifier = Modifier.weight(1f),
                                onClick = onNavigateToSales
                            )
                            if (canBuy) {
                                StatCard(
                                    title = "Today's Purchases",
                                    value = CurrencyUtils.format(uiState.summary.todayPurchases, sym),
                                    icon = Icons.Default.ShoppingBag,
                                    iconTint = SecondaryBlue,
                                    subtitle = "Stock inbound",
                                    modifier = Modifier.weight(1f),
                                    onClick = onNavigateToPurchases
                                )
                            } else {
                                StatCard(
                                    title = "Customer Due",
                                    value = CurrencyUtils.format(uiState.summary.customerDue, sym),
                                    icon = Icons.Default.AccountBalanceWallet,
                                    iconTint = WarningAmber,
                                    subtitle = "Total receivables",
                                    modifier = Modifier.weight(1f),
                                    onClick = onNavigateToCustomers
                                )
                            }
                        }

                        // Row 2: Profit & Expenses (owner/manager only)
                        if (canSeeMoney) Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            StatCard(
                                title = "Today's Profit",
                                value = CurrencyUtils.format(uiState.summary.todayProfit, sym),
                                icon = Icons.Default.TrendingUp,
                                iconTint = StockGreen,
                                subtitle = "After returns, excl. tax",
                                modifier = Modifier.weight(1f),
                                onClick = onNavigateToReports
                            )
                            StatCard(
                                title = "Today's Expenses",
                                value = CurrencyUtils.format(uiState.summary.todayExpenses, sym),
                                icon = Icons.Default.ReceiptLong,
                                iconTint = AlertRose,
                                subtitle = "Store operations",
                                modifier = Modifier.weight(1f),
                                onClick = onNavigateToExpenses
                            )
                        }

                        // Row 3: Customer Due & Supplier Due
                        if (canBuy) Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            StatCard(
                                title = "Customer Due",
                                value = CurrencyUtils.format(uiState.summary.customerDue, sym),
                                icon = Icons.Default.AccountBalanceWallet,
                                iconTint = WarningAmber,
                                subtitle = "Total receivables",
                                modifier = Modifier.weight(1f),
                                onClick = onNavigateToCustomers
                            )
                            StatCard(
                                title = "Supplier Due",
                                value = CurrencyUtils.format(uiState.summary.supplierDue, sym),
                                icon = Icons.Default.LocalShipping,
                                iconTint = AccentPurple,
                                subtitle = "Total payables",
                                modifier = Modifier.weight(1f),
                                onClick = onNavigateToSuppliers
                            )
                        }

                        // Row 4: Stock Valuation & Product Health
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            StatCard(
                                title = if (canSeeMoney) "Stock Valuation" else "Products",
                                value = if (canSeeMoney) CurrencyUtils.format(uiState.summary.totalStockValue, sym) else "${uiState.summary.totalProductCount}",
                                icon = Icons.Default.Inventory2,
                                iconTint = PrimaryCyan,
                                subtitle = "${uiState.summary.totalProductCount} products in catalog",
                                modifier = Modifier.weight(1f),
                                onClick = onNavigateToProducts
                            )
                            StatCard(
                                title = "Alerts",
                                value = "${uiState.summary.lowStockCount} Low / ${uiState.summary.outOfStockCount} Out",
                                icon = Icons.Default.Warning,
                                iconTint = if (uiState.summary.outOfStockCount > 0) AlertRose else WarningAmber,
                                subtitle = "Inventory warnings",
                                modifier = Modifier.weight(1f),
                                onClick = onNavigateToProducts
                            )
                        }
                    }
                }

                // Low Stock Preview Section
                item {
                    Spacer(modifier = Modifier.height(14.dp))
                    SectionHeader(
                        title = "Low Stock Alert (${uiState.lowStockProducts.size})"
                    )

                    if (uiState.lowStockProducts.isEmpty()) {
                        EmptyStateCard(
                            message = "All products have adequate stock levels.",
                            icon = Icons.Default.CheckCircle,
                            tint = StockGreen
                        )
                    } else {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            uiState.lowStockProducts.take(4).forEach { product ->
                                ProductStockPreviewCard(product = product, isOutOfStock = false)
                            }
                        }
                    }
                }

                // Out of Stock Section
                if (uiState.outOfStockProducts.isNotEmpty()) {
                    item {
                        Spacer(modifier = Modifier.height(14.dp))
                        SectionHeader(
                            title = "Out of Stock Items (${uiState.outOfStockProducts.size})"
                        )
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            uiState.outOfStockProducts.take(4).forEach { product ->
                                ProductStockPreviewCard(product = product, isOutOfStock = true)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun UserWelcomeHeader(userName: String, userRole: String) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(
                    text = "Welcome back, $userName",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                )
                Text(
                    text = "Electronics & Mobile Terminal",
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                    )
                )
            }

            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.primary
            ) {
                Text(
                    text = UserRoles.label(userRole),
                    color = MaterialTheme.colorScheme.onPrimary,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    ),
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }
    }
}

@Composable
private fun QuickActionsRow(
    role: String?,
    onNavigateToPOS: () -> Unit,
    onNavigateToSales: () -> Unit,
    onNavigateToCustomers: () -> Unit,
    onNavigateToAddCustomer: () -> Unit,
    onNavigateToProducts: () -> Unit,
    onNavigateToAddProduct: () -> Unit,
    onNavigateToSuppliers: () -> Unit,
    onNavigateToAddSupplier: () -> Unit,
    onNavigateToPurchases: () -> Unit,
    onNavigateToNewPurchase: () -> Unit,
    onShopProfileClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onNavigateToCloudSync: () -> Unit = {},
    onNavigateToWarranty: () -> Unit = {},
    onNavigateToExpenses: () -> Unit = {},
    onNavigateToCashBook: () -> Unit = {},
    onNavigateToReports: () -> Unit = {}
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (AccessControl.can(role, Feature.CLOUD_SYNC)) item {
            QuickActionButton(
                label = "Cloud Sync",
                icon = Icons.Default.CloudSync,
                containerColor = PrimaryCyan,
                onClick = onNavigateToCloudSync
            )
        }
        item {
            QuickActionButton(
                label = "POS / Sale",
                icon = Icons.Default.PointOfSale,
                containerColor = PrimaryCyan,
                onClick = onNavigateToPOS
            )
        }
        item {
            QuickActionButton(
                label = "Sales History",
                icon = Icons.Default.ReceiptLong,
                containerColor = SecondaryBlue,
                onClick = onNavigateToSales
            )
        }
        item {
            QuickActionButton(
                label = "Customers",
                icon = Icons.Default.People,
                containerColor = WarningAmber,
                onClick = onNavigateToCustomers
            )
        }
        item {
            QuickActionButton(
                label = "Add Customer",
                icon = Icons.Default.PersonAdd,
                containerColor = WarningAmber,
                onClick = onNavigateToAddCustomer
            )
        }
        if (AccessControl.can(role, Feature.PURCHASES)) item {
            QuickActionButton(
                label = "New Purchase",
                icon = Icons.Default.AddShoppingCart,
                containerColor = StockGreen,
                onClick = onNavigateToNewPurchase
            )
        }
        if (AccessControl.can(role, Feature.PURCHASES)) item {
            QuickActionButton(
                label = "Purchases",
                icon = Icons.Default.ShoppingBag,
                containerColor = SecondaryBlue,
                onClick = onNavigateToPurchases
            )
        }
        if (AccessControl.can(role, Feature.SUPPLIERS)) item {
            QuickActionButton(
                label = "Suppliers",
                icon = Icons.Default.LocalShipping,
                containerColor = AccentPurple,
                onClick = onNavigateToSuppliers
            )
        }
        if (AccessControl.can(role, Feature.SUPPLIERS)) item {
            QuickActionButton(
                label = "Add Supplier",
                icon = Icons.Default.AddBusiness,
                containerColor = PrimaryCyanVariant,
                onClick = onNavigateToAddSupplier
            )
        }
        item {
            QuickActionButton(
                label = "Products",
                icon = Icons.Default.Inventory2,
                containerColor = PrimaryCyan,
                onClick = onNavigateToProducts
            )
        }
        if (AccessControl.can(role, Feature.REPORTS)) item {
            QuickActionButton(
                label = "Reports",
                icon = Icons.Default.Assessment,
                containerColor = AccentPurple,
                onClick = onNavigateToReports
            )
        }
        if (AccessControl.can(role, Feature.EXPENSES)) item {
            QuickActionButton(
                label = "Expenses",
                icon = Icons.Default.ReceiptLong,
                containerColor = AlertRose,
                onClick = onNavigateToExpenses
            )
        }
        if (AccessControl.can(role, Feature.CASH_BOOK)) item {
            QuickActionButton(
                label = "Cash Book",
                icon = Icons.Default.AccountBalanceWallet,
                containerColor = StockGreen,
                onClick = onNavigateToCashBook
            )
        }
        if (AccessControl.can(role, Feature.WARRANTY)) item {
            QuickActionButton(
                label = "Warranty",
                icon = Icons.Default.VerifiedUser,
                containerColor = WarningAmber,
                onClick = onNavigateToWarranty
            )
        }
        if (AccessControl.can(role, Feature.PRODUCTS_EDIT)) item {
            QuickActionButton(
                label = "Add Product",
                icon = Icons.Default.AddBox,
                containerColor = SecondaryBlue,
                onClick = onNavigateToAddProduct
            )
        }
        if (AccessControl.can(role, Feature.SHOP_PROFILE)) item {
            QuickActionButton(
                label = "Shop Profile",
                icon = Icons.Default.Storefront,
                containerColor = SurfaceDarkBorder,
                onClick = onShopProfileClick
            )
        }
        item {
            QuickActionButton(
                label = "Settings",
                icon = Icons.Default.Tune,
                containerColor = SurfaceDarkBorder,
                onClick = onSettingsClick
            )
        }
    }
}

@Composable
private fun QuickActionButton(
    label: String,
    icon: ImageVector,
    containerColor: Color,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = containerColor.copy(alpha = 0.15f),
        modifier = Modifier.testTag("quick_action_$label")
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = containerColor,
                modifier = Modifier.size(20.dp)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            )
        }
    }
}

@Composable
private fun ProductStockPreviewCard(product: ProductEntity, isOutOfStock: Boolean) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = product.name,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                    maxLines = 1
                )
                Text(
                    text = "${product.categoryName} • ${product.brandName}",
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                )
            }

            Surface(
                shape = RoundedCornerShape(8.dp),
                color = if (isOutOfStock) AlertRose.copy(alpha = 0.15f) else WarningAmber.copy(alpha = 0.15f)
            ) {
                Text(
                    text = if (isOutOfStock) "Out of Stock" else "${product.currentStock} in stock (Min ${product.minStock})",
                    color = if (isOutOfStock) AlertRose else WarningAmber,
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }
    }
}
