package com.example.core.invoice

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintJob
import android.print.PrintManager
import androidx.core.content.FileProvider
import com.example.data.local.database.AppDatabase
import com.example.data.local.entities.ShopProfileEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream

/** A generated invoice PDF in cacheDir/invoices/, shareable through [uri]. */
data class InvoicePdf(
    val file: File,
    val uri: Uri,
    val pageCount: Int,
    val format: InvoiceFormat,
    val invoiceNumber: String,
    /** Height of the first page in PDF points (the receipt's paper length). */
    val firstPageHeight: Int = format.pageHeight
)

/**
 * Loads a sale and turns it into a PDF (share / print) or a plain-text summary.
 *
 * Create once (e.g. in AppContainer) with the application context. [terms] supplies the optional
 * terms printed under "Thank you" (the shop profile has no such field yet).
 */
class InvoiceExporter(
    context: Context,
    private val database: AppDatabase,
    private val terms: (ShopProfileEntity) -> String = { "" },
    private val generator: InvoicePdfGenerator = InvoicePdfGenerator()
) {
    private val appContext: Context = context.applicationContext ?: context

    val authority: String get() = "${appContext.packageName}.fileprovider"

    /** Content Uri for a generated file (replaceable in tests: androidx FileProvider can't match Windows paths). */
    internal var uriForFile: (File) -> Uri = { file -> FileProvider.getUriForFile(appContext, authority, file) }

    /** Everything printed on the invoice for [saleId]. */
    suspend fun loadInvoiceData(saleId: Long): InvoiceData = withContext(Dispatchers.IO) {
        val sale = database.saleDao().getSaleById(saleId)
            ?: throw IllegalArgumentException("Sale not found (id $saleId)")
        val shop = database.settingsAndAuditDao().getShopProfileSync() ?: ShopProfileEntity()
        val items = database.saleDao().getSaleItems(saleId).sortedBy { it.id }
        val productDao = database.productDao()
        val variantDao = database.productVariantDao()
        val lines = items.map { item ->
            val product = productDao.getProductById(item.productId)
            val variantName = if (item.variantId > 0) variantDao.getVariantById(item.variantId)?.variantName.orEmpty() else ""
            val name = if (variantName.isNotBlank() && !item.productName.contains(variantName, ignoreCase = true)) {
                "${item.productName} ($variantName)"
            } else {
                item.productName
            }
            InvoiceLine(
                name = name,
                serials = InvoiceText.parseSerials(item.serialImeisJson),
                serialLabel = InvoiceText.serialLabel(item.trackingType),
                quantity = item.quantity,
                unitPrice = item.unitPrice,
                discount = item.discount,
                tax = item.tax,
                lineTotal = item.totalPrice,
                warranty = InvoiceText.warrantyLine(product, sale.saleDate)
            )
        }
        val returns = database.salesReturnDao().getReturnsForSaleSync(saleId).sortedBy { it.returnDate }
        val customerAddress = sale.customerId?.takeIf { it > 0 }
            ?.let { database.customerDao().getCustomerById(it)?.address.orEmpty().trim() }
            .orEmpty()
        InvoiceData(
            shop = shop,
            sale = sale,
            lines = lines,
            returns = returns,
            customerAddress = customerAddress,
            terms = runCatching { terms(shop) }.getOrDefault(""),
            logo = loadLogo(shop.logoUri)
        )
    }

    /** (a) Writes the PDF into cacheDir/invoices/ and returns it with a FileProvider content Uri. */
    suspend fun exportPdf(saleId: Long, format: InvoiceFormat): InvoicePdf {
        val data = loadInvoiceData(saleId)
        return withContext(Dispatchers.IO) {
            val dir = File(appContext.cacheDir, INVOICE_DIR).apply { mkdirs() }
            cleanOldFiles(dir)
            val safeNumber = data.sale.invoiceNumber.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "invoice_$saleId" }
            val suffix = when (format) {
                InvoiceFormat.A4 -> "A4"
                InvoiceFormat.A5 -> "A5"
                InvoiceFormat.RECEIPT_80MM -> "receipt"
            }
            val file = File(dir, "${safeNumber}_$suffix.pdf")
            val temp = File(dir, file.name + ".tmp")
            val pages = try {
                FileOutputStream(temp).use { out -> generator.writePdf(data, format, out) }
            } catch (e: Throwable) {
                temp.delete()
                throw e
            }
            if (file.exists()) file.delete()
            if (!temp.renameTo(file)) {
                temp.copyTo(file, overwrite = true)
                temp.delete()
            }
            val uri = uriForFile(file)
            InvoicePdf(file, uri, pages.size, format, data.sale.invoiceNumber, pages.firstOrNull()?.height ?: format.pageHeight)
        }
    }

    /** (b) A chooser for sending the PDF (WhatsApp, Gmail, Drive...). Start it from an Activity. */
    suspend fun createPdfShareIntent(saleId: Long, format: InvoiceFormat): Intent =
        pdfShareIntent(exportPdf(saleId, format))

    fun pdfShareIntent(pdf: InvoicePdf): Intent {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, pdf.uri)
            putExtra(Intent.EXTRA_SUBJECT, "Invoice ${pdf.invoiceNumber}")
            putExtra(Intent.EXTRA_TITLE, pdf.file.name)
            clipData = ClipData.newRawUri(pdf.file.name, pdf.uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, "Share invoice ${pdf.invoiceNumber}").apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /**
     * (c) Opens the system print dialog for the invoice. [activityContext] must be (or wrap) an
     * Activity: PrintManager refuses to print from the application context.
     */
    suspend fun print(activityContext: Context, saleId: Long, format: InvoiceFormat): PrintJob {
        val activity = activityContext.findActivity()
            ?: throw IllegalStateException("Printing needs an Activity context")
        val pdf = exportPdf(saleId, format)
        return withContext(Dispatchers.Main.immediate) {
            val printManager = activity.getSystemService(Context.PRINT_SERVICE) as? PrintManager
                ?: throw IllegalStateException("Printing is not available on this device")
            val jobName = "Invoice ${pdf.invoiceNumber}"
            printManager.print(jobName, PdfFilePrintAdapter(pdf.file, pdf.file.name, pdf.pageCount), printAttributes(pdf))
        }
    }

    /** (d) Plain-text invoice for "Share as text" (WhatsApp / SMS). */
    suspend fun buildTextSummary(saleId: Long): String = InvoiceText.summary(loadInvoiceData(saleId))

    suspend fun createTextShareIntent(saleId: Long): Intent {
        val data = loadInvoiceData(saleId)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, InvoiceText.summary(data))
            putExtra(Intent.EXTRA_SUBJECT, "Invoice ${data.sale.invoiceNumber}")
        }
        return Intent.createChooser(send, "Share invoice ${data.sale.invoiceNumber}")
    }

    // ---------------------------------------------------------------------------------------------

    private fun printAttributes(pdf: InvoicePdf): PrintAttributes {
        val builder = PrintAttributes.Builder()
        when (pdf.format) {
            InvoiceFormat.A4 -> builder.setMediaSize(PrintAttributes.MediaSize.ISO_A4)
            InvoiceFormat.A5 -> builder.setMediaSize(PrintAttributes.MediaSize.ISO_A5)
            InvoiceFormat.RECEIPT_80MM -> {
                // 80 mm = 3150 mils; height of the (first) receipt page, points -> mils
                val heightMils = (pdf.firstPageHeight / 72f * 1000f).toInt().coerceAtLeast(1000)
                builder.setMediaSize(PrintAttributes.MediaSize("ROLL_80MM", "80 mm receipt", 3150, heightMils))
                builder.setColorMode(PrintAttributes.COLOR_MODE_MONOCHROME)
                builder.setMinMargins(PrintAttributes.Margins.NO_MARGINS)
            }
        }
        return builder.build()
    }

    private fun cleanOldFiles(dir: File) {
        val cutoff = System.currentTimeMillis() - CACHE_MAX_AGE_MS
        dir.listFiles()?.forEach { f -> if (f.isFile && f.lastModified() < cutoff) f.delete() }
    }

    /** Logo from the shop profile (content:// or file path); null when missing or unreadable. */
    private fun loadLogo(uriString: String): Bitmap? {
        if (uriString.isBlank()) return null
        return try {
            val uri = Uri.parse(uriString)
            val open: () -> InputStream? = {
                when (uri.scheme) {
                    null, "", "file" -> FileInputStream(uri.path ?: uriString)
                    else -> appContext.contentResolver.openInputStream(uri)
                }
            }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            open()?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= LOGO_MAX_PX) sample *= 2
            open()?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
        } catch (_: Throwable) {
            null
        }
    }

    private fun Context.findActivity(): Activity? {
        var current: Context? = this
        while (current != null) {
            if (current is Activity) return current
            current = (current as? ContextWrapper)?.baseContext
        }
        return null
    }

    /** Serves an already generated PDF to the print framework. */
    private class PdfFilePrintAdapter(
        private val file: File,
        private val documentName: String,
        private val pageCount: Int
    ) : PrintDocumentAdapter() {
        override fun onLayout(
            oldAttributes: PrintAttributes?,
            newAttributes: PrintAttributes?,
            cancellationSignal: CancellationSignal?,
            callback: LayoutResultCallback,
            extras: Bundle?
        ) {
            if (cancellationSignal?.isCanceled == true) {
                callback.onLayoutCancelled()
                return
            }
            val info = PrintDocumentInfo.Builder(documentName)
                .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                .setPageCount(if (pageCount > 0) pageCount else PrintDocumentInfo.PAGE_COUNT_UNKNOWN)
                .build()
            callback.onLayoutFinished(info, oldAttributes == null || oldAttributes != newAttributes)
        }

        override fun onWrite(
            pages: Array<out PageRange>?,
            destination: ParcelFileDescriptor,
            cancellationSignal: CancellationSignal?,
            callback: WriteResultCallback
        ) {
            try {
                FileInputStream(file).use { input ->
                    FileOutputStream(destination.fileDescriptor).use { output -> input.copyTo(output) }
                }
                if (cancellationSignal?.isCanceled == true) callback.onWriteCancelled()
                else callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
            } catch (e: Exception) {
                callback.onWriteFailed(e.message ?: "Could not write the invoice")
            }
        }
    }

    companion object {
        const val INVOICE_DIR = "invoices"
        private const val CACHE_MAX_AGE_MS = 24L * 60L * 60L * 1000L
        private const val LOGO_MAX_PX = 320
    }
}
