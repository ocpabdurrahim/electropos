package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
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
import com.example.data.local.entities.CustomerLedgerEntryEntity
import com.example.data.local.entities.CustomerPaymentEntity
import com.example.data.local.entities.SaleEntity
import com.example.data.repository.CustomerRepository
import com.example.ui.components.EmptyStateCard
import com.example.ui.theme.AlertRed
import com.example.ui.theme.AppGreen
import com.example.ui.theme.AppOrange
import com.example.ui.theme.CardElevation
import com.example.ui.viewmodels.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomerListScreen(
    viewModel: CustomerListViewModel,
    onNavigateBack: () -> Unit,
    onAddCustomerClick: () -> Unit,
    onCustomerClick: (Long) -> Unit,
    onPayCustomerClick: (Long) -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Customer Directory", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack, modifier = Modifier.testTag("customer_back_btn")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    FilledTonalButton(
                        onClick = onAddCustomerClick,
                        modifier = Modifier
                            .padding(end = 8.dp)
                            .testTag("add_customer_btn")
                    ) {
                        Icon(Icons.Default.PersonAdd, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Add")
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
                    .testTag("customer_search_input"),
                placeholder = { Text("Search by name, phone or code...") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (uiState.searchQuery.isNotBlank()) {
                        IconButton(onClick = { viewModel.onSearchQueryChanged("") }) {
                            Icon(Icons.Default.Clear, contentDescription = "Clear")
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(12.dp)
            )

            // Receivable Due Summary Card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Total Customer Receivable",
                            style = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onPrimaryContainer)
                        )
                        Text(
                            text = "${uiState.customers.size} Customers Recorded",
                            style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f))
                        )
                    }
                    Text(
                        text = CurrencyUtils.formatBDT(uiState.totalCustomerDue),
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    )
                }
            }

            if (uiState.isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (uiState.customers.isEmpty()) {
                EmptyStateCard(
                    icon = Icons.Default.People,
                    title = "No Customers Found",
                    description = if (uiState.searchQuery.isBlank()) "Add your first registered customer to track debts and sales." else "No matching customer for '${uiState.searchQuery}'.",
                    actionText = "Add Customer",
                    onActionClick = onAddCustomerClick
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(uiState.customers, key = { it.id }) { customer ->
                        CustomerCard(
                            customer = customer,
                            onClick = { onCustomerClick(customer.id) },
                            onPayClick = { onPayCustomerClick(customer.id) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun CustomerCard(
    customer: CustomerEntity,
    onClick: () -> Unit,
    onPayClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .testTag("customer_card_${customer.id}"),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Avatar
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.secondaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = customer.name.take(1).uppercase(),
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = customer.name,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            text = customer.customerCode,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }

                Text(
                    text = customer.phone + if (customer.address.isNotBlank()) " • ${customer.address}" else "",
                    style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                )
            }

            Column(horizontalAlignment = Alignment.End) {
                val balanceColor = when {
                    customer.currentDue > 0 -> AlertRed
                    customer.currentDue < 0 -> AppOrange
                    else -> AppGreen
                }
                val balanceLabel = when {
                    customer.currentDue > 0 -> "Due"
                    customer.currentDue < 0 -> "Advance"
                    else -> "Settled"
                }

                Text(
                    text = CurrencyUtils.formatBDT(kotlin.math.abs(customer.currentDue)),
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        color = balanceColor
                    )
                )
                Text(
                    text = balanceLabel,
                    style = MaterialTheme.typography.labelSmall.copy(color = balanceColor)
                )

                if (customer.currentDue > 0) {
                    Spacer(modifier = Modifier.height(4.dp))
                    OutlinedButton(
                        onClick = onPayClick,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                        modifier = Modifier.height(28.dp)
                    ) {
                        Text("Pay", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddEditCustomerScreen(
    viewModel: AddEditCustomerViewModel,
    onNavigateBack: () -> Unit,
    onCustomerSaved: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(uiState.isSuccess) {
        if (uiState.isSuccess) {
            onCustomerSaved()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (uiState.id == 0L) "Add Customer" else "Edit Customer", fontWeight = FontWeight.Bold) },
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
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(vertical = 16.dp)
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
                OutlinedTextField(
                    value = uiState.customerCode,
                    onValueChange = {},
                    label = { Text("Customer Code") },
                    readOnly = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )
            }

            item {
                OutlinedTextField(
                    value = uiState.name,
                    onValueChange = viewModel::onNameChanged,
                    label = { Text("Customer Name *") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("customer_name_input"),
                    shape = RoundedCornerShape(12.dp),
                    singleLine = true
                )
            }

            item {
                OutlinedTextField(
                    value = uiState.phone,
                    onValueChange = viewModel::onPhoneChanged,
                    label = { Text("Phone Number *") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("customer_phone_input"),
                    shape = RoundedCornerShape(12.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    singleLine = true
                )
            }

            item {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = uiState.alternativePhone,
                        onValueChange = viewModel::onAlternativePhoneChanged,
                        label = { Text("Alt Phone") },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = uiState.whatsApp,
                        onValueChange = viewModel::onWhatsAppChanged,
                        label = { Text("WhatsApp") },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        singleLine = true
                    )
                }
            }

            item {
                OutlinedTextField(
                    value = uiState.email,
                    onValueChange = viewModel::onEmailChanged,
                    label = { Text("Email") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    singleLine = true
                )
            }

            item {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = uiState.address,
                        onValueChange = viewModel::onAddressChanged,
                        label = { Text("Address") },
                        modifier = Modifier.weight(1.5f),
                        shape = RoundedCornerShape(12.dp)
                    )
                    OutlinedTextField(
                        value = uiState.city,
                        onValueChange = viewModel::onCityChanged,
                        label = { Text("City") },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    )
                }
            }

            if (uiState.id == 0L) {
                item {
                    OutlinedTextField(
                        value = uiState.openingDue,
                        onValueChange = viewModel::onOpeningDueChanged,
                        label = { Text("Opening Balance (৳)") },
                        supportingText = { Text("Enter positive amount if customer already owes the shop") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("customer_opening_input"),
                        shape = RoundedCornerShape(12.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true
                    )
                }
            }

            item {
                OutlinedTextField(
                    value = uiState.creditLimit,
                    onValueChange = viewModel::onCreditLimitChanged,
                    label = { Text("Credit Limit (৳)") },
                    supportingText = { Text("Maximum allowed due (0 = no limit)") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true
                )
            }

            item {
                OutlinedTextField(
                    value = uiState.notes,
                    onValueChange = viewModel::onNotesChanged,
                    label = { Text("Notes") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    minLines = 2
                )
            }

            item {
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = viewModel::saveCustomer,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .testTag("customer_save_btn"),
                    enabled = !uiState.isSaving
                ) {
                    if (uiState.isSaving) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), color = MaterialTheme.colorScheme.onPrimary)
                    } else {
                        Text(if (uiState.id == 0L) "Save Customer" else "Update Customer", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomerDetailsScreen(
    viewModel: CustomerDetailsViewModel,
    onNavigateBack: () -> Unit,
    onEditCustomerClick: (Long) -> Unit,
    onPayCustomerClick: (Long) -> Unit,
    onViewLedgerClick: (Long) -> Unit,
    onSaleClick: (Long) -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    var showDeactivateDialog by remember { mutableStateOf(false) }

    val customer = uiState.customer

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(customer?.name ?: "Customer Details", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (customer != null) {
                        IconButton(onClick = { onEditCustomerClick(customer.id) }) {
                            Icon(Icons.Default.Edit, contentDescription = "Edit")
                        }
                        IconButton(onClick = { showDeactivateDialog = true }) {
                            Icon(Icons.Default.PersonOff, contentDescription = "Deactivate", tint = AlertRed)
                        }
                    }
                }
            )
        }
    ) { padding ->
        if (uiState.isLoading || customer == null) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Balance Banner Card
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = if (customer.currentDue > 0) AlertRed.copy(alpha = 0.12f)
                            else AppGreen.copy(alpha = 0.12f)
                        ),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Column(modifier = Modifier.padding(18.dp)) {
                            Text(
                                text = "Current Balance (Owed to Shop)",
                                style = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                            )
                            Text(
                                text = CurrencyUtils.formatBDT(customer.currentDue),
                                style = MaterialTheme.typography.headlineMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = if (customer.currentDue > 0) AlertRed else AppGreen
                                )
                            )

                            Spacer(modifier = Modifier.height(14.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Button(
                                    onClick = { onPayCustomerClick(customer.id) },
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                                ) {
                                    Icon(Icons.Default.Payment, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Pay Due")
                                }

                                OutlinedButton(
                                    onClick = { onViewLedgerClick(customer.id) },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.AutoMirrored.Filled.ReceiptLong, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Ledger")
                                }
                            }
                        }
                    }
                }

                // Profile Summary Card
                item {
                    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Contact & Account Info", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                            DetailRow("Code", customer.customerCode)
                            DetailRow("Phone", customer.phone)
                            if (customer.alternativePhone.isNotBlank()) DetailRow("Alt Phone", customer.alternativePhone)
                            if (customer.whatsApp.isNotBlank()) DetailRow("WhatsApp", customer.whatsApp)
                            if (customer.email.isNotBlank()) DetailRow("Email", customer.email)
                            if (customer.address.isNotBlank()) DetailRow("Address", customer.address)
                            DetailRow("Credit Limit", if (customer.creditLimit > 0) CurrencyUtils.formatBDT(customer.creditLimit) else "No Limit")
                            DetailRow("Opening Due", CurrencyUtils.formatBDT(customer.openingDue))
                        }
                    }
                }

                // Activity Overview
                item {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Card(modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp)) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Text("Total Purchases", style = MaterialTheme.typography.labelSmall)
                                Text(CurrencyUtils.formatBDT(uiState.totalPurchasedAmount), style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                            }
                        }
                        Card(modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp)) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Text("Total Paid", style = MaterialTheme.typography.labelSmall)
                                Text(CurrencyUtils.formatBDT(uiState.totalPaidAmount), style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, color = AppGreen))
                            }
                        }
                    }
                }

                // Recent Sales Header
                item {
                    Text("Recent Invoices", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                }

                if (uiState.sales.isEmpty()) {
                    item {
                        Text("No sales invoices recorded yet.", style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant))
                    }
                } else {
                    items(uiState.sales.take(5), key = { it.id }) { sale ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSaleClick(sale.id) },
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text(sale.invoiceNumber, fontWeight = FontWeight.Bold)
                                    Text(DateTimeUtils.formatDate(sale.saleDate, "dd MMM yyyy, hh:mm a"), style = MaterialTheme.typography.bodySmall)
                                }
                                Column(horizontalAlignment = Alignment.End) {
                                    Text(CurrencyUtils.formatBDT(sale.totalAmount), fontWeight = FontWeight.Bold)
                                    Surface(
                                        color = if (sale.status == "CANCELLED") AlertRed.copy(alpha = 0.15f) else AppGreen.copy(alpha = 0.15f),
                                        shape = RoundedCornerShape(4.dp)
                                    ) {
                                        Text(
                                            text = sale.status,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                            style = MaterialTheme.typography.labelSmall.copy(
                                                color = if (sale.status == "CANCELLED") AlertRed else AppGreen
                                            )
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showDeactivateDialog) {
        AlertDialog(
            onDismissRequest = { showDeactivateDialog = false },
            title = { Text("Deactivate Customer") },
            text = { Text("Are you sure you want to deactivate '${customer?.name}'? Their transaction history and ledger will remain intact.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeactivateDialog = false
                        viewModel.deactivateCustomer(onNavigateBack)
                    }
                ) {
                    Text("Deactivate", color = AlertRed)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeactivateDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant))
        Text(value, style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomerLedgerScreen(
    customerId: Long,
    customerRepository: CustomerRepository,
    onNavigateBack: () -> Unit
) {
    val customer by customerRepository.observeCustomerById(customerId).collectAsState(initial = null)
    val ledgerEntries by customerRepository.getLedgerEntriesForCustomer(customerId).collectAsState(initial = emptyList())

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Customer Statement", fontWeight = FontWeight.Bold)
                        Text(
                            text = customer?.name ?: "",
                            style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
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
        ) {
            // Balance Header Card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("Current Balance (Receivable)", style = MaterialTheme.typography.labelSmall)
                        Text(
                            text = CurrencyUtils.formatBDT(customer?.currentDue ?: 0.0),
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.Bold,
                                color = if ((customer?.currentDue ?: 0.0) > 0) AlertRed else AppGreen
                            )
                        )
                    }
                    Text(
                        text = "${ledgerEntries.size} Entries",
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium)
                    )
                }
            }

            if (ledgerEntries.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No ledger transactions recorded yet.")
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(ledgerEntries, key = { it.id }) { entry ->
                        CustomerLedgerCard(entry)
                    }
                }
            }
        }
    }
}

