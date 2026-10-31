package com.example.data.repository

import com.example.core.utils.DateTimeUtils
import com.example.data.local.database.AppDatabase
import com.example.data.local.entities.ProductEntity
import com.example.data.local.entities.ShopProfileEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

data class DashboardSummary(
    val todaySales: Double = 0.0,
    val todayPurchases: Double = 0.0,
    val todayProfit: Double = 0.0,
    val todayExpenses: Double = 0.0,
    val customerDue: Double = 0.0,
    val supplierDue: Double = 0.0,
    val totalStockValue: Double = 0.0,
    val lowStockCount: Int = 0,
    val outOfStockCount: Int = 0,
    val totalProductCount: Int = 0,
    val currencySymbol: String = "৳"
)

class DashboardRepository(private val database: AppDatabase) {
    private val productDao = database.productDao()
    private val saleDao = database.saleDao()
    private val purchaseDao = database.purchaseDao()
    private val financeDao = database.financeDao()
    private val masterDao = database.masterDao()
    private val settingsDao = database.settingsAndAuditDao()

    val shopProfile: Flow<ShopProfileEntity?> = settingsDao.getShopProfile()
    val lowStockProducts: Flow<List<ProductEntity>> = productDao.getLowStockProducts()
    val outOfStockProducts: Flow<List<ProductEntity>> = productDao.getOutOfStockProducts()

    fun getDashboardSummaryStream(): Flow<DashboardSummary> {
        val startOfDay = DateTimeUtils.getStartOfDay()
        val endOfDay = DateTimeUtils.getEndOfDay()

        val returnDao = database.salesReturnDao()
        // Today's figures are net of today's returns
        val salesFlow = combine(
            saleDao.getTotalSalesAmountBetween(startOfDay, endOfDay),
            returnDao.getTotalReturnsBetween(startOfDay, endOfDay)
        ) { sold, returned -> (sold ?: 0.0) - returned }
        val purchasesFlow = purchaseDao.getTotalPurchaseAmountBetween(startOfDay, endOfDay)
        val profitFlow = combine(
            saleDao.getTotalProfitBetween(startOfDay, endOfDay),
            returnDao.getReturnedProfitBetween(startOfDay, endOfDay)
        ) { profit, givenBack -> (profit ?: 0.0) - givenBack }
        val expensesFlow = financeDao.getTotalExpensesBetween(startOfDay, endOfDay)
        val customerDueFlow = masterDao.getTotalCustomerDue()
        val supplierDueFlow = masterDao.getTotalSupplierDue()
        val stockValFlow = productDao.getTotalStockValue()
        val lowStockCountFlow = productDao.getLowStockCount()
        val outOfStockCountFlow = productDao.getOutOfStockCount()
        val totalProductsFlow = productDao.getTotalProductCount()
        val profileFlow = settingsDao.getShopProfile()

        // Combine all real-time Room observable database streams
        return combine(
            salesFlow, purchasesFlow, profitFlow, expensesFlow, customerDueFlow
        ) { s, p, pr, e, cd ->
            DashboardPartial1(s, p ?: 0.0, pr, e ?: 0.0, cd ?: 0.0)
        }.combine(
            combine(supplierDueFlow, stockValFlow, lowStockCountFlow, outOfStockCountFlow, totalProductsFlow) { sd, sv, ls, os, tp ->
                DashboardPartial2(sd ?: 0.0, sv ?: 0.0, ls, os, tp)
            }
        ) { part1, part2 ->
            Pair(part1, part2)
        }.combine(profileFlow) { (part1, part2), profile ->
            DashboardSummary(
                todaySales = part1.sales,
                todayPurchases = part1.purchases,
                todayProfit = part1.profit,
                todayExpenses = part1.expenses,
                customerDue = part1.customerDue,
                supplierDue = part2.supplierDue,
                totalStockValue = part2.stockValue,
                lowStockCount = part2.lowStockCount,
                outOfStockCount = part2.outOfStockCount,
                totalProductCount = part2.totalProducts,
                currencySymbol = profile?.currencySymbol ?: "৳"
            )
        }
    }
}

private data class DashboardPartial1(
    val sales: Double,
    val purchases: Double,
    val profit: Double,
    val expenses: Double,
    val customerDue: Double
)

private data class DashboardPartial2(
    val supplierDue: Double,
    val stockValue: Double,
    val lowStockCount: Int,
    val outOfStockCount: Int,
    val totalProducts: Int
)
