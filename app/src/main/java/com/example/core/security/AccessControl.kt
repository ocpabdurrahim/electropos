package com.example.core.security

import com.example.data.local.entities.UserEntity

object UserRoles {
    const val ADMIN = "ADMIN"
    const val MANAGER = "MANAGER"
    const val SALESPERSON = "SALESPERSON"

    val ALL = listOf(ADMIN, MANAGER, SALESPERSON)

    fun label(role: String): String = when (role) {
        ADMIN -> "Admin (owner)"
        MANAGER -> "Manager"
        SALESPERSON -> "Salesperson"
        else -> role
    }

    fun description(role: String): String = when (role) {
        ADMIN -> "Everything, including users, backup/restore and reset"
        MANAGER -> "Sales, purchases, suppliers, products, reports. Not users, restore or reset"
        SALESPERSON -> "POS, sales, returns and customers. No purchases, costs or profit"
        else -> ""
    }
}

/** Things a role may or may not do. Checked by navigation (screens) and by the screens themselves (buttons, numbers). */
enum class Feature {
    POS,
    SALES_HISTORY,
    SALES_RETURN,
    CANCEL_SALE,
    CUSTOMERS,
    PRODUCTS_VIEW,
    PRODUCTS_EDIT,
    CATEGORIES_BRANDS,
    PURCHASES,
    SUPPLIERS,
    VIEW_COSTS_AND_PROFIT,
    SHOP_PROFILE,
    SETTINGS,
    CLOUD_SYNC,
    BACKUP,
    RESTORE,
    USERS,
    RESET_APP,
    EXPENSES,
    CASH_BOOK,
    REPORTS,
    WARRANTY
}

object AccessControl {
    private val salesperson = setOf(
        Feature.POS, Feature.SALES_HISTORY, Feature.SALES_RETURN, Feature.CUSTOMERS, Feature.PRODUCTS_VIEW, Feature.SETTINGS,
        Feature.WARRANTY
    )
    private val manager = Feature.values().toSet() - setOf(Feature.USERS, Feature.RESTORE, Feature.RESET_APP)

    fun can(role: String?, feature: Feature): Boolean = when (role) {
        UserRoles.ADMIN -> true
        UserRoles.MANAGER -> feature in manager
        UserRoles.SALESPERSON -> feature in salesperson
        else -> false
    }

    fun can(user: UserEntity?, feature: Feature): Boolean = user != null && user.isActive && can(user.role, feature)
}
