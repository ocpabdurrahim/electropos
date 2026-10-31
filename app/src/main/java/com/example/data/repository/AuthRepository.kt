package com.example.data.repository

import androidx.room.withTransaction
import com.example.core.security.UserRoles
import com.example.core.utils.SecurityUtils
import com.example.data.local.database.AppDatabase
import com.example.data.local.entities.AuditLogEntity
import com.example.data.local.entities.ShopProfileEntity
import com.example.data.local.entities.UserEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import com.example.sync.SyncLocal

class AuthRepository(private val database: AppDatabase) {
    private val userDao = database.userDao()
    private val settingsDao = database.settingsAndAuditDao()

    private val _currentUser = MutableStateFlow<UserEntity?>(null)
    val currentUser: StateFlow<UserEntity?> = _currentUser.asStateFlow()

    private val _isLoggedIn = MutableStateFlow(false)
    val isLoggedIn: StateFlow<Boolean> = _isLoggedIn.asStateFlow()

    val shopProfile: Flow<ShopProfileEntity?> = settingsDao.getShopProfile()

    val isSetupCompleted: Flow<Boolean> = settingsDao.getShopProfile().map { profile ->
        profile?.setupCompleted == true
    }

    suspend fun isSetupCompletedNow(): Boolean = settingsDao.getShopProfileSync()?.setupCompleted == true

    suspend fun checkAutoLogin(): Boolean {
        val profile = settingsDao.getShopProfileSync() ?: return false
        if (!profile.setupCompleted || !profile.rememberLogin || profile.lastActiveUserId <= 0L) {
            return false
        }
        val user = userDao.getUserById(profile.lastActiveUserId)
        return if (user != null && user.isActive) {
            _currentUser.value = user
            _isLoggedIn.value = true
            userDao.updateLastLogin(user.id)
            true
        } else {
            false
        }
    }

    suspend fun completeSetup(
        shopProfile: ShopProfileEntity,
        adminName: String,
        adminUsername: String,
        pin: String
    ): Result<Unit> = runCatching {
        database.withTransaction {
            val salt = SecurityUtils.generateSalt()
            val pinHash = SecurityUtils.hashPin(pin, salt)

            val adminUser = UserEntity(
                name = adminName.trim(),
                username = adminUsername.trim().lowercase(),
                pinHash = pinHash,
                salt = salt,
                role = "ADMIN",
                isActive = true
            )
            val adminId = userDao.insertUser(adminUser)

            val completedProfile = shopProfile.copy(
                id = 1,
                setupCompleted = true,
                lastActiveUserId = adminId,
                updatedAt = System.currentTimeMillis()
            )
            settingsDao.saveShopProfile(completedProfile)

            settingsDao.insertAuditLog(
                AuditLogEntity(
                    userRole = "ADMIN",
                    action = "SHOP_SETUP_COMPLETED",
                    module = "SETUP",
                    recordId = "1",
                    description = "Initial shop setup completed for '${shopProfile.shopName}' by $adminName"
                )
            )

            val createdUser = adminUser.copy(id = adminId)
            _currentUser.value = createdUser
            _isLoggedIn.value = true
        }
    }

    // Wrong-PIN lockout: after 5 failures the next try waits 30 s, doubling each further failure (max 15 min)
    private val failedAttempts = mutableMapOf<String, Int>()
    private val lockedUntil = mutableMapOf<String, Long>()

    suspend fun login(username: String, pin: String): Result<UserEntity> = runCatching {
        val cleanUsername = username.trim().lowercase()
        val now = System.currentTimeMillis()
        val waitUntil = lockedUntil[cleanUsername] ?: 0L
        if (now < waitUntil) {
            val seconds = ((waitUntil - now) / 1000L) + 1
            throw IllegalStateException("Too many wrong PINs. Try again in $seconds seconds.")
        }

        val user = userDao.getUserByUsername(cleanUsername)
        val isValid = user != null && SecurityUtils.verifyPin(pin, user.salt, user.pinHash)
        if (user == null || !isValid) {
            val failures = (failedAttempts[cleanUsername] ?: 0) + 1
            failedAttempts[cleanUsername] = failures
            if (failures >= 5) {
                val delayMs = (30_000L shl (failures - 5).coerceAtMost(5)).coerceAtMost(15 * 60_000L)
                lockedUntil[cleanUsername] = now + delayMs
            }
            throw IllegalArgumentException("Invalid username or PIN.")
        }
        failedAttempts.remove(cleanUsername)
        lockedUntil.remove(cleanUsername)

        // Upgrade PINs stored with the old, faster hash
        if (SecurityUtils.needsRehash(user.pinHash)) {
            val newSalt = SecurityUtils.generateSalt()
            userDao.updatePin(user.id, SecurityUtils.hashPin(pin, newSalt), newSalt)
        }

        userDao.updateLastLogin(user.id)
        val profile = settingsDao.getShopProfileSync()
        if (profile != null && profile.rememberLogin) {
            settingsDao.setLastActiveUserId(user.id)
        }

        settingsDao.insertAuditLog(
            AuditLogEntity(
                userRole = user.role,
                action = "USER_LOGIN",
                module = "AUTH",
                recordId = user.id.toString(),
                description = "User '${user.username}' logged in successfully"
            )
        )

        _currentUser.value = user
        _isLoggedIn.value = true
        user
    }

    suspend fun logout() {
        settingsDao.setLastActiveUserId(0L)
        _currentUser.value = null
        _isLoggedIn.value = false
    }

