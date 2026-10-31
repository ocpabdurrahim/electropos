package com.example.core.invoice

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import com.example.core.utils.DateTimeUtils
import java.io.OutputStream
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Draws a sale invoice into a PDF with [PdfDocument].
 *
 * All text goes through [StaticLayout] + [TextPaint], so Android's font fallback renders Bangla names
 * and the ৳ sign, and long names wrap. Content is built as a list of measured blocks first, then
 * paginated: A4/A5 get as many fixed-size pages as needed (the item table header repeats on each
 * page), the 80 mm receipt is one long page whose height follows the content.
 */
class InvoicePdfGenerator {

    /** Writes the PDF to [out] (not closed) and returns its pages (count and sizes). */
    fun writePdf(data: InvoiceData, format: InvoiceFormat, out: OutputStream): List<InvoicePage> {
        val pages = layout(data, format)
        val document = PdfDocument()
        try {
            pages.forEachIndexed { index, page ->
                val info = PdfDocument.PageInfo.Builder(page.width, page.height, index + 1).create()
                val pdfPage = document.startPage(info)
                page.draw(pdfPage.canvas, index, pages.size)
                document.finishPage(pdfPage)
            }
            document.writeTo(out)
        } finally {
            document.close()
        }
        return pages
    }

    /** Measures and paginates the invoice; each page can then be drawn on any canvas. */
    fun layout(data: InvoiceData, format: InvoiceFormat): List<InvoicePage> {
        val spec = Spec(format)
        val builder = BlockBuilder(data, spec)
        val blocks = if (format.isReceipt) builder.receiptBlocks() else builder.pageBlocks()
        return paginate(blocks, spec, if (format.isReceipt) null else builder.tableHeader(), data)
    }

    // ---------------------------------------------------------------------------------------------
    // Pagination
    // ---------------------------------------------------------------------------------------------

