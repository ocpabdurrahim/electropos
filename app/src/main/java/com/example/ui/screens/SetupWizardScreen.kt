package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.viewmodels.SetupViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupWizardScreen(
    viewModel: SetupViewModel,
    onSetupComplete: () -> Unit
) {
    val state by viewModel.state.collectAsState()

    LaunchedEffect(state.isCompleted) {
        if (state.isCompleted) {
            onSetupComplete()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Shop Setup Wizard",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Step Indicator Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 24.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                for (s in 1..4) {
                    val isActive = s == state.step
                    val isPast = s < state.step
                    val bgColor = when {
                        isActive -> MaterialTheme.colorScheme.primary
                        isPast -> MaterialTheme.colorScheme.primaryContainer
                        else -> MaterialTheme.colorScheme.surfaceVariant
                    }
                    val textColor = when {
                        isActive -> MaterialTheme.colorScheme.onPrimary
                        isPast -> MaterialTheme.colorScheme.onPrimaryContainer
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }

                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(bgColor),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = s.toString(),
                            style = MaterialTheme.typography.labelLarge.copy(
                                fontWeight = FontWeight.Bold,
                                color = textColor
                            )
                        )
                    }
                    if (s < 4) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(2.dp)
                                .padding(horizontal = 4.dp)
                                .background(
                                    if (s < state.step) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                                )
                        )
                    }
                }
            }

            // Error Banner
            if (state.errorMessage != null) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = "Error",
                            tint = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = state.errorMessage!!,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }

            // Wizard Steps Content
            when (state.step) {
                1 -> StepShopInformation(state = state, viewModel = viewModel)
                2 -> StepBusinessSettings(state = state, viewModel = viewModel)
                3 -> StepAdminAccount(state = state, viewModel = viewModel)
                4 -> StepReviewAndFinish(state = state, viewModel = viewModel)
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Navigation Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                if (state.step > 1) {
                    OutlinedButton(
                        onClick = { viewModel.previousStep() },
                        modifier = Modifier
                            .weight(1f)
                            .height(50.dp)
                            .padding(end = 8.dp)
                            .testTag("setup_back_button")
                    ) {
                        Icon(Icons.Default.ArrowBack, contentDescription = null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Back")
                    }
                }

                Button(
                    onClick = {
                        if (state.step < 4) viewModel.nextStep()
                        else viewModel.completeSetup()
                    },
                    modifier = Modifier
                        .weight(1f)
                        .height(50.dp)
                        .padding(start = if (state.step > 1) 8.dp else 0.dp)
                        .testTag("setup_next_button"),
                    enabled = !state.isLoading
                ) {
                    if (state.isLoading) {
                        CircularProgressIndicator(
                            color = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text(if (state.step == 4) "Complete Setup" else "Next Step")
                        Spacer(modifier = Modifier.width(6.dp))
                        Icon(
                            if (state.step == 4) Icons.Default.Check else Icons.Default.ArrowForward,
                            contentDescription = null
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StepShopInformation(
    state: com.example.ui.viewmodels.SetupWizardState,
    viewModel: SetupViewModel
) {
    Text(
        text = "Shop Information",
        style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold)
    )
    Text(
        text = "Enter your store details for branding and invoice printing.",
        style = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
        modifier = Modifier.padding(top = 4.dp, bottom = 16.dp)
    )

    OutlinedTextField(
        value = state.shopName,
        onValueChange = { viewModel.updateShopName(it) },
        label = { Text("Shop Name *") },
        placeholder = { Text("e.g. ElectroTrack Gadgets & Mobile") },
        leadingIcon = { Icon(Icons.Default.Storefront, contentDescription = null) },
        singleLine = true,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("setup_shop_name_input")
    )
    Spacer(modifier = Modifier.height(12.dp))

    OutlinedTextField(
        value = state.shopAddress,
        onValueChange = { viewModel.updateShopAddress(it) },
        label = { Text("Shop Address") },
        placeholder = { Text("e.g. Shop 42, Multiplan Center, Dhaka") },
        leadingIcon = { Icon(Icons.Default.LocationOn, contentDescription = null) },
        modifier = Modifier.fillMaxWidth()
    )
    Spacer(modifier = Modifier.height(12.dp))

    OutlinedTextField(
        value = state.phone,
        onValueChange = { viewModel.updatePhone(it) },
        label = { Text("Primary Phone Number") },
        placeholder = { Text("+880 1711-XXXXXX") },
        leadingIcon = { Icon(Icons.Default.Phone, contentDescription = null) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
    Spacer(modifier = Modifier.height(12.dp))

    OutlinedTextField(
        value = state.alternativePhone,
        onValueChange = { viewModel.updateAlternativePhone(it) },
        label = { Text("Alternative Phone / WhatsApp") },
        placeholder = { Text("+880 1819-XXXXXX") },
        leadingIcon = { Icon(Icons.Default.PhoneAndroid, contentDescription = null) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
    Spacer(modifier = Modifier.height(12.dp))

    OutlinedTextField(
        value = state.email,
        onValueChange = { viewModel.updateEmail(it) },
        label = { Text("Shop Email") },
        placeholder = { Text("contact@electrotrack.bd") },
        leadingIcon = { Icon(Icons.Default.Email, contentDescription = null) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun StepBusinessSettings(
    state: com.example.ui.viewmodels.SetupWizardState,
    viewModel: SetupViewModel
) {
    Text(
        text = "Business Settings",
        style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold)
    )
    Text(
        text = "Configure your currency, default tax, and stock thresholds.",
        style = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
        modifier = Modifier.padding(top = 4.dp, bottom = 16.dp)
    )

    Text(
        text = "Select Currency",
        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
        modifier = Modifier.fillMaxWidth()
    )
    Spacer(modifier = Modifier.height(6.dp))

    val currencies = listOf(
        Pair("BDT", "৳ (Bangladeshi Taka)"),
        Pair("USD", "$ (US Dollar)"),
        Pair("EUR", "€ (Euro)"),
        Pair("INR", "₹ (Indian Rupee)"),
        Pair("GBP", "£ (British Pound)")
    )

    Row(modifier = Modifier.fillMaxWidth()) {
        currencies.take(3).forEach { (code, label) ->
            FilterChip(
                selected = state.currencyCode == code,
                onClick = {
                    val sym = when (code) {
                        "BDT" -> "৳"
                        "USD" -> "$"
                        "EUR" -> "€"
                        "INR" -> "₹"
                        else -> "£"
                    }
                    viewModel.updateCurrency(code, sym)
                },
                label = { Text(code) },
                modifier = Modifier
                    .padding(end = 8.dp)
                    .minimumInteractiveComponentSize()
            )
        }
    }

    Spacer(modifier = Modifier.height(16.dp))

    OutlinedTextField(
        value = if (state.defaultTaxRate == 0.0) "" else state.defaultTaxRate.toString(),
        onValueChange = { viewModel.updateTaxRate(it.toDoubleOrNull() ?: 0.0) },
        label = { Text("Default Tax / VAT % (Optional)") },
        placeholder = { Text("e.g. 5.0") },
        leadingIcon = { Icon(Icons.Default.Percent, contentDescription = null) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
    Spacer(modifier = Modifier.height(12.dp))

    OutlinedTextField(
        value = state.lowStockThreshold.toString(),
        onValueChange = { viewModel.updateLowStockThreshold(it.toIntOrNull() ?: 5) },
        label = { Text("Low Stock Alert Threshold") },
        placeholder = { Text("5") },
        leadingIcon = { Icon(Icons.Default.WarningAmber, contentDescription = null) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun StepAdminAccount(
    state: com.example.ui.viewmodels.SetupWizardState,
    viewModel: SetupViewModel
) {
    Text(
        text = "Admin Account",
        style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold)
    )
    Text(
        text = "Set up your secure Admin PIN/password for signing in.",
        style = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
        modifier = Modifier.padding(top = 4.dp, bottom = 16.dp)
    )

    OutlinedTextField(
        value = state.adminName,
        onValueChange = { viewModel.updateAdminName(it) },
        label = { Text("Admin Full Name *") },
        leadingIcon = { Icon(Icons.Default.Person, contentDescription = null) },
        singleLine = true,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("setup_admin_name_input")
    )
    Spacer(modifier = Modifier.height(12.dp))

    OutlinedTextField(
        value = state.username,
        onValueChange = { viewModel.updateUsername(it) },
        label = { Text("Username *") },
        leadingIcon = { Icon(Icons.Default.AccountCircle, contentDescription = null) },
        singleLine = true,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("setup_username_input")
    )
    Spacer(modifier = Modifier.height(12.dp))

    OutlinedTextField(
        value = state.pin,
        onValueChange = { viewModel.updatePin(it) },
        label = { Text("Security PIN / Password (min 4 digits) *") },
        leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) },
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        singleLine = true,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("setup_pin_input")
    )
    Spacer(modifier = Modifier.height(12.dp))

    OutlinedTextField(
        value = state.confirmPin,
        onValueChange = { viewModel.updateConfirmPin(it) },
        label = { Text("Confirm PIN / Password *") },
        leadingIcon = { Icon(Icons.Default.LockReset, contentDescription = null) },
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        singleLine = true,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("setup_confirm_pin_input")
    )
}

@Composable
private fun StepReviewAndFinish(
    state: com.example.ui.viewmodels.SetupWizardState,
    viewModel: SetupViewModel
) {
    Text(
        text = "Review & Complete",
        style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold)
    )
    Text(
        text = "Confirm your details to finalize initialization.",
        style = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
        modifier = Modifier.padding(top = 4.dp, bottom = 16.dp)
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            ReviewRow(label = "Shop Name", value = state.shopName)
            ReviewRow(label = "Address", value = state.shopAddress.ifBlank { "Not specified" })
            ReviewRow(label = "Phone", value = state.phone.ifBlank { "Not specified" })
            ReviewRow(label = "Currency", value = "${state.currencyCode} (${state.currencySymbol})")
            ReviewRow(label = "Admin User", value = "${state.adminName} (@${state.username})")
        }
    }

    Spacer(modifier = Modifier.height(16.dp))

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(
                checked = state.loadSampleData,
                onCheckedChange = { viewModel.updateLoadSampleData(it) }
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column {
                Text(
                    text = "Load Starter Catalog & Sample Data",
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                )
                Text(
                    text = "Seeds initial categories (Mobiles, Chargers, Routers, Audio), brands, and demo inventory.",
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                    )
                )
            }
        }
    }
}

@Composable
private fun ReviewRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium.copy(
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Medium
            )
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
        )
    }
}