@Composable
fun CustomerLedgerCard(entry: CustomerLedgerEntryEntity) {
    val isDebit = entry.signedAmount > 0 // Customer owes more
    val amountColor = if (isDebit) AlertRed else AppGreen
    val amountPrefix = if (isDebit) "+" else "-"

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        elevation = CardDefaults.cardElevation(2.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        color = if (isDebit) AlertRed.copy(alpha = 0.12f) else AppGreen.copy(alpha = 0.12f),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            text = entry.transactionType,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, color = amountColor)
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = entry.referenceNumber,
                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium)
                    )
                }

                Text(
                    text = "$amountPrefix${CurrencyUtils.formatBDT(entry.amount)}",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        color = amountColor
                    )
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            if (entry.description.isNotBlank()) {
                Text(
                    text = entry.description,
                    style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = DateTimeUtils.formatDate(entry.createdAt, "dd MMM yyyy, hh:mm a"),
                    style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
                )
                Text(
                    text = "Balance: ${CurrencyUtils.formatBDT(entry.runningBalance)}",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold)
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomerPaymentScreen(
    viewModel: CustomerPaymentViewModel,
    onNavigateBack: () -> Unit,
    onPaymentSuccess: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(uiState.isSuccess) {
        if (uiState.isSuccess) {
            onPaymentSuccess()
        }
    }

    val customer = uiState.customer

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Receive Customer Payment", fontWeight = FontWeight.Bold) },
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
            verticalArrangement = Arrangement.spacedBy(16.dp)
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

            // Customer Due Banner
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = customer?.name ?: "Customer",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                        )
                        Text(
                            text = "Phone: ${customer?.phone ?: ""}",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Current Due: ${CurrencyUtils.formatBDT(customer?.currentDue ?: 0.0)}",
                            style = MaterialTheme.typography.headlineSmall.copy(
                                fontWeight = FontWeight.Bold,
                                color = AlertRed
                            )
                        )
                    }
                }
            }

            // Payment Amount Input
            item {
                OutlinedTextField(
                    value = uiState.amount,
                    onValueChange = viewModel::onAmountChanged,
                    label = { Text("Payment Amount (৳) *") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("customer_payment_amount_input"),
                    shape = RoundedCornerShape(12.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true
                )
            }

            // Payment Method Selector
            item {
                Text("Payment Method", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
                Spacer(modifier = Modifier.height(8.dp))
                val methods = listOf("CASH", "BKASH", "NAGAD", "ROCKET", "CARD", "BANK")
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    methods.take(3).forEach { method ->
                        FilterChip(
                            selected = uiState.paymentMethod == method,
                            onClick = { viewModel.onPaymentMethodChanged(method) },
                            label = { Text(method) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    methods.drop(3).forEach { method ->
                        FilterChip(
                            selected = uiState.paymentMethod == method,
                            onClick = { viewModel.onPaymentMethodChanged(method) },
                            label = { Text(method) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            // Reference Number
            item {
                OutlinedTextField(
                    value = uiState.referenceNumber,
                    onValueChange = viewModel::onReferenceNumberChanged,
                    label = { Text("Transaction Reference / TrxID") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    singleLine = true
                )
            }

            // Notes
            item {
                OutlinedTextField(
                    value = uiState.notes,
                    onValueChange = viewModel::onNotesChanged,
                    label = { Text("Notes / Remarks") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    minLines = 2
                )
            }

            // Submit Button
            item {
                Spacer(modifier = Modifier.height(10.dp))
                Button(
                    onClick = viewModel::submitPayment,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .testTag("submit_customer_payment_btn"),
                    enabled = !uiState.isSaving
                ) {
                    if (uiState.isSaving) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), color = MaterialTheme.colorScheme.onPrimary)
                    } else {
                        Text("Record Payment", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
