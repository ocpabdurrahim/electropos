package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import com.example.data.local.entities.ProductEntity
import com.example.ui.components.AppTopBar
import com.example.ui.theme.*
import com.example.ui.viewmodels.ProductListViewModel
import com.example.ui.viewmodels.ProductSortOption
import com.example.ui.viewmodels.StockFilterOption

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProductListScreen(
    viewModel: ProductListViewModel,
    onNavigateBack: () -> Unit,
    onAddProductClick: () -> Unit,
    onProductClick: (Long) -> Unit,
    onManageCategoriesClick: () -> Unit,
    onManageBrandsClick: () -> Unit,
    onScanBarcodeClick: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    var showFilterSheet by remember { mutableStateOf(false) }
    var showSortMenu by remember { mutableStateOf(false) }

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(uiState.message) {
        uiState.message?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            viewModel.clearMessage()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            AppTopBar(
                title = "Product Catalog",
                subtitle = "${uiState.products.size} items listed",
                onBackClick = onNavigateBack,
                actions = {
                    IconButton(
                        onClick = onScanBarcodeClick,
                        modifier = Modifier.testTag("scan_barcode_header_button")
                    ) {
                        Icon(Icons.Default.QrCodeScanner, contentDescription = "Scan Barcode")
                    }
                    IconButton(
                        onClick = { showFilterSheet = true },
                        modifier = Modifier.testTag("filter_button")
                    ) {
                        Icon(Icons.Default.FilterList, contentDescription = "Filter Products")
                    }
                    IconButton(
                        onClick = { showSortMenu = true },
                        modifier = Modifier.testTag("sort_button")
                    ) {
                        Icon(Icons.Default.Sort, contentDescription = "Sort Products")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onAddProductClick,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.testTag("add_product_fab")
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add Product")
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Search Bar
            OutlinedTextField(
                value = uiState.filter.query,
                onValueChange = { viewModel.updateSearchQuery(it) },
                placeholder = { Text("Search by name, SKU, barcode, model...") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (uiState.filter.query.isNotBlank()) {
                        IconButton(onClick = { viewModel.updateSearchQuery("") }) {
                            Icon(Icons.Default.Clear, contentDescription = "Clear Search")
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .testTag("product_search_input")
            )

            // Category Horizontal Filter Pills
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    FilterChip(
                        selected = uiState.filter.categoryId == null,
                        onClick = { viewModel.setCategoryFilter(null) },
                        label = { Text("All Categories") }
                    )
                }
                items(uiState.categories) { cat ->
                    FilterChip(
                        selected = uiState.filter.categoryId == cat.id,
                        onClick = { viewModel.setCategoryFilter(cat.id) },
                        label = { Text(cat.name) }
                    )
                }
            }

            // Quick Stock Status Filter Pills
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(StockFilterOption.entries) { opt ->
                    val label = when (opt) {
                        StockFilterOption.ALL -> "All Stock"
                        StockFilterOption.IN_STOCK -> "In Stock"
                        StockFilterOption.LOW_STOCK -> "Low Stock"
                        StockFilterOption.OUT_OF_STOCK -> "Out of Stock"
                    }
                    FilterChip(
                        selected = uiState.filter.stockFilter == opt,
                        onClick = { viewModel.setStockFilter(opt) },
                        label = { Text(label) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Products List
            if (uiState.isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (uiState.products.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.Inventory2,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.size(56.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "No products found",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                        )
                        Text(
                            text = "Try adjusting your search query or filters, or add a new product.",
                            style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                            modifier = Modifier.padding(top = 4.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(onClick = onAddProductClick) {
                            Icon(Icons.Default.Add, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Add Product")
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 80.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(uiState.products, key = { it.id }) { product ->
                        ProductItemCard(
                            product = product,
                            onClick = { onProductClick(product.id) }
                        )
                    }
                }
            }
        }
    }

    // Sort Dropdown Menu
    if (showSortMenu) {
        DropdownMenu(
            expanded = showSortMenu,
            onDismissRequest = { showSortMenu = false }
        ) {
            DropdownMenuItem(
                text = { Text("Name (A to Z)") },
                onClick = { viewModel.setSortOption(ProductSortOption.NAME_ASC); showSortMenu = false }
            )
            DropdownMenuItem(
                text = { Text("Name (Z to A)") },
                onClick = { viewModel.setSortOption(ProductSortOption.NAME_DESC); showSortMenu = false }
            )
            DropdownMenuItem(
                text = { Text("Price (Low to High)") },
                onClick = { viewModel.setSortOption(ProductSortOption.PRICE_ASC); showSortMenu = false }
            )
            DropdownMenuItem(
                text = { Text("Price (High to Low)") },
                onClick = { viewModel.setSortOption(ProductSortOption.PRICE_DESC); showSortMenu = false }
            )
            DropdownMenuItem(
                text = { Text("Stock (Low to High)") },
                onClick = { viewModel.setSortOption(ProductSortOption.STOCK_ASC); showSortMenu = false }
            )
            DropdownMenuItem(
                text = { Text("Stock (High to Low)") },
                onClick = { viewModel.setSortOption(ProductSortOption.STOCK_DESC); showSortMenu = false }
            )
            DropdownMenuItem(
                text = { Text("Newest First") },
                onClick = { viewModel.setSortOption(ProductSortOption.NEWEST); showSortMenu = false }
            )
        }
    }

    // Filter Bottom Sheet
    if (showFilterSheet) {
        ModalBottomSheet(
            onDismissRequest = { showFilterSheet = false }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Filter Products",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                    )
                    TextButton(onClick = { viewModel.resetFilters() }) {
                        Text("Reset All")
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text("Tracking Type", style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold))
                Spacer(modifier = Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("QUANTITY", "SERIAL", "IMEI").forEach { type ->
                        FilterChip(
                            selected = uiState.filter.trackingType == type,
                            onClick = {
                                viewModel.setTrackingTypeFilter(if (uiState.filter.trackingType == type) null else type)
                            },
                            label = { Text(type) }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text("Brand", style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold))
                Spacer(modifier = Modifier.height(6.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(uiState.brands) { brand ->
                        FilterChip(
                            selected = uiState.filter.brandId == brand.id,
                            onClick = {
                                viewModel.setBrandFilter(if (uiState.filter.brandId == brand.id) null else brand.id)
                            },
                            label = { Text(brand.name) }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                Button(
                    onClick = { showFilterSheet = false },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Apply Filters")
                }
            }
        }
    }
}

@Composable
fun ProductItemCard(
    product: ProductEntity,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .testTag("product_card_${product.id}"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Icon / Thumbnail Box
            val icon = when (product.trackingType) {
                "IMEI" -> Icons.Default.PhoneAndroid
                "SERIAL" -> Icons.Default.Router
                else -> Icons.Default.DevicesOther
            }

            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp)
                )
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = product.name,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    maxLines = 1
                )
                Text(
                    text = "${product.brandName.ifBlank { "Generic" }} • ${product.categoryName}",
                    style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                )

                Spacer(modifier = Modifier.height(4.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (product.sku.isNotBlank()) {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = MaterialTheme.colorScheme.surface,
                            modifier = Modifier.padding(end = 6.dp)
                        ) {
                            Text(
                                text = product.sku,
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                            )
                        }
                    }

                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f)
                    ) {
                        Text(
                            text = product.trackingType,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            ),
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(10.dp))

            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = CurrencyUtils.format(product.retailPrice),
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                )

                Spacer(modifier = Modifier.height(4.dp))

                val isOut = product.currentStock <= 0
                val isLow = product.currentStock in 1..product.minStock
                val badgeColor = when {
                    isOut -> AlertRose
                    isLow -> WarningAmber
                    else -> StockGreen
                }
                val badgeText = when {
                    isOut -> "Out of Stock"
                    isLow -> "${product.currentStock} ${product.unit} (Low)"
                    else -> "${product.currentStock} ${product.unit}"
                }

                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = badgeColor.copy(alpha = 0.15f)
                ) {
                    Text(
                        text = badgeText,
                        color = badgeColor,
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
        }
    }
}
