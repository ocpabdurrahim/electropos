package com.example.data.local.dao

import androidx.room.Dao
import androidx.room.Query

/** Totals of the sales made in a period (cancelled and void sales left out). */
data class SalesTotalsRow(
    val invoiceCount: Int,
    /** Invoice totals, tax included. */
    val grossSales: Double,
    val discounts: Double,
    val taxOnSales: Double,
    /** sales.totalProfit: total - tax - cost of goods. */
    val saleProfit: Double,
    /** Part of the invoices not paid at the counter when the sale was made. */
    val dueCreated: Double,
    /** Part of these invoices that is still unpaid today. */
    val stillUnpaid: Double
)

/** Totals of the completed customer returns made in a period. */
data class ReturnTotalsRow(
    val returnCount: Int,
    /** Value refunded or credited, tax included. */
    val returnsValue: Double,
    val taxReturned: Double,
    val refundedCash: Double,
    /** Profit given back: value excl. tax, minus the cost of units that went back on the shelf. */
    val returnedProfit: Double
)

data class PurchaseTotalsRow(
    val purchaseCount: Int,
    val purchased: Double,
    val paid: Double,
    val owed: Double,
    val returnsValue: Double,
    val returnRefunds: Double
)

data class LabelAmountRow(
    val label: String,
    val amount: Double
)

/** One sale, return or expense, used to build the per-day rows and the chart. */
data class DatedAmountRow(
    val date: Long,
    val amount: Double,
    val tax: Double,
    val discount: Double,
    val profit: Double
)

data class TopProductRow(
    val productId: Long,
    val productName: String,
    val quantity: Int,
    val netSales: Double,
    val profit: Double
)

data class PartyDueRow(
    val id: Long,
    val name: String,
    val phone: String,
    val due: Double
)

data class PartyDueTotalsRow(
    /** Sum of the positive balances. */
    val owed: Double,
    /** Sum of the negative balances (advance or credit held), as a positive number. */
    val advance: Double,
    val partyCount: Int
)

data class StockTotalsRow(
    val stockValue: Double,
    val lowStockCount: Int,
    val outOfStockCount: Int,
    val productCount: Int
)

/**
 * Read-only aggregates for the Reports screen. Every date range is inclusive (BETWEEN), like the rest of the app.
 * Cancelled sales are status 'CANCELLED' (also ignore 'VOID'); returns count only when 'COMPLETED'.
 */
@Dao
interface ReportsDao {

    @Query("""
        SELECT
            COUNT(*) AS invoiceCount,
            COALESCE(SUM(s.totalAmount), 0.0) AS grossSales,
            COALESCE(SUM(MAX(s.discountAmount, s.itemDiscount + s.invoiceDiscount)), 0.0) AS discounts,
            COALESCE(SUM(s.taxAmount), 0.0) AS taxOnSales,
            COALESCE(SUM(s.totalProfit), 0.0) AS saleProfit,
            COALESCE(SUM(MAX(0.0, s.totalAmount - COALESCE(
                (SELECT SUM(c.amount) FROM cash_register_transactions c
                 WHERE c.type = 'SALE_CASH' AND c.flowDirection = 'IN' AND c.referenceId = s.id), 0.0))), 0.0) AS dueCreated,
            COALESCE(SUM(s.dueAmount), 0.0) AS stillUnpaid
        FROM sales s
        WHERE s.saleDate BETWEEN :startDate AND :endDate AND s.status NOT IN ('CANCELLED', 'VOID')
    """)
    suspend fun getSalesTotals(startDate: Long, endDate: Long): SalesTotalsRow

    /** Same profit logic as SalesReturnDao.getReturnedProfitBetween. */
    @Query("""
        SELECT
            (SELECT COUNT(*) FROM sales_returns
             WHERE returnDate BETWEEN :startDate AND :endDate AND status = 'COMPLETED') AS returnCount,
            (SELECT COALESCE(SUM(totalAmount), 0.0) FROM sales_returns
             WHERE returnDate BETWEEN :startDate AND :endDate AND status = 'COMPLETED') AS returnsValue,
            (SELECT COALESCE(SUM(taxAmount), 0.0) FROM sales_returns
             WHERE returnDate BETWEEN :startDate AND :endDate AND status = 'COMPLETED') AS taxReturned,
            (SELECT COALESCE(SUM(refundAmount), 0.0) FROM sales_returns
             WHERE returnDate BETWEEN :startDate AND :endDate AND status = 'COMPLETED') AS refundedCash,
            (SELECT COALESCE(SUM(sri.totalPrice - CASE WHEN sri.restockOption = 'RESTOCK_SELLABLE' THEN sri.unitCostAtSale * sri.quantity ELSE 0 END), 0.0)
             FROM sales_return_items sri
             INNER JOIN sales_returns sr ON sri.salesReturnId = sr.id
             WHERE sr.returnDate BETWEEN :startDate AND :endDate AND sr.status = 'COMPLETED')
          - (SELECT COALESCE(SUM(taxAmount), 0.0) FROM sales_returns
             WHERE returnDate BETWEEN :startDate AND :endDate AND status = 'COMPLETED') AS returnedProfit
    """)
    suspend fun getReturnTotals(startDate: Long, endDate: Long): ReturnTotalsRow

