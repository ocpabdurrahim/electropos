package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.core.utils.*
import com.example.data.local.entities.CustomerEntity
import com.example.data.local.entities.ProductEntity
import com.example.data.local.entities.ProductSerialImeiEntity
import com.example.ui.theme.AlertRed
import com.example.ui.theme.AppGreen
import com.example.ui.theme.AppOrange
import com.example.ui.viewmodels.POSCartItem
import com.example.ui.viewmodels.POSViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun POSScreen(
    viewModel: POSViewModel,
    onNavigateBack: () -> Unit,
    onScanBarcodeRequested: () -> Unit,
    onViewInvoiceRequested: (Long) -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val activeCustomers by viewModel.activeCustomers.collectAsState(initial = emptyList())

    var showCustomerPicker by remember { mutableStateOf(false) }
    var showCheckoutSheet by remember { mutableStateOf(false) }
    var priceEditItem by remember { mutableStateOf<POSCartItem?>(null) }

    uiState.variantPickerProduct?.let { product ->
        val variants = uiState.variantsByProduct[product.id].orEmpty()
        val unassigned = uiState.unassignedStock(product)
        AlertDialog(
            onDismissRequest = viewModel::dismissVariantPicker,
            title = { Text(product.name, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Which one?", style = MaterialTheme.typography.bodySmall)
                    variants.forEach { v ->
                        ListItem(
                            headlineContent = { Text(v.variantName, fontWeight = FontWeight.SemiBold) },
                            supportingContent = { Text("Stock: ${v.currentStock}") },
                            trailingContent = { Text(CurrencyUtils.formatBDT(v.retailPrice.takeIf { it > 0 } ?: product.retailPrice)) },
                            modifier = Modifier
                                .clickable(enabled = v.currentStock > 0) { viewModel.pickVariant(v) }
                                .testTag("pick_variant_${v.id}")
                        )
                    }
                    if (unassigned > 0) {
                        ListItem(
                            headlineContent = { Text("No specific variant") },
                            supportingContent = { Text("Stock: $unassigned") },
                            trailingContent = { Text(CurrencyUtils.formatBDT(product.retailPrice)) },
                            modifier = Modifier.clickable { viewModel.pickVariant(null) }
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = viewModel::dismissVariantPicker) { Text("Cancel") } }
        )
    }

    priceEditItem?.let { editing ->
        var priceText by remember(editing.cartItemId) { mutableStateOf(CurrencyUtils.toInput(editing.unitPrice)) }
        var discountText by remember(editing.cartItemId) { mutableStateOf(if (editing.discount > 0) CurrencyUtils.toInput(editing.discount) else "") }
        var editError by remember(editing.cartItemId) { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { priceEditItem = null },
            title = { Text(editing.product.name, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = priceText,
                        onValueChange = { priceText = it; editError = null },
                        label = { Text("Selling price each (৳)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("pos_price_input")
                    )
                    OutlinedTextField(
                        value = discountText,
                        onValueChange = { discountText = it; editError = null },
                        label = { Text("Discount on this line (৳)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("pos_line_discount_input")
                    )
                    if (editing.product.minSellingPrice > 0) {
                        Text(
                            "Minimum selling price: ${CurrencyUtils.formatBDT(editing.product.minSellingPrice)} each",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    editError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }
            },
            confirmButton = {
                Button(onClick = {
                    val price = priceText.toDoubleOrNull()
                    if (price == null) {
                        editError = "Enter a valid price"
                    } else {
                        editError = viewModel.updateItemPrice(editing.cartItemId, price, discountText.toDoubleOrNull() ?: 0.0)
                        if (editError == null) priceEditItem = null
                    }
                }) { Text("Apply") }
            },
            dismissButton = { TextButton(onClick = { priceEditItem = null }) { Text("Cancel") } }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("POS Counter", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack, modifier = Modifier.testTag("pos_back_btn")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    // Customer Chip / Button
                    AssistChip(
                        onClick = { showCustomerPicker = true },
                        label = {
                            Text(
                                text = uiState.selectedCustomer?.name ?: "Walk-in Customer",
                                fontWeight = FontWeight.SemiBold
                            )
                        },
                        leadingIcon = {
                            Icon(Icons.Default.Person, contentDescription = null, modifier = Modifier.size(16.dp))
                        },
                        modifier = Modifier
                            .padding(end = 4.dp)
                            .testTag("pos_customer_chip")
                    )

                    // Barcode scan
                    IconButton(onClick = onScanBarcodeRequested, modifier = Modifier.testTag("pos_barcode_scan_btn")) {
                        Icon(Icons.Default.QrCodeScanner, contentDescription = "Scan")
                    }

                    // Clear cart
                    if (uiState.cartItems.isNotEmpty()) {
                        IconButton(onClick = viewModel::clearCart, modifier = Modifier.testTag("pos_clear_cart_btn")) {
                            Icon(Icons.Default.DeleteSweep, contentDescription = "Clear Cart")
                        }
                    }
                }
            )
        },
        bottomBar = {
            if (uiState.cartItems.isNotEmpty()) {
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 8.dp,
                    shadowElevation = 8.dp
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "${uiState.totalItemsCount} item(s) in Cart",
                                style = MaterialTheme.typography.labelMedium.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                            )
                            Text(
                                text = CurrencyUtils.formatBDT(uiState.grandTotal),
                                style = MaterialTheme.typography.titleLarge.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            )
                        }

                        Button(
                            onClick = { showCheckoutSheet = true },
                            modifier = Modifier
                                .height(48.dp)
                                .testTag("pos_checkout_button"),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.ShoppingCartCheckout, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Checkout", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Error banner
            if (uiState.errorMessage != null) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Error, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = uiState.errorMessage ?: "",
                            style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onErrorContainer)
                        )
                    }
                }
            }

            // Search Bar
            OutlinedTextField(
                value = uiState.searchQuery,
                onValueChange = viewModel::onSearchQueryChanged,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .testTag("pos_search_product_input"),
                placeholder = { Text("Search products by name, SKU or barcode...") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true,
                shape = RoundedCornerShape(12.dp)
            )

            // Category Chips
            val categories = uiState.products.map { it.categoryName }.distinct().filter { it.isNotBlank() }
            if (categories.isNotEmpty()) {
                LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item {
                        FilterChip(
                            selected = uiState.selectedCategory == null,
                            onClick = { viewModel.onCategorySelected(null) },
                            label = { Text("All") }
                        )
                    }
                    items(categories) { cat ->
                        FilterChip(
                            selected = uiState.selectedCategory == cat,
                            onClick = { viewModel.onCategorySelected(if (uiState.selectedCategory == cat) null else cat) },
                            label = { Text(cat) }
                        )
                    }
                }
            }

            // Products Catalog List
            val filteredProducts = uiState.products.filter { p ->
                val matchesQuery = uiState.searchQuery.isBlank() ||
                        p.name.contains(uiState.searchQuery, ignoreCase = true) ||
                        p.sku.contains(uiState.searchQuery, ignoreCase = true) ||
                        p.barcode.contains(uiState.searchQuery, ignoreCase = true)
                val matchesCat = uiState.selectedCategory == null || p.categoryName == uiState.selectedCategory
                matchesQuery && matchesCat
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                items(filteredProducts, key = { it.id }) { product ->
                    val inCart = uiState.cartItems.find { it.product.id == product.id }
                    POSProductCard(
                        product = product,
                        cartQuantity = inCart?.quantity ?: 0,
                        onAddToCart = { viewModel.requestAdd(product) }
                    )
                }
            }
        }
    }

    // Customer Picker Dialog
    if (showCustomerPicker) {
        AlertDialog(
            onDismissRequest = { showCustomerPicker = false },
            title = { Text("Select Customer", fontWeight = FontWeight.Bold) },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    // Walk-in option
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                viewModel.selectCustomer(null)
                                showCustomerPicker = false
                            }
                            .padding(vertical = 8.dp),
                        color = if (uiState.selectedCustomer == null) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.DirectionsWalk, contentDescription = null)
                            Spacer(modifier = Modifier.width(10.dp))
                            Text("Walk-in Customer (Cash / Full settlement)", fontWeight = FontWeight.Medium)
                        }
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                    LazyColumn(modifier = Modifier.heightIn(max = 300.dp)) {
                        items(activeCustomers) { customer ->
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        viewModel.selectCustomer(customer)
                                        showCustomerPicker = false
                                    }
                                    .padding(vertical = 4.dp),
                                color = if (uiState.selectedCustomer?.id == customer.id) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(8.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column {
                                        Text(customer.name, fontWeight = FontWeight.SemiBold)
                                        Text("${customer.customerCode} • ${customer.phone}", style = MaterialTheme.typography.bodySmall)
                                    }
                                    if (customer.currentDue > 0) {
                                        Text("Due: ${CurrencyUtils.formatBDT(customer.currentDue)}", color = AlertRed, style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showCustomerPicker = false }) { Text("Close") }
            }
        )
    }

    // Checkout Bottom Sheet
    if (showCheckoutSheet) {
        ModalBottomSheet(
            onDismissRequest = { showCheckoutSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp)
                    .padding(bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("Cart & Payment Checkout", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold))

                // Cart item list inside checkout
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 240.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(uiState.cartItems, key = { it.cartItemId }) { item ->
                        POSCartItemRow(
                            item = item,
                            onQuantityChanged = { qty -> viewModel.updateQuantity(item.cartItemId, qty) },
                            onSelectSerials = { viewModel.openSerialPicker(item.cartItemId) },
                            onRemove = { viewModel.removeFromCart(item.cartItemId) },
                            onEditPrice = { priceEditItem = item }
                        )
                    }
                }

                HorizontalDivider()

                // Calculations
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Subtotal:")
                    Text(CurrencyUtils.formatBDT(uiState.subtotal), fontWeight = FontWeight.SemiBold)
                }

                if (uiState.itemDiscounts > 0) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Item Discounts:")
                        Text("-${CurrencyUtils.formatBDT(uiState.itemDiscounts)}", color = AppGreen)
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Invoice Discount:")
                    OutlinedTextField(
                        value = uiState.invoiceDiscountInput,
                        onValueChange = viewModel::onInvoiceDiscountInputChanged,
                        modifier = Modifier.width(110.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        placeholder = { Text("0") }
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Grand Total:", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                    Text(
                        CurrencyUtils.formatBDT(uiState.grandTotal),
                        style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    )
                }

                // Paid Amount
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = uiState.paidAmountInput,
                        onValueChange = viewModel::onPaidAmountInputChanged,
                        label = { Text("Amount Paid (৳)") },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("pos_paid_amount_input"),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true
                    )
                    OutlinedButton(
                        onClick = viewModel::setQuickPaidExact,
                        modifier = Modifier.height(52.dp)
                    ) {
                        Text("Exact")
                    }
                }

                // Due or Change summary
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    if (uiState.dueAmount > 0) {
                        Text("Remaining Due: ${CurrencyUtils.formatBDT(uiState.dueAmount)}", color = AlertRed, fontWeight = FontWeight.Bold)
                    } else if (uiState.changeAmount > 0) {
                        Text("Change to Return: ${CurrencyUtils.formatBDT(uiState.changeAmount)}", color = AppGreen, fontWeight = FontWeight.Bold)
                    } else {
                        Text("Fully Settled", color = AppGreen, fontWeight = FontWeight.Bold)
                    }
                    Text("Customer: ${uiState.selectedCustomer?.name ?: "Walk-in"}", style = MaterialTheme.typography.labelSmall)
                }

                // Payment Method
                val methods = listOf("CASH", "BKASH", "NAGAD", "ROCKET", "CARD", "BANK")
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(methods) { method ->
                        FilterChip(
                            selected = uiState.paymentMethod == method,
                            onClick = { viewModel.onPaymentMethodChanged(method) },
                            label = { Text(method) }
                        )
                    }
                }

                // Complete Sale Button
                Button(
                    onClick = {
                        viewModel.completeSale()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp)
                        .testTag("complete_sale_btn"),
                    enabled = !uiState.isCheckingOut,
                    shape = RoundedCornerShape(12.dp)
                ) {
                    if (uiState.isCheckingOut) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), color = MaterialTheme.colorScheme.onPrimary)
                    } else {
                        Text("Complete Sale", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                    }
                }
            }
        }
    }

    // Serial/IMEI Picker Dialog
    if (uiState.isSerialPickerOpen && uiState.serialPickerTargetItem != null) {
        val target = uiState.serialPickerTargetItem!!
        AlertDialog(
            onDismissRequest = viewModel::closeSerialPicker,
            title = {
                Column {
                    Text("Select Serial/IMEI", fontWeight = FontWeight.Bold)
                    Text(
                        "${target.product.name} (Pick ${target.quantity})",
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "Selected: ${target.selectedSerialImeiIds.size}/${target.quantity}",
                        fontWeight = FontWeight.Bold,
                        color = if (target.selectedSerialImeiIds.size == target.quantity) AppGreen else AlertRed
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    if (uiState.availableSerialsForPicker.isEmpty()) {
                        Text("No serial units currently available in stock.")
                    } else {
                        LazyColumn(modifier = Modifier.heightIn(max = 260.dp)) {
                            items(uiState.availableSerialsForPicker) { serial ->
                                val isChecked = target.selectedSerialImeiIds.contains(serial.id)
                                // Phones are known by IMEI 1, so it leads; the serial number goes underneath
                                val label = serial.imei1?.takeIf { it.isNotBlank() } ?: serial.serialNumber ?: "Unit-${serial.id}"

                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { viewModel.toggleSerialSelection(serial) }
                                        .padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Checkbox(
                                        checked = isChecked,
                                        onCheckedChange = { viewModel.toggleSerialSelection(serial) }
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Column {
                                        Text(label, fontWeight = FontWeight.Medium)
                                        if (!serial.imei1.isNullOrBlank() && !serial.serialNumber.isNullOrBlank()) {
                                            Text("Serial: ${serial.serialNumber}", style = MaterialTheme.typography.bodySmall)
                                        }
                                        if (serial.imei2?.isNotBlank() == true) {
                                            Text("IMEI 2: ${serial.imei2}", style = MaterialTheme.typography.bodySmall)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = viewModel::closeSerialPicker) {
                    Text("Done")
                }
            }
        )
    }

    // Success Completion Dialog
    if (uiState.isCompleted) {
        AlertDialog(
            onDismissRequest = {
                viewModel.resetSaleState()
                showCheckoutSheet = false
            },
            icon = { Icon(Icons.Default.CheckCircle, contentDescription = null, tint = AppGreen, modifier = Modifier.size(48.dp)) },
            title = { Text("Sale Completed Successfully!", fontWeight = FontWeight.Bold) },
            text = {
                Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Invoice Number: ${uiState.completedInvoiceNumber ?: ""}", fontWeight = FontWeight.Bold)
                    Text("Grand Total: ${CurrencyUtils.formatBDT(uiState.grandTotal)}")
                    Text("Paid Amount: ${CurrencyUtils.formatBDT(uiState.paidAmount)}")
                    if (uiState.dueAmount > 0) {
                        Text("Remaining Due: ${CurrencyUtils.formatBDT(uiState.dueAmount)}", color = AlertRed, fontWeight = FontWeight.Bold)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val sId = uiState.completedSaleId
                        viewModel.resetSaleState()
                        showCheckoutSheet = false
                        if (sId != null) {
                            onViewInvoiceRequested(sId)
                        }
                    }
                ) {
                    Text("View Invoice")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        viewModel.resetSaleState()
                        showCheckoutSheet = false
                    }
                ) {
                    Text("New Sale")
                }
            }
        )
    }
}

@Composable
fun POSProductCard(
    product: ProductEntity,
    cartQuantity: Int,
    onAddToCart: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("pos_product_item_${product.id}"),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(product.name, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                Text(
                    "${product.categoryName} • ${product.brandName}",
                    style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = CurrencyUtils.formatBDT(product.retailPrice),
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    val stockColor = if (product.currentStock > 0) AppGreen else AlertRed
                    Text(
                        text = "Stock: ${product.currentStock}",
                        style = MaterialTheme.typography.labelSmall.copy(color = stockColor, fontWeight = FontWeight.SemiBold)
                    )
                }
            }

            if (product.currentStock > 0) {
                Button(
                    onClick = onAddToCart,
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.testTag("pos_add_to_cart_btn_${product.id}")
                ) {
                    Icon(Icons.Default.AddShoppingCart, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(if (cartQuantity > 0) "Add ($cartQuantity)" else "Add")
                }
            } else {
                Surface(
                    color = AlertRed.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(
                        text = "Out of Stock",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall.copy(color = AlertRed, fontWeight = FontWeight.Bold)
                    )
                }
            }
        }
    }
}

@Composable
fun POSCartItemRow(
    item: POSCartItem,
    onQuantityChanged: (Int) -> Unit,
    onSelectSerials: () -> Unit,
    onRemove: () -> Unit,
    onEditPrice: () -> Unit = {}
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier
                    .weight(1f)
                    .clickable(onClick = onEditPrice)) {
                    Text(item.product.name + (item.variant?.let { " (${it.variantName})" } ?: ""), fontWeight = FontWeight.Bold)
                    Text(
                        text = "${CurrencyUtils.formatBDT(item.unitPrice)} each" +
                            (if (item.discount > 0) " • -${CurrencyUtils.formatBDT(item.discount)}" else "") + "  ✎",
                        style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.primary)
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { onQuantityChanged(item.quantity - 1) }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Remove, contentDescription = "Decrease", modifier = Modifier.size(16.dp))
                    }
                    Text("${item.quantity}", fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 6.dp))
                    IconButton(onClick = { onQuantityChanged(item.quantity + 1) }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Add, contentDescription = "Increase", modifier = Modifier.size(16.dp))
                    }
                    IconButton(onClick = onRemove, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Remove", tint = AlertRed, modifier = Modifier.size(16.dp))
                    }
                }
            }

            if (item.requiresSerials) {
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val isComplete = item.selectedSerialImeiIds.size == item.quantity
                    Text(
                        text = if (isComplete) "Serials selected: ${item.selectedSerialsOrImeis.joinToString(", ")}"
                        else "Requires ${item.quantity} serial/IMEI (${item.selectedSerialImeiIds.size} picked)",
                        style = MaterialTheme.typography.labelSmall.copy(color = if (isComplete) AppGreen else AlertRed),
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = onSelectSerials, contentPadding = PaddingValues(0.dp)) {
                        Text("Pick Serials")
                    }
                }
            }
        }
    }
}
