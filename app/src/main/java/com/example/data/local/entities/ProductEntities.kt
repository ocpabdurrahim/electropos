package com.example.data.local.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "products",
    indices = [
        Index(value = ["sku"], unique = false),
        Index(value = ["barcode"], unique = false),
        Index(value = ["name"]),
        Index(value = ["categoryId"]),
        Index(value = ["brandId"]),
        Index(value = ["supplierId"])
    ]
)
data class ProductEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sku: String = "",
    val barcode: String = "",
    val name: String,
    val categoryId: Long = 0,
    val categoryName: String = "",
    val subcategoryId: Long = 0,
    val subcategoryName: String = "",
    val brandId: Long = 0,
    val brandName: String = "",
    val model: String = "",
    val description: String = "",
    val unitId: Long = 0,
    val unit: String = "Pcs", // Pcs, Box, Set, Meter
    val color: String = "",
    val variant: String = "", // e.g. 8GB/128GB, 256GB
    val productType: String = "PHYSICAL", // PHYSICAL, SERVICE
    val trackingType: String = "QUANTITY", // QUANTITY, SERIAL, IMEI, SERIAL_AND_IMEI
    val purchasePrice: Double = 0.0, // Weighted average purchase cost
    val wholesalePrice: Double = 0.0,
    val retailPrice: Double = 0.0,
    val minSellingPrice: Double = 0.0,
    val taxPercent: Double = 0.0,
    val discountPercent: Double = 0.0,
    val currentStock: Int = 0,
    val minStock: Int = 5,
    val maxStock: Int = 1000,
    val openingStock: Int = 0,
    val supplierId: Long = 0,
    val supplierName: String = "",
    val hasVariants: Boolean = false,
    val hasWarranty: Boolean = false,
    val warrantyPeriod: Int = 0,
    val warrantyUnit: String = "MONTHS", // DAYS, MONTHS, YEARS
    val warrantyPeriodMonths: Int = 0, // e.g. 12 months
    val warrantyType: String = "SERVICE", // SERVICE, REPLACEMENT, NONE
    val isSerialRequired: Boolean = false,
    val isImeiRequired: Boolean = false,
    val isBarcodeRequired: Boolean = false,
    val imageUri: String = "",
    val isActive: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val notes: String = ""
)

object InventoryStatus {
    const val IN_STOCK = "IN_STOCK"
    const val SOLD = "SOLD"
    const val RETURNED = "RETURNED"
    const val RETURNED_TO_SUPPLIER = "RETURNED" // Backwards-compatible alias
    const val DAMAGED = "DAMAGED"
    const val LOST = "LOST"
    const val CANCELLED = "CANCELLED"

    val ALL_STATUSES = setOf(IN_STOCK, SOLD, RETURNED, DAMAGED, LOST, CANCELLED)
    val SELLABLE_STATUSES = setOf(IN_STOCK)
    val NON_SELLABLE_STATUSES = setOf(SOLD, RETURNED, DAMAGED, LOST, CANCELLED)

    fun isSellable(status: String): Boolean = status == IN_STOCK
}

object TrackingType {
    const val QUANTITY = "QUANTITY"
    const val SERIAL = "SERIAL"
    const val IMEI = "IMEI"
    const val SERIAL_AND_IMEI = "SERIAL_AND_IMEI"

    fun requiresSerial(trackingType: String): Boolean =
        trackingType == SERIAL || trackingType == SERIAL_AND_IMEI

    fun requiresImei(trackingType: String): Boolean =
        trackingType == IMEI || trackingType == SERIAL_AND_IMEI
}

@Entity(
    tableName = "product_serial_imei",
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
        Index(value = ["serialNumber"], unique = true),
        Index(value = ["imei1"], unique = true),
        Index(value = ["imei2"], unique = true),
        Index(value = ["status"]),
        Index(value = ["purchaseItemId"])
    ]
)
data class ProductSerialImeiEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val productId: Long,
    val variantId: Long = 0,
    val purchaseItemId: Long = 0,
    val serialNumber: String? = null,
    val imei1: String? = null,
    val imei2: String? = null,
    val ram: String = "",
    val storage: String = "",
    val color: String = "",
    val purchasePrice: Double = 0.0,
    val sellingPrice: Double = 0.0,
    val supplierId: Long = 0,
    val purchaseId: Long = 0,
    val saleId: Long = 0,
    val invoiceNumber: String = "",
    val status: String = InventoryStatus.IN_STOCK,
    val warrantyPeriodMonths: Int = 0,
    val warrantyStartDate: Long = 0,
    val warrantyEndDate: Long = 0,
    val notes: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    init {
        val s1 = imei1?.trim()?.ifEmpty { null }
        val s2 = imei2?.trim()?.ifEmpty { null }
        if (s1 != null && s2 != null) {
            require(s1 != s2) { "IMEI 1 and IMEI 2 cannot be identical ($s1)" }
        }
    }
}