    /** Money taken in: payments at the counter and due collections, by payment method. */
    @Query("""
        SELECT CASE WHEN paymentMethod = '' THEN 'CASH' ELSE paymentMethod END AS label, COALESCE(SUM(amount), 0.0) AS amount
        FROM cash_register_transactions
        WHERE flowDirection = 'IN' AND type IN ('SALE_CASH', 'CUSTOMER_COLLECTION') AND date BETWEEN :startDate AND :endDate
        GROUP BY label
        ORDER BY amount DESC
    """)
    suspend fun getMoneyReceivedByMethod(startDate: Long, endDate: Long): List<LabelAmountRow>

    @Query("""
        SELECT COALESCE(SUM(amount), 0.0) FROM cash_register_transactions
        WHERE flowDirection = 'IN' AND type = :type AND date BETWEEN :startDate AND :endDate
    """)
    suspend fun getMoneyInOfType(type: String, startDate: Long, endDate: Long): Double

    /** Money handed back to customers (returns and cancelled sales). Supplier refunds are 'IN', so they are not counted. */
    @Query("""
        SELECT COALESCE(SUM(amount), 0.0) FROM cash_register_transactions
        WHERE flowDirection = 'OUT' AND type = 'REFUND' AND date BETWEEN :startDate AND :endDate
    """)
    suspend fun getRefundsToCustomers(startDate: Long, endDate: Long): Double

    @Query("""
        SELECT
            (SELECT COUNT(*) FROM purchases
             WHERE purchaseDate BETWEEN :startDate AND :endDate AND status != 'CANCELLED') AS purchaseCount,
            (SELECT COALESCE(SUM(totalAmount), 0.0) FROM purchases
             WHERE purchaseDate BETWEEN :startDate AND :endDate AND status != 'CANCELLED') AS purchased,
            (SELECT COALESCE(SUM(paidAmount), 0.0) FROM purchases
             WHERE purchaseDate BETWEEN :startDate AND :endDate AND status != 'CANCELLED') AS paid,
            (SELECT COALESCE(SUM(dueAmount), 0.0) FROM purchases
             WHERE purchaseDate BETWEEN :startDate AND :endDate AND status != 'CANCELLED') AS owed,
            (SELECT COALESCE(SUM(totalAmount), 0.0) FROM purchase_returns
             WHERE returnDate BETWEEN :startDate AND :endDate AND status != 'CANCELLED') AS returnsValue,
            (SELECT COALESCE(SUM(refundAmount), 0.0) FROM purchase_returns
             WHERE returnDate BETWEEN :startDate AND :endDate AND status != 'CANCELLED') AS returnRefunds
    """)
    suspend fun getPurchaseTotals(startDate: Long, endDate: Long): PurchaseTotalsRow

    @Query("SELECT COALESCE(SUM(amount), 0.0) FROM expenses WHERE expenseDate BETWEEN :startDate AND :endDate")
    suspend fun getExpenseTotal(startDate: Long, endDate: Long): Double

    @Query("""
        SELECT CASE WHEN TRIM(categoryName) = '' THEN 'Other' ELSE categoryName END AS label, COALESCE(SUM(amount), 0.0) AS amount
        FROM expenses
        WHERE expenseDate BETWEEN :startDate AND :endDate
        GROUP BY label
        ORDER BY amount DESC
    """)
    suspend fun getExpensesByCategory(startDate: Long, endDate: Long): List<LabelAmountRow>

    // ------------------------------------------------------------ rows for the per-day table and the chart

    @Query("""
        SELECT saleDate AS date, totalAmount AS amount, taxAmount AS tax,
            MAX(discountAmount, itemDiscount + invoiceDiscount) AS discount, totalProfit AS profit
        FROM sales
        WHERE saleDate BETWEEN :startDate AND :endDate AND status NOT IN ('CANCELLED', 'VOID')
    """)
    suspend fun getSaleRows(startDate: Long, endDate: Long): List<DatedAmountRow>

    /** profit = profit given back by that return (same logic as getReturnTotals). */
    @Query("""
        SELECT sr.returnDate AS date, sr.totalAmount AS amount, sr.taxAmount AS tax, 0.0 AS discount,
            COALESCE((SELECT SUM(sri.totalPrice - CASE WHEN sri.restockOption = 'RESTOCK_SELLABLE' THEN sri.unitCostAtSale * sri.quantity ELSE 0 END)
                      FROM sales_return_items sri WHERE sri.salesReturnId = sr.id), 0.0) - sr.taxAmount AS profit
        FROM sales_returns sr
        WHERE sr.returnDate BETWEEN :startDate AND :endDate AND sr.status = 'COMPLETED'
    """)
    suspend fun getReturnRows(startDate: Long, endDate: Long): List<DatedAmountRow>

