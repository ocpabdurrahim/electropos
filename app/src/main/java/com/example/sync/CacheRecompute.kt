package com.example.sync

import com.example.data.local.database.AppDatabase
import com.example.data.local.entities.StockMovementType
import com.example.data.local.entities.StockTransactionEntity
import com.example.data.repository.CustomerRepository
import com.example.data.repository.SupplierRepository
import kotlin.math.abs

/** Rows whose derived numbers must be worked out again after cloud records arrived. */
class Affected {
    val products = HashSet<Long>()
    val variants = HashSet<Long>()
    val customers = HashSet<Long>()
    val suppliers = HashSet<Long>()

    val isEmpty: Boolean get() = products.isEmpty() && variants.isEmpty() && customers.isEmpty() && suppliers.isEmpty()

    /** Notes what a row of [table] (its values before or after the change) feeds into. */
    fun note(table: String, row: Map<String, Any?>) {
        fun id(col: String) = (row[col] as? Number)?.toLong()?.takeIf { it > 0 }
        when (table) {
            "products" -> id("id")?.let(products::add)
            "product_variants" -> id("id")?.let(variants::add)
            "stock_transactions" -> {
                id("productId")?.let(products::add)
                id("variantId")?.let(variants::add)
            }
            "customers" -> id("id")?.let(customers::add)
            "customer_ledger_entries", "customer_payments", "sales", "sales_returns" -> id("customerId")?.let(customers::add)
            "suppliers" -> id("id")?.let(suppliers::add)
            "supplier_ledger_entries", "supplier_payments", "purchases", "purchase_returns" -> id("supplierId")?.let(suppliers::add)
        }
    }

    companion object {
        /** Tables whose rows feed a cache somewhere. */
        val FEEDING = setOf(
            "products", "product_variants", "stock_transactions", "customers", "customer_ledger_entries",
            "customer_payments", "sales", "sales_returns", "suppliers", "supplier_ledger_entries", "supplier_payments",
            "purchases", "purchase_returns"
        )
    }
}

/**
 * Cache columns are never trusted from another device (docs/data-model.md, "Cache columns"): stock,
 * weighted cost, dues, running balances and invoice paid/due are worked out here from the records.
 * Runs inside the transaction that applied the records (applying = 1), so nothing goes back out.
 */
class CacheRecompute(private val db: AppDatabase) {
    private val sql get() = db.openHelper.writableDatabase

    suspend fun run(affected: Affected) {
        affected.products.forEach { product(it) }
        affected.variants.forEach { variant(it) }
        affected.customers.forEach { customer(it) }
        affected.suppliers.forEach { supplier(it) }
    }

    /** Everything (after a whole shop was downloaded). */
    suspend fun all() {
        val a = Affected()
        ids("products").forEach { a.products.add(it) }
        ids("product_variants").forEach { a.variants.add(it) }
        ids("customers").forEach { a.customers.add(it) }
        ids("suppliers").forEach { a.suppliers.add(it) }
        run(a)
    }

    private fun ids(table: String): List<Long> = sql.query("SELECT id FROM $table").use { c ->
        val out = ArrayList<Long>(c.count)
        while (c.moveToNext()) out.add(c.getLong(0))
        out
    }

    private suspend fun product(id: Long) {
        val product = db.productDao().getProductById(id) ?: return
        val stock = db.stockDao().calculateAuthoritativeStock(id)
        val cost = WeightedCost.replay(db.stockDao().getTransactionsForProductSync(id), product.purchasePrice, restoreOnCancel = true)
        if (product.currentStock != stock || product.purchasePrice != cost) {
            sql.execSQL("UPDATE products SET currentStock = ?, purchasePrice = ? WHERE id = ?", arrayOf<Any>(stock, cost, id))
        }
    }

