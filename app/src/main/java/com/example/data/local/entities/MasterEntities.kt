package com.example.data.local.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "categories",
    // Not unique: two phones can add the same name offline; the copy shows under Sync issues
    indices = [Index(value = ["name"])]
)
data class CategoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val description: String = "",
    val iconName: String = "category",
    val isActive: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "brands",
    // Not unique: two phones can add the same name offline; the copy shows under Sync issues
    indices = [Index(value = ["name"])]
)
data class BrandEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val description: String = "",
    val originCountry: String = "",
    val logoUri: String = "",
    val isActive: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "suppliers",
    indices = [
        Index(value = ["supplierCode"], unique = true),
        Index(value = ["name"]),
        Index(value = ["phone"])
    ]
)
data class SupplierEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val supplierCode: String = "",
    val name: String,
    val company: String = "",
    val phone: String,
    val alternativePhone: String = "",
    val whatsApp: String = "",
    val email: String = "",
    val address: String = "",
    val city: String = "",
    val openingDue: Double = 0.0,
    val currentDue: Double = 0.0,
    val creditLimit: Double = 0.0,
    val notes: String = "",
    val isActive: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "customers",
    indices = [
        Index(value = ["customerCode"], unique = true),
        Index(value = ["phone"]),
        Index(value = ["name"])
    ]
)
data class CustomerEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val customerCode: String = "",
    val name: String,
    val phone: String,
    val alternativePhone: String = "",
    val whatsApp: String = "",
    val email: String = "",
    val address: String = "",
    val city: String = "",
    val customerType: String = "REGULAR", // CASH, REGULAR, CREDIT
    val openingDue: Double = 0.0,
    val currentDue: Double = 0.0,
    val creditLimit: Double = 0.0,
    val notes: String = "",
    val isActive: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
