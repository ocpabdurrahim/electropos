package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.core.utils.CurrencyUtils
import com.example.core.utils.DateTimeUtils
import com.example.ui.components.AppTopBar
import com.example.ui.components.SectionHeader
import com.example.ui.theme.*
import com.example.ui.viewmodels.ProductDetailsViewModel
import com.example.data.local.entities.ProductEntity
import com.example.data.local.entities.ProductVariantEntity
import com.example.data.local.entities.StockMovementType
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProductDetailsScreen(
    viewModel: ProductDetailsViewModel,
    onNavigateBack: () -> Unit,
    onEditProductClick: (Long) -> Unit,
    /** Owner/manager: edit, variants, stock adjustment. */
    canEdit: Boolean = true,
    /** Purchase cost and stock value are hidden from salespeople. */
    canSeeCost: Boolean = true
) {
    val uiState by viewModel.uiState.collectAsState()
    val product = uiState.product

    var showDeactivateConfirm by remember { mutableStateOf(false) }
    var variantDialog by remember { mutableStateOf<ProductVariantEntity?>(null) }
    var showAdjustStock by remember { mutableStateOf(false) }
    var actionMessage by remember { mutableStateOf<String?>(null) }

    variantDialog?.let { editing ->
        if (product != null) {
            VariantEditDialog(
                initial = editing,
                productDefaults = product,
                onDismiss = { variantDialog = null },
                onSave = { v, setError ->
                    viewModel.saveVariant(v) { err ->
                        if (err == null) {
                            variantDialog = null
                            actionMessage = "Variant '${v.variantName.trim()}' saved."
                        } else setError(err)
                    }
                },
                onDeactivate = if (editing.id > 0) { setError ->
                    viewModel.deactivateVariant(editing.id) { err ->
                        if (err == null) {
                            variantDialog = null
                            actionMessage = "Variant '${editing.variantName}' removed."
                        } else setError(err)
                    }
                } else null
            )
        }
    }

    if (showAdjustStock && product != null) {
        StockAdjustDialog(
            product = product,
            variants = uiState.variants,
            onDismiss = { showAdjustStock = false },
            onApply = { delta, reason, notes, variantId, setError ->
                viewModel.adjustStock(delta, reason, notes, variantId) { err ->
                    if (err == null) {
                        showAdjustStock = false
                        actionMessage = "Stock adjusted by ${if (delta > 0) "+" else ""}$delta."
                    } else setError(err)
                }
            }
        )
    }

    if (showDeactivateConfirm && product != null) {
        AlertDialog(
            onDismissRequest = { showDeactivateConfirm = false },
            title = { Text("Deactivate Product?") },
            text = { Text("Are you sure you want to deactivate '${product.name}'? It will no longer appear in POS or active sales.") },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.deactivateProduct()
                        showDeactivateConfirm = false
                        onNavigateBack()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Deactivate")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showDeactivateConfirm = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            AppTopBar(
                title = product?.name ?: "Product Details",
                subtitle = "SKU: ${product?.sku ?: "N/A"}",
                onBackClick = onNavigateBack,
                actions = {
                    if (product != null && canEdit) {
                        IconButton(
                            onClick = { onEditProductClick(product.id) },
                            modifier = Modifier.testTag("edit_product_header_button")
                        ) {
                            Icon(Icons.Default.Edit, contentDescription = "Edit Product")
                        }
                    }
                }
            )
        }
    ) { padding ->
        if (uiState.isLoading || product == null) {
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
                // Hero Header Card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(64.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(MaterialTheme.colorScheme.primaryContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = when (product.trackingType) {
                                    "IMEI" -> Icons.Default.PhoneAndroid
                                    "SERIAL" -> Icons.Default.Router
                                    else -> Icons.Default.DevicesOther
                                },
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(36.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(16.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = product.name,
                                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                            )
                            Text(
                                text = "${product.brandName.ifBlank { "Generic" }} • ${product.model.ifBlank { "Standard" }}",
                                style = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Category: ${product.categoryName}${if (product.subcategoryName.isNotBlank()) " > ${product.subcategoryName}" else ""}",
                                style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                            )
                        }
                    }
                }

                actionMessage?.let { msg ->
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                        Text(msg, modifier = Modifier.padding(12.dp))
                    }
                }

                // Pricing Card
                DetailSection(title = "PRICING") {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        PriceItem(label = "Retail Price", amount = product.retailPrice, color = MaterialTheme.colorScheme.primary, isBold = true)
                        if (canSeeCost) {
                            PriceItem(label = "Purchase Cost", amount = product.purchasePrice, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        PriceItem(label = "Wholesale Price", amount = product.wholesalePrice, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        PriceItem(label = "Min Selling Price", amount = product.minSellingPrice, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                // Variants (e.g. 8/128 Black, 8/256 Blue): each has its own price and stock
                if (uiState.variants.isNotEmpty() || canEdit) {
                    DetailSection(title = "VARIANTS (${uiState.variants.size})") {
                        if (uiState.variants.isEmpty()) {
                            Text(
                                "No variants. Add them when the same model comes in different storage, colour or size with its own price.",
                                style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                            )
                        }
                        uiState.variants.forEach { v ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = canEdit) { variantDialog = v }
                                    .padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(v.variantName, fontWeight = FontWeight.SemiBold)
                                    Text(
                                        listOf(v.sku, v.barcode).filter { it.isNotBlank() }.joinToString(" • ").ifBlank { "No SKU / barcode" },
                                        style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    )
                                }
                                Column(horizontalAlignment = Alignment.End) {
                                    Text(CurrencyUtils.format(v.retailPrice.takeIf { it > 0 } ?: product.retailPrice), fontWeight = FontWeight.Bold)
                                    Text("Stock: ${v.currentStock}", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
                        }
                        val unassigned = product.currentStock - uiState.variants.sumOf { it.currentStock }
                        if (uiState.variants.isNotEmpty() && unassigned > 0) {
                            Text(
                                "$unassigned in stock without a variant (bought before the variants were added).",
                                style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                                modifier = Modifier.padding(top = 6.dp)
                            )
                        }
                        if (canEdit) {
                            TextButton(
                                onClick = { variantDialog = ProductVariantEntity(productId = product.id, variantName = "") },
                                modifier = Modifier.testTag("add_variant_button")
                            ) {
                                Icon(Icons.Default.Add, contentDescription = null)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Add variant")
                            }
                        }
                    }
                }

                // Inventory & Stock
                DetailSection(title = "STOCK & INVENTORY STATUS") {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        InfoRow(label = "Current Stock", value = "${product.currentStock} ${product.unit}")
                        InfoRow(label = "Min Stock Alert", value = "${product.minStock} ${product.unit}")
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        if (canSeeCost) {
                            InfoRow(label = "Estimated Stock Value", value = CurrencyUtils.format(product.currentStock * product.purchasePrice))
                        }
                        InfoRow(label = "Max Stock Limit", value = "${product.maxStock} ${product.unit}")
                    }
                    if (canEdit && product.trackingType == "QUANTITY") {
                        TextButton(onClick = { showAdjustStock = true }, modifier = Modifier.testTag("adjust_stock_button")) {
                            Icon(Icons.Default.Tune, contentDescription = null)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Adjust stock (count, damaged, lost)")
                        }
                    }
                }

                // Identification & Tracking
                DetailSection(title = "TRACKING & IDENTIFICATION") {
                    InfoRow(label = "SKU", value = product.sku.ifBlank { "None" })
                    Spacer(modifier = Modifier.height(6.dp))
                    InfoRow(label = "Barcode", value = product.barcode.ifBlank { "None" })
                    Spacer(modifier = Modifier.height(6.dp))
                    InfoRow(label = "Tracking Mode", value = product.trackingType)
                    Spacer(modifier = Modifier.height(6.dp))
                    InfoRow(
                        label = "Warranty",
                        value = when {
                            product.warrantyPeriod > 0 -> "${product.warrantyPeriod} ${product.warrantyUnit.lowercase()} (${product.warrantyType})"
                            product.warrantyPeriodMonths > 0 -> "${product.warrantyPeriodMonths} months (${product.warrantyType})"
                            else -> "No Warranty"
                        }
                    )
                }

                // Price History List
                if (canSeeCost && uiState.priceHistory.isNotEmpty()) {
                    DetailSection(title = "PRICE CHANGE HISTORY (${uiState.priceHistory.size})") {
                        uiState.priceHistory.forEach { entry ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text(
                                        text = "Retail: ${CurrencyUtils.format(entry.retailPrice)} • Cost: ${CurrencyUtils.format(entry.purchasePrice)}",
                                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
                                    )
                                    Text(
                                        text = "Changed by ${entry.changedBy} on ${DateTimeUtils.formatDateTime(entry.changedAt)}",
                                        style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    )
                                }
                            }
                            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
                        }
                    }
                }

                // Actions
                if (canEdit) Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = { onEditProductClick(product.id) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Edit, contentDescription = null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Edit")
                    }

                    OutlinedButton(
                        onClick = { showDeactivateConfirm = true },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = AlertRose)
                    ) {
                        Icon(Icons.Default.Block, contentDescription = null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Deactivate")
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailSection(title: String, content: @Composable ColumnScope.() -> Unit) {
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
            Column(modifier = Modifier.padding(16.dp), content = content)
        }
    }
}

@Composable
private fun PriceItem(label: String, amount: Double, color: Color, isBold: Boolean = false) {
    Column {
        Text(text = label, style = MaterialTheme.typography.labelMedium.copy(color = MaterialTheme.colorScheme.onSurfaceVariant))
        Text(
            text = CurrencyUtils.format(amount),
            style = MaterialTheme.typography.titleMedium.copy(
                color = color,
                fontWeight = if (isBold) FontWeight.ExtraBold else FontWeight.SemiBold
            )
        )
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurfaceVariant))
        Text(text = value, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold))
    }
}