    private suspend fun variant(id: Long) {
        val variant = db.productVariantDao().getVariantById(id) ?: return
        val stock = db.stockDao().calculateAuthoritativeVariantStock(variant.productId, id)
        val moves = db.stockDao().getTransactionsForProductSync(variant.productId).filter { it.variantId == id }
        // The phone never put a cancelled purchase's cost back into a variant's cost, so neither does the replay
        val cost = WeightedCost.replay(moves, variant.purchasePrice, restoreOnCancel = false)
        if (variant.currentStock != stock || variant.purchasePrice != cost) {
            sql.execSQL("UPDATE product_variants SET currentStock = ?, purchasePrice = ? WHERE id = ?", arrayOf<Any>(stock, cost, id))
        }
    }

    private suspend fun customer(id: Long) {
        if (db.customerDao().getCustomerById(id) == null) return
        // Sets currentDue from the ledger and settles the invoices (same rules as on this phone)
        CustomerRepository(db).reallocateSalePayments(id)
        runningBalances("customer_ledger_entries", "customerId", id)
    }

    private suspend fun supplier(id: Long) {
        if (db.supplierDao().getSupplierById(id) == null) return
        db.supplierDao().setSupplierDue(id, db.supplierLedgerDao().calculateAuthoritativeBalance(id))
        SupplierRepository(db).reallocatePurchasePayments(id)
        runningBalances("supplier_ledger_entries", "supplierId", id)
    }

    /** Running Σ signedAmount ordered by createdAt, id. */
    private fun runningBalances(table: String, ownerColumn: String, ownerId: Long) {
        val changes = ArrayList<Pair<Long, Double>>()
        sql.query("SELECT id, signedAmount, runningBalance FROM $table WHERE $ownerColumn = ? ORDER BY createdAt ASC, id ASC", arrayOf<Any>(ownerId)).use { c ->
            var running = 0.0
            while (c.moveToNext()) {
                running += c.getDouble(1)
                if (abs(c.getDouble(2) - running) > 1e-9) changes.add(c.getLong(0) to running)
            }
        }
        for ((rowId, balance) in changes) {
            sql.execSQL("UPDATE $table SET runningBalance = ? WHERE id = ?", arrayOf<Any>(balance, rowId))
        }
    }
}

/**
 * Weighted average cost from the stock movements (docs/data-model.md, "Weighted average cost"),
 * the same arithmetic PurchaseRepository uses when it records a purchase or a cancellation.
 */
object WeightedCost {
    /**
     * @param moves one product's (or variant's) movements ordered by createdAt, id.
     * @param stored the cost in the row; kept when there is no purchase (it is the entered cost).
     * @param restoreOnCancel take a cancelled purchase back out of the average (products; variants don't).
     */
    fun replay(moves: List<StockTransactionEntity>, stored: Double, restoreOnCancel: Boolean): Double {
        val purchases = moves.filter { it.transactionType == StockMovementType.PURCHASE }
        if (purchases.isEmpty()) return stored
        val used = HashSet<Long>()
        var stock = 0L
        // Before the first purchase the cost is the entered one, which the first movement (opening stock) recorded
        var avg = moves.first().unitCost
        for (m in moves) {
            when (m.transactionType) {
                StockMovementType.PURCHASE -> {
                    val base = stock.coerceAtLeast(0L)
                    val q = m.quantityChange
                    avg = if (base + q > 0) ((base * avg) + (q * m.unitCost)) / (base + q) else m.unitCost
                    stock += q
                }
                StockMovementType.PURCHASE_CANCELLED -> {
                    val q = -m.quantityChange
                    val newStock = stock - q
                    if (restoreOnCancel && newStock > 0) {
                        // The purchase's own cost: its PURCHASE movement of the same line (same purchase, variant, quantity)
                        val candidates = purchases.filter { it.referenceId == m.referenceId && it.variantId == m.variantId && it.id !in used }
                        val match = candidates.firstOrNull { it.quantityChange == q } ?: candidates.firstOrNull()
                        if (match != null) {
                            used.add(match.id)
                            val restored = ((stock * avg) - (q * match.unitCost)) / newStock
                            if (restored > 0) avg = restored
                        }
                    }
                    stock = newStock
                }
                else -> stock += m.quantityChange
            }
        }
        return avg
    }
}
