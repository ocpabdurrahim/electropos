package com.example.core.utils

import java.security.MessageDigest
import java.security.SecureRandom
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

object SecurityUtils {
    fun generateSalt(): String {
        val random = SecureRandom()
        val saltBytes = ByteArray(16)
        random.nextBytes(saltBytes)
        return saltBytes.joinToString("") { "%02x".format(it) }
    }

    private const val PBKDF2_PREFIX = "pbkdf2$"
    private const val PBKDF2_ITERATIONS = 120_000

    /** PBKDF2-HMAC-SHA256: slow on purpose, so a copied database can't be brute-forced quickly. */
    fun hashPin(pin: String, salt: String): String {
        val spec = javax.crypto.spec.PBEKeySpec(pin.toCharArray(), salt.toByteArray(Charsets.UTF_8), PBKDF2_ITERATIONS, 256)
        val key = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        spec.clearPassword()
        return PBKDF2_PREFIX + key.joinToString("") { "%02x".format(it) }
    }

    /** Hash format used before PBKDF2 (salted SHA-256 x1001); only kept to verify old PINs. */
    private fun legacyHashPin(pin: String, salt: String): String {
        val input = "$salt:$pin:electropos_salt_key"
        val md = MessageDigest.getInstance("SHA-256")
        var digest = md.digest(input.toByteArray(Charsets.UTF_8))
        for (i in 0 until 1000) {
            digest = md.digest(digest)
        }
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun verifyPin(pin: String, salt: String, expectedHash: String): Boolean {
        val computed = if (expectedHash.startsWith(PBKDF2_PREFIX)) hashPin(pin, salt) else legacyHashPin(pin, salt)
        return MessageDigest.isEqual(computed.toByteArray(Charsets.UTF_8), expectedHash.toByteArray(Charsets.UTF_8))
    }

    /** True for PINs stored with the old scheme; they are re-hashed on the next successful login. */
    fun needsRehash(storedHash: String): Boolean = !storedHash.startsWith(PBKDF2_PREFIX)
}

object WarrantyUtils {

    /** End of a product's warranty that starts at [start] (calendar days/months/years); 0 when it has none. */
    fun endDate(product: com.example.data.local.entities.ProductEntity, start: Long = System.currentTimeMillis()): Long {
        if (product.warrantyPeriodMonths <= 0 && product.warrantyPeriod <= 0) return 0L
        val cal = Calendar.getInstance().apply { timeInMillis = start }
        when {
            product.warrantyPeriod > 0 && product.warrantyUnit == "DAYS" -> cal.add(Calendar.DAY_OF_YEAR, product.warrantyPeriod)
            product.warrantyPeriod > 0 && product.warrantyUnit == "YEARS" -> cal.add(Calendar.YEAR, product.warrantyPeriod)
            product.warrantyPeriod > 0 -> cal.add(Calendar.MONTH, product.warrantyPeriod)
            else -> cal.add(Calendar.MONTH, product.warrantyPeriodMonths)
        }
        return cal.timeInMillis
    }
}

object DateTimeUtils {
    fun getStartOfDay(timestamp: Long = System.currentTimeMillis()): Long {
        val cal = Calendar.getInstance()
        cal.timeInMillis = timestamp
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    fun getEndOfDay(timestamp: Long = System.currentTimeMillis()): Long {
        val cal = Calendar.getInstance()
        cal.timeInMillis = timestamp
        cal.set(Calendar.HOUR_OF_DAY, 23)
        cal.set(Calendar.MINUTE, 59)
        cal.set(Calendar.SECOND, 59)
        cal.set(Calendar.MILLISECOND, 999)
        return cal.timeInMillis
    }

    fun formatDate(timestamp: Long, pattern: String = "dd MMM yyyy"): String {
        if (timestamp <= 0L) return "N/A"
        val sdf = SimpleDateFormat(pattern, Locale.getDefault())
        return sdf.format(Date(timestamp))
    }

    fun formatDateTime(timestamp: Long): String {
        if (timestamp <= 0L) return "N/A"
        val sdf = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault())
        return sdf.format(Date(timestamp))
    }
}

object CurrencyUtils {
    /** Amount as typed into a text field: "1500" for whole taka, "1363.95" when there are paisa. */
    fun toInput(amount: Double): String {
        val rounded = Math.round(amount * 100.0) / 100.0
        return if (rounded % 1.0 == 0.0) rounded.toLong().toString()
        else java.math.BigDecimal.valueOf(rounded).setScale(2, java.math.RoundingMode.HALF_UP).toPlainString()
    }

    fun format(amount: Double?, symbol: String = "৳"): String {
        val value = amount ?: 0.0
        val formatter = NumberFormat.getNumberInstance(Locale.US)
        formatter.minimumFractionDigits = 2
        formatter.maximumFractionDigits = 2
        return "$symbol ${formatter.format(value)}"
    }

    fun formatBDT(amount: Double?): String = format(amount, "৳")
    fun formatBDT(amount: Number?): String = format(amount?.toDouble(), "৳")
}

fun Double?.formatBDT(): String = CurrencyUtils.format(this, "৳")
fun Number?.formatBDT(): String = CurrencyUtils.format(this?.toDouble(), "৳")

