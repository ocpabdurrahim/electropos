package com.example.data.repository

import com.example.data.local.dao.SettingsAndAuditDao
import com.example.data.local.dao.WarrantyDao
import com.example.data.local.entities.ShopProfileEntity
import com.example.data.local.entities.WarrantyRecordEntity
import kotlinx.coroutines.flow.Flow

class WarrantyRepository(private val warrantyDao: WarrantyDao) {
    val allWarranties: Flow<List<WarrantyRecordEntity>> = warrantyDao.getAllWarranties()

    fun getExpiringSoonWarranties(daysAhead: Int = 30): Flow<List<WarrantyRecordEntity>> {
        val now = System.currentTimeMillis()
        val threshold = now + (daysAhead.toLong() * 24L * 60L * 60L * 1000L)
        return warrantyDao.getExpiringSoonWarranties(now, threshold)
    }

    fun getExpiredWarranties(): Flow<List<WarrantyRecordEntity>> {
        val now = System.currentTimeMillis()
        return warrantyDao.getExpiredWarranties(now)
    }

    fun searchWarranties(query: String) = warrantyDao.searchWarranties(query)

    suspend fun updateWarrantyStatus(warranty: WarrantyRecordEntity, newStatus: String, notes: String) {
        warrantyDao.updateWarranty(
            warranty.copy(
                status = newStatus,
                claimNotes = notes
            )
        )
    }
}

class SettingsRepository(private val settingsDao: SettingsAndAuditDao) {
    val shopProfile: Flow<ShopProfileEntity?> = settingsDao.getShopProfile()
    suspend fun getShopProfileSync(): ShopProfileEntity {
        return settingsDao.getShopProfileSync() ?: ShopProfileEntity().also {
            settingsDao.saveShopProfile(it)
        }
    }

    suspend fun updateShopProfile(profile: ShopProfileEntity) {
        settingsDao.saveShopProfile(profile.copy(updatedAt = System.currentTimeMillis()))
    }

    fun getRecentAuditLogs(limit: Int = 100) = settingsDao.getRecentAuditLogs(limit)
}
