package com.example.data.local.dao

import androidx.room.*
import com.example.sync.SyncIds
import com.example.data.local.entities.UserEntity
import kotlinx.coroutines.flow.Flow

@Dao
abstract class UserDao(private val db: RoomDatabase) {
    @Query("SELECT * FROM users WHERE username = :username AND isActive = 1 LIMIT 1")
    abstract suspend fun getUserByUsername(username: String): UserEntity?

    @Query("SELECT * FROM users WHERE id = :id LIMIT 1")
    abstract suspend fun getUserById(id: Long): UserEntity?

    @Query("SELECT * FROM users ORDER BY name ASC")
    abstract fun getAllUsers(): Flow<List<UserEntity>>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertUserRaw(user: UserEntity): Long

    suspend fun insertUser(user: UserEntity): Long =
        insertUserRaw(if (user.id == 0L) user.copy(id = SyncIds.next(db, "users")) else user)

    @Query("SELECT * FROM users WHERE username = :username LIMIT 1")
    abstract suspend fun getAnyUserByUsername(username: String): UserEntity?

    @Query("SELECT COUNT(*) FROM users WHERE role = 'ADMIN' AND isActive = 1")
    abstract suspend fun countActiveAdmins(): Int

    @Update
    abstract suspend fun updateUser(user: UserEntity)

    @Query("UPDATE users SET lastLoginAt = :timestamp WHERE id = :id")
    abstract suspend fun updateLastLogin(id: Long, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE users SET pinHash = :pinHash, salt = :salt, updatedAt = :timestamp WHERE id = :id")
    abstract suspend fun updatePin(id: Long, pinHash: String, salt: String, timestamp: Long = System.currentTimeMillis())

    @Query("SELECT COUNT(*) FROM users WHERE isActive = 1")
    abstract suspend fun getUserCount(): Int

    @Query("SELECT * FROM users WHERE role = 'ADMIN' AND isActive = 1 LIMIT 1")
    abstract suspend fun getPrimaryAdmin(): UserEntity?
}
