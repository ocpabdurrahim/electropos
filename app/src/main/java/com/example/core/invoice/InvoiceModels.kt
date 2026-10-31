package com.example.core.invoice

import android.graphics.Bitmap
import com.example.core.utils.CurrencyUtils
import com.example.data.local.entities.ProductEntity
import com.example.data.local.entities.SaleEntity
import com.example.data.local.entities.SalesReturnEntity
import com.example.data.local.entities.ShopProfileEntity
import org.json.JSONArray

/** Page formats an invoice can be drawn in. Sizes are PDF points (1/72 inch). */
enum class InvoiceFormat(val label: String, val pageWidth: Int, val pageHeight: Int) {
    A4("A4", 595, 842),
    A5("A5", 420, 595),

    /** 80 mm roll: fixed width, the page grows with the content. */
    RECEIPT_80MM("80 mm receipt", 227, 0);

    val isReceipt: Boolean get() = this == RECEIPT_80MM
}

/** One sold line as it is printed. */
data class InvoiceLine(
    val name: String,
    val serials: List<String> = emptyList(),
    /** "IMEI" or "S/N", printed in front of the serial list. */
    val serialLabel: String = "IMEI",
    val quantity: Int,
    val unitPrice: Double,
    val discount: Double = 0.0,
    val tax: Double = 0.0,
    /** Line total after its discount, including tax (SaleItemEntity.totalPrice). */
    val lineTotal: Double,
    /** e.g. "12 months service, until 31 Oct 2027"; null when the product has none. */
    val warranty: String? = null
)

/** Everything needed to draw one invoice; built by [InvoiceExporter.loadInvoiceData]. */
data class InvoiceData(
    val shop: ShopProfileEntity,
    val sale: SaleEntity,
    val lines: List<InvoiceLine>,
    val returns: List<SalesReturnEntity> = emptyList(),
    val customerAddress: String = "",
    /** Optional terms / conditions printed under "Thank you". */
    val terms: String = "",
    val logo: Bitmap? = null
) {
    val symbol: String get() = shop.currencySymbol.ifBlank { "৳" }
    val returnsTotal: Double get() = returns.sumOf { it.totalAmount }
    val shopName: String get() = shop.shopName.ifBlank { "ElectroPOS" }
    val phones: List<String> get() = listOf(shop.phone, shop.alternativePhone).map { it.trim() }.filter { it.isNotEmpty() }

    /** Discount not covered by the item/invoice split (older sales only stored the sum). */
    val otherDiscount: Double
        get() = (sale.discountAmount - sale.itemDiscount - sale.invoiceDiscount).coerceAtLeast(0.0)

    fun money(amount: Double): String = CurrencyUtils.format(amount, symbol)

    /** Amount without the currency sign, for narrow table cells. */
    fun number(amount: Double): String = CurrencyUtils.format(amount, "").trim()
}

internal object InvoiceText {
    const val THANK_YOU = "Thank you for shopping with us!"

