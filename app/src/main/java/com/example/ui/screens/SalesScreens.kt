package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
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
import com.example.core.utils.*
import com.example.data.local.entities.SaleEntity
import com.example.data.local.entities.SaleItemEntity
import com.example.ui.components.EmptyStateCard
import com.example.core.invoice.InvoiceExporter
import com.example.ui.components.InvoiceActionsCard
import com.example.ui.theme.AlertRed
import com.example.ui.theme.AppGreen
import com.example.ui.theme.AppOrange
import com.example.ui.theme.CardElevation
import com.example.ui.viewmodels.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SalesHistoryScreen(
    viewModel: SalesHistoryViewModel,
    onNavigateBack: () -> Unit,
    onSaleClick: (Long) -> Unit,
    onNewSaleClick: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sales Invoices", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    FilledTonalButton(
                        onClick = onNewSaleClick,
                        modifier = Modifier
                            .padding(end = 8.dp)
                            .testTag("new_sale_btn")
                    ) {
                        Icon(Icons.Default.AddShoppingCart, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("New Sale")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Search Bar
            OutlinedTextField(
                value = uiState.searchQuery,
                onValueChange = viewModel::onSearchQueryChanged,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .testTag("sales_search_input"),
                placeholder = { Text("Search by invoice #, customer name or phone...") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true,
                shape = RoundedCornerShape(12.dp)
            )

            // Status Filter Chips
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val statuses = listOf("ALL", "COMPLETED", "CANCELLED")
                items(statuses) { status ->
                    FilterChip(
                        selected = uiState.statusFilter == status,
                        onClick = { viewModel.onStatusFilterChanged(status) },
                        label = { Text(status) }
                    )
                }
            }

            // Payment Filter Chips
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val paymentStates = listOf("ALL" to "Any payment", "PAID" to "Paid", "PARTIAL" to "Partial", "DUE" to "Due")
                items(paymentStates) { (value, label) ->
                    FilterChip(
                        selected = uiState.paymentFilter == value,
                        onClick = { viewModel.onPaymentFilterChanged(value) },
                        label = { Text(label) }
                    )
                }
            }

            // Summary Totals
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text("Active Sales Total", style = MaterialTheme.typography.labelSmall)
                        Text(
                            CurrencyUtils.formatBDT(uiState.totalSalesAmount),
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("Outstanding Due", style = MaterialTheme.typography.labelSmall)
                        Text(
                            CurrencyUtils.formatBDT(uiState.totalDueAmount),
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, color = AlertRed)
                        )
                    }
                }
            }

            if (uiState.isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (uiState.sales.isEmpty()) {
                EmptyStateCard(
                    icon = Icons.Default.Receipt,
                    title = "No Invoices Found",
                    description = if (uiState.searchQuery.isBlank()) "Start your first POS checkout to view sales invoices." else "No invoices match '${uiState.searchQuery}'.",
                    actionText = "Open POS",
                    onActionClick = onNewSaleClick
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(uiState.sales, key = { it.id }) { sale ->
                        SaleInvoiceCard(sale = sale, onClick = { onSaleClick(sale.id) })
                    }
                }
            }
        }
    }
}

