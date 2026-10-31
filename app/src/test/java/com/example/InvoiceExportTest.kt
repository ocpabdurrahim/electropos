package com.example

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.core.invoice.InvoiceExporter
import com.example.core.invoice.InvoiceFormat
import com.example.core.invoice.InvoicePdfGenerator
import com.example.core.utils.CurrencyUtils
import com.example.data.local.database.AppDatabase
import com.example.data.local.entities.CustomerEntity
import com.example.data.local.entities.ProductEntity
import com.example.data.local.entities.SaleEntity
import com.example.data.local.entities.SaleItemEntity
import com.example.data.local.entities.SalesReturnEntity
import com.example.data.local.entities.ShopProfileEntity
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import java.io.File
import java.io.OutputStream

/**
 * Invoice PDF + text export. Robolectric has no native PdfDocument, so [ShadowBitmapPdfDocument]
 * renders each page onto a real (native-graphics) bitmap instead; the generator's layout and all
 * StaticLayout text drawing still run for real.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], shadows = [InvoiceExportTest.ShadowBitmapPdfDocument::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class InvoiceExportTest {

    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var exporter: InvoiceExporter

    private val bnCustomer = "রহিম উদ্দিন"
    private val imeis = listOf("356789012345671", "356789012345672")

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        exporter = InvoiceExporter(context, database, terms = { "Goods once sold are not refundable without the invoice." })
        if (File.separatorChar == '\\') {
            // androidx FileProvider matches its roots with '/', which never matches a Windows path under
            // Robolectric; on a device (and on Linux CI) the real FileProvider is used.
            exporter.uriForFile = { file ->
                Uri.Builder().scheme(ContentResolver.SCHEME_CONTENT).authority(exporter.authority)
                    .appendPath(InvoiceExporter.INVOICE_DIR).appendPath(file.name).build()
            }
        }
    }

    @After
    fun tearDown() {
        database.close()
    }

    /** 2 phones with IMEIs (line discount + 5% tax), 1 charger, an invoice discount and one return. */
    private suspend fun seedSale(extraItems: Int = 0, extraSerials: Int = 0): Long {
        database.settingsAndAuditDao().saveShopProfile(
            ShopProfileEntity(
                shopName = "মোবাইল জোন / Mobile Zone",
                address = "Shop 12, Bashundhara City, Dhaka",
                phone = "01711000000",
                alternativePhone = "01811000000",
                email = "mobilezone@example.com"
            )
        )
        val phoneId = database.productDao().insertProduct(
            ProductEntity(
                name = "Samsung Galaxy A15 8/128GB Light Blue", trackingType = "IMEI", retailPrice = 18000.0,
                hasWarranty = true, warrantyPeriod = 12, warrantyUnit = "MONTHS", warrantyPeriodMonths = 12, warrantyType = "SERVICE"
            )
        )
        val chargerId = database.productDao().insertProduct(ProductEntity(name = "USB-C Charger 25W", retailPrice = 1500.0))
        val customerId = database.customerDao().insertCustomer(
            CustomerEntity(name = bnCustomer, phone = "01712345678", address = "মিরপুর ১০, ঢাকা")
        )

        val phoneTotal = 2 * 18000.0 - 500.0 + 1775.0 // 37,275
        val extraTotal = extraItems * 250.0
        val subTotal = 36000.0 + 1500.0 + extraTotal
        val total = phoneTotal + 1500.0 + extraTotal - 200.0
        val saleId = database.saleDao().insertSale(
            SaleEntity(
                invoiceNumber = "INV-1001", customerId = customerId, customerName = bnCustomer, customerPhone = "01712345678",
                subTotal = subTotal, itemDiscount = 500.0, invoiceDiscount = 200.0, discountAmount = 700.0,
                taxAmount = 1775.0, totalAmount = total, paidAmount = 30000.0, dueAmount = total - 30000.0,
                paymentStatus = "PARTIAL", paymentMethod = "BKASH"
            )
        )
        val serials = imeis + (1..extraSerials).map { "35000000000%04d".format(it) }
        val items = mutableListOf(
            SaleItemEntity(
                saleId = saleId, productId = phoneId, productName = "Samsung Galaxy A15 8/128GB Light Blue",
                quantity = 2, unitPrice = 18000.0, unitCostAtSale = 15000.0, discount = 500.0, tax = 1775.0,
                totalPrice = phoneTotal, trackingType = "IMEI", serialImeisJson = JSONArray(serials).toString()
            ),
            SaleItemEntity(
                saleId = saleId, productId = chargerId, productName = "USB-C Charger 25W",
                quantity = 1, unitPrice = 1500.0, unitCostAtSale = 900.0, totalPrice = 1500.0
            )
        )
        repeat(extraItems) { i ->
            items += SaleItemEntity(
                saleId = saleId, productId = chargerId, productName = "Accessory item number ${i + 1} with a rather long descriptive name",
                quantity = 1, unitPrice = 250.0, unitCostAtSale = 100.0, totalPrice = 250.0
            )
        }
        database.saleDao().insertSaleItems(items)
        database.salesReturnDao().insertReturn(
            SalesReturnEntity(
                returnNumber = "SRT-0001", saleId = saleId, invoiceNumber = "INV-1001", customerId = customerId,
                customerName = bnCustomer, totalAmount = 1500.0, refundAmount = 1500.0
            )
        )
        return saleId
    }

    @Test
    fun a4PdfIsWrittenToCacheAndSharedThroughFileProvider() = runBlocking {
        val saleId = seedSale()
        val pdf = exporter.exportPdf(saleId, InvoiceFormat.A4)

        assertTrue("PDF has pages", pdf.pageCount >= 1)
        assertEquals(pdf.pageCount, ShadowBitmapPdfDocument.lastPageCount)
        assertTrue("PDF file exists", pdf.file.exists())
        assertTrue("PDF is not empty", pdf.file.length() > 0)
        assertEquals("invoices", pdf.file.parentFile?.name)
        assertEquals(context.cacheDir.canonicalPath, pdf.file.parentFile?.parentFile?.canonicalPath)
        assertEquals(ContentResolver.SCHEME_CONTENT, pdf.uri.scheme)
        assertEquals("${context.packageName}.fileprovider", pdf.uri.authority)
        assertTrue(pdf.uri.toString().endsWith("/INV-1001_A4.pdf"))
        val provider = context.packageManager.resolveContentProvider(exporter.authority, PackageManager.GET_META_DATA)
        assertEquals("androidx.core.content.FileProvider", provider?.name)
        assertTrue(provider?.metaData?.containsKey("android.support.FILE_PROVIDER_PATHS") == true)

        val chooser = exporter.pdfShareIntent(pdf)
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        @Suppress("DEPRECATION")
        val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("application/pdf", send.type)
        @Suppress("DEPRECATION")
        assertEquals(pdf.uri, send.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
        assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
    }

    @Test
    fun receiptAndA5AreGeneratedAndReceiptGrowsWithItems() = runBlocking {
        val saleId = seedSale()
        val receipt = exporter.exportPdf(saleId, InvoiceFormat.RECEIPT_80MM)
        assertEquals(1, receipt.pageCount)
        assertTrue(receipt.file.length() > 0)
        assertEquals(227, ShadowBitmapPdfDocument.lastPageWidth)
        val shortHeight = receipt.firstPageHeight

        val a5 = exporter.exportPdf(saleId, InvoiceFormat.A5)
        assertTrue(a5.pageCount >= 1 && a5.file.length() > 0)
        assertTrue("separate files per format", a5.file != receipt.file)

        val data = exporter.loadInvoiceData(saleId)
        val longer = data.copy(lines = data.lines + List(40) { data.lines[1].copy(name = "Extra cable ${it + 1}") })
        val longHeight = InvoicePdfGenerator().layout(longer, InvoiceFormat.RECEIPT_80MM).first().height
        assertTrue("receipt height grows ($shortHeight -> $longHeight)", longHeight > shortHeight + 200)
    }

    @Test
    fun manyItemsAndSerialsSpreadOverSeveralA4Pages() = runBlocking {
        val saleId = seedSale(extraItems = 70, extraSerials = 300)
        val pdf = exporter.exportPdf(saleId, InvoiceFormat.A4)
        assertTrue("expected several pages, got ${pdf.pageCount}", pdf.pageCount >= 3)
        assertEquals(pdf.pageCount, ShadowBitmapPdfDocument.lastPageCount)
        assertTrue(pdf.file.length() > 0)

        val pages = InvoicePdfGenerator().layout(exporter.loadInvoiceData(saleId), InvoiceFormat.A4)
        pages.forEach { page ->
            assertEquals(595, page.width)
            assertEquals(842, page.height)
            assertTrue(page.blockCount > 0)
        }
    }

    @Test
    fun textSummaryHasCustomerItemsAndTotals() = runBlocking {
        val saleId = seedSale()
        val text = exporter.buildTextSummary(saleId)
        val sale = database.saleDao().getSaleById(saleId)!!

        assertTrue(text.contains("INV-1001"))
        assertTrue(text.contains(bnCustomer))
        assertTrue(text.contains("মোবাইল জোন"))
        assertTrue(text.contains(imeis[0]) && text.contains(imeis[1]))
        assertTrue(text.contains("Warranty: 12 months service"))
        assertTrue(text.contains("Subtotal: " + CurrencyUtils.format(sale.subTotal, "৳")))
        assertTrue(text.contains("Item discounts: -" + CurrencyUtils.format(500.0, "৳")))
        assertTrue(text.contains("Invoice discount: -" + CurrencyUtils.format(200.0, "৳")))
        assertTrue(text.contains("Tax: " + CurrencyUtils.format(1775.0, "৳")))
        assertTrue(text.contains("Grand Total: " + CurrencyUtils.format(sale.totalAmount, "৳")))
        assertTrue(text.contains("Paid: " + CurrencyUtils.format(30000.0, "৳")))
        assertTrue(text.contains("Due: " + CurrencyUtils.format(sale.dueAmount, "৳")))
        assertTrue(text.contains("SRT-0001"))
        assertTrue(text.contains("Net after returns: " + CurrencyUtils.format(sale.totalAmount - 1500.0, "৳")))
        assertTrue(text.contains("Goods once sold"))

        val share = exporter.createTextShareIntent(saleId)
        @Suppress("DEPRECATION")
        val send = share.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals("text/plain", send.type)
        assertEquals(text, send.getStringExtra(Intent.EXTRA_TEXT))
    }

    /** Stand-in for the native PDF writer: draws every page on a bitmap and writes them as PNGs. */
    @Implements(PdfDocument::class)
    class ShadowBitmapPdfDocument {
        private val infos = mutableListOf<PdfDocument.PageInfo>()
        private val bitmaps = mutableListOf<Bitmap>()
        private var current: Bitmap? = null

        @Implementation
        fun startPage(pageInfo: PdfDocument.PageInfo): PdfDocument.Page {
            val bitmap = Bitmap.createBitmap(pageInfo.pageWidth, pageInfo.pageHeight, Bitmap.Config.ARGB_8888)
            current = bitmap
            val constructor = PdfDocument.Page::class.java.getDeclaredConstructor(Canvas::class.java, PdfDocument.PageInfo::class.java)
            constructor.isAccessible = true
            return constructor.newInstance(Canvas(bitmap), pageInfo)
        }

        @Implementation
        fun finishPage(page: PdfDocument.Page) {
            infos += page.info
            current?.let { bitmaps += it }
            current = null
        }

        @Implementation
        fun getPages(): List<PdfDocument.PageInfo> = infos.toList()

        @Implementation
        fun writeTo(out: OutputStream) {
            lastPageCount = infos.size
            lastPageWidth = infos.firstOrNull()?.pageWidth ?: 0
            out.write("%PDF-1.4\n".toByteArray())
            bitmaps.forEach { it.compress(Bitmap.CompressFormat.PNG, 100, out) }
        }

        @Implementation
        fun close() {
            bitmaps.forEach { it.recycle() }
            bitmaps.clear()
        }

        companion object {
            @JvmStatic var lastPageCount = 0
            @JvmStatic var lastPageWidth = 0
        }
    }
}
