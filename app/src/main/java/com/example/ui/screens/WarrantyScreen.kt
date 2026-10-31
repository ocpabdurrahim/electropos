package com.example.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.core.utils.DateTimeUtils
import com.example.data.local.entities.WarrantyRecordEntity
import com.example.data.repository.WarrantyRepository
import com.example.ui.components.AppTopBar
import com.example.ui.components.EmptyStateCard
import com.example.ui.theme.AlertRose
import com.example.ui.theme.StockGreen
import com.example.ui.theme.WarningAmber
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

private const val DAY_MS = 24L * 60L * 60L * 1000L

enum class WarrantyFilter(val label: String) {
    ACTIVE("Active"),
    EXPIRING("Expiring in 30 days"),
    EXPIRED("Expired"),
    CLAIMS("Claims"),
    ALL("All")
}

data class WarrantyUiState(
    val records: List<WarrantyRecordEntity> = emptyList(),
    val filter: WarrantyFilter = WarrantyFilter.ACTIVE,
    val query: String = "",
    val counts: Map<WarrantyFilter, Int> = emptyMap()
)

class WarrantyViewModel(private val warrantyRepository: WarrantyRepository) : ViewModel() {
    private val filter = MutableStateFlow(WarrantyFilter.ACTIVE)
    private val query = MutableStateFlow("")

    val uiState: StateFlow<WarrantyUiState> = combine(warrantyRepository.allWarranties, filter, query) { all, f, q ->
        val now = System.currentTimeMillis()
        fun matches(r: WarrantyRecordEntity, which: WarrantyFilter) = when (which) {
            WarrantyFilter.ACTIVE -> r.status == "ACTIVE" && r.warrantyEndDate >= now
            WarrantyFilter.EXPIRING -> r.status == "ACTIVE" && r.warrantyEndDate in now..(now + 30 * DAY_MS)
            WarrantyFilter.EXPIRED -> (r.status == "ACTIVE" && r.warrantyEndDate < now) || r.status == "EXPIRED"
            WarrantyFilter.CLAIMS -> r.status in setOf("CLAIMED", "REPAIRED", "REPLACED")
            // Returned or cancelled sales no longer carry a warranty, so they are only shown under "All"
            WarrantyFilter.ALL -> true
        }
        val text = q.trim()
        val searched = if (text.isEmpty()) all else all.filter {
            it.serialOrImei.contains(text, true) || it.invoiceNumber.contains(text, true) ||
                it.customerName.contains(text, true) || it.customerPhone.contains(text) || it.productName.contains(text, true)
        }
        WarrantyUiState(
            // A search looks through everything; otherwise the chosen tab
            records = (if (text.isEmpty()) searched.filter { matches(it, f) } else searched).sortedBy { it.warrantyEndDate },
            filter = f,
            query = q,
            counts = WarrantyFilter.values().associateWith { which -> all.count { matches(it, which) } }
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), WarrantyUiState())

    fun setFilter(f: WarrantyFilter) { filter.value = f }
    fun setQuery(q: String) { query.value = q }

    /** Records a claim step (received for repair, repaired, replaced) and keeps a dated note trail. */
    fun recordClaim(record: WarrantyRecordEntity, status: String, note: String, onDone: () -> Unit) {
        viewModelScope.launch {
            val line = "${DateTimeUtils.formatDateTime(System.currentTimeMillis())}: $status${if (note.isNotBlank()) " - ${note.trim()}" else ""}"
            val notes = listOf(record.claimNotes, line).filter { it.isNotBlank() }.joinToString("\n")
            warrantyRepository.updateWarrantyStatus(record, status, notes)
            onDone()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WarrantyScreen(
    viewModel: WarrantyViewModel,
    onNavigateBack: () -> Unit,
    onOpenSale: (Long) -> Unit
) {
    val state by viewModel.uiState.collectAsState()
    var selected by remember { mutableStateOf<WarrantyRecordEntity?>(null) }

    Scaffold(
        topBar = { AppTopBar(title = "Warranty", subtitle = "Check a phone's warranty or record a claim", onBackClick = onNavigateBack) }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = state.query,
                onValueChange = viewModel::setQuery,
                placeholder = { Text("IMEI, serial, invoice, customer or phone") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag("warranty_search")
            )
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(WarrantyFilter.values().toList()) { f ->
                    FilterChip(
                        selected = state.filter == f && state.query.isBlank(),
                        onClick = { viewModel.setFilter(f) },
                        label = { Text("${f.label} (${state.counts[f] ?: 0})") }
                    )
                }
            }
            if (state.records.isEmpty()) {
                EmptyStateCard(
                    message = if (state.query.isBlank()) "Nothing here yet. Warranties appear when products with a warranty are sold."
                    else "No warranty matches '${state.query}'.",
                    icon = Icons.Default.VerifiedUser,
                    modifier = Modifier.padding(16.dp)
                )
            }
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(state.records, key = { it.id }) { record ->
                    WarrantyRow(record = record, onClick = { selected = record })
                }
            }
        }
    }