    /** Labels stored in SaleItemEntity.serialImeisJson (a JSON array; tolerates older plain text). */
    fun parseSerials(json: String): List<String> {
        val raw = json.trim()
        if (raw.isEmpty() || raw == "[]") return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).map { array.optString(it).trim() }.filter { it.isNotEmpty() }
        } catch (_: Exception) {
            raw.removePrefix("[").removeSuffix("]").split(',')
                .map { it.trim().trim('"').trim() }
                .filter { it.isNotEmpty() }
        }
    }

    fun serialLabel(trackingType: String): String =
        if (trackingType.contains("IMEI", ignoreCase = true)) "IMEI" else "S/N"

    /** "12 months service, until 31 Oct 2027", or null when the product has no warranty. */
    fun warrantyLine(product: ProductEntity?, saleDate: Long): String? {
        if (product == null || product.warrantyPeriodMonths <= 0) return null
        val (count, unit) = if (product.warrantyPeriod > 0) {
            product.warrantyPeriod to when (product.warrantyUnit.uppercase()) {
                "DAYS" -> "day"
                "YEARS" -> "year"
                else -> "month"
            }
        } else {
            product.warrantyPeriodMonths to "month"
        }
        val period = "$count $unit" + if (count == 1) "" else "s"
        val type = when (product.warrantyType.uppercase()) {
            "SERVICE" -> " service"
            "REPLACEMENT" -> " replacement"
            else -> ""
        }
        val end = com.example.core.utils.WarrantyUtils.endDate(product, saleDate)
        val until = if (end > 0) ", until " + com.example.core.utils.DateTimeUtils.formatDate(end, "dd MMM yyyy") else ""
        return "$period$type$until"
    }

    fun paymentLine(sale: SaleEntity): String {
        val method = sale.paymentMethod.ifBlank { "CASH" }
        return if (sale.paymentStatus.isBlank()) method else "$method (${sale.paymentStatus})"
    }

    /** Plain-text invoice for WhatsApp / SMS ("*bold*" is WhatsApp markup). */
    fun summary(data: InvoiceData): String {
        val sale = data.sale
        val sb = StringBuilder()
        sb.appendLine("*${data.shopName}*")
        if (data.shop.address.isNotBlank()) sb.appendLine(data.shop.address.trim())
        if (data.phones.isNotEmpty()) sb.appendLine("Phone: " + data.phones.joinToString(", "))
        sb.appendLine()
        sb.appendLine("Invoice: ${sale.invoiceNumber}")
        sb.appendLine("Date: " + com.example.core.utils.DateTimeUtils.formatDateTime(sale.saleDate))
        val customer = sale.customerName.ifBlank { "Walk-in Customer" }
        sb.appendLine("Customer: $customer" + if (sale.customerPhone.isNotBlank()) " (${sale.customerPhone})" else "")
        sb.appendLine("Payment: " + paymentLine(sale))
        if (sale.status != "COMPLETED") sb.appendLine("Status: ${sale.status}")
        sb.appendLine()
        sb.appendLine("Items:")
        data.lines.forEachIndexed { index, line ->
            sb.appendLine("${index + 1}. ${line.name}")
            val discount = if (line.discount > 0.005) " - disc. ${data.money(line.discount)}" else ""
            sb.appendLine("   ${line.quantity} x ${data.money(line.unitPrice)}$discount = ${data.money(line.lineTotal)}")
            if (line.serials.isNotEmpty()) sb.appendLine("   ${line.serialLabel}: " + line.serials.joinToString(", "))
            if (line.tax > 0.005) sb.appendLine("   Incl. tax: ${data.money(line.tax)}")
            line.warranty?.let { sb.appendLine("   Warranty: $it") }
        }
        sb.appendLine()
        sb.appendLine("Subtotal: ${data.money(sale.subTotal)}")
        if (sale.itemDiscount > 0.005) sb.appendLine("Item discounts: -${data.money(sale.itemDiscount)}")
        if (sale.invoiceDiscount > 0.005) sb.appendLine("Invoice discount: -${data.money(sale.invoiceDiscount)}")
        if (data.otherDiscount > 0.005) sb.appendLine("Discount: -${data.money(data.otherDiscount)}")
        if (sale.taxAmount > 0.005) sb.appendLine("Tax: ${data.money(sale.taxAmount)}")
        sb.appendLine("*Grand Total: ${data.money(sale.totalAmount)}*")
        sb.appendLine("Paid: ${data.money(sale.paidAmount)}")
        if (sale.dueAmount > 0.005) sb.appendLine("*Due: ${data.money(sale.dueAmount)}*")
        if (data.returns.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("Returns:")
            data.returns.forEach { ret ->
                val date = com.example.core.utils.DateTimeUtils.formatDate(ret.returnDate, "dd MMM yyyy")
                sb.appendLine("- ${ret.returnNumber} ($date): -${data.money(ret.totalAmount)}")
            }
            sb.appendLine("Net after returns: ${data.money((sale.totalAmount - data.returnsTotal).coerceAtLeast(0.0))}")
        }
        sb.appendLine()
        sb.append(THANK_YOU)
        if (data.terms.isNotBlank()) sb.append("\n").append(data.terms.trim())
        return sb.toString()
    }
}
