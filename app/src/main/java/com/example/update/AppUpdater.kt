package com.example.update

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.content.pm.PackageInfoCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.example.BuildConfig
import com.example.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Keeps the app up to date from its GitHub releases.
 *
 * It downloads a newer APK, accepts it only if it is this app, signed with the
 * same key and has a higher version code, and installs it through
 * PackageInstaller while the app is in the background. On Android 12+ that needs
 * no tap (an app may update itself silently); otherwise, or when Android wants a
 * confirmation, a notification asks for one tap.
 */
class AppUpdater(
    private val context: Context,
    private val settings: UpdatePrefs,
    private val scope: CoroutineScope,
) {
    private val prefs = context.getSharedPreferences("updates", Context.MODE_PRIVATE)
    private val dir = File(context.noBackupFilesDir, "updates")
    private val lock = Mutex()

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state

    /** "1.1.0" for both "1.1.0" and the test build's "1.1.0-debug". */
    val currentVersion: String = BuildConfig.VERSION_NAME.substringBefore('-')

    @Volatile private var installing = false
    /** Android showed its own confirmation screen; don't start another install until the user is back. */
    @Volatile private var awaitingUser = false
    private var backgroundJob: Job? = null

    init {
        createChannel()
    }

    /** Whether Android lets this app install APKs (the per-app "Install unknown apps" switch). */
    fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    /**
     * Looks for a newer release and downloads it. Automatic runs respect the
     * setting and check at most every few hours; [userAsked] runs always check.
     */
    suspend fun checkAndDownload(userAsked: Boolean = false) {
        if (!lock.tryLock()) return
        try {
            if (!userAsked) {
                if (!settings.autoUpdate) return
                if (System.currentTimeMillis() - prefs.getLong(KEY_LAST_CHECK, 0) < MIN_CHECK_INTERVAL) {
                    restoreDownloaded()
                    return
                }
            }
            _state.value = UpdateState.Checking
            val release = withContext(Dispatchers.IO) { fetchLatest() }
            val now = System.currentTimeMillis()
            prefs.edit { putLong(KEY_LAST_CHECK, now) }
            if (release == null || Versions.compare(release.version, currentVersion) <= 0) {
                Log.i(TAG, "Up to date ($currentVersion; latest ${release?.version})")
                _state.value = UpdateState.UpToDate(now)
                cleanup()
                return
            }
            Log.i(TAG, "Downloading ${release.version}")
            val file = withContext(Dispatchers.IO) { downloadVerified(release) }
            _state.value = UpdateState.Ready(release, file)
            if (!isForeground() && settings.autoUpdate) install(release, file)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Update check failed", e)
            _state.value = UpdateState.Failed(e.message ?: e.javaClass.simpleName)
        } finally {
            lock.unlock()
        }
    }

    /** Starts installing the downloaded update now (the "Install" button). */
    fun installNow() {
        val ready = _state.value as? UpdateState.Ready ?: return
        install(ready.release, ready.file)
    }

    fun onAppForeground() {
        awaitingUser = false
        backgroundJob?.cancel()
    }

    /** Leaving the app: a waiting update installs a little later, unless the user comes straight back. */
    fun onAppBackground() {
        val ready = _state.value as? UpdateState.Ready ?: return
        if (installing || awaitingUser) return
        backgroundJob?.cancel()
        backgroundJob = scope.launch {
            delay(BACKGROUND_DELAY) // e.g. a quick trip to the file picker for a backup
            if (settings.autoUpdate && !isForeground()) install(ready.release, ready.file)
        }
    }

    /** Deletes downloads for versions that are now installed (or older). */
    fun cleanup() {
        dir.listFiles()?.forEach { f ->
            val version = f.name.removePrefix("ElectroPOS-").removeSuffix(".apk")
            if (!f.name.endsWith(".apk") || Versions.compare(version, currentVersion) <= 0) f.delete()
        }
    }

    // ---- Checking and downloading -------------------------------------------

    private fun fetchLatest(): ReleaseInfo? {
        val conn = URL(BuildConfig.UPDATE_API_URL).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 15_000
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.setRequestProperty("User-Agent", "ElectroPOS-Android/$currentVersion")
        try {
            return when (val code = conn.responseCode) {
                200 -> ReleaseParser.parse(conn.inputStream.bufferedReader().readText())
                404 -> null // no releases yet
                else -> error("GitHub answered $code")
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun restoreDownloaded() {
        if (_state.value is UpdateState.Ready) return
        val newest = dir.listFiles { f -> f.name.startsWith("ElectroPOS-") && f.name.endsWith(".apk") }
            ?.maxWithOrNull { a, b -> Versions.compare(versionOf(a), versionOf(b)) } ?: return
        if (Versions.compare(versionOf(newest), currentVersion) > 0 && isGenuineUpdate(newest)) {
            _state.value = UpdateState.Ready(ReleaseInfo(versionOf(newest), "", newest.length(), null, ""), newest)
        }
    }

    private fun versionOf(f: File) = f.name.removePrefix("ElectroPOS-").removeSuffix(".apk")

    private fun downloadVerified(release: ReleaseInfo): File {
        dir.mkdirs()
        val target = File(dir, "ElectroPOS-${release.version}.apk")
        if (target.exists() && isGenuineUpdate(target)) return target

        val partial = File(dir, "download.part")
        val digest = MessageDigest.getInstance("SHA-256")
        val conn = URL(release.apkUrl).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.setRequestProperty("User-Agent", "ElectroPOS-Android/$currentVersion")
        try {
            check(conn.responseCode == 200) { "Download failed (${conn.responseCode})" }
            val total = if (release.size > 0) release.size else conn.contentLengthLong
            var done = 0L
            var lastReport = 0L
            conn.inputStream.use { input ->
                partial.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        digest.update(buffer, 0, n)
                        done += n
                        if (total > 0 && done - lastReport > 256 * 1024) {
                            lastReport = done
                            _state.value = UpdateState.Downloading(release, done.toFloat() / total)
                        }
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
        val sha = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        if (release.sha256 != null && sha != release.sha256) {
            partial.delete()
            error("The download was damaged; it will be retried")
        }
        target.delete()
        check(partial.renameTo(target)) { "Could not save the update" }
        if (!isGenuineUpdate(target)) {
            target.delete()
            error("That file is not a newer ElectroPOS signed with the same key")
        }
        return target
    }

    /** This app, a higher version code, and exactly the same signing certificate. */
    @Suppress("DEPRECATION")
    private fun isGenuineUpdate(apk: File): Boolean {
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val archive = pm.getPackageArchiveInfo(apk.path, flags) ?: return false
        if (archive.packageName != context.packageName) return false
        val installed = pm.getPackageInfo(context.packageName, flags)
        if (PackageInfoCompat.getLongVersionCode(archive) <= PackageInfoCompat.getLongVersionCode(installed)) return false
        val mine = signers(installed)
        return mine.isNotEmpty() && signers(archive) == mine
    }

    @Suppress("DEPRECATION")
    private fun signers(info: PackageInfo): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
        return signatures.orEmpty().map { sig ->
            MessageDigest.getInstance("SHA-256").digest(sig.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        }.toSet()
    }

    // ---- Installing ---------------------------------------------------------

    private fun install(release: ReleaseInfo, file: File) {
        if (installing) return
        installing = true
        _state.value = UpdateState.Installing(release)
        try {
            val installer = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(context.packageName)
                setSize(file.length())
                // Android 12+: an app updating itself may do so without asking.
                if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
            val sessionId = installer.createSession(params)
            installer.openSession(sessionId).use { session ->
                session.openWrite("base.apk", 0, file.length()).use { out ->
                    file.inputStream().use { it.copyTo(out) }
                    session.fsync(out)
                }
                val callback = Intent(context, InstallResultReceiver::class.java)
                    .putExtra(EXTRA_VERSION, release.version)
                // Mutable: Android adds the result to this intent.
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                session.commit(PendingIntent.getBroadcast(context, sessionId, callback, flags).intentSender)
            }
            Log.i(TAG, "Install of ${release.version} committed")
        } catch (e: Exception) {
            Log.w(TAG, "Install failed", e)
            installing = false
            _state.value = UpdateState.Failed("Could not install the update: ${e.message}")
        }
    }

    /** Called by [InstallResultReceiver] with PackageInstaller's verdict. */
    fun onInstallResult(status: Int, message: String?, confirm: Intent?, version: String?) {
        Log.i(TAG, "Install result $status ${message.orEmpty()}")
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                installing = false
                restoreDownloaded()
                if (confirm == null) return
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                val foreground = ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
                if (foreground && runCatching { context.startActivity(confirm) }.isSuccess) {
                    awaitingUser = true
                } else {
                    notifyConfirm(confirm, version)
                }
            }
            PackageInstaller.STATUS_SUCCESS -> Unit // this process is about to be replaced
            else -> {
                installing = false
                _state.value = UpdateState.Failed(message ?: "The update was not installed")
            }
        }
    }

    private suspend fun isForeground(): Boolean = withContext(Dispatchers.Main) {
        ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
    }

    // ---- Notifications ------------------------------------------------------

    private fun createChannel() {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "App updates", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "When a new version of ElectroPOS is ready or installed"
            }
        )
    }

    private fun canNotify() = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission") // checked by canNotify()
    private fun notifyConfirm(confirm: Intent, version: String?) {
        if (!canNotify()) return
        val pending = PendingIntent.getActivity(context, 0, confirm, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_electropos)
            .setContentTitle("ElectroPOS ${version.orEmpty()} is ready")
            .setContentText("Tap to finish updating.")
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(NOTIFY_READY, notification)
    }

    @SuppressLint("MissingPermission") // checked by canNotify()
    fun notifyUpdated() {
        NotificationManagerCompat.from(context).cancel(NOTIFY_READY)
        if (!canNotify()) return
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
        val pending = PendingIntent.getActivity(context, 1, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_electropos)
            .setContentTitle("ElectroPOS updated to $currentVersion")
            .setContentText("Your records are untouched.")
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(NOTIFY_UPDATED, notification)
    }

    companion object {
        const val EXTRA_VERSION = "com.example.update.VERSION"
        private const val TAG = "ElectroPosUpdate"
        private const val CHANNEL_ID = "app_updates"
        private const val NOTIFY_READY = 2001
        private const val NOTIFY_UPDATED = 2002
        private const val KEY_LAST_CHECK = "lastCheck"
        private const val MIN_CHECK_INTERVAL = 6 * 60 * 60 * 1000L
        private const val BACKGROUND_DELAY = 20_000L
    }
}
