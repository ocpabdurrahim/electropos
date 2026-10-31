package com.example.sync

import androidx.room.withTransaction
import com.example.data.local.database.AppDatabase
import java.util.Locale

/**
 * Who may put a shop online (docs/data-model.md, "Who may create a shop"): the app owner, and
 * people the app owner approved under `creators/{email}`.
 */
object AppOwner {
    /** Must match isAppAdmin() in firebase/firestore.rules and APP_OWNERS in the web app. */
    val EMAILS = setOf("ocpabdurrahim@gmail.com")

    fun isAppOwner(email: String?): Boolean = email != null && email.trim().lowercase() in EMAILS
}

/** Member roles in a cloud shop (not the PIN roles of the counter). */
object ShopRoles {
    const val OWNER = "OWNER"
    const val ADMIN = "ADMIN"
    const val MANAGER = "MANAGER"
    const val SALESPERSON = "SALESPERSON"

    /** Roles that may link a phone to the shop. */
    val PHONE = setOf(OWNER, ADMIN, MANAGER)

    /** Roles an owner or admin can hand out (nobody else becomes owner). */
    val ASSIGNABLE = listOf(ADMIN, MANAGER, SALESPERSON)

    fun canManageMembers(role: String?) = role == OWNER || role == ADMIN

    fun label(role: String?): String = when (role) {
        OWNER -> "Owner"
        ADMIN -> "Admin"
        MANAGER -> "Manager"
        SALESPERSON -> "Salesperson"
        else -> role ?: "-"
    }
}

/** Till codes: A … V, X, Y, Z, AA, AB, … (a single W is the web's). */
object TillCodes {
    const val WEB = "W"
    private val SINGLE = ('A'..'Z').filter { it != 'W' }

    fun forIndex(index: Long): String {
        require(index >= 0)
        if (index < SINGLE.size) return SINGLE[index.toInt()].toString()
        var n = index - SINGLE.size
        var length = 2
        var span = 26L * 26L
        while (n >= span) {
            n -= span
            length++
            span *= 26L
        }
        val chars = CharArray(length)
        for (i in length - 1 downTo 0) {
            chars[i] = 'A' + (n % 26L).toInt()
            n /= 26L
        }
        return String(chars)
    }
}

/** The phone's link state as the rest of the app needs it (repositories, lifecycle hooks). */
object SyncLocal {
    /** Linked to a cloud shop, or joining one. */
    suspend fun isLinked(db: AppDatabase): Boolean =
        db.syncDao().flags()?.let { it.isLinked || it.downloading } == true

    /**
     * Stops recording and forgets the shop, without sending anything (no tombstones). Changes not
     * uploaded yet stay on this phone only. The id block stays: it belongs to this phone for good.
     */
    suspend fun unlink(db: AppDatabase) {
        val dao = db.syncDao()
        db.withTransaction {
            val flags = dao.flags() ?: SyncFlagsEntity()
            dao.saveFlags(
                flags.copy(
                    tracking = false, applying = false, tillCode = null, shopId = null, shopName = null, email = null,
                    role = null, downloading = false, linkedAt = 0
                )
            )
            dao.clearOutbox()
            dao.clearParked()
            dao.clearCursors()
            dao.clearConflicts()
            dao.clearCounters()
        }
    }
}

/**
 * Numbers people see (docs/data-model.md, "Numbers people see"). While the phone is online every
 * new number carries its till code and comes from its own counter; offline the old formats are
 * used unchanged (the generators fall back when [next] returns null).
 */
object DocNumbers {
    enum class Series(val key: String) {
        INVOICE("inv"), PURCHASE("pur"), PURCHASE_RETURN("prt"), SALES_RETURN("srt"),
        SUPPLIER_PAYMENT("spay"), CUSTOMER_PAYMENT("cpay"), CUSTOMER_CODE("cus"), SUPPLIER_CODE("sup")
    }

    /** This phone's till code while it is linked to a shop, else null. */
    suspend fun till(db: AppDatabase): String? = db.syncDao().flags()?.takeIf { it.isLinked }?.tillCode

    /**
     * The first free number of [series] on this till: the counter (starting at 1), moved past any
     * number already in the data. The counter is saved there, so each call checks at most a few.
     */
    suspend fun next(db: AppDatabase, series: Series, format: (till: String, n: Long) -> String, taken: suspend (String) -> Boolean): String? {
        val till = till(db) ?: return null
        val dao = db.syncDao()
        var n = dao.counter(series.key) ?: 1L
        var number = format(till, n)
        while (taken(number)) {
            n++
            number = format(till, n)
        }
        dao.setCounter(SyncCounterEntity(series.key, n))
        return number
    }

    // Online formats; T = till code
    fun invoice(prefix: String, year: Int, till: String, n: Long) = String.format(Locale.US, "%s%d-%s%05d", prefix, year, till, n)
    fun purchase(year: Int, till: String, n: Long) = String.format(Locale.US, "PUR-%d-%s%05d", year, till, n)
    fun purchaseReturn(year: Int, till: String, n: Long) = String.format(Locale.US, "PRT-%d-%s%05d", year, till, n)
    fun salesReturn(prefix: String, year: Int, till: String, n: Long) = String.format(Locale.US, "%s%d-%s%05d", prefix, year, till, n)
    fun supplierPayment(year: Int, till: String, n: Long) = String.format(Locale.US, "SPAY-%d-%s%05d", year, till, n)
    fun customerPayment(prefix: String, till: String, n: Long) = String.format(Locale.US, "%s%s%05d", prefix, till, n)
    fun customerCode(prefix: String, till: String, n: Long) = String.format(Locale.US, "%s%s%04d", prefix, till, n)
    fun supplierCode(till: String, n: Long) = String.format(Locale.US, "SUP-%s%04d", till, n)
}
