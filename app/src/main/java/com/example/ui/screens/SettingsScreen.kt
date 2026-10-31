package com.example.ui.screens

import com.example.core.security.AccessControl
import com.example.core.security.Feature
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.components.AppTopBar
import com.example.ui.theme.AlertRose
import com.example.ui.viewmodels.SettingsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onNavigateBack: () -> Unit,
    onNavigateToShopProfile: () -> Unit,
    onNavigateToCategories: () -> Unit,
    onNavigateToBrands: () -> Unit,
    onNavigateToChangePin: () -> Unit,
    onNavigateToUsers: () -> Unit = {},
    onNavigateToBackup: () -> Unit = {},
    onLoggedOut: () -> Unit,
    onAppResetCompleted: () -> Unit
) {
    val state by viewModel.state.collectAsState()
    val currentUser by viewModel.currentUser.collectAsState()
    val shopProfile by viewModel.shopProfile.collectAsState(initial = null)
    val role = currentUser?.role

    var showLogoutDialog by remember { mutableStateOf(false) }
    var showResetDialog by remember { mutableStateOf(false) }
    var resetConfirmInput by remember { mutableStateOf("") }

    if (showLogoutDialog) {
        AlertDialog(
            onDismissRequest = { showLogoutDialog = false },
            title = { Text("Logout Confirmation", fontWeight = FontWeight.Bold) },
            text = { Text("Are you sure you want to sign out from this terminal?") },
            confirmButton = {
                Button(
                    onClick = {
                        showLogoutDialog = false
                        viewModel.logout(onLoggedOut)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Logout")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showLogoutDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = { Text("Reset Entire Application?", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text(
                        "WARNING: This will permanently delete all inventory, sales, purchases, customers, and shop data.\n\nA safety copy is kept on this phone first (Backup & Restore can bring it back), but make a backup file too if you are not sure.\n\nType 'RESET' below to confirm:",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = resetConfirmInput,
                        onValueChange = { resetConfirmInput = it },
                        placeholder = { Text("RESET") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (resetConfirmInput.trim() == "RESET") {
                            showResetDialog = false
                            viewModel.resetApplication(onAppResetCompleted)
                        }
                    },
                    enabled = resetConfirmInput.trim() == "RESET",
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Permanently Wipe Data")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showResetDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            AppTopBar(
                title = "Settings",
                subtitle = "App preferences, security & database",
                onBackClick = onNavigateBack
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
            state.message?.let { msg ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Text(msg, color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.padding(12.dp))
                }
            }

            // Section: Shop (owner / manager)
            if (AccessControl.can(role, Feature.SHOP_PROFILE)) SettingsGroup(title = "STORE") {
                SettingsNavigationItem(
                    title = "Shop Profile & Invoicing",
                    subtitle = shopProfile?.shopName?.ifBlank { "Configure name, logo, tax" } ?: "Configure name, logo, tax",
                    icon = Icons.Default.Storefront,
                    onClick = onNavigateToShopProfile
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                SettingsNavigationItem(
                    title = "Category Management",
                    subtitle = "Organize categories and subcategories",
                    icon = Icons.Default.Category,
                    onClick = onNavigateToCategories
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                SettingsNavigationItem(
                    title = "Brand Management",
                    subtitle = "Brands, manufacturers & origins",
                    icon = Icons.Default.BrandingWatermark,
                    onClick = onNavigateToBrands
                )
            }

            // Section: Data safety
            if (AccessControl.can(role, Feature.BACKUP)) SettingsGroup(title = "DATA") {
                SettingsNavigationItem(
                    title = "Backup & Restore",
                    subtitle = "Save all shop data to a file or Google Drive, export to Excel",
                    icon = Icons.Default.Backup,
                    onClick = onNavigateToBackup
                )
            }

            // Section: Security
            SettingsGroup(title = "SECURITY & ACCESS") {
                SettingsNavigationItem(
                    title = "Change PIN / Password",
                    subtitle = "Update authentication PIN for @${currentUser?.username ?: "admin"}",
                    icon = Icons.Default.Lock,
                    onClick = onNavigateToChangePin
                )

                if (AccessControl.can(role, Feature.USERS)) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                    SettingsNavigationItem(
                        title = "Users & Roles",
                        subtitle = "Add staff and choose what each one can do",
                        icon = Icons.Default.People,
                        onClick = onNavigateToUsers
                    )
                }

                // Shop-wide setting: only the owner/manager may let the app skip the PIN
                if (AccessControl.can(role, Feature.SHOP_PROFILE)) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Remember Login",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold)
                        )
                        Text(
                            text = "Skip the PIN when the app starts (anyone holding the phone gets in)",
                            style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                        )
                    }
                    Switch(
                        checked = state.rememberLogin,
                        onCheckedChange = { viewModel.setRememberLogin(it) },
                        modifier = Modifier.testTag("remember_login_switch")
                    )
                }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))

                SettingsNavigationItem(
                    title = "Sign Out",
                    subtitle = "Log out of terminal",
                    icon = Icons.Default.Logout,
                    iconTint = AlertRose,
                    onClick = { showLogoutDialog = true }
                )
            }

            // Section: Appearance
            SettingsGroup(title = "APPEARANCE") {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "App Theme",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold)
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf(
                            Pair("SYSTEM", "System"),
                            Pair("LIGHT", "Light"),
                            Pair("DARK", "Dark")
                        ).forEach { (mode, label) ->
                            FilterChip(
                                selected = state.themeMode == mode,
                                onClick = { viewModel.setThemeMode(mode) },
                                label = { Text(label) },
                                modifier = Modifier.minimumInteractiveComponentSize()
                            )
                        }
                    }
                }
            }

            // Section: Advanced
            if (AccessControl.can(role, Feature.RESET_APP)) {
                SettingsGroup(title = "ADVANCED / DANGER ZONE") {
                    SettingsNavigationItem(
                        title = "Reset Application",
                        subtitle = "Wipes all local data and resets to initial setup wizard",
                        icon = Icons.Default.DeleteForever,
                        iconTint = AlertRose,
                        onClick = { showResetDialog = true }
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsGroup(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Column {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium.copy(
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                letterSpacing = 1.sp
            ),
            modifier = Modifier.padding(start = 8.dp, bottom = 6.dp)
        )
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(content = content)
        }
    }
}

@Composable
private fun SettingsNavigationItem(
    title: String,
    subtitle: String,
    icon: ImageVector,
    iconTint: Color = MaterialTheme.colorScheme.primary,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = iconTint,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold)
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.size(20.dp)
        )
    }
}