@Composable
fun SaleInvoiceCard(sale: SaleEntity, onClick: () -> Unit) {
    val isCancelled = sale.status == "CANCELLED"

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .testTag("sale_card_${sale.id}"),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(sale.invoiceNumber, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = "${sale.customerName}" + if (sale.customerPhone.isNotBlank()) " • ${sale.customerPhone}" else "",
                        style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                    )
                }

                Surface(
                    color = when {
                        isCancelled -> AlertRed.copy(alpha = 0.15f)
                        sale.dueAmount <= 0.001 -> AppGreen.copy(alpha = 0.15f)
                        sale.paidAmount <= 0.001 -> AlertRed.copy(alpha = 0.15f)
                        else -> AppOrange.copy(alpha = 0.15f)
                    },
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(
                        text = if (isCancelled) "CANCELLED" else sale.paymentStatus,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,
                            color = when {
                                isCancelled -> AlertRed
                                sale.dueAmount <= 0.001 -> AppGreen
                                sale.paidAmount <= 0.001 -> AlertRed
                                else -> AppOrange
                            }
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = DateTimeUtils.formatDate(sale.saleDate, "dd MMM yyyy, hh:mm a"),
                    style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                )

                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = CurrencyUtils.formatBDT(sale.totalAmount),
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium
                    )
                    if (sale.dueAmount > 0 && !isCancelled) {
                        Text(
                            text = "Due: ${CurrencyUtils.formatBDT(sale.dueAmount)}",
                            style = MaterialTheme.typography.labelSmall.copy(color = AlertRed)
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SaleDetailsScreen(
    viewModel: SaleDetailsViewModel,
    onNavigateBack: () -> Unit,
    onReturnClick: (Long) -> Unit,
    invoiceExporter: InvoiceExporter? = null
) {
    val uiState by viewModel.uiState.collectAsState()
    var showCancelDialog by remember { mutableStateOf(false) }
    var cancelReason by remember { mutableStateOf("") }

    val sale = uiState.sale

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(sale?.invoiceNumber ?: "Invoice Details", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (sale != null && sale.status != "CANCELLED") {
                        FilledTonalButton(
                            onClick = { onReturnClick(sale.id) },
                            modifier = Modifier.padding(end = 6.dp)
                        ) {
                            Icon(Icons.Default.KeyboardReturn, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Return")
                        }
                        IconButton(onClick = { showCancelDialog = true }) {
                            Icon(Icons.Default.Cancel, contentDescription = "Cancel Sale", tint = AlertRed)
                        }
                    }
                }
            )
        }
    ) { padding ->
        if (uiState.isLoading || sale == null) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                if (uiState.errorMessage != null) {
                    item {
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                            Text(
                                text = uiState.errorMessage ?: "",
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.padding(12.dp)
                            )
                        }
                    }
                }

                // Invoice Header Card
                item {
                    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Invoice #: ${sale.invoiceNumber}", fontWeight = FontWeight.Bold)
                                Surface(
                                    color = if (sale.status == "CANCELLED") AlertRed.copy(alpha = 0.15f) else AppGreen.copy(alpha = 0.15f),
                                    shape = RoundedCornerShape(4.dp)
                                ) {
                                    Text(
                                        text = sale.status,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            color = if (sale.status == "CANCELLED") AlertRed else AppGreen,
                                            fontWeight = FontWeight.Bold
                                        )
                                    )
                                }
                            }
                            Text("Customer: ${sale.customerName}" + if (sale.customerPhone.isNotBlank()) " (${sale.customerPhone})" else "")
                            Text("Date: ${DateTimeUtils.formatDate(sale.saleDate, "dd MMMM yyyy, hh:mm a")}", style = MaterialTheme.typography.bodySmall)
                            Text("Payment Method: ${sale.paymentMethod}", style = MaterialTheme.typography.bodySmall)
                            if (sale.notes.isNotBlank()) Text("Notes: ${sale.notes}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }

                // Invoice: share PDF / print / share as text
                if (invoiceExporter != null) {
                    item {
                        InvoiceActionsCard(exporter = invoiceExporter, saleId = sale.id)
                    }
                }

                // Items Purchased Header
                item {
                    Text("Purchased Items (${uiState.items.size})", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                }

                items(uiState.items, key = { it.id }) { item ->
                    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp)) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(item.productName, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                                Text(CurrencyUtils.formatBDT(item.totalPrice), fontWeight = FontWeight.Bold)
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("${item.quantity} × ${CurrencyUtils.formatBDT(item.unitPrice)}", style = MaterialTheme.typography.bodySmall)
                                if (item.discount > 0) {
                                    Text("Discount: -${CurrencyUtils.formatBDT(item.discount)}", color = AppGreen, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                            if (item.serialImeisJson.isNotBlank() && item.serialImeisJson != "[]") {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text("Serials/IMEIs: ${item.serialImeisJson.replace("\"", "").replace("[", "").replace("]", "")}", style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.primary))
                            }
                        }
                    }
                }

                // Financial Summary
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Subtotal:")
                                Text(CurrencyUtils.formatBDT(sale.subTotal))
                            }
                            if (sale.discountAmount > 0) {
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("Total Discount:")
                                    Text("-${CurrencyUtils.formatBDT(sale.discountAmount)}", color = AppGreen)
                                }
                            }
                            if (sale.taxAmount > 0) {
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("Tax:")
                                    Text(CurrencyUtils.formatBDT(sale.taxAmount))
                                }
                            }
                            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Grand Total:", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                                Text(CurrencyUtils.formatBDT(sale.totalAmount), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                            }
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Paid Amount:", color = AppGreen)
                                Text(CurrencyUtils.formatBDT(sale.paidAmount), color = AppGreen, fontWeight = FontWeight.Bold)
                            }
                            if (sale.dueAmount > 0) {
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("Due Amount:", color = AlertRed, fontWeight = FontWeight.Bold)
                                    Text(CurrencyUtils.formatBDT(sale.dueAmount), color = AlertRed, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }

                // Existing Returns if any
                if (uiState.returns.isNotEmpty()) {
                    item {
                        Text("Completed Returns for this Sale", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    }
                    items(uiState.returns) { ret ->
                        Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp)) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column {
                                    Text(ret.returnNumber, fontWeight = FontWeight.Bold)
                                    Text(DateTimeUtils.formatDate(ret.returnDate, "dd MMM yyyy"), style = MaterialTheme.typography.bodySmall)
                                }
                                Text("-${CurrencyUtils.formatBDT(ret.totalAmount)}", color = AlertRed, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }

    if (showCancelDialog) {
        AlertDialog(
            onDismissRequest = { showCancelDialog = false },
            title = { Text("Cancel Sale Invoice") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Are you sure you want to cancel invoice #${sale?.invoiceNumber}? Stock will be reversed and customer debt adjusted.")
                    OutlinedTextField(
                        value = cancelReason,
                        onValueChange = { cancelReason = it },
                        label = { Text("Reason for Cancellation *") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (cancelReason.isNotBlank()) {
                            showCancelDialog = false
                            viewModel.cancelSale(cancelReason)
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AlertRed)
                ) {
                    Text("Confirm Cancellation")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCancelDialog = false }) { Text("Close") }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SalesReturnScreen(
    viewModel: SalesReturnViewModel,
    onNavigateBack: () -> Unit,
    onReturnSuccess: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(uiState.isSuccess) {
        if (uiState.isSuccess) {
            onReturnSuccess()
        }
    }

    val sale = uiState.sale

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sales Return (#${sale?.invoiceNumber ?: ""})", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            if (uiState.errorMessage != null) {
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                        Text(
                            text = uiState.errorMessage ?: "",
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }
            }

            item {
                Text("Select Items to Return", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            }

            items(uiState.itemSelections, key = { it.saleItem.id }) { selection ->
                val item = selection.saleItem
                val isSerialized = selection.isSerialized

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (selection.isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                        else MaterialTheme.colorScheme.surface
                    )
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = selection.isSelected,
                                onCheckedChange = { viewModel.toggleItemSelection(item.id) },
                                enabled = selection.remainingReturnable > 0
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(item.productName, fontWeight = FontWeight.Bold)
                                Text(
                                    "Sold: ${item.quantity} | Available to return: ${selection.remainingReturnable} @ ${CurrencyUtils.formatBDT(selection.unitRefundValue)} paid",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }

                        if (selection.isSelected) {
                            Spacer(modifier = Modifier.height(8.dp))
                            if (!isSerialized) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("Quantity to Return:")
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        IconButton(onClick = { viewModel.updateReturnQuantity(item.id, selection.returnQuantity - 1) }) {
                                            Icon(Icons.Default.Remove, contentDescription = "Decrease")
                                        }
                                        Text("${selection.returnQuantity}", fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 8.dp))
                                        IconButton(onClick = { viewModel.updateReturnQuantity(item.id, selection.returnQuantity + 1) }) {
                                            Icon(Icons.Default.Add, contentDescription = "Increase")
                                        }
                                    }
                                }
                            } else {
                                Text("Tick each Serial/IMEI unit being returned:", style = MaterialTheme.typography.labelSmall)
                                if (selection.availableSerials.isEmpty()) {
                                    Text("All units of this line have already been returned.", style = MaterialTheme.typography.bodySmall)
                                }
                                selection.availableSerials.forEach { serial ->
                                    val label = serial.displayLabel()
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { viewModel.toggleSerialUnit(item.id, serial.id) }
                                            .padding(vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Checkbox(
                                            checked = selection.selectedSerialIds.contains(serial.id),
                                            onCheckedChange = { viewModel.toggleSerialUnit(item.id, serial.id) }
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(label)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Restock Option
            item {
                Text("Restock Condition", fontWeight = FontWeight.Bold)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = uiState.restockOption == "RESTOCK_SELLABLE",
                        onClick = { viewModel.onRestockOptionChanged("RESTOCK_SELLABLE") },
                        label = { Text("Restock as Sellable") }
                    )
                    FilterChip(
                        selected = uiState.restockOption == "DEFECTIVE_DAMAGED",
                        onClick = { viewModel.onRestockOptionChanged("DEFECTIVE_DAMAGED") },
                        label = { Text("Defective / Damaged") }
                    )
                }
            }

            // Financial Breakdown Card
            item {
                Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Total Return Value:", fontWeight = FontWeight.Bold)
                            Text(CurrencyUtils.formatBDT(uiState.totalReturnAmount), fontWeight = FontWeight.Bold, color = AlertRed)
                        }

                        if (sale?.customerId ?: 0L > 0) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Deduct from Customer Debt:")
                                Text(CurrencyUtils.formatBDT(uiState.customerCredit), fontWeight = FontWeight.Bold)
                            }
                        }

                        if (uiState.isWalkIn) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Cash to hand back (walk-in):")
                                Text(CurrencyUtils.formatBDT(uiState.refundAmountDouble), fontWeight = FontWeight.Bold)
                            }
                        } else {
                            OutlinedTextField(
                                value = uiState.refundAmount,
                                onValueChange = viewModel::onRefundAmountChanged,
                                label = { Text("Cash Refund to Customer (৳)") },
                                supportingText = { Text("Leave empty to reduce the customer's due instead") },
                                modifier = Modifier.fillMaxWidth(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                singleLine = true
                            )
                        }
                    }
                }
            }

            item {
                OutlinedTextField(
                    value = uiState.reason,
                    onValueChange = viewModel::onReasonChanged,
                    label = { Text("Reason for Return") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2
                )
            }

            item {
                Button(
                    onClick = viewModel::submitReturn,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .testTag("submit_sales_return_btn"),
                    enabled = !uiState.isSaving && uiState.totalReturnAmount > 0
                ) {
                    if (uiState.isSaving) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), color = MaterialTheme.colorScheme.onPrimary)
                    } else {
                        Text("Process Sales Return", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
