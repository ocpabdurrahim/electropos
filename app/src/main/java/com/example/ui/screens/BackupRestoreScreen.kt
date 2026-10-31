package com.example.ui.screens

import android.content.ActivityNotFoundException
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.repository.BackupInspection
import com.example.data.repository.BackupRepository
import com.example.data.repository.SafetyCopy
import com.example.ui.components.AppTopBar
import com.example.ui.components.EmptyStateCard
import com.example.ui.theme.AlertRose
import com.example.ui.theme.StockGreen
import com.example.ui.theme.WarningAmber
import com.example.ui.viewmodels.BackupMessage
import com.example.ui.viewmodels.BackupViewModel

private const val STALE_BACKUP_MS = 7L * 24 * 60 * 60 * 1000

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupRestoreScreen(
    viewModel: BackupViewModel,
    onNavigateBack: () -> Unit,
    /** Restoring replaces all data, so only the owner (admin) gets the restore buttons. */
    canRestore: Boolean = true
) {
    val state by viewModel.state.collectAsState()
    // Survives activity recreation while the system file picker is open.
    var safetyCopyToSave by rememberSaveable { mutableStateOf<String?>(null) }

    val createBackupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(BackupRepository.ZIP_MIME)
    ) { uri: Uri? -> if (uri != null) viewModel.createBackup(uri) }

    val openBackupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? -> if (uri != null) viewModel.inspectBackup(uri) }

    val exportCsvLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(BackupRepository.ZIP_MIME)
    ) { uri: Uri? -> if (uri != null) viewModel.exportCsv(uri) }

    val saveSafetyCopyLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(BackupRepository.ZIP_MIME)
    ) { uri: Uri? ->
        val path = safetyCopyToSave
        safetyCopyToSave = null
        if (uri != null && path != null) viewModel.exportSafetyCopy(path, uri)
    }

    fun launchPicker(launch: () -> Unit) {
        try {
            launch()
        } catch (e: ActivityNotFoundException) {
            viewModel.showError("No file picker was found on this phone, so the file can't be chosen.")
        }
    }

    state.inspection?.let { inspection ->
        BackupRestoreConfirmDialog(
            inspection = inspection,
            onConfirm = { viewModel.confirmRestore() },
            onDismiss = { viewModel.cancelRestore() }
        )
    }

    if (state.restarting) {
        AlertDialog(
            onDismissRequest = { },
            confirmButton = { },
            title = { Text("Restarting ElectroPOS", fontWeight = FontWeight.Bold) },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
                    Spacer(modifier = Modifier.width(16.dp))
                    Text("The restore is ready. The app will close and open again with the restored data.")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            AppTopBar(
                title = "Backup & Restore",
                subtitle = "Keep your shop data safe",
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
            state.busyText?.let { BackupBusyCard(it) }
            state.message?.let { BackupMessageCard(it, onDismiss = { viewModel.dismissMessage() }) }

            if (state.pendingRestore && !state.restarting) {
                BackupPendingRestoreCard(onRestartNow = { viewModel.restartNow() })
            }

            BackupLastBackupCard(
                lastBackupAt = state.lastBackup?.time ?: 0L,
                lastBackupName = state.lastBackup?.fileName,
                lastRestoreText = state.lastRestore?.let { r ->
                    if (r.error != null) {
                        "Last restore did not complete (${BackupRepository.formatDateTime(r.appliedAt)}): ${r.error}"
                    } else {
                        val made = if (r.backupCreatedAt > 0) ", backup made ${BackupRepository.formatDateTime(r.backupCreatedAt)}" else ""
                        "Last restore: ${BackupRepository.formatDateTime(r.appliedAt)} from ${r.source}$made"
                    }
                }
            )

            BackupSection(title = "FULL BACKUP") {
                BackupSectionText(
                    "Saves everything (products, stock, IMEIs, sales, purchases, customers, suppliers, " +
                        "payments and settings) into one .zip file."
                )
                BackupTipRow(
                    icon = Icons.Default.CloudUpload,
                    text = "Tip: when the save screen opens, tap the ☰ menu and choose Google Drive. " +
                        "A backup kept only on this phone is lost if the phone is lost or broken."
                )
                Button(
                    onClick = { launchPicker { createBackupLauncher.launch(viewModel.suggestedBackupFileName()) } },
                    enabled = !state.isBusy,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                        .testTag("create_backup_button")
                ) {
                    Icon(Icons.Default.Backup, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Create backup")
                }
            }

            if (canRestore) BackupSection(title = "RESTORE") {
                BackupSectionText(
                    "Replace the data on this phone with a backup file (from Google Drive, Downloads, etc.). " +
                        "You will see what is inside the file before anything changes."
                )
                OutlinedButton(
                    onClick = { launchPicker { openBackupLauncher.launch(BackupRepository.OPEN_MIME_TYPES) } },
                    enabled = !state.isBusy,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                        .testTag("restore_backup_button")
                ) {
                    Icon(Icons.Default.Restore, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Restore from backup file")
                }
            }

            BackupSection(title = "EXPORT FOR EXCEL") {
                BackupSectionText(
                    "Products, customers, suppliers, sales and purchases as CSV files in one .zip. " +
                        "Opens in Excel or Google Sheets, and Bangla names show correctly. " +
                        "This is for reports only; it can't be restored."
                )
                if (state.lastExportAt > 0L) {
                    BackupSectionText("Last export: ${BackupRepository.formatDateTime(state.lastExportAt)}", muted = true)
                }
                OutlinedButton(
                    onClick = { launchPicker { exportCsvLauncher.launch(viewModel.suggestedExportFileName()) } },
                    enabled = !state.isBusy,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                        .testTag("export_csv_button")
                ) {
                    Icon(Icons.Default.TableChart, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Export CSV for Excel")
                }
            }

            BackupSection(title = "SAFETY COPIES ON THIS PHONE") {
                BackupSectionText(
                    "Made automatically before a restore or a reset; the newest ${BackupRepository.MAX_SAFETY_COPIES} are kept. " +
                        "They stay on this phone, so also use \"Create backup\"."
                )
                if (state.safetyCopies.isEmpty()) {
                    EmptyStateCard(
                        title = "No safety copies yet",
                        description = "One is saved here automatically before every restore or reset.",
                        icon = Icons.Default.Shield,
                        modifier = Modifier.padding(16.dp)
                    )
                } else {
                    state.safetyCopies.forEachIndexed { index, copy ->
                        if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                        BackupSafetyCopyRow(
                            copy = copy,
                            enabled = !state.isBusy,
                            showRestore = canRestore,
                            onRestore = { viewModel.inspectSafetyCopy(copy) },
                            onSave = {
                                safetyCopyToSave = copy.path
                                launchPicker { saveSafetyCopyLauncher.launch(copy.suggestedFileName) }
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
private fun BackupRestoreConfirmDialog(
    inspection: BackupInspection,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    var understood by remember(inspection) { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Replace all data with this backup?",
                color = MaterialTheme.colorScheme.error,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                BackupDetailLine("File", inspection.sourceLabel)
                BackupDetailLine("Made on", BackupRepository.formatDateTime(inspection.createdAt))
                if (inspection.shopName.isNotBlank()) BackupDetailLine("Shop", inspection.shopName)
                BackupDetailLine("Contains", BackupRepository.summarizeCounts(inspection.counts).ifBlank { "No records" })
                if (inspection.needsUpgrade) {
                    BackupDetailLine(
                        "Note",
                        "Made by an older app version (database v${inspection.schemaVersion}); it will be upgraded to v${inspection.currentSchemaVersion}."
                    )
                }

                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "This replaces ALL data on this phone.",
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Sales, stock changes and customers entered after this backup was made will be gone.",
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
                Text(
                    text = "A safety copy of the current data is saved first, so you can undo this from \"Safety copies\". " +
                        "The app then restarts; sign in with the PIN that was in use when the backup was made.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { understood = !understood }
                        .testTag("restore_understood_row"),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(checked = understood, onCheckedChange = { understood = it })
                    Text("I understand that all current data will be replaced.", style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = understood,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.testTag("confirm_restore_button")
            ) {
                Text("Replace & restart")
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun BackupDetailLine(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(76.dp)
        )
        Text(text = value, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun BackupLastBackupCard(lastBackupAt: Long, lastBackupName: String?, lastRestoreText: String?) {
    val now = System.currentTimeMillis()
    val stale = lastBackupAt <= 0L || now - lastBackupAt > STALE_BACKUP_MS
    val accent = if (stale) WarningAmber else StockGreen
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = accent.copy(alpha = 0.12f))
    ) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
            Icon(
                imageVector = if (stale) Icons.Default.Warning else Icons.Default.CheckCircle,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(28.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Last backup",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = if (lastBackupAt > 0L) BackupRepository.formatDateTime(lastBackupAt) else "Never",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                )
                if (lastBackupAt > 0L && !lastBackupName.isNullOrBlank()) {
                    Text(
                        text = lastBackupName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (stale) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = if (lastBackupAt <= 0L) "No backup yet. Make one now and keep it on Google Drive."
                        else "More than a week old. Make a fresh backup.",
                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold)
                    )
                }
                if (lastRestoreText != null) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = lastRestoreText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun BackupPendingRestoreCard(onRestartNow: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = WarningAmber.copy(alpha = 0.15f))
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("A restore is waiting", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
            Text(
                "It is applied the next time the app starts. Anything entered before then will be replaced.",
                style = MaterialTheme.typography.bodySmall
            )
            Button(onClick = onRestartNow) { Text("Restart now") }
        }
    }
}

@Composable
private fun BackupBusyCard(text: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun BackupMessageCard(message: BackupMessage, onDismiss: () -> Unit) {
    val accent = if (message.isError) AlertRose else StockGreen
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(if (message.isError) "backup_error_message" else "backup_success_message"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = accent.copy(alpha = 0.12f))
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (message.isError) Icons.Default.Error else Icons.Default.CheckCircle,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = message.text,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onDismiss) {
                Icon(Icons.Default.Close, contentDescription = "Dismiss")
            }
        }
    }
}

@Composable
private fun BackupSafetyCopyRow(
    copy: SafetyCopy,
    enabled: Boolean,
    onRestore: () -> Unit,
    onSave: () -> Unit,
    showRestore: Boolean = true
) {
    Column(modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Default.History,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = BackupRepository.formatDateTime(copy.createdAt),
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold)
                )
                Text(
                    text = "${copy.reason} · ${BackupRepository.formatSize(copy.sizeBytes)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                val counts = BackupRepository.summarizeCounts(copy.counts)
                if (counts.isNotBlank()) {
                    Text(
                        text = counts,
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onSave, enabled = enabled) {
                Icon(Icons.Default.SaveAlt, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Save to file")
            }
            if (showRestore) TextButton(
                onClick = onRestore,
                enabled = enabled,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
            ) {
                Icon(Icons.Default.Restore, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Restore")
            }
        }
    }
}

@Composable
private fun BackupSection(title: String, content: @Composable ColumnScope.() -> Unit) {
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
            Column(modifier = Modifier.padding(top = 4.dp), content = content)
        }
    }
}

@Composable
private fun BackupSectionText(text: String, muted: Boolean = false) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = if (muted) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp)
    )
}

@Composable
private fun BackupTipRow(icon: ImageVector, text: String, tint: Color = MaterialTheme.colorScheme.primary) {
    Row(
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp),
        verticalAlignment = Alignment.Top
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}
