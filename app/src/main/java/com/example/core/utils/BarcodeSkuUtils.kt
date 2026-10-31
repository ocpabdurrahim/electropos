package com.example.core.utils

import java.util.Locale
import kotlin.random.Random

object SkuUtils {
    fun generateSku(
        categoryName: String,
        brandName: String,
        model: String,
        suffix: Long = System.currentTimeMillis() % 10000
    ): String {
        val catPrefix = categoryName.trim().filter { it.isLetterOrDigit() }.take(3).uppercase(Locale.US).ifBlank { "PRD" }
        val brandPrefix = brandName.trim().filter { it.isLetterOrDigit() }.take(3).uppercase(Locale.US).ifBlank { "GEN" }
        val modelClean = model.trim().replace("\\s+".toRegex(), "-").filter { it.isLetterOrDigit() || it == '-' }.take(6).uppercase(Locale.US).ifBlank { "MOD" }
        val formattedSuffix = "%04d".format(suffix)
        return "$catPrefix-$brandPrefix-$modelClean-$formattedSuffix"
    }
}

object BarcodeUtils {
    /**
     * Generates a valid 13-digit EAN-13 barcode with internal prefix "20" (in-store retail)
     * and a verified modulo 10 checksum digit.
     */
    fun generateEan13(productIdHint: Long = System.currentTimeMillis() % 1000000000L): String {
        val raw12 = "20" + "%010d".format(Math.abs(productIdHint))
        val checksum = calculateEan13Checksum(raw12)
        return "$raw12$checksum"
    }

    fun calculateEan13Checksum(first12Digits: String): Int {
        var sum = 0
        for (i in 0 until 12) {
            val digit = first12Digits[i].digitToInt()
            sum += if (i % 2 == 0) digit else digit * 3
        }
        val remainder = sum % 10
        return if (remainder == 0) 0 else 10 - remainder
    }

    fun isValidEan13(barcode: String): Boolean {
        if (barcode.length != 13 || !barcode.all { it.isDigit() }) return false
        val checkDigit = barcode[12].digitToInt()
        val calculated = calculateEan13Checksum(barcode.substring(0, 12))
        return checkDigit == calculated
    }
}
