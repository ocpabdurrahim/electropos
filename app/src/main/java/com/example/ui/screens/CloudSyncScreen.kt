package com.example.ui.screens

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.BuildConfig
import com.example.sync.ShopMember
import com.example.sync.ShopRef
import com.example.sync.ShopRoles
import com.example.sync.SyncConflictEntity
import com.example.sync.SyncStatus
import com.example.ui.components.AppTopBar
import com.example.ui.components.EmptyStateCard
import com.example.ui.theme.AlertRose
import com.example.ui.viewmodels.CloudSyncViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun CloudSyncScreen(
    viewModel: CloudSyncViewModel,
    onNavigateBack: () -> Unit
) {
    val state by viewModel.state.collectAsState()
    val status by viewModel.status.collectAsState()
    val user by viewModel.user.collectAsState()
    val issues by viewModel.issues.collectAsState(initial = emptyList())
    val activity = LocalContext.current.findActivity()

    var joinTarget by remember { mutableStateOf<ShopRef?>(null) }
    var confirmOnline by remember { mutableStateOf(false) }
    var confirmUnlink by remember { mutableStateOf(false) }
    var addingMember by remember { mutableStateOf(false) }
    var memberToEdit by remember { mutableStateOf<ShopMember?>(null) }

    joinTarget?.let { shop ->
        AlertDialog(
            onDismissRequest = { joinTarget = null },
            title = { Text("Join ${shop.name}?", fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "Everything on this phone is replaced by the shop's records from the cloud. A safety copy of the " +
                        "current data is kept first (Backup & Restore can bring it back).\n\nThe whole shop downloads before " +
                        "the app can be used again; then sign in with one of the shop's PIN users."
                )
            },
            confirmButton = {
                Button(onClick = { joinTarget = null; viewModel.join(shop) }) { Text("Join and download") }
            },
            dismissButton = { OutlinedButton(onClick = { joinTarget = null }) { Text("Cancel") } }
        )
    }

    if (confirmOnline) {
        AlertDialog(
            onDismissRequest = { confirmOnline = false },
            title = { Text("Put this shop online?", fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "A cloud shop is created with ${user?.email ?: "this account"} as its owner, and every record on this " +
                        "phone is uploaded. Staff can then use the web app, and other phones can join.\n\nNew invoice, " +
                        "purchase and payment numbers from this phone carry its till code (A)."
                )
            },
            confirmButton = { Button(onClick = { confirmOnline = false; viewModel.putShopOnline() }) { Text("Put online") } },
            dismissButton = { OutlinedButton(onClick = { confirmOnline = false }) { Text("Cancel") } }
        )
    }

    if (confirmUnlink) {
        AlertDialog(
            onDismissRequest = { confirmUnlink = false },
            title = { Text("Unlink this phone?", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "The phone stops syncing. Its records stay here and in the cloud; nothing is deleted." +
                        if (status.pending > 0) "\n\n${status.pending} change(s) made here are not uploaded yet and will stay on this phone only." else ""
                )
            },
            confirmButton = {
                Button(
                    onClick = { confirmUnlink = false; viewModel.unlink() },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("Unlink") }
            },
            dismissButton = { OutlinedButton(onClick = { confirmUnlink = false }) { Text("Cancel") } }
        )
    }

    if (addingMember) {
        AddMemberDialog(
            onDismiss = { addingMember = false },
            onAdd = { email, role -> addingMember = false; viewModel.addMember(email, role) }
        )
    }

    memberToEdit?.let { member ->
        EditMemberDialog(
            member = member,
            onDismiss = { memberToEdit = null },
            onRole = { role -> memberToEdit = null; viewModel.changeRole(member, role) },
            onActive = { active -> memberToEdit = null; viewModel.setActive(member, active) }
        )
    }

    Scaffold(
        topBar = {
            AppTopBar(
                title = "Cloud Sync",
                subtitle = status.shopName?.let { "Linked to $it" } ?: "Phone and web, kept in step",
                onBackClick = onNavigateBack,
                actions = {
                    if (status.isLinked) {
                        IconButton(onClick = viewModel::syncNow, modifier = Modifier.testTag("cloud_sync_now")) {
                            Icon(Icons.Default.Sync, contentDescription = "Sync now")
                        }
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
            state.busy?.let { busy ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(busy, style = MaterialTheme.typography.bodyMedium)
                }
            }
            state.message?.let { msg ->
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = if (state.isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            msg,
                            color = if (state.isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = viewModel::clearMessage) { Icon(Icons.Default.Close, contentDescription = "Close") }
                    }
                }
            }

            if (!viewModel.available) {
                EmptyStateCard(
                    title = "Cloud sync isn't set up in this build",
                    description = "Everything keeps working on this phone. A build with the shop's Firebase project can link to the cloud.",
                    icon = Icons.Default.CloudOff
                )
                return@Column
            }

            CloudGroup("ACCOUNT") {
                val email = user?.email
                if (email != null) {
                    CloudRow(Icons.Default.AccountCircle, email, "Signed in with Google (for the cloud; the counter still uses PINs)") {
                        TextButton(onClick = viewModel::signOut, enabled = state.busy == null) { Text("Sign out") }
                    }
                } else {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            "Sign in with the shop's Google account to put this shop online or to join a shop.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Button(
                            onClick = { activity?.let(viewModel::signIn) },
                            enabled = state.busy == null && activity != null,
                            modifier = Modifier.fillMaxWidth().testTag("cloud_google_sign_in")
                        ) { Text("Sign in with Google") }
                        if (BuildConfig.FIREBASE_EMULATOR_HOST.isNotEmpty()) {
                            // Test builds talk to the Firebase emulator, which accepts any email
                            var testEmail by remember { mutableStateOf("") }
                            OutlinedTextField(
                                value = testEmail,
                                onValueChange = { testEmail = it.trim().lowercase() },
                                label = { Text("Test account email (emulator)") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth().testTag("cloud_test_email")
                            )
                            OutlinedButton(
                                onClick = { viewModel.signInForTests(testEmail) },
                                enabled = testEmail.contains('@') && state.busy == null,
                                modifier = Modifier.fillMaxWidth().testTag("cloud_test_sign_in")
                            ) { Text("Sign in as test user") }
                        }
                    }
                }
            }

            if (status.isLinked || status.downloading) {
                StatusGroup(status)
            }

            if (!status.isLinked && user != null) {
                CloudGroup("YOUR SHOPS") {
                    val shops = state.shops
                    when {
                        shops == null -> CloudRow(Icons.Default.HourglassEmpty, "Looking up your shops…", "")
                        shops.isEmpty() -> CloudRow(Icons.Default.Storefront, "No shops yet", "Ask a shop's owner to add ${user?.email} under Members, or put this shop online.")
                        else -> shops.forEachIndexed { i, shop ->
                            if (i > 0) CloudDivider()
                            val canLink = shop.role in ShopRoles.PHONE
                            CloudRow(
                                Icons.Default.Storefront,
                                shop.name,
                                if (canLink) ShopRoles.label(shop.role) else "${ShopRoles.label(shop.role)}: use the web app (phones need owner, admin or manager)"
                            ) {
                                Button(onClick = { joinTarget = shop }, enabled = canLink && state.busy == null) { Text("Join") }
                            }
                        }
                    }
                }
                if (state.mayCreateShops) {
                    Button(
                        onClick = { confirmOnline = true },
                        enabled = state.busy == null,
                        modifier = Modifier.fillMaxWidth().testTag("cloud_put_online")
                    ) {
                        Icon(Icons.Default.CloudUpload, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Put this shop online")
                    }
                } else if (state.shops != null) {
                    Text(
                        "Putting a new shop online needs the app owner's approval.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (status.isLinked && !status.downloading) {
                if (ShopRoles.canManageMembers(status.role)) {
                    CloudGroup("MEMBERS") {
                        state.members.forEachIndexed { i, m ->
                            if (i > 0) CloudDivider()
                            CloudRow(
                                if (m.active) Icons.Default.Person else Icons.Default.PersonOff,
                                m.email,
                                ShopRoles.label(m.role) + if (!m.active) " · deactivated" else ""
                            ) {
                                if (m.role != ShopRoles.OWNER) {
                                    IconButton(onClick = { memberToEdit = m }) { Icon(Icons.Default.Edit, contentDescription = "Change ${m.email}") }
                                }
                            }
                        }
                        CloudDivider()
                        TextButton(onClick = { addingMember = true }, modifier = Modifier.padding(8.dp)) {
                            Icon(Icons.Default.PersonAdd, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Add member by Google email")
                        }
                    }
                }

                CloudGroup("DEVICES") {
                    if (state.devices.isEmpty()) CloudRow(Icons.Default.Devices, if (state.loadingLists) "Loading…" else "No devices listed", "")
                    state.devices.forEachIndexed { i, d ->
                        if (i > 0) CloudDivider()
                        CloudRow(
                            if (d.kind == "WEB") Icons.Default.Language else Icons.Default.PhoneAndroid,
                            "Till ${d.tillCode.ifBlank { "-" }} · ${d.label.ifBlank { d.kind.lowercase().replaceFirstChar { it.uppercase() } }}" +
                                if (d.block == status.block && d.kind == "PHONE") " (this phone)" else "",
                            listOf(d.email, "block ${d.block}", formatTime(d.createdAt)).filter { it.isNotBlank() }.joinToString(" · ")
                        )
                    }
                }

                CloudGroup("SYNC ISSUES") {
                    if (issues.isEmpty()) {
                        CloudRow(Icons.Default.CheckCircle, "No sync issues", "Clashes between devices (same IMEI, same number…) would show here.")
                    }
                    issues.forEachIndexed { i, issue ->
                        if (i > 0) CloudDivider()
                        IssueRow(issue, onDismiss = { viewModel.dismissIssue(issue) })
                    }
                }

                OutlinedButton(
                    onClick = { confirmUnlink = true },
                    enabled = state.busy == null,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = AlertRose),
                    modifier = Modifier.fillMaxWidth().testTag("cloud_unlink")
                ) {
                    Icon(Icons.Default.LinkOff, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Unlink this phone")
                }
            }
        }
    }
}

@Composable
private fun StatusGroup(status: SyncStatus) {
    CloudGroup("STATUS") {
        val (icon, title) = when (status.state) {
            SyncStatus.State.UP_TO_DATE -> Icons.Default.CloudDone to "Up to date"
            SyncStatus.State.SYNCING -> Icons.Default.CloudSync to "Syncing…"
            SyncStatus.State.CONNECTING -> Icons.Default.CloudQueue to "Connecting…"
            SyncStatus.State.DOWNLOADING -> Icons.Default.CloudDownload to "Downloading shop…"
            SyncStatus.State.SIGNED_OUT -> Icons.Default.CloudOff to "Signed out: changes wait on this phone"
            SyncStatus.State.ERROR -> Icons.Default.ErrorOutline to "Sync problem"
            else -> Icons.Default.Cloud to "Not linked"
        }
        CloudRow(icon, title, status.error ?: status.downloadProgress ?: "Last sync: ${formatTime(status.lastSyncAt).ifBlank { "not yet" }}")
        CloudDivider()
        CloudRow(
            Icons.Default.Storefront,
            status.shopName ?: "-",
            listOfNotNull(
                status.role?.let { ShopRoles.label(it) },
                status.tillCode?.let { "till $it" },
                status.block?.let { "id block $it" }
            ).joinToString(" · ")
        )
        CloudDivider()
        CloudRow(
            Icons.Default.Upload,
            if (status.pending == 0) "Nothing waiting to upload" else "${status.pending} change(s) waiting to upload",
            listOfNotNull(
                status.waiting.takeIf { it > 0 }?.let { "$it cloud record(s) wait for a related record" },
                status.conflicts.takeIf { it > 0 }?.let { "$it sync issue(s)" }
            ).joinToString(" · ")
        )
    }
}

@Composable
private fun IssueRow(issue: SyncConflictEntity, onDismiss: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.Top) {
        Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                when (issue.kind) {
                    "DUPLICATE" -> "Clash with a record on this phone"
                    "SAME_NAME" -> "Same name twice"
                    "REJECTED" -> "Refused by the cloud"
                    else -> issue.kind
                },
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold)
            )
            Text(issue.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(formatTime(issue.createdAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TextButton(onClick = onDismiss) { Text("Dismiss") }
    }
}

@Composable
private fun AddMemberDialog(onDismiss: () -> Unit, onAdd: (String, String) -> Unit) {
    var email by remember { mutableStateOf("") }
    var role by remember { mutableStateOf(ShopRoles.SALESPERSON) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add member", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it.trim().lowercase() },
                    label = { Text("Google email") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                RolePicker(role) { role = it }
                Text(
                    "Admins manage members; managers do everything except members and PIN users; salespeople sell.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = { Button(onClick = { onAdd(email, role) }, enabled = email.contains('@')) { Text("Add") } },
        dismissButton = { OutlinedButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun EditMemberDialog(member: ShopMember, onDismiss: () -> Unit, onRole: (String) -> Unit, onActive: (Boolean) -> Unit) {
    var role by remember { mutableStateOf(member.role.takeIf { it in ShopRoles.ASSIGNABLE } ?: ShopRoles.SALESPERSON) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(member.email, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                RolePicker(role) { role = it }
                OutlinedButton(
                    onClick = { onActive(!member.active) },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = if (member.active) AlertRose else MaterialTheme.colorScheme.primary),
                    modifier = Modifier.fillMaxWidth()
                ) { Text(if (member.active) "Deactivate" else "Activate again") }
            }
        },
        confirmButton = { Button(onClick = { onRole(role) }, enabled = role != member.role) { Text("Save role") } },
        dismissButton = { OutlinedButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun RolePicker(selected: String, onSelect: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ShopRoles.ASSIGNABLE.forEach { r ->
            FilterChip(selected = selected == r, onClick = { onSelect(r) }, label = { Text(ShopRoles.label(r)) })
        }
    }
}

/** Shown instead of the app while a joined shop downloads (the data isn't complete yet). */
@Composable
fun DownloadingShopScreen(status: SyncStatus, onSyncNow: () -> Unit, onUnlink: () -> Unit) {
    var confirmCancel by remember { mutableStateOf(false) }
    if (confirmCancel) {
        AlertDialog(
            onDismissRequest = { confirmCancel = false },
            title = { Text("Stop joining?", fontWeight = FontWeight.Bold) },
            text = { Text("This phone keeps only what has downloaded so far. Backup & Restore has the safety copy from before joining.") },
            confirmButton = {
                Button(
                    onClick = { confirmCancel = false; onUnlink() },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("Stop and unlink") }
            },
            dismissButton = { OutlinedButton(onClick = { confirmCancel = false }) { Text("Keep downloading") } }
        )
    }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.fillMaxSize().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(Icons.Default.CloudDownload, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(56.dp))
            Spacer(Modifier.height(16.dp))
            Text("Downloading shop…", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            status.shopName?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
            Spacer(Modifier.height(16.dp))
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(12.dp))
            Text(
                status.error ?: status.downloadProgress
                    ?: if (status.signedInEmail == null) "Sign in again on the Cloud screen to continue." else "Keep the app open and the internet on.",
                style = MaterialTheme.typography.bodyMedium,
                color = if (status.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(24.dp))
            if (status.error != null) {
                Button(onClick = onSyncNow) { Text("Try again") }
                Spacer(Modifier.height(8.dp))
            }
            TextButton(onClick = { confirmCancel = true }) { Text("Stop joining") }
        }
    }
}

@Composable
private fun CloudGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
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
private fun CloudRow(icon: ImageVector, title: String, subtitle: String, trailing: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
            if (subtitle.isNotBlank()) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant))
            }
        }
        trailing()
    }
}

@Composable
private fun CloudDivider() = HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))

private fun formatTime(ms: Long): String =
    if (ms <= 0L) "" else SimpleDateFormat("dd MMM yyyy, h:mm a", Locale.getDefault()).format(Date(ms))

private fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
