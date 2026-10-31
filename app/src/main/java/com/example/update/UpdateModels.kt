package com.example.update

import org.json.JSONObject
import java.io.File

/** The APK attached to a GitHub release. */
data class ReleaseInfo(
    val version: String,
    val apkUrl: String,
    /** Bytes, or -1 if unknown. */
    val size: Long,
    /** Lower-case hex SHA-256 that GitHub publishes for the asset, if any. */
    val sha256: String?,
    val notes: String,
)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class UpToDate(val checkedAt: Long) : UpdateState
    data class Downloading(val release: ReleaseInfo, val progress: Float) : UpdateState
    /** Downloaded and verified; installs when the app is in the background. */
    data class Ready(val release: ReleaseInfo, val file: File) : UpdateState
    data class Installing(val release: ReleaseInfo) : UpdateState
    data class Failed(val message: String) : UpdateState
}

object Versions {
    /** Compares "1.10.2"-style versions part by part; a leading "v" and anything after "-" are ignored. */
    fun compare(a: String, b: String): Int {
        val pa = parts(a)
        val pb = parts(b)
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val d = pa.getOrElse(i) { 0 }.compareTo(pb.getOrElse(i) { 0 })
            if (d != 0) return d
        }
        return 0
    }

    private fun parts(v: String) = v.trim().removePrefix("v").substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
}

object ReleaseParser {
    /** GitHub's "latest release" JSON -> its APK, or null when the release has none. */
    fun parse(json: String): ReleaseInfo? {
        val root = JSONObject(json)
        val tag = root.optString("tag_name")
        if (tag.isEmpty()) return null
        val assets = root.optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val asset = assets.getJSONObject(i)
            if (!asset.optString("name").endsWith(".apk")) continue
            val digest = asset.optString("digest")
            return ReleaseInfo(
                version = tag.removePrefix("v"),
                apkUrl = asset.getString("browser_download_url"),
                size = asset.optLong("size", -1),
                sha256 = if (digest.startsWith("sha256:")) digest.removePrefix("sha256:").lowercase() else null,
                notes = root.optString("body"),
            )
        }
        return null
    }
}

/** The phone's own update settings (not synced with the shop). */
class UpdatePrefs(context: android.content.Context) {
    private val prefs = context.getSharedPreferences("app_updates", android.content.Context.MODE_PRIVATE)

    /** Download and install new releases by itself (Settings → App updates). */
    var autoUpdate: Boolean
        get() = prefs.getBoolean("auto_update", true)
        set(value) = prefs.edit().putBoolean("auto_update", value).apply()
}
