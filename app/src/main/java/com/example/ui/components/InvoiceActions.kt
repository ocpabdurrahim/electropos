package com.example.ui.components

import android.content.ActivityNotFoundException
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.core.invoice.InvoiceExporter
import com.example.core.invoice.InvoiceFormat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private enum class InvoiceAction(val progress: String) {
    SHARE_PDF("Preparing PDF…"),
    PRINT("Preparing to print…"),
    SHARE_TEXT("Preparing text…")
}

/** Share PDF / Print / Share as text for one sale, with an A4 / A5 / 80 mm receipt choice. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun InvoiceActionsCard(
    exporter: InvoiceExporter,
    saleId: Long,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var formatName by rememberSaveable { mutableStateOf(InvoiceFormat.A4.name) }
    val format = InvoiceFormat.entries.firstOrNull { it.name == formatName } ?: InvoiceFormat.A4
    var busy by remember { mutableStateOf<InvoiceAction?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    fun run(action: InvoiceAction, work: suspend () -> Unit) {
        if (busy != null) return
        busy = action
        error = null
        scope.launch {
            try {
                work()
            } catch (e: CancellationException) {
                throw e
            } catch (e: ActivityNotFoundException) {
                error = "No app found to handle this. Install WhatsApp, a PDF viewer or a print service."
            } catch (e: Exception) {
                error = e.message?.takeIf { it.isNotBlank() } ?: "Could not prepare the invoice"
            } finally {
                busy = null
            }
        }
    }

    Card(modifier = modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Invoice", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.size(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                InvoiceFormat.entries.forEach { f ->
                    FilterChip(
                        selected = f == format,
                        onClick = { formatName = f.name },
                        enabled = busy == null,
                        label = { Text(f.label) },
                        modifier = Modifier.testTag("invoice_format_${f.name}")
                    )
                }
            }
            Spacer(Modifier.size(4.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Button(
                    onClick = {
                        run(InvoiceAction.SHARE_PDF) {
                            context.startActivity(exporter.createPdfShareIntent(saleId, format))
                        }
                    },
                    enabled = busy == null,
                    modifier = Modifier.testTag("invoice_share_pdf")
                ) { ButtonContent(Icons.Default.Share, "Share PDF") }
                OutlinedButton(
                    onClick = { run(InvoiceAction.PRINT) { exporter.print(context, saleId, format) } },
                    enabled = busy == null,
                    modifier = Modifier.testTag("invoice_print")
                ) { ButtonContent(Icons.Default.Print, "Print") }
                OutlinedButton(
                    onClick = {
                        run(InvoiceAction.SHARE_TEXT) {
                            context.startActivity(exporter.createTextShareIntent(saleId))
                        }
                    },
                    enabled = busy == null,
                    modifier = Modifier.testTag("invoice_share_text")
                ) { ButtonContent(Icons.Default.Sms, "Share as text") }
            }
            busy?.let { action ->
                Spacer(Modifier.size(8.dp))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(action.progress, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
            }
            error?.let { message ->
                Spacer(Modifier.size(8.dp))
                Text(
                    message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag("invoice_error")
                )
            }
        }
    }
}

@Composable
private fun ButtonContent(icon: ImageVector, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(label)
    }
}
