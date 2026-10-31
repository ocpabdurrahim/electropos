package com.example.data.local.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

object StockMovementType {
    const val PURCHASE = "PURCHASE"                     // + Stock in from vendor
    const val PURCHASE_CANCELLED = "PURCHASE_CANCELLED" // - Reversal of purchase
    const val PURCHASE_RETURN = "PURCHASE_RETURN"       // - Stock returned to vendor
    const val OPENING_STOCK = "OPENING_STOCK"           // + Initial opening balance
    const val ADJUSTMENT_MANUAL = "ADJUSTMENT_MANUAL"   // +/- Manual inventory correction
    const val ADJUSTMENT_FOUND = "ADJUSTMENT_FOUND"     // + Found inventory
    const val ADJUSTMENT_DAMAGE = "ADJUSTMENT_DAMAGE"   // - Damaged inventory written off
    const val ADJUSTMENT_LOST = "ADJUSTMENT_LOST"       // - Lost/stolen inventory written off
    const val INTERNAL_USE = "INTERNAL_USE"             // - Consumed for shop/testing
    const val SALE = "SALE"                             // - Customer sale stock out
    const val SALE_RETURN = "SALE_RETURN"               // + Customer return stock in

    fun isStockIn(type: String): Boolean = when (type) {
        PURCHASE, OPENING_STOCK, ADJUSTMENT_FOUND, SALE_RETURN -> true
        else -> false
    }

    fun isStockOut(type: String): Boolean = when (type) {
        PURCHASE_RETURN, PURCHASE_CANCELLED, ADJUSTMENT_DAMAGE, ADJUSTMENT_LOST, INTERNAL_USE, SALE -> true
        else -> false
    }
}

@Entity(
    tableName = "stock_transactions",
    foreignKeys = [
        ForeignKey(
            entity = ProductEntity::class,
            parentColumns = ["id"],
            childColumns = ["productId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["productId"]),
        Index(value = ["variantId"]),
        Index(value = ["transactionType"]),
        Index(value = ["createdAt"]),
        Index(value = ["referenceId", "referenceType"]),
        Index(value = ["serialUnitId"])
    ]
)
data class StockTransactionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val productId: Long,
    val productName: String,
    val variantId: Long = 0,
    val transactionType: String, // See StockMovementType
    val quantityChange: Int, // e.g. +10, -2
    val previousStock: Int,
    val newStock: Int,
    val unitCost: Double,
    val referenceId: Long = 0,
    val referenceType: String = "", // PURCHASE, PURCHASE_RETURN, SALE, ADJUSTMENT, PRODUCT
    val serialUnitId: Long = 0,
    val balanceAfter: Int = 0,
    val createdBy: String = "Admin",
    val notes: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "payment_transactions",
    indices = [
        Index(value = ["transactionType"]),
        Index(value = ["customerId"]),
        Index(value = ["supplierId"]),
        Index(value = ["transactionDate"])
    ]
)
data class PaymentTransactionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val transactionType: String, // CUSTOMER_PAYMENT, SUPPLIER_PAYMENT
    val customerId: Long = 0,
    val supplierId: Long = 0,
    val saleId: Long = 0,
    val purchaseId: Long = 0,
    val amount: Double,
    val paymentMethod: String = "CASH", // CASH, BKASH, NAGAD, ROCKET, CARD, BANK, OTHER
    val referenceNo: String = "",
    val notes: String = "",
    val transactionDate: Long = System.currentTimeMillis(),
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "expenses",
    indices = [
        Index(value = ["categoryName"]),
        Index(value = ["expenseDate"])
    ]
)
data class ExpenseEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val categoryName: String,
    val title: String,
    val description: String = "",
    val amount: Double,
    val paymentMethod: String = "CASH",
    val referenceNo: String = "",
    val expenseDate: Long = System.currentTimeMillis(),
    val notes: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "expense_categories",
    // Not unique: two phones can add the same name offline; the copy shows under Sync issues
    indices = [Index(value = ["name"])]
)
data class ExpenseCategoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val isDefault: Boolean = false
)

@Entity(
    tableName = "cash_register_transactions",
    indices = [
        Index(value = ["type"]),
        Index(value = ["date"])
    ]
)
data class CashRegisterTransactionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: String, // OPENING_CASH, SALE_CASH, CUSTOMER_COLLECTION, SUPPLIER_PAYMENT, EXPENSE, REFUND, MANUAL_ADJUSTMENT
    val amount: Double,
    val flowDirection: String, // IN, OUT
    val paymentMethod: String = "CASH",
    val referenceId: Long = 0,
    val referenceText: String = "",
    val notes: String = "",
    val date: Long = System.currentTimeMillis(),
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "warranty_records",
    indices = [
        Index(value = ["invoiceNumber"]),
        Index(value = ["serialOrImei"]),
        Index(value = ["customerPhone"]),
        Index(value = ["status"]),
        Index(value = ["warrantyEndDate"])
    ]
)
data class WarrantyRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val saleId: Long,
    val invoiceNumber: String,
    val customerId: Long,
    val customerName: String,
    val customerPhone: String,
    val productId: Long,
    val productName: String,
    val serialImeiId: Long = 0,
    val serialOrImei: String = "",
    val saleDate: Long = System.currentTimeMillis(),
    val warrantyPeriodMonths: Int,
    val warrantyStartDate: Long = System.currentTimeMillis(),
    val warrantyEndDate: Long,
    val status: String = "ACTIVE", // ACTIVE, EXPIRING_SOON, EXPIRED, CLAIMED, REPAIRED, REPLACED
    val claimNotes: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "shop_profile"
)
data class ShopProfileEntity(
    @PrimaryKey val id: Long = 1,
    val shopName: String = "",
    val slogan: String = "Mobile, Computer & Accessories",
    val address: String = "",
    val phone: String = "",
    val alternativePhone: String = "",
    val email: String = "",
    val website: String = "",
    val logoUri: String = "",
    val currencySymbol: String = "৳",
    val currencyCode: String = "BDT",
    val invoicePrefix: String = "INV-",
    val invoiceNextNumber: Long = 1001,
    val purchasePrefix: String = "PUR-",
    val purchaseNextNumber: Long = 1,
    val purchaseReturnPrefix: String = "PRT-",
    val purchaseReturnNextNumber: Long = 1,
    val salesReturnPrefix: String = "SRT-",
    val salesReturnNextNumber: Long = 1,
    val customerPaymentPrefix: String = "CPAY-",
    val customerPaymentNextNumber: Long = 1,
    val customerCodePrefix: String = "CUS-",
    val customerNextNumber: Long = 1,
    val defaultTaxRate: Double = 0.0,
    val defaultDiscountRate: Double = 0.0,
    val lowStockAlertThreshold: Int = 5,
    val adminPin: String = "",
    val isPinRequired: Boolean = true,
    val isSampleDataLoaded: Boolean = false,
    val setupCompleted: Boolean = false,
    val themeMode: String = "SYSTEM", // LIGHT, DARK, SYSTEM
    val rememberLogin: Boolean = false, // off: the PIN is asked every time the app starts
    val lastActiveUserId: Long = 0L,
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "audit_logs",
    indices = [
        Index(value = ["timestamp"]),
        Index(value = ["module"])
    ]
)
data class AuditLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val userRole: String = "ADMIN", // ADMIN, MANAGER, SALESPERSON
    val action: String, // PRODUCT_CREATED, SALE_CREATED, etc.
    val module: String, // PRODUCTS, SALES, PURCHASES, etc.
    val recordId: String = "",
    val description: String,
    val timestamp: Long = System.currentTimeMillis()
)