    @Query("""
        SELECT expenseDate AS date, amount AS amount, 0.0 AS tax, 0.0 AS discount, 0.0 AS profit
        FROM expenses
        WHERE expenseDate BETWEEN :startDate AND :endDate
    """)
    suspend fun getExpenseRows(startDate: Long, endDate: Long): List<DatedAmountRow>

    // ------------------------------------------------------------ products

    /**
     * Products sold in the period, less what customers later returned from those same sale lines.
     * Line value and profit carry their share of the invoice discount; returned profit uses the same
     * rule as the period totals (tax share removed, cost added back only for units put back on the shelf).
     */
    @Query("""
        SELECT si.productId AS productId,
            MAX(si.productName) AS productName,
            SUM(si.quantity) - COALESCE(SUM(r.retQty), 0) AS quantity,
            SUM(si.totalPrice * (CASE WHEN s.totalAmount + s.invoiceDiscount > 0 THEN s.totalAmount / (s.totalAmount + s.invoiceDiscount) ELSE 0.0 END))
                - COALESCE(SUM(r.retValue), 0.0) AS netSales,
            SUM(si.totalPrice * (CASE WHEN s.totalAmount + s.invoiceDiscount > 0 THEN s.totalAmount / (s.totalAmount + s.invoiceDiscount) ELSE 0.0 END)
                - si.tax - si.unitCostAtSale * si.quantity)
                - COALESCE(SUM(r.retProfit), 0.0) AS profit
        FROM sale_items si
        INNER JOIN sales s ON si.saleId = s.id
        LEFT JOIN (
            SELECT sri.saleItemId AS saleItemId,
                SUM(sri.quantity) AS retQty,
                SUM(sri.totalPrice) AS retValue,
                SUM(sri.totalPrice
                    - CASE WHEN sr.totalAmount > 0 THEN sr.taxAmount * sri.totalPrice / sr.totalAmount ELSE 0.0 END
                    - CASE WHEN sri.restockOption = 'RESTOCK_SELLABLE' THEN sri.unitCostAtSale * sri.quantity ELSE 0.0 END) AS retProfit
            FROM sales_return_items sri
            INNER JOIN sales_returns sr ON sri.salesReturnId = sr.id
            WHERE sr.status = 'COMPLETED' AND sri.saleItemId > 0
            GROUP BY sri.saleItemId
        ) r ON r.saleItemId = si.id
        WHERE s.saleDate BETWEEN :startDate AND :endDate AND s.status NOT IN ('CANCELLED', 'VOID')
        GROUP BY si.productId
    """)
    suspend fun getProductSales(startDate: Long, endDate: Long): List<TopProductRow>

    // ------------------------------------------------------------ dues (current balances, not period based)

    @Query("""
        SELECT
            COALESCE(SUM(CASE WHEN currentDue > 0 THEN currentDue ELSE 0.0 END), 0.0) AS owed,
            COALESCE(SUM(CASE WHEN currentDue < 0 THEN -currentDue ELSE 0.0 END), 0.0) AS advance,
            COUNT(CASE WHEN currentDue > 0.005 THEN 1 END) AS partyCount
        FROM customers
    """)
    suspend fun getCustomerDueTotals(): PartyDueTotalsRow

    @Query("SELECT id, name, phone, currentDue AS due FROM customers WHERE currentDue > 0.005 ORDER BY currentDue DESC, name ASC LIMIT :limit")
    suspend fun getTopCustomerDues(limit: Int): List<PartyDueRow>

    @Query("""
        SELECT
            COALESCE(SUM(CASE WHEN currentDue > 0 THEN currentDue ELSE 0.0 END), 0.0) AS owed,
            COALESCE(SUM(CASE WHEN currentDue < 0 THEN -currentDue ELSE 0.0 END), 0.0) AS advance,
            COUNT(CASE WHEN currentDue > 0.005 THEN 1 END) AS partyCount
        FROM suppliers
    """)
    suspend fun getSupplierDueTotals(): PartyDueTotalsRow

    @Query("SELECT id, name, phone, currentDue AS due FROM suppliers WHERE currentDue > 0.005 ORDER BY currentDue DESC, name ASC LIMIT :limit")
    suspend fun getTopSupplierDues(limit: Int): List<PartyDueRow>

    // ------------------------------------------------------------ stock (same rules as the dashboard)

    @Query("""
        SELECT
            (SELECT COALESCE(SUM(currentStock * purchasePrice), 0.0) FROM products WHERE isActive = 1) AS stockValue,
            (SELECT COUNT(*) FROM products WHERE isActive = 1 AND currentStock <= minStock AND currentStock > 0) AS lowStockCount,
            (SELECT COUNT(*) FROM products WHERE isActive = 1 AND currentStock <= 0) AS outOfStockCount,
            (SELECT COUNT(*) FROM products WHERE isActive = 1) AS productCount
    """)
    suspend fun getStockTotals(): StockTotalsRow
}