    selected?.let { record ->
        WarrantyDetailDialog(
            record = record,
            onDismiss = { selected = null },
            onOpenSale = { selected = null; onOpenSale(record.saleId) },
            onClaim = { status, note -> viewModel.recordClaim(record, status, note) { selected = null } }
        )
    }
}

@Composable
private fun WarrantyRow(record: WarrantyRecordEntity, onClick: () -> Unit) {
    val now = System.currentTimeMillis()
    val daysLeft = ((record.warrantyEndDate - now) / DAY_MS).toInt()
    val (statusText, statusColor) = when {
        record.status in setOf("RETURNED", "CANCELLED") -> "Sale ${record.status.lowercase()}" to MaterialTheme.colorScheme.outline
        record.status != "ACTIVE" -> record.status.lowercase().replaceFirstChar { it.uppercase() } to WarningAmber
        daysLeft < 0 -> "Expired" to AlertRose
        daysLeft <= 30 -> "$daysLeft days left" to WarningAmber
        else -> "$daysLeft days left" to StockGreen
    }
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(record.productName, fontWeight = FontWeight.Bold)
                if (record.serialOrImei.isNotBlank()) {
                    Text(record.serialOrImei, style = MaterialTheme.typography.bodySmall)
                }
                Text(
                    "${record.customerName} • #${record.invoiceNumber} • until ${DateTimeUtils.formatDate(record.warrantyEndDate)}",
                    style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                )
            }
            Text(statusText, color = statusColor, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WarrantyDetailDialog(
    record: WarrantyRecordEntity,
    onDismiss: () -> Unit,
    onOpenSale: () -> Unit,
    onClaim: (status: String, note: String) -> Unit
) {
    var status by remember { mutableStateOf("CLAIMED") }
    var note by remember { mutableStateOf("") }
    val canClaim = record.status !in setOf("RETURNED", "CANCELLED")

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(record.productName, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (record.serialOrImei.isNotBlank()) Text("IMEI / Serial: ${record.serialOrImei}")
                Text("Customer: ${record.customerName}${if (record.customerPhone.isNotBlank()) " (${record.customerPhone})" else ""}")
                Text("Invoice: #${record.invoiceNumber}")
                Text("Warranty: ${DateTimeUtils.formatDate(record.warrantyStartDate)} → ${DateTimeUtils.formatDate(record.warrantyEndDate)}")
                Text("Status: ${record.status}")
                if (record.claimNotes.isNotBlank()) {
                    Text("History:", fontWeight = FontWeight.SemiBold)
                    Text(record.claimNotes, style = MaterialTheme.typography.bodySmall)
                }
                if (canClaim) {
                    HorizontalDivider()
                    Text("Record a claim step", fontWeight = FontWeight.SemiBold)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("CLAIMED" to "Received", "REPAIRED" to "Repaired", "REPLACED" to "Replaced", "ACTIVE" to "Back to active")
                            .forEach { (code, label) ->
                                FilterChip(selected = status == code, onClick = { status = code }, label = { Text(label) })
                            }
                    }
                    OutlinedTextField(note, { note = it }, label = { Text("Note (problem, service centre...)") }, modifier = Modifier.fillMaxWidth())
                }
                TextButton(onClick = onOpenSale) { Text("Open invoice") }
            }
        },
        confirmButton = {
            if (canClaim) Button(onClick = { onClaim(status, note) }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}
