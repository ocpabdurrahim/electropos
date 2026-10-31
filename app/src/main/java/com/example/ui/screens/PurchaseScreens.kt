package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.core.utils.CurrencyUtils
import com.example.core.utils.DateTimeUtils
import com.example.data.local.entities.*
import com.example.data.repository.PurchaseItemDraft
import com.example.data.repository.SerialImeiInput
import com.example.ui.components.EmptyStateCard
import com.example.ui.theme.*
import com.example.ui.viewmodels.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PurchaseListScreen(
    viewModel: PurchaseListViewModel,
    onNavigateBack: () -> Unit,
    onNewPurchaseClick: () -> Unit,
    onPurchaseClick: (Long) -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Purchases & Inbound", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack, modifier = Modifier.testTag("purchase_list_back_btn")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = onNewPurchaseClick, modifier = Modifier.testTag("new_purchase_top_btn")) {
                        Icon(Icons.Default.AddShoppingCart, contentDescription = "New Purchase")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onNewPurchaseClick,
                containerColor = MaterialTheme.colorScheme.primary,
                modifier = Modifier.testTag("new_purchase_fab")
            ) {
                Icon(Icons.Default.Add, contentDescription = "New Purchase")
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Search and Status Filters
            Column(modifier = Modifier.padding(16.dp)) {
                OutlinedTextField(
                    value = uiState.searchQuery,
                    onValueChange = viewModel::onSearchQueryChanged,
                    placeholder = { Text("Search by purchase #, supplier, invoice...") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("purchase_search_input"),
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp)
                )

                Spacer(modifier = Modifier.height(10.dp))

                val filterOptions = listOf("ALL", "DUE", "PARTIAL", "PAID", "CANCELLED")
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    filterOptions.forEach { opt ->
                        FilterChip(
                            selected = uiState.selectedStatusFilter == opt,
                            onClick = { viewModel.onStatusFilterChanged(opt) },
                            label = { Text(opt, style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }
            }

            if (uiState.purchases.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    EmptyStateCard(
                        message = if (uiState.searchQuery.isBlank()) "No purchases recorded yet. Tap '+' to create a purchase order." else "No purchases match your filter.",
                        icon = Icons.Default.ShoppingBag
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(uiState.purchases, key = { it.id }) { purchase ->
                        PurchaseListItemCard(
                            purchase = purchase,
                            onClick = { onPurchaseClick(purchase.id) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PurchaseListItemCard(
    purchase: PurchaseEntity,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .testTag("purchase_item_${purchase.id}"),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = purchase.purchaseNumber.ifBlank { purchase.invoiceNumber },
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                    Text(
                        text = DateTimeUtils.formatDate(purchase.purchaseDate, "dd MMM yyyy"),
                        style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                    )
                }

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = when {
                        purchase.status == "CANCELLED" -> AlertRose.copy(alpha = 0.15f)
                        purchase.paymentStatus == "PAID" -> StockGreen.copy(alpha = 0.15f)
                        purchase.paymentStatus == "PARTIAL" -> WarningAmber.copy(alpha = 0.15f)
                        else -> AlertRose.copy(alpha = 0.15f)
                    }
                ) {
                    Text(
                        text = if (purchase.status == "CANCELLED") "CANCELLED" else purchase.paymentStatus,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,
                            color = when {
                                purchase.status == "CANCELLED" -> AlertRose
                                purchase.paymentStatus == "PAID" -> StockGreen
                                purchase.paymentStatus == "PARTIAL" -> WarningAmber
                                else -> AlertRose
                            }
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = purchase.supplierName,
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
                    )
                    if (purchase.supplierInvoiceNumber.isNotBlank()) {
                        Text(
                            text = "Inv #${purchase.supplierInvoiceNumber}",
                            style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                        )
                    }
                }

                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = CurrencyUtils.format(purchase.totalAmount, "৳"),
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                    if (purchase.dueAmount > 0 && purchase.status != "CANCELLED") {
                        Text(
                            text = "Due: ${CurrencyUtils.format(purchase.dueAmount, "৳")}",
                            style = MaterialTheme.typography.bodySmall.copy(color = AlertRose, fontWeight = FontWeight.Bold)
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PurchaseEntryScreen(
    viewModel: PurchaseEntryViewModel,
    onNavigateBack: () -> Unit,
    onPurchaseSaved: () -> Unit,
    onScanBarcodeRequested: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val scrollState = rememberScrollState()

    var showProductPicker by remember { mutableStateOf(false) }
    var showSerialInputDialog by remember { mutableStateOf(false) }
    var itemForSerialInput by remember { mutableStateOf<PendingSerialPurchaseItem?>(null) }

    LaunchedEffect(uiState.isSavedSuccess) {
        if (uiState.isSavedSuccess) {
            onPurchaseSaved()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("New Purchase Order", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack, modifier = Modifier.testTag("purchase_entry_back_btn")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(scrollState)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (uiState.errorMessage != null) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Text(
                        text = uiState.errorMessage ?: "",
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }

            // Header Section: Supplier & Order Numbers
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("1. Supplier & Invoice Info", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))

                    // Supplier Selector Dropdown/Picker
                    var supplierExpanded by remember { mutableStateOf(false) }
                    ExposedDropdownMenuBox(
                        expanded = supplierExpanded,
                        onExpandedChange = { supplierExpanded = !supplierExpanded }
                    ) {
                        OutlinedTextField(
                            value = uiState.selectedSupplier?.name ?: "Select Supplier *",
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Supplier *") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = supplierExpanded) },
                            modifier = Modifier
                                .menuAnchor()
                                .fillMaxWidth()
                                .testTag("purchase_supplier_select"),
                            shape = RoundedCornerShape(12.dp)
                        )
                        ExposedDropdownMenu(
                            expanded = supplierExpanded,
                            onDismissRequest = { supplierExpanded = false }
                        ) {
                            uiState.suppliers.forEach { s ->
                                DropdownMenuItem(
                                    text = { Text("${s.name} (${s.supplierCode})") },
                                    onClick = {
                                        viewModel.onSelectSupplier(s)
                                        supplierExpanded = false
                                    }
                                )
                            }
                        }
                    }

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(
                            value = uiState.supplierInvoiceNumber,
                            onValueChange = viewModel::onSupplierInvoiceChange,
                            label = { Text("Supplier Inv #") },
                            placeholder = { Text("e.g. INV-9041") },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp)
                        )
                        OutlinedTextField(
                            value = uiState.referenceNumber,
                            onValueChange = viewModel::onReferenceChange,
                            label = { Text("Challan / Ref #") },
                            placeholder = { Text("e.g. CH-201") },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp)
                        )
                    }
                }
            }

            // Items Section
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("2. Products Added (${uiState.items.size})", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            IconButton(onClick = onScanBarcodeRequested) {
                                Icon(Icons.Default.QrCodeScanner, contentDescription = "Scan")
                            }
                            FilledTonalButton(
                                onClick = { showProductPicker = true },
                                modifier = Modifier.testTag("add_product_to_purchase_btn"),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                            ) {
                                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Add Product")
                            }
                        }
                    }

                    if (uiState.items.isEmpty()) {
                        EmptyStateCard(
                            message = "No products added to this purchase yet. Tap '+ Add Product' or scan a barcode.",
                            icon = Icons.Default.Inventory2
                        )
                    } else {
                        uiState.items.forEachIndexed { index, item ->
                            PurchaseItemRowCard(
                                item = item,
                                onRemove = { viewModel.removeItem(index) }
                            )
                        }
                    }
                }
            }

            // Summary & Calculations Section
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("3. Summary & Financials", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Items Subtotal", style = MaterialTheme.typography.bodyMedium)
                        Text(CurrencyUtils.format(viewModel.getSubtotal(), "৳"), fontWeight = FontWeight.Bold)
                    }

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(
                            value = uiState.invoiceDiscount,
                            onValueChange = viewModel::onInvoiceDiscountChange,
                            label = { Text("Invoice Discount (৳)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp)
                        )
                        OutlinedTextField(
                            value = uiState.taxAmount,
                            onValueChange = viewModel::onTaxAmountChange,
                            label = { Text("Tax / VAT (৳)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp)
                        )
                    }

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(
                            value = uiState.transportCost,
                            onValueChange = viewModel::onTransportCostChange,
                            label = { Text("Transport Cost (৳)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp)
                        )
                        OutlinedTextField(
                            value = uiState.otherCost,
                            onValueChange = viewModel::onOtherCostChange,
                            label = { Text("Other Cost (৳)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp)
                        )
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Grand Total", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                        Text(
                            CurrencyUtils.format(viewModel.getGrandTotal(), "৳"),
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        )
                    }

                    // Payment row
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(
                            value = uiState.paidAmount,
                            onValueChange = viewModel::onPaidAmountChange,
                            label = { Text("Paid Amount (৳)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier
                                .weight(1.2f)
                                .testTag("purchase_paid_input"),
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp)
                        )
                        OutlinedTextField(
                            value = uiState.paymentMethod,
                            onValueChange = viewModel::onPaymentMethodChange,
                            label = { Text("Method") },
                            modifier = Modifier.weight(0.8f),
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp)
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Due Amount to Supplier", style = MaterialTheme.typography.bodyMedium.copy(color = AlertRose, fontWeight = FontWeight.Bold))
                        Text(CurrencyUtils.format(viewModel.getDueAmount(), "৳"), style = MaterialTheme.typography.titleMedium.copy(color = AlertRose, fontWeight = FontWeight.Bold))
                    }
                }
            }

            // Save Button
            Button(
                onClick = viewModel::savePurchase,
                enabled = !uiState.isSaving,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .testTag("save_purchase_btn"),
                shape = RoundedCornerShape(12.dp)
            ) {
                if (uiState.isSaving) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), color = MaterialTheme.colorScheme.onPrimary)
                } else {
                    Icon(Icons.Default.Save, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Complete Purchase & Stock-In", fontWeight = FontWeight.Bold)
                }
            }
        }
    }

    // Product Picker Dialog (also opened with the product a barcode scan found)
    if (showProductPicker || uiState.scannedProduct != null) {
        ProductSelectorDialog(
            products = uiState.availableProducts,
            initialProduct = uiState.scannedProduct,
            initialVariant = uiState.scannedVariant,
            variantsFor = { productId -> uiState.variantsByProduct[productId].orEmpty() },
            onDismiss = {
                showProductPicker = false
                viewModel.clearScannedProduct()
            },
            onProductSelected = { product, variant, quantity, unitCost, discount ->
                viewModel.clearScannedProduct()
                if (product.trackingType == "SERIAL" || product.trackingType == "IMEI" || product.trackingType == "SERIAL_AND_IMEI") {
                    itemForSerialInput = PendingSerialPurchaseItem(product, quantity, unitCost, discount, variant)
                    showSerialInputDialog = true
                } else {
                    viewModel.addItem(
                        PurchaseItemDraft(
                            productId = product.id,
                            productName = product.name + (variant?.let { " (${it.variantName})" } ?: ""),
                            variantId = variant?.id ?: 0L,
                            variantName = variant?.variantName ?: "",
                            quantity = quantity,
                            unitCost = unitCost,
                            discount = discount,
                            trackingType = product.trackingType
                        )
                    )
                }
                showProductPicker = false
            }
        )
    }

    // Serial & IMEI Input Dialog
    if (showSerialInputDialog && itemForSerialInput != null) {
        val pending = itemForSerialInput!!
        val prod = pending.product
        SerialImeiInputDialog(
            product = prod,
            quantity = pending.quantity,
            onDismiss = { showSerialInputDialog = false },
            onConfirmed = { serialsList ->
                viewModel.addItem(
                    PurchaseItemDraft(
                        productId = prod.id,
                        productName = prod.name + (pending.variant?.let { " (${it.variantName})" } ?: ""),
                        variantId = pending.variant?.id ?: 0L,
                        variantName = pending.variant?.variantName ?: "",
                        quantity = pending.quantity,
                        unitCost = pending.unitCost,
                        discount = pending.discount,
                        trackingType = prod.trackingType,
                        serialsOrImeis = serialsList
                    )
                )
                showSerialInputDialog = false
            }
        )
    }
}

/** Serialized line waiting for its serials/IMEIs; keeps the cost and discount typed in the product dialog. */
private data class PendingSerialPurchaseItem(
    val product: ProductEntity,
    val quantity: Int,
    val unitCost: Double,
    val discount: Double,
    val variant: ProductVariantEntity? = null
)

@Composable
private fun PurchaseItemRowCard(
    item: PurchaseItemDraft,
    onRemove: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(item.productName, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = "${item.quantity} × ৳${item.unitCost.toInt()} = ৳${((item.quantity * item.unitCost) - item.discount + item.tax).toInt()}",
                    style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                )
                if (item.serialsOrImeis.isNotEmpty()) {
                    Text(
                        text = "${item.serialsOrImeis.size} serials/IMEIs assigned",
                        style = MaterialTheme.typography.labelSmall.copy(color = PrimaryCyan)
                    )
                }
            }
            IconButton(onClick = onRemove) {
                Icon(Icons.Default.Delete, contentDescription = "Remove", tint = AlertRose)
            }
        }
    }
}

