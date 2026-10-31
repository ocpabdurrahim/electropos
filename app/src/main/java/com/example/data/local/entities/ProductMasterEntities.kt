package com.example.data.local.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "subcategories",
    foreignKeys = [
        ForeignKey(
            entity = CategoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["categoryId"],
            onDelete = ForeignKey.RESTRICT
        )
    ],
    indices = [
        Index(value = ["categoryId"]),
        Index(value = ["name"])
    ]
)
data class SubcategoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val categoryId: Long,
    val name: String,
    val description: String = "",
    val isActive: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "units",
    // Not unique: two phones can add the same name offline; the copy shows under Sync issues
    indices = [Index(value = ["name"])]
)
data class UnitEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String, // Piece, Box, Pack, Meter, Set, Pair
    val code: String = "Pcs",
    val isActive: Boolean = true,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "product_variants",
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
        Index(value = ["sku"]),
        Index(value = ["barcode"])
    ]
)
data class ProductVariantEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val productId: Long,
    val variantName: String, // e.g. "8GB / 256GB / Black"
    val sku: String = "",
    val barcode: String = "",
    val color: String = "",
    val ram: String = "",
    val storage: String = "",
    val otherAttributes: String = "", // e.g. "Size: 42, Speed: 3200MHz"
    val purchasePrice: Double = 0.0,
    val wholesalePrice: Double = 0.0,
    val retailPrice: Double = 0.0,
    val minSellingPrice: Double = 0.0,
    val minStock: Int = 2,
    val currentStock: Int = 0,
    val isActive: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "product_price_history",
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
        Index(value = ["changedAt"])
    ]
)
data class ProductPriceHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val productId: Long,
    val purchasePrice: Double,
    val wholesalePrice: Double,
    val retailPrice: Double,
    val minSellingPrice: Double,
    val changedAt: Long = System.currentTimeMillis(),
    val changedBy: String = "Admin",
    val note: String = ""
)