    suspend fun changePin(userId: Long, oldPin: String, newPin: String): Result<Unit> = runCatching {
        val user = userDao.getUserById(userId)
            ?: throw IllegalStateException("User not found.")

        if (!SecurityUtils.verifyPin(oldPin, user.salt, user.pinHash)) {
            throw IllegalArgumentException("Current PIN is incorrect.")
        }

        if (newPin.length < 4) {
            throw IllegalArgumentException("New PIN must be at least 4 digits.")
        }

        val newSalt = SecurityUtils.generateSalt()
        val newHash = SecurityUtils.hashPin(newPin, newSalt)
        userDao.updatePin(userId, newHash, newSalt)

        settingsDao.insertAuditLog(
            AuditLogEntity(
                userRole = user.role,
                action = "PIN_CHANGED",
                module = "SECURITY",
                recordId = userId.toString(),
                description = "User '${user.username}' updated security PIN"
            )
        )
    }

    suspend fun updateUserName(userId: Long, newName: String): Result<Unit> = runCatching {
        val user = userDao.getUserById(userId)
            ?: throw IllegalStateException("User not found.")
        userDao.updateUser(user.copy(name = newName.trim(), updatedAt = System.currentTimeMillis()))
        _currentUser.value = user.copy(name = newName.trim())
    }

    suspend fun updateShopProfile(profile: ShopProfileEntity): Result<Unit> = runCatching {
        settingsDao.saveShopProfile(profile.copy(updatedAt = System.currentTimeMillis()))
    }

    suspend fun updateThemeMode(mode: String) {
        settingsDao.setThemeMode(mode)
    }

    suspend fun updateRememberLogin(remember: Boolean) {
        settingsDao.setRememberLogin(remember)
    }

    val allUsers: Flow<List<UserEntity>> = userDao.getAllUsers()

    private fun requireAdmin(): UserEntity {
        val me = _currentUser.value ?: throw IllegalStateException("Please sign in again.")
        require(me.role == UserRoles.ADMIN) { "Only an admin can manage users." }
        return me
    }

    private fun validatePin(pin: String) {
        require(pin.length in 4..12 && pin.all { it.isDigit() }) { "PIN must be 4 to 12 digits." }
    }

    suspend fun createUser(name: String, username: String, role: String, pin: String): Result<Long> = runCatching {
        val me = requireAdmin()
        val cleanName = name.trim()
        val cleanUsername = username.trim().lowercase()
        require(cleanName.isNotBlank()) { "Name is required." }
        require(cleanUsername.matches(Regex("[a-z0-9._-]{3,30}"))) { "Username: 3-30 letters, digits, dot, dash or underscore." }
        require(role in UserRoles.ALL) { "Unknown role." }
        validatePin(pin)
        if (userDao.getAnyUserByUsername(cleanUsername) != null) {
            throw IllegalArgumentException("Username '$cleanUsername' is already taken.")
        }
        val salt = SecurityUtils.generateSalt()
        val id = userDao.insertUser(
            UserEntity(name = cleanName, username = cleanUsername, pinHash = SecurityUtils.hashPin(pin, salt), salt = salt, role = role)
        )
        settingsDao.insertAuditLog(
            AuditLogEntity(userRole = me.role, action = "USER_CREATED", module = "SECURITY", recordId = id.toString(),
                description = "${me.username} created user '$cleanUsername' ($role)")
        )
        id
    }

    suspend fun updateUser(userId: Long, name: String, role: String, isActive: Boolean): Result<Unit> = runCatching {
        val me = requireAdmin()
        val user = userDao.getUserById(userId) ?: throw IllegalArgumentException("User not found.")
        require(name.isNotBlank()) { "Name is required." }
        require(role in UserRoles.ALL) { "Unknown role." }
        if (user.id == me.id) {
            require(isActive) { "You can't deactivate your own account." }
            require(role == UserRoles.ADMIN) { "You can't remove your own admin role." }
        }
        val losesAdmin = user.role == UserRoles.ADMIN && user.isActive && (role != UserRoles.ADMIN || !isActive)
        if (losesAdmin && userDao.countActiveAdmins() <= 1) {
            throw IllegalStateException("At least one active admin is needed.")
        }
        userDao.updateUser(user.copy(name = name.trim(), role = role, isActive = isActive, updatedAt = System.currentTimeMillis()))
        settingsDao.insertAuditLog(
            AuditLogEntity(userRole = me.role, action = "USER_UPDATED", module = "SECURITY", recordId = userId.toString(),
                description = "${me.username} updated '${user.username}': role $role, ${if (isActive) "active" else "inactive"}")
        )
    }

    suspend fun resetUserPin(userId: Long, newPin: String): Result<Unit> = runCatching {
        val me = requireAdmin()
        validatePin(newPin)
        val user = userDao.getUserById(userId) ?: throw IllegalArgumentException("User not found.")
        val salt = SecurityUtils.generateSalt()
        userDao.updatePin(user.id, SecurityUtils.hashPin(newPin, salt), salt)
        settingsDao.insertAuditLog(
            AuditLogEntity(userRole = me.role, action = "USER_PIN_RESET", module = "SECURITY", recordId = userId.toString(),
                description = "${me.username} reset the PIN of '${user.username}'")
        )
    }

    suspend fun resetApplication(): Result<Unit> = runCatching {
        database.withTransaction {
            // Unlinked first, so wiping the shop sends no deletions to the cloud
            SyncLocal.unlink(database)
            database.clearAllTables()
            _currentUser.value = null
            _isLoggedIn.value = false
        }
    }
}