@Composable
fun ProductSelectorDialog(
    products: List<ProductEntity>,
    onDismiss: () -> Unit,
    onProductSelected: (ProductEntity, ProductVariantEntity?, Int, Double, Double) -> Unit,
    initialProduct: ProductEntity? = null,
    initialVariant: ProductVariantEntity? = null,
    variantsFor: (Long) -> List<ProductVariantEntity> = { emptyList() }
) {
    var search by remember { mutableStateOf("") }
    var selectedProduct by remember(initialProduct) { mutableStateOf(initialProduct) }
    var selectedVariant by remember(initialProduct, initialVariant) { mutableStateOf(initialVariant) }
    var quantityText by remember { mutableStateOf("1") }
    var costText by remember(initialProduct) {
        mutableStateOf(initialProduct?.purchasePrice?.takeIf { it > 0 }?.let { com.example.core.utils.CurrencyUtils.toInput(it) } ?: "")
    }
    var discountText by remember { mutableStateOf("0") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (selectedProduct == null) "Select Product" else "Enter Quantities & Cost") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                if (selectedProduct == null) {
                    OutlinedTextField(
                        value = search,
                        onValueChange = { search = it },
                        placeholder = { Text("Search product...") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    val filtered = products.filter {
                        it.name.contains(search, ignoreCase = true) ||
                        it.sku.contains(search, ignoreCase = true) ||
                        it.barcode.contains(search, ignoreCase = true)
                    }
                    LazyColumn(modifier = Modifier.heightIn(max = 260.dp)) {
                        items(filtered) { p ->
                            ListItem(
                                headlineContent = { Text(p.name, fontWeight = FontWeight.Bold) },
                                supportingContent = { Text("Cost: ৳${p.purchasePrice.toInt()} • Stock: ${p.currentStock}") },
                                modifier = Modifier
                                    .clickable {
                                        selectedProduct = p
                                        costText = if (p.purchasePrice > 0) com.example.core.utils.CurrencyUtils.toInput(p.purchasePrice) else ""
                                    }
                                    .testTag("select_product_${p.id}")
                            )
                        }
                    }
                } else {
                    val p = selectedProduct!!
                    Text("Product: ${p.name}", fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(10.dp))

                    // A product with variants is always bought as one of them, so each variant's stock adds up
                    val variants = variantsFor(p.id)
                    if (variants.isNotEmpty()) {
                        Text("Variant *", style = MaterialTheme.typography.labelMedium)
                        variants.forEach { v ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        selectedVariant = v
                                        if (v.purchasePrice > 0) costText = com.example.core.utils.CurrencyUtils.toInput(v.purchasePrice)
                                    }
                                    .testTag("purchase_variant_${v.id}"),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(selected = selectedVariant?.id == v.id, onClick = {
                                    selectedVariant = v
                                    if (v.purchasePrice > 0) costText = com.example.core.utils.CurrencyUtils.toInput(v.purchasePrice)
                                })
                                Text("${v.variantName}  •  stock ${v.currentStock}")
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                    }

                    OutlinedTextField(
                        value = quantityText,
                        onValueChange = { quantityText = it },
                        label = { Text("Quantity *") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("dialog_qty_input")
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = costText,
                        onValueChange = { costText = it },
                        label = { Text("Unit Purchase Cost (৳) *") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("dialog_cost_input")
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = discountText,
                        onValueChange = { discountText = it },
                        label = { Text("Line Discount (৳)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {
            if (selectedProduct != null) {
                val qty = quantityText.toIntOrNull() ?: 0
                val cost = costText.toDoubleOrNull()
                val needsVariant = variantsFor(selectedProduct!!.id).isNotEmpty() && selectedVariant == null
                Button(
                    onClick = {
                        val disc = discountText.toDoubleOrNull() ?: 0.0
                        onProductSelected(selectedProduct!!, selectedVariant, qty, cost ?: 0.0, disc)
                    },
                    enabled = qty > 0 && cost != null && cost >= 0 && !needsVariant,
                    modifier = Modifier.testTag("dialog_confirm_add_product")
                ) {
                    Text("Add to Purchase")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
fun SerialImeiInputDialog(
    product: ProductEntity,
    quantity: Int,
    onDismiss: () -> Unit,
    onConfirmed: (List<SerialImeiInput>) -> Unit
) {
    val inputs = remember { mutableStateListOf<SerialImeiInput>().apply { repeat(quantity) { add(SerialImeiInput()) } } }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Enter Serials / IMEIs (${inputs.size} required)") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                if (errorMessage != null) {
                    Text(errorMessage ?: "", color = AlertRose, style = MaterialTheme.typography.bodySmall)
                    Spacer(modifier = Modifier.height(6.dp))
                }

                inputs.indices.forEach { index ->
                    Text("Unit #${index + 1}", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                    if (product.trackingType == "SERIAL" || product.trackingType == "SERIAL_AND_IMEI") {
                        OutlinedTextField(
                            value = inputs[index].serialNumber,
                            onValueChange = { v -> inputs[index] = inputs[index].copy(serialNumber = v.trim()) },
                            label = { Text("Serial Number *") },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("serial_input_$index"),
                            singleLine = true
                        )
                    }
                    if (product.trackingType == "IMEI" || product.trackingType == "SERIAL_AND_IMEI") {
                        OutlinedTextField(
                            value = inputs[index].imei1,
                            onValueChange = { v -> inputs[index] = inputs[index].copy(imei1 = v.trim()) },
                            label = { Text("IMEI 1 *") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("imei1_input_$index"),
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = inputs[index].imei2,
                            onValueChange = { v -> inputs[index] = inputs[index].copy(imei2 = v.trim()) },
                            label = { Text("IMEI 2 (Optional)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("imei2_input_$index"),
                            singleLine = true
                        )
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    // Pre-validation
                    val serialSet = mutableSetOf<String>()
                    val imeiSet = mutableSetOf<String>()
                    for (i in inputs) {
                        val reqSerial = product.trackingType == "SERIAL" || product.trackingType == "SERIAL_AND_IMEI"
                        val reqImei = product.trackingType == "IMEI" || product.trackingType == "SERIAL_AND_IMEI"

                        if (reqSerial && i.serialNumber.isBlank()) {
                            errorMessage = "All serial numbers are required"
                            return@Button
                        }
                        if (reqImei && i.imei1.isBlank()) {
                            errorMessage = "All IMEI 1 entries are required"
                            return@Button
                        }
                        if (i.imei2.isNotBlank() && i.imei1.trim() == i.imei2.trim()) {
                            errorMessage = "IMEI 1 and IMEI 2 cannot be identical (${i.imei1.trim()})"
                            return@Button
                        }
                        if (i.serialNumber.isNotBlank() && !serialSet.add(i.serialNumber.trim())) {
                            errorMessage = "Duplicate serial number in entry: ${i.serialNumber.trim()}"
                            return@Button
                        }
                        if (i.imei1.isNotBlank() && !imeiSet.add(i.imei1.trim())) {
                            errorMessage = "Duplicate IMEI in entry: ${i.imei1.trim()}"
                            return@Button
                        }
                        if (i.imei2.isNotBlank() && !imeiSet.add(i.imei2.trim())) {
                            errorMessage = "Duplicate IMEI in entry: ${i.imei2.trim()}"
                            return@Button
                        }
                    }
                    onConfirmed(inputs.toList())
                },
                modifier = Modifier.testTag("confirm_serials_btn")
            ) {
                Text("Confirm Entries")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PurchaseDetailsScreen(
    viewModel: PurchaseDetailsViewModel,
    onNavigateBack: () -> Unit,
    onReturnClick: (Long) -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val purchase = uiState.purchase
    var showCancelDialog by remember { mutableStateOf(false) }
    var cancelReason by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(purchase?.purchaseNumber ?: "Purchase Details", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack, modifier = Modifier.testTag("purchase_details_back_btn")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (purchase != null && purchase.status != "CANCELLED") {
                        IconButton(onClick = { showCancelDialog = true }) {
                            Icon(Icons.Default.Cancel, contentDescription = "Cancel Purchase", tint = AlertRose)
                        }
                    }
                }
            )
        }
    ) { padding ->
        if (uiState.isLoading || purchase == null) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Status banner
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (purchase.status == "CANCELLED") AlertRose.copy(alpha = 0.15f) else MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(purchase.purchaseNumber, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                            Text(
                                if (purchase.status == "CANCELLED") "CANCELLED" else purchase.paymentStatus,
                                fontWeight = FontWeight.Bold,
                                color = if (purchase.status == "CANCELLED") AlertRose else StockGreen
                            )
                        }
                        Text("Supplier: ${purchase.supplierName}", style = MaterialTheme.typography.bodyMedium)
                        Text("Date: ${DateTimeUtils.formatDate(purchase.purchaseDate, "dd MMM yyyy, hh:mm a")}", style = MaterialTheme.typography.bodySmall)
                    }
                }

                // Line items
                Text("Items Purchased", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                uiState.items.forEach { item ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(item.productName, fontWeight = FontWeight.Bold)
                                Text("৳${item.lineTotal.toInt()}", fontWeight = FontWeight.Bold)
                            }
                            Text("${item.quantity} units @ ৳${item.unitCost.toInt()} (Allocated landed cost: ৳${item.allocatedLandedCost.toInt()})", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }

                // Financial Summary
                Card(shape = RoundedCornerShape(14.dp)) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Subtotal:")
                            Text(CurrencyUtils.format(purchase.subTotal, "৳"))
                        }
                        if (purchase.discountAmount > 0) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Discount:")
                                Text("-${CurrencyUtils.format(purchase.discountAmount, "৳")}", color = StockGreen)
                            }
                        }
                        if (purchase.taxAmount > 0) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Tax / VAT:")
                                Text("+${CurrencyUtils.format(purchase.taxAmount, "৳")}")
                            }
                        }
                        if (purchase.transportCost > 0 || purchase.otherCost > 0) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Transport & Other:")
                                Text("+${CurrencyUtils.format(purchase.transportCost + purchase.otherCost, "৳")}")
                            }
                        }
                        HorizontalDivider()
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Grand Total:", fontWeight = FontWeight.Bold)
                            Text(CurrencyUtils.format(purchase.totalAmount, "৳"), fontWeight = FontWeight.Bold)
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Paid via ${purchase.paymentMethod}:", color = StockGreen)
                            Text(CurrencyUtils.format(purchase.paidAmount, "৳"), color = StockGreen, fontWeight = FontWeight.Bold)
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Due to Supplier:", color = AlertRose, fontWeight = FontWeight.Bold)
                            Text(CurrencyUtils.format(purchase.dueAmount, "৳"), color = AlertRose, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                // Action: Return Items
                if (purchase.status != "CANCELLED") {
                    Button(
                        onClick = { onReturnClick(purchase.id) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                            .testTag("initiate_purchase_return_btn"),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.AssignmentReturn, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Create Purchase Return")
                    }
                }
            }
        }
    }

    if (showCancelDialog) {
        AlertDialog(
            onDismissRequest = { showCancelDialog = false },
            title = { Text("Cancel Purchase Order") },
            text = {
                Column {
                    Text("Are you sure? Stock will be reversed and supplier liabilities will be adjusted.")
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedTextField(
                        value = cancelReason,
                        onValueChange = { cancelReason = it },
                        label = { Text("Cancellation Reason *") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.cancelPurchase(cancelReason)
                        showCancelDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AlertRose)
                ) {
                    Text("Confirm Cancel")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCancelDialog = false }) { Text("Dismiss") }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PurchaseReturnScreen(
    viewModel: PurchaseReturnViewModel,
    onNavigateBack: () -> Unit,
    onReturnSuccess: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(uiState.isSuccess) {
        if (uiState.isSuccess) {
            onReturnSuccess()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Purchase Return", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack, modifier = Modifier.testTag("purchase_return_back_btn")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (uiState.errorMessage != null) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Text(
                        text = uiState.errorMessage ?: "",
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }

            Text("Select Items to Return", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)

            uiState.returnableItems.forEach { itemInfo ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(itemInfo.product.name, fontWeight = FontWeight.Bold)
                        Text(
                            text = "Purchased: ${itemInfo.purchaseItem.quantity} • Already Returned: ${itemInfo.alreadyReturnedQty} • Available: ${itemInfo.availableReturnQty}",
                            style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                        )
                        Text(
                            text = "Credit per unit: ${CurrencyUtils.format(itemInfo.returnUnitValue, "৳")} (after discounts)",
                            style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        if (itemInfo.isSerialized && itemInfo.serialUnits.isEmpty()) {
                            Text(
                                text = "No units from this line are in stock, so nothing can be returned.",
                                style = MaterialTheme.typography.bodySmall.copy(color = AlertRose)
                            )
                        } else if (itemInfo.isSerialized) {
                            // Serial unit pickers
                            Text("Select Units to Return:", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold))
                            itemInfo.serialUnits.forEach { sUnit ->
                                val isChecked = uiState.selectedSerials.contains(sUnit.id)
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { viewModel.onToggleSerial(sUnit.id, itemInfo.purchaseItem.id) }
                                        .padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Checkbox(
                                        checked = isChecked,
                                        onCheckedChange = { viewModel.onToggleSerial(sUnit.id, itemInfo.purchaseItem.id) }
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = if (!sUnit.imei1.isNullOrBlank()) "IMEI: ${sUnit.imei1}" else "SN: ${sUnit.serialNumber ?: ""}",
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                }
                            }
                        } else {
                            // Quantity picker
                            val selectedQty = uiState.returnSelections[itemInfo.purchaseItem.id] ?: 0
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Return Quantity:")
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    IconButton(
                                        onClick = { viewModel.onQuantityChange(itemInfo.purchaseItem.id, (selectedQty - 1).coerceAtLeast(0)) },
                                        enabled = selectedQty > 0
                                    ) {
                                        Icon(Icons.Default.Remove, contentDescription = "Decrease")
                                    }
                                    Text(selectedQty.toString(), fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 8.dp))
                                    IconButton(
                                        onClick = { viewModel.onQuantityChange(itemInfo.purchaseItem.id, (selectedQty + 1).coerceAtMost(itemInfo.availableReturnQty)) },
                                        enabled = selectedQty < itemInfo.availableReturnQty
                                    ) {
                                        Icon(Icons.Default.Add, contentDescription = "Increase")
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Summary Card
            Card(
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f))
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Total Return Value:", fontWeight = FontWeight.Bold)
                        Text(CurrencyUtils.format(viewModel.getTotalReturnAmount(), "৳"), fontWeight = FontWeight.Bold, color = AlertRose)
                    }

                    OutlinedTextField(
                        value = uiState.refundAmount,
                        onValueChange = viewModel::onRefundAmountChange,
                        label = { Text("Cash / Bank Refund Received (৳)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    OutlinedTextField(
                        value = uiState.reason,
                        onValueChange = viewModel::onReasonChange,
                        label = { Text("Reason for Return") },
                        placeholder = { Text("e.g. Defective unit, incorrect item") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }
            }

            Button(
                onClick = viewModel::submitReturn,
                enabled = !uiState.isSubmitting,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .testTag("submit_purchase_return_btn"),
                shape = RoundedCornerShape(12.dp)
            ) {
                if (uiState.isSubmitting) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), color = MaterialTheme.colorScheme.onPrimary)
                } else {
                    Icon(Icons.Default.AssignmentReturn, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Complete Purchase Return", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
