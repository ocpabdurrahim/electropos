package com.example.data.local.dao

import androidx.room.*
import com.example.sync.SyncIds
import com.example.data.local.entities.SubcategoryEntity
import com.example.data.local.entities.UnitEntity
import kotlinx.coroutines.flow.Flow

@Dao
abstract class SubcategoryDao(private val db: RoomDatabase) {
    @Query("SELECT * FROM subcategories WHERE categoryId = :categoryId AND isActive = 1 ORDER BY name ASC")
    abstract fun getSubcategoriesByCategory(categoryId: Long): Flow<List<SubcategoryEntity>>

    @Query("SELECT * FROM subcategories WHERE categoryId = :categoryId AND isActive = 1 ORDER BY name ASC")
    abstract suspend fun getSubcategoriesByCategorySync(categoryId: Long): List<SubcategoryEntity>

    @Query("SELECT * FROM subcategories WHERE isActive = 1 ORDER BY name ASC")
    abstract fun getAllSubcategories(): Flow<List<SubcategoryEntity>>

    @Query("SELECT * FROM subcategories WHERE id = :id LIMIT 1")
    abstract suspend fun getSubcategoryById(id: Long): SubcategoryEntity?

    @Query("UPDATE products SET subcategoryName = :name WHERE subcategoryId = :subcategoryId")
    abstract suspend fun renameSubcategoryOnProducts(subcategoryId: Long, name: String)

    @Query("SELECT * FROM subcategories WHERE categoryId = :categoryId AND name = :name LIMIT 1")
    abstract suspend fun getSubcategoryByName(categoryId: Long, name: String): SubcategoryEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertSubcategoryRaw(subcategory: SubcategoryEntity): Long

    suspend fun insertSubcategory(subcategory: SubcategoryEntity): Long =
        insertSubcategoryRaw(if (subcategory.id == 0L) subcategory.copy(id = SyncIds.next(db, "subcategories")) else subcategory)

    @Update
    abstract suspend fun updateSubcategory(subcategory: SubcategoryEntity)

    @Query("UPDATE subcategories SET isActive = 0 WHERE id = :id")
    abstract suspend fun deactivateSubcategory(id: Long)

    @Query("SELECT COUNT(*) FROM products WHERE subcategoryId = :subcategoryId AND isActive = 1")
    abstract suspend fun countProductsInSubcategory(subcategoryId: Long): Int

    @Delete
    abstract suspend fun deleteSubcategory(subcategory: SubcategoryEntity)
}

@Dao
abstract class UnitDao(private val db: RoomDatabase) {
    @Query("SELECT * FROM units WHERE isActive = 1 ORDER BY name ASC")
    abstract fun getAllUnits(): Flow<List<UnitEntity>>

    @Query("SELECT * FROM units WHERE isActive = 1 ORDER BY name ASC")
    abstract suspend fun getAllUnitsSync(): List<UnitEntity>

    @Query("SELECT * FROM units WHERE id = :id LIMIT 1")
    abstract suspend fun getUnitById(id: Long): UnitEntity?

    @Query("SELECT * FROM units WHERE name = :name LIMIT 1")
    abstract suspend fun getUnitByName(name: String): UnitEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertUnitRaw(unit: UnitEntity): Long

    suspend fun insertUnit(unit: UnitEntity): Long =
        insertUnitRaw(if (unit.id == 0L) unit.copy(id = SyncIds.next(db, "units")) else unit)

    @Update
    abstract suspend fun updateUnit(unit: UnitEntity)

    @Query("UPDATE units SET isActive = 0 WHERE id = :id")
    abstract suspend fun deactivateUnit(id: Long)

    /** Linked to a cloud shop (or joining one): its lists come from the cloud. */
    @Query("SELECT EXISTS(SELECT 1 FROM sync_flags WHERE id = 1 AND (shopId IS NOT NULL OR downloading = 1))")
    abstract suspend fun isCloudLinked(): Boolean
}
