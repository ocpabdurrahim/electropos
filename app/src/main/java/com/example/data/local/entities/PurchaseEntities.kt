package com.example.data.local.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "purchases",
    foreignKeys = [
        ForeignKey(
            entity = SupplierEntity::class,
            parentColumns = ["id"],
            childColumns = ["supplierId"],
            onDelete = ForeignKey.RESTRICT
        )
    ],
    indices = [
        Index(value = ["purchaseNumber"], unique = true),
        Index(value = ["invoiceNumber"]),
        Index(value = ["supplierId"]),
        Index(value = ["purchaseDate"]),
        Index(value = ["paymentStatus"]),
        Index(value = ["status"])
    ]
)
data class PurchaseEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val purchaseNumber: String = "", // e.g. PUR-2026-000001
    val invoiceNumber: String = "",
    val supplierInvoiceNumber: String = "",
    val referenceNumber: String = "",
    val supplierId: Long,
    val supplierName: String = "",
    val purchaseDate: Long = System.currentTimeMillis(),
    val paymentMethod: String = "CASH", // CASH, BKASH, NAGAD, ROCKET, CARD, BANK, OTHER
    val subTotal: Double = 0.0,
    val itemDiscount: Double = 0.0,
    val invoiceDiscount: Double = 0.0,
    val discountAmount: Double = 0.0, // Total discount (itemDiscount + invoiceDiscount)
    val taxAmount: Double = 0.0,
    val transportCost: Double = 0.0,
    val otherCost: Double = 0.0,
    val totalAmount: Double = 0.0, // Grand Total
    val paidAmount: Double = 0.0,
    val dueAmount: Double = 0.0,
    val paymentStatus: String = "DUE", // PAID, PARTIAL, DUE
    val notes: String = "",
    val status: String = "COMPLETED", // COMPLETED, CANCELLED
    val createdBy: String = "Admin",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "purchase_items",
    foreignKeys = [
        ForeignKey(
            entity = PurchaseEntity::class,
            parentColumns = ["id"],
            childColumns = ["purchaseId"],
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
        Index(value = ["purchaseId"]),
        Index(value = ["productId"]),
        Index(value = ["variantId"])
    ]
)
data class PurchaseItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val purchaseId: Long,
    val productId: Long,
    val productName: String = "",
    val variantId: Long = 0,
    val quantity: Int,
    val unitCost: Double,
    val discount: Double = 0.0,
    val tax: Double = 0.0,
    val lineTotal: Double = 0.0,
    val totalCost: Double = 0.0, // Effective cost after line discount and tax
    val trackingType: String = "QUANTITY", // QUANTITY, SERIAL, IMEI, SERIAL_AND_IMEI
    val allocatedLandedCost: Double = 0.0, // Proportional transport + other cost allocated to line
    val serialImeisJson: String = "" // Serialized JSON array of entered serials/IMEIs
)

@Entity(
    tableName = "purchase_returns",
    foreignKeys = [
        ForeignKey(
            entity = PurchaseEntity::class,
            parentColumns = ["id"],
            childColumns = ["purchaseId"],
            onDelete = ForeignKey.RESTRICT
        ),
        ForeignKey(
            entity = SupplierEntity::class,
            parentColumns = ["id"],
            childColumns = ["supplierId"],
            onDelete = ForeignKey.RESTRICT
        )
    ],
    indices = [
        Index(value = ["returnNumber"], unique = true),
        Index(value = ["purchaseId"]),
        Index(value = ["supplierId"]),
        Index(value = ["returnDate"])
    ]
)
data class PurchaseReturnEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val returnNumber: String = "", // e.g. PRT-2026-000001
    val purchaseId: Long,
    val supplierId: Long,
    val supplierName: String = "",
    val returnDate: Long = System.currentTimeMillis(),
    val subtotal: Double = 0.0,
    val discount: Double = 0.0,
    val tax: Double = 0.0,
    val totalAmount: Double = 0.0, // Net return total credited/refunded
    val refundAmount: Double = 0.0, // Cash/bank refund from supplier
    val supplierCredit: Double = 0.0, // Balance adjusted on supplier account
    val reason: String = "",
    val status: String = "COMPLETED", // COMPLETED, CANCELLED
    val createdBy: String = "Admin",
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "purchase_return_items",
    foreignKeys = [
        ForeignKey(
            entity = PurchaseReturnEntity::class,
            parentColumns = ["id"],
            childColumns = ["returnId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["returnId"]),
        Index(value = ["productId"]),
        Index(value = ["purchaseItemId"])
    ]
)
data class PurchaseReturnItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val returnId: Long,
    val purchaseItemId: Long = 0,
    val productId: Long,
    val productName: String = "",
    val variantId: Long = 0,
    val quantity: Int,
    val unitCost: Double,
    val totalAmount: Double,
    val serialUnitId: Long = 0,
    val serialOrImei: String = ""
)

@Entity(
    tableName = "supplier_payments",
    foreignKeys = [
        ForeignKey(
            entity = SupplierEntity::class,
            parentColumns = ["id"],
            childColumns = ["supplierId"],
            onDelete = ForeignKey.RESTRICT
        )
    ],
    indices = [
        Index(value = ["paymentNumber"], unique = true),
        Index(value = ["supplierId"]),
        Index(value = ["purchaseId"]),
        Index(value = ["paymentDate"])
    ]
)
data class SupplierPaymentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val paymentNumber: String = "", // e.g. SPAY-2026-000001
    val supplierId: Long,
    val supplierName: String = "",
    val purchaseId: Long = 0, // Optional linked purchase invoice
    val paymentDate: Long = System.currentTimeMillis(),
    val amount: Double,
    val paymentMethod: String = "CASH", // CASH, BKASH, NAGAD, ROCKET, CARD, BANK, OTHER
    val referenceNumber: String = "",
    val notes: String = "",
    val createdBy: String = "Admin",
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "supplier_ledger_entries",
    foreignKeys = [
        ForeignKey(
            entity = SupplierEntity::class,
            parentColumns = ["id"],
            childColumns = ["supplierId"],
            onDelete = ForeignKey.RESTRICT
        )
    ],
    indices = [
        Index(value = ["supplierId"]),
        Index(value = ["transactionType"]),
        Index(value = ["createdAt"]),
        Index(value = ["referenceNumber"])
    ]
)
data class SupplierLedgerEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val supplierId: Long,
    val transactionType: String, // OPENING_BALANCE, PURCHASE, PURCHASE_RETURN, SUPPLIER_PAYMENT, SUPPLIER_REFUND, DEBIT_ADJUSTMENT, CREDIT_ADJUSTMENT
    val referenceId: Long = 0,
    val referenceNumber: String = "",
    val amount: Double, // Always positive absolute amount
    val signedAmount: Double, // Signed impact on supplier balance (+ for payable increase, - for decrease)
    val runningBalance: Double = 0.0, // Running balance after this transaction
    val description: String = "",
    val createdBy: String = "Admin",
    val createdAt: Long = System.currentTimeMillis()
)

