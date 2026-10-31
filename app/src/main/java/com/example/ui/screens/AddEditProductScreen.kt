package com.example.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.components.AppTopBar
import com.example.ui.theme.StockGreen
import com.example.ui.viewmodels.ProductFormViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddEditProductScreen(
    productId: Long = 0,
    viewModel: ProductFormViewModel,
    onNavigateBack: () -> Unit,
    onProductSaved: () -> Unit,
    onScanBarcodeRequested: () -> Unit
) {
    val state by viewModel.state.collectAsState()

    LaunchedEffect(productId) {
        if (productId > 0) {
            viewModel.loadProduct(productId)
        }
    }

    LaunchedEffect(state.isSaved) {
        if (state.isSaved) {
            onProductSaved()
        }
    }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
        onResult = { uri: Uri? ->
            if (uri != null) {
                viewModel.updateImageUri(uri.toString())
            }
        }
    )

    Scaffold(
        topBar = {
            AppTopBar(
                title = if (productId > 0) "Edit Product" else "New Product",
                subtitle = if (productId > 0) state.name else "Master Inventory Entry",
                onBackClick = onNavigateBack,
                actions = {
                    IconButton(
                        onClick = { viewModel.saveProduct() },
                        enabled = !state.isLoading,
                        modifier = Modifier.testTag("save_product_header_button")
                    ) {
                        Icon(Icons.Default.Save, contentDescription = "Save Product")
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
            // Error Banner
            if (state.errorMessage != null) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = state.errorMessage!!,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }

            // 1. Basic Information
            FormSection(title = "1. BASIC INFORMATION") {
                OutlinedTextField(
                    value = state.name,
                    onValueChange = { viewModel.updateName(it) },
                    label = { Text("Product Name *") },
                    placeholder = { Text("e.g. Samsung Galaxy A15 (8/256GB)") },
                    leadingIcon = { Icon(Icons.Default.ShoppingBag, contentDescription = null) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("product_name_input")
                )

                Spacer(modifier = Modifier.height(10.dp))

                // Category Dropdown
                var catExpanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(
                    expanded = catExpanded,
                    onExpandedChange = { catExpanded = it }
                ) {
                    OutlinedTextField(
                        value = state.categoryName.ifBlank { "Select Category *" },
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Category *") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = catExpanded) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor()
                    )
                    ExposedDropdownMenu(
                        expanded = catExpanded,
                        onDismissRequest = { catExpanded = false }
                    ) {
                        state.categories.forEach { cat ->
                            DropdownMenuItem(
                                text = { Text(cat.name) },
                                onClick = {
                                    viewModel.updateCategory(cat)
                                    catExpanded = false
                                }
                            )
                        }
                    }
                }

                // Subcategory Dropdown (Cascading)
                if (state.subcategories.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(10.dp))
                    var subExpanded by remember { mutableStateOf(false) }
                    ExposedDropdownMenuBox(
                        expanded = subExpanded,
                        onExpandedChange = { subExpanded = it }
                    ) {
                        OutlinedTextField(
                            value = state.subcategoryName.ifBlank { "Select Subcategory (Optional)" },
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Subcategory") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = subExpanded) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor()
                        )
                        ExposedDropdownMenu(
                            expanded = subExpanded,
                            onDismissRequest = { subExpanded = false }
                        ) {
                            state.subcategories.forEach { sub ->
                                DropdownMenuItem(
                                    text = { Text(sub.name) },
                                    onClick = {
                                        viewModel.updateSubcategory(sub)
                                        subExpanded = false
                                    }
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Brand Dropdown
                var brandExpanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(
                    expanded = brandExpanded,
                    onExpandedChange = { brandExpanded = it }
                ) {
                    OutlinedTextField(
                        value = state.brandName.ifBlank { "Select Brand" },
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Brand") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = brandExpanded) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor()
                    )
                    ExposedDropdownMenu(
                        expanded = brandExpanded,
                        onDismissRequest = { brandExpanded = false }
                    ) {
                        state.brands.forEach { brand ->
                            DropdownMenuItem(
                                text = { Text(brand.name) },
                                onClick = {
                                    viewModel.updateBrand(brand)
                                    brandExpanded = false
                                }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                OutlinedTextField(
                    value = state.model,
                    onValueChange = { viewModel.updateModel(it) },
                    label = { Text("Model / Series") },
                    placeholder = { Text("e.g. SM-A155F, Archer C6") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(10.dp))

                OutlinedTextField(
                    value = state.description,
                    onValueChange = { viewModel.updateDescription(it) },
                    label = { Text("Description / Notes") },
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // 2. Identification & Barcodes
            FormSection(title = "2. IDENTIFICATION & BARCODE") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = state.sku,
                        onValueChange = { viewModel.updateSku(it) },
                        label = { Text("SKU (Stock Keeping Unit)") },
                        singleLine = true,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("product_sku_input")
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    OutlinedButton(
                        onClick = { viewModel.generateAutoSku() },
                        modifier = Modifier.height(56.dp)
                    ) {
                        Text("Auto SKU")
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = state.barcode,
                        onValueChange = { viewModel.updateBarcode(it) },
                        label = { Text("Barcode / EAN-13") },
                        singleLine = true,
                        trailingIcon = {
                            IconButton(onClick = onScanBarcodeRequested, modifier = Modifier.testTag("product_scan_barcode_button")) {
                                Icon(Icons.Default.QrCodeScanner, contentDescription = "Scan barcode")
                            }
                        },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("product_barcode_input")
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    OutlinedButton(
                        onClick = { viewModel.generateAutoBarcode() },
                        modifier = Modifier.height(56.dp)
                    ) {
                        Text("Auto EAN")
                    }
                }
            }

            // 3. Tracking & Serialization
            FormSection(title = "3. TRACKING TYPE") {
                Text(
                    text = "Select how each physical item of this product is tracked in inventory:",
                    style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                )
                Spacer(modifier = Modifier.height(8.dp))

                listOf(
                    Pair("QUANTITY", "Quantity Only (Cables, Chargers, Keyboards, Earphones)"),
                    Pair("IMEI", "IMEI Number Tracked (Smartphones & Feature phones)"),
                    Pair("SERIAL", "Serial Number Tracked (Routers, SSDs, Modems, Laptops)"),
                    Pair("SERIAL_AND_IMEI", "Both Serial & IMEI (Cellular Modems / Specialized Devices)")
                ).forEach { (type, description) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { viewModel.updateTrackingType(type) }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = state.trackingType == type,
                            onClick = { viewModel.updateTrackingType(type) }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = description,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontWeight = if (state.trackingType == type) FontWeight.Bold else FontWeight.Normal
                            )
                        )
                    }
                }
            }

            // 4. Pricing
            FormSection(title = "4. PRICING CONFIGURATION") {
                Row(modifier = Modifier.fillMaxWidth()) {
                    MoneyField(
                        value = state.purchasePrice,
                        onValue = viewModel::updatePurchasePrice,
                        label = "Purchase Cost *",
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 6.dp)
                            .testTag("purchase_price_input")
                    )
                    MoneyField(
                        value = state.retailPrice,
                        onValue = viewModel::updateRetailPrice,
                        label = "Retail Price *",
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 6.dp)
                            .testTag("retail_price_input")
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                Row(modifier = Modifier.fillMaxWidth()) {
                    MoneyField(
                        value = state.wholesalePrice,
                        onValue = viewModel::updateWholesalePrice,
                        label = "Wholesale Price",
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 6.dp)
                    )
                    MoneyField(
                        value = state.minSellingPrice,
                        onValue = viewModel::updateMinSellingPrice,
                        label = "Min Selling Price",
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 6.dp)
                    )
                }
            }

            // 5. Stock Levels
            FormSection(title = "5. STOCK LEVELS") {
                Row(modifier = Modifier.fillMaxWidth()) {
                    val serializedProduct = state.trackingType != "QUANTITY"
                    OutlinedTextField(
                        value = state.currentStock.toString(),
                        onValueChange = { viewModel.updateCurrentStock(it.toIntOrNull() ?: 0) },
                        label = { Text(if (productId > 0) "Current Stock" else "Opening Stock") },
                        // Stock changes later only through purchases, sales and returns; serialized units only via purchases
                        readOnly = productId > 0 || serializedProduct,
                        enabled = productId == 0L && !serializedProduct,
                        supportingText = {
                            if (productId > 0) Text("Changes via purchases & sales")
                            else if (serializedProduct) Text("Add units with a purchase")
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 6.dp)
                            .testTag("initial_stock_input")
                    )
                    OutlinedTextField(
                        value = state.minStock.toString(),
                        onValueChange = { viewModel.updateMinStock(it.toIntOrNull() ?: 5) },
                        label = { Text("Min Stock Alert") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 6.dp)
                    )
                }
            }

            // 6. Warranty
            FormSection(title = "6. WARRANTY SETTINGS") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Enable Warranty for this Product", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold))
                    Switch(
                        checked = state.hasWarranty,
                        onCheckedChange = { viewModel.updateHasWarranty(it) }
                    )
                }

                if (state.hasWarranty) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(modifier = Modifier.fillMaxWidth()) {
                        OutlinedTextField(
                            value = state.warrantyPeriod.toString(),
                            onValueChange = { viewModel.updateWarrantyPeriod(it.toIntOrNull() ?: 0) },
                            label = { Text("Period") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier
                                .weight(1f)
                                .padding(end = 6.dp)
                        )
                        var unitExpanded by remember { mutableStateOf(false) }
                        ExposedDropdownMenuBox(
                            expanded = unitExpanded,
                            onExpandedChange = { unitExpanded = it },
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = 6.dp)
                        ) {
                            OutlinedTextField(
                                value = state.warrantyUnit,
                                onValueChange = {},
                                readOnly = true,
                                label = { Text("Unit") },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = unitExpanded) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .menuAnchor()
                            )
                            ExposedDropdownMenu(
                                expanded = unitExpanded,
                                onDismissRequest = { unitExpanded = false }
                            ) {
                                listOf("DAYS", "MONTHS", "YEARS").forEach { u ->
                                    DropdownMenuItem(
                                        text = { Text(u) },
                                        onClick = {
                                            viewModel.updateWarrantyUnit(u)
                                            unitExpanded = false
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 7. Product Image
            FormSection(title = "7. PRODUCT IMAGE") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (state.imageUri.isNotBlank()) Icons.Default.CheckCircle else Icons.Default.Image,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Column {
                        Button(
                            onClick = {
                                photoPickerLauncher.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                )
                            }
                        ) {
                            Text(if (state.imageUri.isNotBlank()) "Change Image" else "Select Image")
                        }
                        if (state.imageUri.isNotBlank()) {
                            Text("Image attached", style = MaterialTheme.typography.bodySmall.copy(color = StockGreen))
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Submit Button
            Button(
                onClick = { viewModel.saveProduct() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
                    .testTag("submit_product_button"),
                enabled = !state.isLoading
            ) {
                if (state.isLoading) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(24.dp))
                } else {
                    Icon(Icons.Default.Save, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        if (productId > 0) "Update Product" else "Save Product to Inventory",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                }
            }
        }
    }
}

@Composable
private fun FormSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium.copy(
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                letterSpacing = 1.sp
            ),
            modifier = Modifier.padding(start = 6.dp, bottom = 6.dp)
        )
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                content = content
            )
        }
    }
}

/** Price input that keeps what is typed ("12." or ".5") instead of reformatting it on every key. */
@Composable
private fun MoneyField(
    value: Double,
    onValue: (Double) -> Unit,
    label: String,
    modifier: Modifier = Modifier
) {
    var text by remember { mutableStateOf(if (value == 0.0) "" else com.example.core.utils.CurrencyUtils.toInput(value)) }
    // A value set from outside (product loaded) replaces the text unless it already means that number
    LaunchedEffect(value) {
        if ((text.toDoubleOrNull() ?: 0.0) != value) {
            text = if (value == 0.0) "" else com.example.core.utils.CurrencyUtils.toInput(value)
        }
    }
    OutlinedTextField(
        value = text,
        onValueChange = { typed ->
            if (typed.isEmpty() || typed.matches(Regex("\\d*\\.?\\d{0,2}"))) {
                text = typed
                onValue(typed.toDoubleOrNull() ?: 0.0)
            }
        },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier
    )
}
