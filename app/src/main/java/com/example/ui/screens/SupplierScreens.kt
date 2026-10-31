package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.core.utils.CurrencyUtils
import com.example.core.utils.DateTimeUtils
import com.example.data.local.entities.SupplierEntity
import com.example.data.repository.*
import com.example.ui.components.EmptyStateCard
import com.example.ui.components.StatCard
import com.example.ui.theme.*
import com.example.ui.viewmodels.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SupplierListScreen(
    viewModel: SupplierListViewModel,
    onNavigateBack: () -> Unit,
    onAddSupplierClick: () -> Unit,
    onSupplierClick: (Long) -> Unit,
    onPaySupplierClick: (Long) -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Suppliers & Vendors", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack, modifier = Modifier.testTag("supplier_list_back_button")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = onAddSupplierClick, modifier = Modifier.testTag("add_supplier_top_button")) {
                        Icon(Icons.Default.AddBusiness, contentDescription = "Add Supplier")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onAddSupplierClick,
                containerColor = MaterialTheme.colorScheme.primary,
                modifier = Modifier.testTag("add_supplier_fab")
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add Supplier")
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Search & Balance Overview Bar
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("Total Supplier Payables", style = MaterialTheme.typography.labelMedium)
                            Text(
                                text = CurrencyUtils.format(uiState.totalSupplierDue, "৳"),
                                style = MaterialTheme.typography.titleLarge.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = if (uiState.totalSupplierDue > 0) AlertRose else StockGreen
                                )
                            )
                        }
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = AccentPurple.copy(alpha = 0.15f)
                        ) {
                            Text(
                                text = "${uiState.suppliers.size} Suppliers",
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = AccentPurple
                                )
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedTextField(
                        value = uiState.searchQuery,
                        onValueChange = viewModel::onSearchQueryChanged,
                        placeholder = { Text("Search by name, company, phone, code...") },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("supplier_search_input"),
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp)
                    )
                }
            }

            if (uiState.suppliers.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    EmptyStateCard(
                        message = if (uiState.searchQuery.isBlank()) "No suppliers added yet. Tap '+' to create your first vendor." else "No suppliers match '${uiState.searchQuery}'.",
                        icon = Icons.Default.LocalShipping
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(uiState.suppliers, key = { it.id }) { supplier ->
                        SupplierCard(
                            supplier = supplier,
                            onClick = { onSupplierClick(supplier.id) },
                            onPayClick = { onPaySupplierClick(supplier.id) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SupplierCard(
    supplier: SupplierEntity,
    onClick: () -> Unit,
    onPayClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .testTag("supplier_card_${supplier.id}"),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = supplier.name.take(1).uppercase(),
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = supplier.name,
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                        )
                        Text(
                            text = if (supplier.company.isNotBlank()) "${supplier.company} • ${supplier.supplierCode}" else supplier.supplierCode,
                            style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                        )
                    }
                }

                val isPayable = supplier.currentDue > 0
                val isAdvance = supplier.currentDue < 0
                val chipColor = when {
                    isPayable -> AlertRose
                    isAdvance -> SecondaryBlue
                    else -> StockGreen
                }

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = chipColor.copy(alpha = 0.12f)
                ) {
                    Column(
                        horizontalAlignment = Alignment.End,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = when {
                                isPayable -> "Payable"
                                isAdvance -> "Advance"
                                else -> "Settled"
                            },
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = chipColor,
                                fontWeight = FontWeight.Bold
                            )
                        )
                        Text(
                            text = CurrencyUtils.format(kotlin.math.abs(supplier.currentDue), "৳"),
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontWeight = FontWeight.Bold,
                                color = chipColor
                            )
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Phone,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = supplier.phone,
                        style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                    )
                    if (supplier.city.isNotBlank()) {
                        Text(
                            text = " • ${supplier.city}",
                            style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                        )
                    }
                }

                if (supplier.currentDue > 0) {
                    FilledTonalButton(
                        onClick = onPayClick,
                        modifier = Modifier
                            .height(32.dp)
                            .testTag("supplier_pay_btn_${supplier.id}"),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                    ) {
                        Text("Pay Due", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddEditSupplierScreen(
    viewModel: AddEditSupplierViewModel,
    onNavigateBack: () -> Unit,
    onSupplierSaved: () -> Unit
) {
    val formState by viewModel.formState.collectAsState()
    val scrollState = rememberScrollState()

    LaunchedEffect(formState.isSavedSuccess) {
        if (formState.isSavedSuccess) {
            onSupplierSaved()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (formState.id == 0L) "New Supplier" else "Edit Supplier", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack, modifier = Modifier.testTag("add_supplier_back_button")) {
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
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            if (formState.errorMessage != null) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = formState.errorMessage ?: "",
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            // Supplier Code
            OutlinedTextField(
                value = formState.supplierCode,
                onValueChange = {},
                readOnly = true,
                label = { Text("Supplier Code") },
                leadingIcon = { Icon(Icons.Default.QrCode, contentDescription = null) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            )

            // Supplier Name
            OutlinedTextField(
                value = formState.name,
                onValueChange = viewModel::onNameChange,
                label = { Text("Supplier Name *") },
                placeholder = { Text("e.g. ABC Mobile Distributor") },
                leadingIcon = { Icon(Icons.Default.Person, contentDescription = null) },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("supplier_name_input"),
                singleLine = true,
                shape = RoundedCornerShape(12.dp)
            )

            // Company Name
            OutlinedTextField(
                value = formState.company,
                onValueChange = viewModel::onCompanyChange,
                label = { Text("Company / Store Name") },
                placeholder = { Text("e.g. ABC Telecom Ltd.") },
                leadingIcon = { Icon(Icons.Default.Business, contentDescription = null) },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("supplier_company_input"),
                singleLine = true,
                shape = RoundedCornerShape(12.dp)
            )

            // Phone
            OutlinedTextField(
                value = formState.phone,
                onValueChange = viewModel::onPhoneChange,
                label = { Text("Primary Phone *") },
                placeholder = { Text("e.g. 01700000000") },
                leadingIcon = { Icon(Icons.Default.Phone, contentDescription = null) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("supplier_phone_input"),
                singleLine = true,
                shape = RoundedCornerShape(12.dp)
            )

            // Alternative Phone & WhatsApp
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = formState.alternativePhone,
                    onValueChange = viewModel::onAlternativePhoneChange,
                    label = { Text("Alternative Phone") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp)
                )
                OutlinedTextField(
                    value = formState.whatsApp,
                    onValueChange = viewModel::onWhatsAppChange,
                    label = { Text("WhatsApp") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp)
                )
            }

            // Email & City
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = formState.email,
                    onValueChange = viewModel::onEmailChange,
                    label = { Text("Email Address") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    modifier = Modifier.weight(1.2f),
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp)
                )
                OutlinedTextField(
                    value = formState.city,
                    onValueChange = viewModel::onCityChange,
                    label = { Text("City") },
                    modifier = Modifier.weight(0.8f),
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp)
                )
            }

            // Address
            OutlinedTextField(
                value = formState.address,
                onValueChange = viewModel::onAddressChange,
                label = { Text("Address / Shop Location") },
                placeholder = { Text("e.g. Shop 42, Multiplan Center, Elephant Road") },
                leadingIcon = { Icon(Icons.Default.LocationOn, contentDescription = null) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            )

            // Opening Due & Credit Limit
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = formState.openingDue,
                    onValueChange = viewModel::onOpeningDueChange,
                    label = { Text("Opening Balance (৳)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("supplier_opening_due_input"),
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp)
                )
                OutlinedTextField(
                    value = formState.creditLimit,
                    onValueChange = viewModel::onCreditLimitChange,
                    label = { Text("Credit Limit (৳)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp)
                )
            }

            // Notes
            OutlinedTextField(
                value = formState.notes,
                onValueChange = viewModel::onNotesChange,
                label = { Text("Notes / Payment Terms") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                shape = RoundedCornerShape(12.dp)
            )

            Spacer(modifier = Modifier.height(10.dp))

            Button(
                onClick = viewModel::saveSupplier,
                enabled = !formState.isSaving,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .testTag("save_supplier_button"),
                shape = RoundedCornerShape(12.dp)
            ) {
                if (formState.isSaving) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), color = MaterialTheme.colorScheme.onPrimary)
                } else {
                    Icon(Icons.Default.Save, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(if (formState.id == 0L) "Save Supplier" else "Update Supplier", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SupplierDetailsScreen(
    viewModel: SupplierDetailsViewModel,
    onNavigateBack: () -> Unit,
    onEditSupplierClick: (Long) -> Unit,
    onNewPurchaseClick: (Long) -> Unit,
    onPaySupplierClick: (Long) -> Unit,
    onViewLedgerClick: (Long) -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val supplier = uiState.supplier

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(supplier?.name ?: "Supplier Details", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack, modifier = Modifier.testTag("supplier_details_back_button")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (supplier != null) {
                        IconButton(onClick = { onEditSupplierClick(supplier.id) }) {
                            Icon(Icons.Default.Edit, contentDescription = "Edit")
                        }
                    }
                }
            )
        }
    ) { padding ->
        if (uiState.isLoading || supplier == null) {
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
                // Header Profile Card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(54.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = supplier.name.take(1).uppercase(),
                                    style = MaterialTheme.typography.titleLarge.copy(
                                        color = MaterialTheme.colorScheme.onPrimary,
                                        fontWeight = FontWeight.Bold
                                    )
                                )
                            }
                            Spacer(modifier = Modifier.width(14.dp))
                            Column {
                                Text(supplier.name, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold))
                                Text("${supplier.supplierCode} • ${supplier.company.ifBlank { "Direct Vendor" }}", style = MaterialTheme.typography.bodyMedium)
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                        Spacer(modifier = Modifier.height(10.dp))

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Phone: ${supplier.phone}", style = MaterialTheme.typography.bodyMedium)
                            if (supplier.city.isNotBlank()) Text("City: ${supplier.city}", style = MaterialTheme.typography.bodyMedium)
                        }
                        if (supplier.email.isNotBlank()) {
                            Text("Email: ${supplier.email}", style = MaterialTheme.typography.bodySmall)
                        }
                        if (supplier.address.isNotBlank()) {
                            Text("Address: ${supplier.address}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }

                // Financial Summary Cards
                Text("Financial Account Summary", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatCard(
                        title = "Total Purchases",
                        value = CurrencyUtils.format(uiState.summary.totalPurchases, "৳"),
                        icon = Icons.Default.ShoppingBag,
                        iconTint = SecondaryBlue,
                        modifier = Modifier.weight(1f)
                    )
                    val isPayable = uiState.summary.currentDue > 0
                    val isAdvance = uiState.summary.currentDue < 0
                    StatCard(
                        title = when {
                            isPayable -> "Current Payable"
                            isAdvance -> "Advance / Credit"
                            else -> "Account Status"
                        },
                        value = if (uiState.summary.currentDue == 0.0) "Settled (৳0)" else CurrencyUtils.format(kotlin.math.abs(uiState.summary.currentDue), "৳"),
                        icon = Icons.Default.AccountBalanceWallet,
                        iconTint = when {
                            isPayable -> AlertRose
                            isAdvance -> SecondaryBlue
                            else -> StockGreen
                        },
                        modifier = Modifier.weight(1f)
                    )
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatCard(
                        title = "Returns Credited",
                        value = CurrencyUtils.format(uiState.summary.totalReturns, "৳"),
                        icon = Icons.Default.AssignmentReturn,
                        iconTint = WarningAmber,
                        modifier = Modifier.weight(1f)
                    )
                    StatCard(
                        title = "Total Payments",
                        value = CurrencyUtils.format(uiState.summary.totalPaid, "৳"),
                        icon = Icons.Default.CheckCircle,
                        iconTint = StockGreen,
                        modifier = Modifier.weight(1f)
                    )
                }

                // Quick Action Buttons
                Text("Quick Actions", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = { onNewPurchaseClick(supplier.id) },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("supplier_new_purchase_btn"),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.AddShoppingCart, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Purchase")
                    }

                    FilledTonalButton(
                        onClick = { onPaySupplierClick(supplier.id) },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("supplier_details_pay_btn"),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.Payments, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Pay Due")
                    }

                    OutlinedButton(
                        onClick = { onViewLedgerClick(supplier.id) },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("supplier_view_ledger_btn"),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.ReceiptLong, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Ledger")
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SupplierLedgerScreen(
    supplierId: Long,
    supplierRepository: SupplierRepository,
    onNavigateBack: () -> Unit
) {
    var supplier by remember { mutableStateOf<SupplierEntity?>(null) }
    var entries by remember { mutableStateOf<List<SupplierLedgerEntry>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(supplierId) {
        supplier = supplierRepository.getSupplierById(supplierId)
        entries = supplierRepository.getSupplierLedger(supplierId)
        isLoading = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("${supplier?.name ?: "Supplier"} Ledger", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        if (isLoading) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (entries.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                EmptyStateCard(message = "No transactions recorded for this supplier.", icon = Icons.Default.ReceiptLong)
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(entries) { entry ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = DateTimeUtils.formatDate(entry.date, "dd MMM yyyy, hh:mm a"),
                                    style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                                )
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = when (entry.transactionType) {
                                        "PURCHASE" -> AlertRose.copy(alpha = 0.15f)
                                        "PURCHASE_RETURN" -> WarningAmber.copy(alpha = 0.15f)
                                        "SUPPLIER_PAYMENT" -> StockGreen.copy(alpha = 0.15f)
                                        "SUPPLIER_REFUND" -> SecondaryBlue.copy(alpha = 0.15f)
                                        "OPENING_BALANCE" -> AccentPurple.copy(alpha = 0.15f)
                                        "DEBIT_ADJUSTMENT" -> AlertRose.copy(alpha = 0.15f)
                                        "CREDIT_ADJUSTMENT" -> StockGreen.copy(alpha = 0.15f)
                                        else -> MaterialTheme.colorScheme.surfaceVariant
                                    }
                                ) {
                                    Text(
                                        text = entry.transactionType.replace("_", " "),
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontWeight = FontWeight.Bold,
                                            color = when (entry.transactionType) {
                                                "PURCHASE", "DEBIT_ADJUSTMENT" -> AlertRose
                                                "SUPPLIER_PAYMENT", "CREDIT_ADJUSTMENT" -> StockGreen
                                                "PURCHASE_RETURN" -> WarningAmber
                                                "SUPPLIER_REFUND" -> SecondaryBlue
                                                else -> AccentPurple
                                            }
                                        )
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(6.dp))
                            Text(entry.description, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium))
                            Spacer(modifier = Modifier.height(8.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                if (entry.signedAmount > 0) {
                                    Text("Payable (+): ৳${entry.amount.toInt()}", color = AlertRose, style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold))
                                } else {
                                    Text("Reduced (-): ৳${entry.amount.toInt()}", color = StockGreen, style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold))
                                }
                                val runningText = when {
                                    entry.runningBalance > 0 -> "Due: ৳${entry.runningBalance.toInt()}"
                                    entry.runningBalance < 0 -> "Advance: ৳${kotlin.math.abs(entry.runningBalance).toInt()}"
                                    else -> "Settled (৳0)"
                                }
                                Text(runningText, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SupplierPaymentScreen(
    viewModel: SupplierPaymentViewModel,
    onNavigateBack: () -> Unit,
    onPaymentSuccess: () -> Unit
) {
    val formState by viewModel.formState.collectAsState()

    LaunchedEffect(formState.isSuccess) {
        if (formState.isSuccess) {
            onPaymentSuccess()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Pay Supplier", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack, modifier = Modifier.testTag("supplier_payment_back_btn")) {
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
            if (formState.errorMessage != null) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Text(
                        text = formState.errorMessage ?: "",
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }

            // Overview Card
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Paying To", style = MaterialTheme.typography.labelMedium)
                    Text(formState.supplierName, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold))
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text("Outstanding Due", style = MaterialTheme.typography.labelSmall)
                            Text(CurrencyUtils.format(formState.currentDue, "৳"), style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, color = AlertRose))
                        }
                        val entered = formState.paymentAmount.toDoubleOrNull() ?: 0.0
                        val remaining = maxOf(0.0, formState.currentDue - entered)
                        Column(horizontalAlignment = Alignment.End) {
                            Text("Remaining Due", style = MaterialTheme.typography.labelSmall)
                            Text(CurrencyUtils.format(remaining, "৳"), style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                        }
                    }
                }
            }

            // Payment Amount
            OutlinedTextField(
                value = formState.paymentAmount,
                onValueChange = viewModel::onPaymentAmountChange,
                label = { Text("Payment Amount (৳) *") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("supplier_payment_amount_input"),
                singleLine = true,
                shape = RoundedCornerShape(12.dp)
            )

            // Payment Method Selector
            Text("Payment Method", style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold))
            val methods = listOf("CASH", "BKASH", "NAGAD", "ROCKET", "BANK", "CARD")
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                methods.take(3).forEach { m ->
                    FilterChip(
                        selected = formState.paymentMethod == m,
                        onClick = { viewModel.onPaymentMethodChange(m) },
                        label = { Text(m) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                methods.drop(3).forEach { m ->
                    FilterChip(
                        selected = formState.paymentMethod == m,
                        onClick = { viewModel.onPaymentMethodChange(m) },
                        label = { Text(m) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // Reference Number
            OutlinedTextField(
                value = formState.reference,
                onValueChange = viewModel::onReferenceChange,
                label = { Text("Transaction Reference / Cheque No.") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(12.dp)
            )

            // Notes
            OutlinedTextField(
                value = formState.notes,
                onValueChange = viewModel::onNotesChange,
                label = { Text("Notes") },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            )

            Spacer(modifier = Modifier.height(10.dp))

            Button(
                onClick = viewModel::submitPayment,
                enabled = !formState.isSubmitting,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .testTag("submit_supplier_payment_btn"),
                shape = RoundedCornerShape(12.dp)
            ) {
                if (formState.isSubmitting) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), color = MaterialTheme.colorScheme.onPrimary)
                } else {
                    Icon(Icons.Default.Payments, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Confirm Payment Voucher", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
