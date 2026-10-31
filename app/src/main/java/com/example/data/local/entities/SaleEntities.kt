package com.example.data.local.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "sales",
    foreignKeys = [
        ForeignKey(
            entity = CustomerEntity::class,
            parentColumns = ["id"],
            childColumns = ["customerId"],
            onDelete = ForeignKey.SET_DEFAULT
        )
    ],
    indices = [
        Index(value = ["invoiceNumber"], unique = true),
        Index(value = ["customerId"]),
        Index(value = ["saleDate"]),
        Index(value = ["status"]),
        Index(value = ["paymentStatus"])
    ]
)
data class SaleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val invoiceNumber: String,
    val customerId: Long? = null,
    val customerName: String = "Walk-in Customer",
    val customerPhone: String = "",
    val saleDate: Long = System.currentTimeMillis(),
    val subTotal: Double = 0.0,
    val itemDiscount: Double = 0.0,
    val invoiceDiscount: Double = 0.0,
    val discountAmount: Double = 0.0,
    val taxAmount: Double = 0.0,
    val totalAmount: Double = 0.0,
    val paidAmount: Double = 0.0,
    val dueAmount: Double = 0.0,
    val paymentStatus: String = "PAID", // PAID, PARTIAL, DUE
    val paymentMethod: String = "CASH", // CASH, BKASH, NAGAD, ROCKET, CARD, BANK, MULTIPLE
    val notes: String = "",
    val status: String = "COMPLETED", // COMPLETED, CANCELLED, RETURNED
    val salespersonName: String = "Admin",
    val totalCostOfGoodsSold: Double = 0.0, // COGS calculated at time of sale
    val totalProfit: Double = 0.0, // totalAmount - totalCostOfGoodsSold
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "sale_items",
    foreignKeys = [
        ForeignKey(
            entity = SaleEntity::class,
            parentColumns = ["id"],
            childColumns = ["saleId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = ProductEntity::class,
            parentColumns = ["id"],
            childColumns = ["productId"],
            onDelete = ForeignKey.RESTRICT
        )
    ],
    indices = [
        Index(value = ["saleId"]),
        Index(value = ["productId"]),
        Index(value = ["variantId"])
    ]
)
data class SaleItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val saleId: Long,
    val productId: Long,
    val productName: String,
    val variantId: Long = 0,
    val quantity: Int,
    val unitPrice: Double,
    val unitCostAtSale: Double, // Cost from weighted-average inventory cost
    val discount: Double = 0.0,
    val tax: Double = 0.0,
    val totalPrice: Double,
    val profit: Double = 0.0, // (unitPrice - unitCostAtSale) * quantity - discount
    val trackingType: String = "QUANTITY",
    val serialImeisJson: String = "" // Serialized JSON array of chosen serials/IMEIs
)

@Entity(
    tableName = "customer_ledger_entries",
    foreignKeys = [
        ForeignKey(
            entity = CustomerEntity::class,
            parentColumns = ["id"],
            childColumns = ["customerId"],
            onDelete = ForeignKey.RESTRICT
        )
    ],
    indices = [
        Index(value = ["customerId"]),
        Index(value = ["transactionType"]),
        Index(value = ["createdAt"]),
        Index(value = ["referenceNumber"])
    ]
)
data class CustomerLedgerEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val customerId: Long,
    val transactionType: String, // OPENING_BALANCE, SALE, SALES_RETURN, CUSTOMER_PAYMENT, CUSTOMER_REFUND, DEBIT_ADJUSTMENT, CREDIT_ADJUSTMENT
    val referenceId: Long = 0,
    val referenceNumber: String = "",
    val amount: Double, // Always positive absolute amount
    val signedAmount: Double, // Signed impact on customer balance (+ for debt increase, - for decrease)
    val runningBalance: Double = 0.0,
    val description: String = "",
    val createdBy: String = "Admin",
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "sales_returns",
    foreignKeys = [
        ForeignKey(
            entity = SaleEntity::class,
            parentColumns = ["id"],
            childColumns = ["saleId"],
            onDelete = ForeignKey.RESTRICT
        )
    ],
    indices = [
        Index(value = ["returnNumber"], unique = true),
        Index(value = ["saleId"]),
        Index(value = ["customerId"]),
        Index(value = ["returnDate"])
    ]
)
data class SalesReturnEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val returnNumber: String, // e.g. SRT-2026-000001
    val saleId: Long,
    val invoiceNumber: String = "",
    val customerId: Long = 0,
    val customerName: String = "Walk-in Customer",
    val customerPhone: String = "",
    val returnDate: Long = System.currentTimeMillis(),
    val subtotal: Double = 0.0,
    val taxAmount: Double = 0.0,
    val discountAmount: Double = 0.0,
    val totalAmount: Double = 0.0,
    val refundAmount: Double = 0.0, // Paid back to customer in cash
    val customerCredit: Double = 0.0, // Deducted from customer debt
    val refundPaymentMethod: String = "CASH",
    val reason: String = "",
    val status: String = "COMPLETED", // COMPLETED, CANCELLED
    val createdBy: String = "Admin",
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "sales_return_items",
    foreignKeys = [
        ForeignKey(
            entity = SalesReturnEntity::class,
            parentColumns = ["id"],
            childColumns = ["salesReturnId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["salesReturnId"]),
        Index(value = ["productId"]),
        Index(value = ["saleItemId"])
    ]
)
data class SalesReturnItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val salesReturnId: Long,
    val saleItemId: Long = 0,
    val productId: Long,
    val productName: String = "",
    val variantId: Long = 0,
    val quantity: Int,
    val unitPrice: Double,
    val unitCostAtSale: Double = 0.0,
    val totalPrice: Double,
    val serialUnitId: Long = 0,
    val serialOrImei: String = "",
    val restockOption: String = "RESTOCK_SELLABLE" // RESTOCK_SELLABLE, DEFECTIVE_DAMAGED
)

@Entity(
    tableName = "customer_payments",
    foreignKeys = [
        ForeignKey(
            entity = CustomerEntity::class,
            parentColumns = ["id"],
            childColumns = ["customerId"],
            onDelete = ForeignKey.RESTRICT
        )
    ],
    indices = [
        Index(value = ["paymentNumber"], unique = true),
        Index(value = ["customerId"]),
        Index(value = ["saleId"]),
        Index(value = ["paymentDate"])
    ]
)
data class CustomerPaymentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val paymentNumber: String, // e.g. CPAY-2026-000001
    val customerId: Long,
    val customerName: String = "",
    val saleId: Long = 0,
    val paymentDate: Long = System.currentTimeMillis(),
    val amount: Double,
    val paymentMethod: String = "CASH",
    val referenceNumber: String = "",
    val notes: String = "",
    val createdBy: String = "Admin",
    val createdAt: Long = System.currentTimeMillis()
)