@Composable
private fun VariantEditDialog(
    initial: ProductVariantEntity,
    productDefaults: ProductEntity,
    onDismiss: () -> Unit,
    onSave: (ProductVariantEntity, (String) -> Unit) -> Unit,
    onDeactivate: (((String) -> Unit) -> Unit)?
) {
    fun money(v: Double) = if (v > 0) CurrencyUtils.toInput(v) else ""
    var name by remember { mutableStateOf(initial.variantName) }
    var storage by remember { mutableStateOf(initial.storage) }
    var color by remember { mutableStateOf(initial.color) }
    var sku by remember { mutableStateOf(initial.sku) }
    var barcode by remember { mutableStateOf(initial.barcode) }
    var retail by remember { mutableStateOf(money(if (initial.id == 0L) productDefaults.retailPrice else initial.retailPrice)) }
    var minPrice by remember { mutableStateOf(money(if (initial.id == 0L) productDefaults.minSellingPrice else initial.minSellingPrice)) }
    var wholesale by remember { mutableStateOf(money(if (initial.id == 0L) productDefaults.wholesalePrice else initial.wholesalePrice)) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial.id == 0L) "Add variant" else "Edit variant", fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                OutlinedTextField(name, { name = it; error = null }, label = { Text("Variant name *") },
                    placeholder = { Text("e.g. 8/256GB Blue") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(storage, { storage = it }, label = { Text("RAM/Storage") }, singleLine = true, modifier = Modifier.weight(1f))
                    OutlinedTextField(color, { color = it }, label = { Text("Colour") }, singleLine = true, modifier = Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(sku, { sku = it; error = null }, label = { Text("SKU") }, singleLine = true, modifier = Modifier.weight(1f))
                    OutlinedTextField(barcode, { barcode = it; error = null }, label = { Text("Barcode") }, singleLine = true, modifier = Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(retail, { retail = it; error = null }, label = { Text("Retail ৳ *") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f))
                    OutlinedTextField(minPrice, { minPrice = it }, label = { Text("Min ৳") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f))
                }
                OutlinedTextField(wholesale, { wholesale = it }, label = { Text("Wholesale ৳") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
                if (initial.id > 0) {
                    Text("Stock: ${initial.currentStock} (changes through purchases and sales)", style = MaterialTheme.typography.bodySmall)
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                if (onDeactivate != null) {
                    TextButton(onClick = { onDeactivate { error = it } }) {
                        Text("Remove this variant", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                val retailValue = retail.toDoubleOrNull()
                if (retailValue == null || retailValue <= 0) {
                    error = "Enter the retail price."
                } else {
                    onSave(
                        initial.copy(
                            variantName = name,
                            storage = storage.trim(),
                            color = color.trim(),
                            sku = sku,
                            barcode = barcode,
                            retailPrice = retailValue,
                            minSellingPrice = minPrice.toDoubleOrNull() ?: 0.0,
                            wholesalePrice = wholesale.toDoubleOrNull() ?: 0.0,
                            purchasePrice = if (initial.id == 0L) productDefaults.purchasePrice else initial.purchasePrice
                        )
                    ) { error = it }
                }
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StockAdjustDialog(
    product: ProductEntity,
    variants: List<ProductVariantEntity>,
    onDismiss: () -> Unit,
    onApply: (delta: Int, reason: String, notes: String, variantId: Long, setError: (String) -> Unit) -> Unit
) {
    val reasons = listOf(
        StockMovementType.ADJUSTMENT_MANUAL to "Count correction",
        StockMovementType.ADJUSTMENT_FOUND to "Found",
        StockMovementType.ADJUSTMENT_DAMAGE to "Damaged",
        StockMovementType.ADJUSTMENT_LOST to "Lost / stolen",
        StockMovementType.INTERNAL_USE to "Shop use"
    )
    var reason by remember { mutableStateOf(StockMovementType.ADJUSTMENT_MANUAL) }
    var removing by remember { mutableStateOf(true) }
    var qtyText by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var variantId by remember { mutableStateOf(0L) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Adjust stock", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${product.name} • now ${product.currentStock} ${product.unit}", style = MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    reasons.forEach { (code, label) ->
                        FilterChip(
                            selected = reason == code,
                            onClick = {
                                reason = code
                                if (code == StockMovementType.ADJUSTMENT_FOUND) removing = false
                                if (code in setOf(StockMovementType.ADJUSTMENT_DAMAGE, StockMovementType.ADJUSTMENT_LOST, StockMovementType.INTERNAL_USE)) removing = true
                            },
                            label = { Text(label) }
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = removing, onClick = { removing = true }, label = { Text("Remove") })
                    FilterChip(selected = !removing, onClick = { removing = false }, label = { Text("Add") })
                }
                OutlinedTextField(
                    value = qtyText,
                    onValueChange = { if (it.all(Char::isDigit)) qtyText = it; error = null },
                    label = { Text("Quantity") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
                if (variants.isNotEmpty()) {
                    Text("Variant", style = MaterialTheme.typography.labelMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(selected = variantId == 0L, onClick = { variantId = 0L }, label = { Text("No variant") })
                        variants.forEach { v ->
                            FilterChip(selected = variantId == v.id, onClick = { variantId = v.id }, label = { Text("${v.variantName} (${v.currentStock})") })
                        }
                    }
                }
                OutlinedTextField(notes, { notes = it }, label = { Text("Note") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            Button(onClick = {
                val qty = qtyText.toIntOrNull() ?: 0
                if (qty <= 0) {
                    error = "Enter a quantity."
                } else {
                    onApply(if (removing) -qty else qty, reason, notes, variantId) { error = it }
                }
            }) { Text("Apply") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