    private fun paginate(blocks: List<Block>, spec: Spec, tableHeader: Block?, data: InvoiceData): List<InvoicePage> {
        val top = spec.margin
        val bottom = if (spec.isReceipt) MAX_RECEIPT_PAGE_HEIGHT - spec.margin else spec.pageHeight - spec.margin
        val capacity = bottom - top

        val pages = mutableListOf<List<Placed>>()
        val usedHeights = mutableListOf<Float>()
        var current = mutableListOf<Placed>()
        var y = top
        var hasContent = false

        fun newPage(continuesItems: Boolean) {
            pages += current
            usedHeights += y
            current = mutableListOf()
            y = top
            hasContent = false
            if (continuesItems && tableHeader != null) {
                current += Placed(tableHeader, y, 0f, tableHeader.height)
                y += tableHeader.height
            }
        }

        blocks.forEachIndexed { index, block ->
            val next = blocks.getOrNull(index + 1)
            val room = bottom - y
            val tooTall = block.height > room + EPS
            val orphan = block.keepWithNext && next != null &&
                block.height + next.height > room + EPS && block.height + next.height <= capacity
            if (hasContent && (tooTall || orphan)) newPage(block.kind == Kind.ITEM)

            if (block.height <= bottom - y + EPS) {
                current += Placed(block, y, 0f, block.height)
                y += block.height
                hasContent = true
            } else {
                // Taller than a whole page (e.g. hundreds of serials): slice it across pages.
                var offset = 0f
                while (offset < block.height - EPS) {
                    val take = min(bottom - y, block.height - offset)
                    current += Placed(block, y, offset, take)
                    y += take
                    offset += take
                    hasContent = true
                    if (offset < block.height - EPS) newPage(block.kind == Kind.ITEM)
                }
            }
        }
        pages += current
        usedHeights += y

        val footer: ((Canvas, Int, Int) -> Unit)? = if (spec.isReceipt) null else { canvas, index, count ->
            val paint = spec.paint(spec.base * 0.8f, color = MUTED)
            val label = "Invoice ${data.sale.invoiceNumber}  •  Page ${index + 1} of $count"
            val layout = textLayout(label, spec.contentWidth, paint, Layout.Alignment.ALIGN_OPPOSITE)
            canvas.save()
            canvas.translate(spec.margin, spec.pageHeight - spec.margin + spec.base * 0.6f)
            layout.draw(canvas)
            canvas.restore()
        }

        return pages.mapIndexed { index, placed ->
            val height = if (spec.isReceipt) ceil(usedHeights[index] + spec.margin).toInt() else spec.pageHeight.toInt()
            InvoicePage(spec.pageWidth.toInt(), max(height, 1), spec.margin, placed, footer)
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Blocks
    // ---------------------------------------------------------------------------------------------

    private class BlockBuilder(private val data: InvoiceData, private val spec: Spec) {
        private val sale = data.sale
        private val width = spec.contentWidth
        private val base = spec.base
        private val ink = INK
        private val muted = if (spec.isReceipt) RECEIPT_MUTED else MUTED
        private val accent = if (spec.isReceipt) INK else ACCENT

        private val body = spec.paint(base, color = ink)
        private val bodyBold = spec.paint(base, bold = true, color = ink)
        private val small = spec.paint(base * (if (spec.isReceipt) 0.92f else 0.86f), color = muted)
        private val heading = spec.paint(base * 0.9f, bold = true, color = ink)

        // Table columns for A4/A5: #, item, qty, price, discount, total
        private val colFractions = floatArrayOf(0.05f, 0.43f, 0.08f, 0.15f, 0.13f, 0.16f)
        private val colX = FloatArray(colFractions.size).also { xs ->
            var x = 0f
            colFractions.forEachIndexed { i, f -> xs[i] = x; x += f * width }
        }
        private val colW = FloatArray(colFractions.size) { colFractions[it] * width }
        private val cellPad = 3f

        // ------------------------------------------------------------------ A4 / A5

        fun pageBlocks(): List<Block> {
            val blocks = mutableListOf<Block>()
            blocks += pageHeader()
            blocks += rule(accent, 1.2f, padTop = 6f, padBottom = 8f)
            blocks += billTo()
            blocks += spacer(8f)
            blocks += tableHeader()
            data.lines.forEachIndexed { index, line -> blocks += pageItem(index, line) }
            blocks += rule(INK, 0.8f, padTop = 0f, padBottom = 6f, keepWithNext = true)
            blocks += pageTotals()
            if (data.returns.isNotEmpty()) {
                blocks += spacer(10f)
                blocks += returnBlocks(x = width * 0.40f, w = width * 0.60f)
            }
            blocks += spacer(16f)
            blocks += footerBlocks(Layout.Alignment.ALIGN_CENTER)
            return blocks
        }

        private fun pageHeader(): Block {
            val logo = data.logo
            val logoBox = if (logo != null) base * 5.2f else 0f
            val leftX = if (logo != null) logoBox + 10f else 0f
            val rightW = width * 0.40f
            val leftW = width - rightW - leftX - 10f

            val left = Rich()
                .line(data.shopName, 1.9f, bold = true, color = accent)
                .line(data.shop.slogan, 0.95f, color = muted)
                .line(data.shop.address)
                .line(if (data.phones.isEmpty()) "" else "Phone: " + data.phones.joinToString(", "))
                .line(listOf(data.shop.email, data.shop.website).filter { it.isNotBlank() }.joinToString("  •  "), 0.95f, color = muted)
                .build(leftW, body)

            val right = Rich()
                .line("INVOICE", 1.7f, bold = true, color = accent)
                .line("Invoice No: ${sale.invoiceNumber}", bold = true)
                .line("Date: " + DateTimeUtils.formatDateTime(sale.saleDate))
                .line("Payment: " + InvoiceText.paymentLine(sale))
                .line(if (sale.salespersonName.isNotBlank()) "Served by: ${sale.salespersonName}" else "")
                .line(if (sale.status != "COMPLETED") sale.status else "", 1.1f, bold = true, color = ALERT)
                .build(rightW, body, Layout.Alignment.ALIGN_OPPOSITE)

            val height = max(logoBox, max(left.height.toFloat(), right.height.toFloat()))
            return Block(height) { c ->
                if (logo != null) drawLogo(c, logo, RectF(0f, 0f, logoBox, logoBox))
                c.at(leftX, 0f) { left.draw(it) }
                c.at(width - rightW, 0f) { right.draw(it) }
            }
        }

        private fun billTo(): Block {
            val layout = Rich()
                .line("BILL TO", 0.85f, bold = true, color = muted)
                .line(sale.customerName.ifBlank { "Walk-in Customer" }, 1.15f, bold = true)
                .line(if (sale.customerPhone.isNotBlank()) "Phone: ${sale.customerPhone}" else "")
                .line(data.customerAddress)
                .build(width * 0.6f, body)
            return Block(layout.height.toFloat()) { c -> layout.draw(c) }
        }

        fun tableHeader(): Block {
            val sym = data.symbol
            val titles = listOf("#", "Item", "Qty", "Price ($sym)", "Disc. ($sym)", "Total ($sym)")
            val aligns = listOf(ALIGN_LEFT, ALIGN_LEFT, ALIGN_CENTER, ALIGN_RIGHT, ALIGN_RIGHT, ALIGN_RIGHT)
            val layouts = titles.mapIndexed { i, t -> textLayout(t, colW[i] - 2 * cellPad, heading, aligns[i]) }
            val padV = 4f
            val height = layouts.maxOf { it.height } + 2 * padV
            return Block(height, Kind.HEADER, keepWithNext = true) { c ->
                c.drawRect(0f, 0f, width, height, fill(HEAD_FILL))
                layouts.forEachIndexed { i, l -> c.at(colX[i] + cellPad, padV) { l.draw(it) } }
            }
        }

        private fun pageItem(index: Int, line: InvoiceLine): List<Block> {
            val blocks = mutableListOf<Block>()
            val padV = 4f
            val cells = listOf(
                textLayout("${index + 1}", colW[0] - 2 * cellPad, body, ALIGN_LEFT),
                textLayout(line.name, colW[1] - 2 * cellPad, bodyBold, ALIGN_LEFT),
                textLayout("${line.quantity}", colW[2] - 2 * cellPad, body, ALIGN_CENTER),
                textLayout(data.number(line.unitPrice), colW[3] - 2 * cellPad, body, ALIGN_RIGHT),
                textLayout(if (line.discount > 0.005) data.number(line.discount) else "-", colW[4] - 2 * cellPad, body, ALIGN_RIGHT),
                textLayout(data.number(line.lineTotal), colW[5] - 2 * cellPad, bodyBold, ALIGN_RIGHT)
            )
            val details = detailLines(line, colW[1] - 2 * cellPad)
            val bottomPad = if (details.isEmpty()) padV else 1f
            val mainHeight = cells.maxOf { it.height } + padV + bottomPad
            blocks += Block(mainHeight, Kind.ITEM, keepWithNext = details.isNotEmpty()) { c ->
                if (index > 0) c.drawLine(0f, 0f, width, 0f, stroke(RULE, 0.5f))
                cells.forEachIndexed { i, l -> c.at(colX[i] + cellPad, padV) { l.draw(it) } }
            }
            details.forEach { l ->
                blocks += Block(l.height.toFloat(), Kind.ITEM) { c -> c.at(colX[1] + cellPad, 0f) { l.draw(it) } }
            }
            if (details.isNotEmpty()) blocks += spacer(padV, Kind.ITEM)
            return blocks
        }

        private fun pageTotals(): Block {
            val colWidth = width * 0.48f
            val x = width - colWidth
            val rows = totalRows(colWidth)
            val summary = textLayout(
                "Items: ${data.lines.size}   •   Quantity: ${data.lines.sumOf { it.quantity }}",
                width - colWidth - 12f, small, ALIGN_LEFT
            )
            val rowsHeight = rows.sumOf { it.height.toDouble() }.toFloat()
            return Block(max(rowsHeight, summary.height.toFloat())) { c ->
                summary.draw(c)
                var y = 0f
                rows.forEach { row -> c.at(x, y) { row.draw(it) }; y += row.height }
            }
        }

        // ------------------------------------------------------------------ 80 mm receipt

        fun receiptBlocks(): List<Block> {
            val blocks = mutableListOf<Block>()
            val center = Layout.Alignment.ALIGN_CENTER
            data.logo?.let { logo ->
                val box = base * 5f
                blocks += Block(box + 4f) { c -> drawLogo(c, logo, RectF((width - box) / 2f, 0f, (width + box) / 2f, box)) }
            }
            val shop = Rich()
                .line(data.shopName, 1.45f, bold = true)
                .line(data.shop.slogan, 0.9f)
                .line(data.shop.address, 0.95f)
                .line(if (data.phones.isEmpty()) "" else "Phone: " + data.phones.joinToString(", "), 0.95f)
                .line(data.shop.email, 0.9f)
                .build(width, body, center)
            blocks += Block(shop.height.toFloat()) { c -> shop.draw(c) }
            blocks += dashed()

            val info = Rich()
                .line("Invoice: ${sale.invoiceNumber}", bold = true)
                .line("Date: " + DateTimeUtils.formatDateTime(sale.saleDate))
                .line("Payment: " + InvoiceText.paymentLine(sale))
                .line(if (sale.salespersonName.isNotBlank()) "Served by: ${sale.salespersonName}" else "")
                .line(if (sale.status != "COMPLETED") "Status: ${sale.status}" else "", bold = true)
                .line("Customer: " + sale.customerName.ifBlank { "Walk-in Customer" }, bold = true)
                .line(if (sale.customerPhone.isNotBlank()) "Phone: ${sale.customerPhone}" else "")
                .line(data.customerAddress)
                .build(width, body)
            blocks += Block(info.height.toFloat()) { c -> info.draw(c) }
            blocks += dashed()
            blocks += pairRow("Item", "Total (${data.symbol})", width, heading, heading)
            blocks += dashed()

            data.lines.forEachIndexed { index, line ->
                val name = textLayout("${index + 1}. ${line.name}", width, bodyBold, ALIGN_LEFT)
                blocks += Block(name.height + 1f, Kind.ITEM, keepWithNext = true) { c -> name.draw(c) }
                val qtyPrice = "${line.quantity} x ${data.number(line.unitPrice)}" +
                    if (line.discount > 0.005) "  (disc. ${data.number(line.discount)})" else ""
                blocks += pairRow(qtyPrice, data.number(line.lineTotal), width, body, bodyBold, kind = Kind.ITEM)
                detailLines(line, width - 6f).forEach { l ->
                    blocks += Block(l.height.toFloat(), Kind.ITEM) { c -> c.at(6f, 0f) { l.draw(it) } }
                }
                blocks += spacer(3f, Kind.ITEM)
            }
            blocks += dashed()
            blocks += totalRows(width)
            if (data.returns.isNotEmpty()) {
                blocks += dashed()
                blocks += returnBlocks(x = 0f, w = width)
            }
            blocks += dashed()
            blocks += footerBlocks(center)
            return blocks
        }

        // ------------------------------------------------------------------ shared pieces

        /** Serial/IMEI list, tax note and warranty under an item, one block per text line. */
        private fun detailLines(line: InvoiceLine, w: Float): List<StaticLayout> {
            val out = mutableListOf<StaticLayout>()
            if (line.serials.isNotEmpty()) {
                out += splitLines("${line.serialLabel}: " + line.serials.joinToString(", "), w, small)
            }
            if (line.tax > 0.005) out += splitLines("Incl. tax: " + data.number(line.tax), w, small)
            line.warranty?.let { out += splitLines("Warranty: $it", w, small) }
            return out
        }

        private fun totalRows(w: Float): List<Block> {
            val rows = mutableListOf<Block>()
            val grand = spec.paint(base * 1.2f, bold = true, color = ink)
            rows += pairRow("Subtotal", data.money(sale.subTotal), w, body, body)
            if (sale.itemDiscount > 0.005) rows += pairRow("Item discounts", "-" + data.money(sale.itemDiscount), w, body, body)
            if (sale.invoiceDiscount > 0.005) rows += pairRow("Invoice discount", "-" + data.money(sale.invoiceDiscount), w, body, body)
            if (data.otherDiscount > 0.005) rows += pairRow("Discount", "-" + data.money(data.otherDiscount), w, body, body)
            if (sale.taxAmount > 0.005) rows += pairRow("Tax", data.money(sale.taxAmount), w, body, body)
            rows += rule(INK, 0.8f, padTop = 2f, padBottom = 2f, w = w)
            rows += pairRow("Grand Total", data.money(sale.totalAmount), w, grand, grand)
            rows += pairRow("Paid", data.money(sale.paidAmount), w, body, body)
            if (sale.dueAmount > 0.005) {
                val due = spec.paint(base, bold = true, color = if (spec.isReceipt) INK else ALERT)
                rows += pairRow("Due", data.money(sale.dueAmount), w, due, due)
            }
            return rows
        }

        private fun returnBlocks(x: Float, w: Float): List<Block> {
            val blocks = mutableListOf<Block>()
            val title = textLayout("Returns on this invoice", w, heading, ALIGN_LEFT)
            blocks += Block(title.height + 2f, keepWithNext = true) { c -> c.at(x, 0f) { title.draw(it) } }
            data.returns.forEach { ret ->
                val parts = mutableListOf(ret.returnNumber, DateTimeUtils.formatDate(ret.returnDate, "dd MMM yyyy"))
                if (ret.refundAmount > 0.005) parts += "refund " + data.money(ret.refundAmount)
                if (ret.customerCredit > 0.005) parts += "credit " + data.money(ret.customerCredit)
                val row = pairRow(parts.joinToString(" • "), "-" + data.money(ret.totalAmount), w, small, body)
                blocks += Block(row.height) { c -> c.at(x, 0f) { row.draw(it) } }
            }
            val net = pairRow("Net after returns", data.money((sale.totalAmount - data.returnsTotal).coerceAtLeast(0.0)), w, bodyBold, bodyBold)
            blocks += Block(net.height) { c -> c.at(x, 0f) { net.draw(it) } }
            return blocks
        }

        private fun footerBlocks(align: Layout.Alignment): List<Block> {
            val blocks = mutableListOf<Block>()
            val thanks = textLayout(InvoiceText.THANK_YOU, width, spec.paint(base * 1.05f, bold = true, color = ink), align)
            blocks += Block(thanks.height + 3f, keepWithNext = data.terms.isNotBlank()) { c -> thanks.draw(c) }
            if (data.terms.isNotBlank()) {
                splitLines(data.terms.trim(), width, small, align).forEach { l ->
                    blocks += Block(l.height.toFloat()) { c -> l.draw(c) }
                }
            }
            return blocks
        }

        private fun pairRow(
            label: String,
            value: String,
            w: Float,
            labelPaint: TextPaint,
            valuePaint: TextPaint,
            padV: Float = 1.5f,
            kind: Kind = Kind.OTHER
        ): Block {
            val valueW = min(w * 0.6f, ceil(valuePaint.measureText(value)) + 4f)
            val valueLayout = textLayout(value, valueW, valuePaint, ALIGN_RIGHT)
            val labelLayout = textLayout(label, w - valueW - 6f, labelPaint, ALIGN_LEFT)
            val height = max(labelLayout.height, valueLayout.height) + 2 * padV
            return Block(height, kind) { c ->
                c.at(0f, padV) { labelLayout.draw(it) }
                c.at(w - valueW, padV) { valueLayout.draw(it) }
            }
        }

        private fun rule(
            color: Int,
            thickness: Float,
            padTop: Float,
            padBottom: Float,
            w: Float = width,
            keepWithNext: Boolean = false
        ): Block =
            Block(padTop + thickness + padBottom, keepWithNext = keepWithNext) { c ->
                val y = padTop + thickness / 2f
                c.drawLine(0f, y, w, y, stroke(color, thickness))
            }

        private fun dashed(): Block = Block(9f) { c ->
            val paint = stroke(INK, 0.6f).apply { pathEffect = DashPathEffect(floatArrayOf(3f, 2f), 0f) }
            c.drawLine(0f, 4.5f, width, 4.5f, paint)
        }

        private fun spacer(h: Float, kind: Kind = Kind.OTHER) = Block(h, kind) { }

        private fun drawLogo(canvas: Canvas, logo: Bitmap, box: RectF) {
            if (logo.width <= 0 || logo.height <= 0) return
            val scale = min(box.width() / logo.width, box.height() / logo.height)
            val w = logo.width * scale
            val h = logo.height * scale
            val left = box.left + (box.width() - w) / 2f
            val top = box.top + (box.height() - h) / 2f
            canvas.drawBitmap(logo, null, RectF(left, top, left + w, top + h), Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        }
    }

    /** Builds one multi-line [StaticLayout] whose lines can have their own size/weight/colour. */
    private class Rich {
        private val sb = SpannableStringBuilder()

        fun line(text: String, scale: Float = 1f, bold: Boolean = false, color: Int? = null): Rich {
            val value = text.trim()
            if (value.isEmpty()) return this
            if (sb.isNotEmpty()) sb.append('\n')
            val start = sb.length
            sb.append(value)
            val end = sb.length
            if (scale != 1f) sb.setSpan(RelativeSizeSpan(scale), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (bold) sb.setSpan(StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (color != null) sb.setSpan(ForegroundColorSpan(color), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            return this
        }

        fun build(width: Float, paint: TextPaint, align: Layout.Alignment = Layout.Alignment.ALIGN_NORMAL): StaticLayout =
            textLayout(sb, width, paint, align)
    }

    private class Spec(val format: InvoiceFormat) {
        val isReceipt = format.isReceipt
        val pageWidth = format.pageWidth.toFloat()
        val pageHeight = format.pageHeight.toFloat()
        val margin = when (format) {
            InvoiceFormat.A4 -> 36f
            InvoiceFormat.A5 -> 24f
            InvoiceFormat.RECEIPT_80MM -> 9f
        }
        val base = when (format) {
            InvoiceFormat.A4 -> 9.5f
            InvoiceFormat.A5 -> 7.8f
            InvoiceFormat.RECEIPT_80MM -> 8.2f
        }
        val contentWidth = pageWidth - 2 * margin

        fun paint(size: Float, bold: Boolean = false, color: Int = INK): TextPaint =
            TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
                textSize = size
                typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                this.color = color
            }
    }

    internal enum class Kind { OTHER, HEADER, ITEM }

    /** A measured piece of content; [draw] paints it with its top-left at (0, 0). */
    internal class Block(
        val height: Float,
        val kind: Kind = Kind.OTHER,
        val keepWithNext: Boolean = false,
        val draw: (Canvas) -> Unit
    )

    internal class Placed(val block: Block, val y: Float, val offset: Float, val visibleHeight: Float)

    companion object {
        /** Longest single receipt page; very long receipts continue on another page. */
        const val MAX_RECEIPT_PAGE_HEIGHT = 14_000f
        private const val EPS = 0.01f

        private const val INK = 0xFF111111.toInt()
        private const val MUTED = 0xFF5F6368.toInt()
        private const val RECEIPT_MUTED = 0xFF222222.toInt()
        private const val ACCENT = 0xFF1B3A6B.toInt()
        private const val RULE = 0xFFD0D4DA.toInt()
        private const val HEAD_FILL = 0xFFEEF2F7.toInt()
        private const val ALERT = 0xFFC62828.toInt()

        private val ALIGN_LEFT = Layout.Alignment.ALIGN_NORMAL
        private val ALIGN_CENTER = Layout.Alignment.ALIGN_CENTER
        private val ALIGN_RIGHT = Layout.Alignment.ALIGN_OPPOSITE

        private fun textLayout(text: CharSequence, width: Float, paint: TextPaint, align: Layout.Alignment): StaticLayout {
            val w = max(1, ceil(width).toInt())
            return StaticLayout.Builder.obtain(text, 0, text.length, paint, w)
                .setAlignment(align)
                .setIncludePad(false)
                .setLineSpacing(0f, 1.08f)
                .build()
        }

        /** Wraps [text] and returns one layout per visual line, so pages can break between lines. */
        private fun splitLines(text: String, width: Float, paint: TextPaint, align: Layout.Alignment = ALIGN_LEFT): List<StaticLayout> {
            val whole = textLayout(text, width, paint, align)
            if (whole.lineCount <= 1) return listOf(whole)
            return (0 until whole.lineCount).mapNotNull { i ->
                val part = text.substring(whole.getLineStart(i), whole.getLineEnd(i)).trimEnd()
                if (part.isEmpty()) null else textLayout(part, width, paint, align)
            }
        }

        private fun stroke(color: Int, width: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = width
            this.color = color
        }

        private fun fill(color: Int) = Paint().apply {
            style = Paint.Style.FILL
            this.color = color
        }

        private inline fun Canvas.at(x: Float, y: Float, block: (Canvas) -> Unit) {
            save()
            translate(x, y)
            block(this)
            restore()
        }
    }
}

/** One laid-out page (size in PDF points). */
class InvoicePage internal constructor(
    val width: Int,
    val height: Int,
    private val margin: Float,
    private val placed: List<InvoicePdfGenerator.Placed>,
    private val footer: ((Canvas, Int, Int) -> Unit)?
) {
    /** Number of content pieces on the page (used by tests). */
    val blockCount: Int get() = placed.size

    fun draw(canvas: Canvas, index: Int, count: Int) {
        placed.forEach { p ->
            canvas.save()
            if (p.offset > 0f || p.visibleHeight < p.block.height) {
                canvas.clipRect(0f, p.y, width.toFloat(), p.y + p.visibleHeight)
            }
            canvas.translate(margin, p.y - p.offset)
            p.block.draw(canvas)
            canvas.restore()
        }
        footer?.invoke(canvas, index, count)
    }
}
