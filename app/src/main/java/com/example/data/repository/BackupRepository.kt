package com.example.data.repository

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.database.Cursor
import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.Log
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.data.local.database.AppDatabase
import com.example.sync.SyncLocal
import com.example.sync.SyncSchema
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** A problem the shop owner can understand and act on (wrong file, newer app, no space...). */
class BackupException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Contents of manifest.json inside every backup / safety-copy zip. */
data class BackupManifest(
    val format: String = BackupRepository.BACKUP_FORMAT,
    val formatVersion: Int = BackupRepository.BACKUP_FORMAT_VERSION,
    val appId: String = "",
    val appVersion: String = "",
    val kind: String = BackupRepository.KIND_FULL,
    val reason: String = "",
    val createdAt: Long = 0L,
    val dbSchemaVersion: Int = 0,
    val shopName: String = "",
    val counts: Map<String, Long> = emptyMap(),
    val dbSizeBytes: Long = 0L,
    val dbSha256: String = "",
    val device: String = ""
) {
    fun toJson(): String {
        val countsJson = JSONObject()
        for ((key, value) in counts) countsJson.put(key, value)
        val root = JSONObject()
        root.put("format", format)
        root.put("formatVersion", formatVersion)
        root.put("appId", appId)
        root.put("appVersion", appVersion)
        root.put("kind", kind)
        root.put("reason", reason)
        root.put("createdAt", createdAt)
        root.put("createdAtText", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).format(Date(createdAt)))
        root.put("dbName", BackupRepository.DB_NAME)
        root.put("dbSchemaVersion", dbSchemaVersion)
        root.put("shopName", shopName)
        root.put("counts", countsJson)
        root.put("dbSizeBytes", dbSizeBytes)
        root.put("dbSha256", dbSha256)
        root.put("device", device)
        return root.toString(2)
    }

    companion object {
        fun fromJson(text: String): BackupManifest {
            val o = try {
                JSONObject(text)
            } catch (e: Exception) {
                throw BackupException("This backup's manifest is damaged, so the file can't be used.", e)
            }
            if (o.optString("format") != BackupRepository.BACKUP_FORMAT) {
                throw BackupException("This file is not an ElectroPOS backup.")
            }
            val formatVersion = o.optInt("formatVersion", 1)
            if (formatVersion > BackupRepository.BACKUP_FORMAT_VERSION) {
                throw BackupException("This backup was made by a newer version of ElectroPOS. Update the app first, then restore.")
            }
            val counts = LinkedHashMap<String, Long>()
            val countsObj = o.optJSONObject("counts")
            if (countsObj != null) {
                val keys = countsObj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    counts[key] = countsObj.optLong(key, 0L)
                }
            }
            return BackupManifest(
                format = o.optString("format"),
                formatVersion = formatVersion,
                appId = o.optString("appId", ""),
                appVersion = o.optString("appVersion", ""),
                kind = o.optString("kind", BackupRepository.KIND_FULL),
                reason = o.optString("reason", ""),
                createdAt = o.optLong("createdAt", 0L),
                dbSchemaVersion = o.optInt("dbSchemaVersion", 0),
                shopName = o.optString("shopName", ""),
                counts = counts,
                dbSizeBytes = o.optLong("dbSizeBytes", 0L),
                dbSha256 = o.optString("dbSha256", ""),
                device = o.optString("device", "")
            )
        }
    }
}

/** Result of checking a backup file. The checked database waits in app storage until [BackupRepository.stageRestore]. */
data class BackupInspection(
    val sourceLabel: String,
    val manifest: BackupManifest,
    /** user_version of the database inside the file (before any upgrade). */
    val schemaVersion: Int,
    val currentSchemaVersion: Int,
    /** Real row counts of the checked database (products, customers, suppliers, sales, purchases). */
    val counts: Map<String, Long>,
    val shopName: String,
    val dbSizeBytes: Long,
    val candidatePath: String
) {
    val createdAt: Long get() = manifest.createdAt
    val needsUpgrade: Boolean get() = schemaVersion < currentSchemaVersion
    val isSafetyCopy: Boolean get() = manifest.kind == BackupRepository.KIND_SAFETY
}

/** A copy of the database kept inside app storage (made before a restore or a reset). */
data class SafetyCopy(
    val path: String,
    val createdAt: Long,
    val reason: String,
    val sizeBytes: Long,
    val schemaVersion: Int,
    val counts: Map<String, Long>
) {
    val suggestedFileName: String
        get() = "ElectroPOS-safety-copy-" + BackupRepository.fileStamp(createdAt) + ".zip"
}

data class BackupResult(val displayName: String?, val sizeBytes: Long, val manifest: BackupManifest)

data class CsvExportResult(val displayName: String?, val rowCounts: Map<String, Int>)

data class LastBackupInfo(val time: Long, val fileName: String?)

data class LastRestoreInfo(val appliedAt: Long, val source: String, val backupCreatedAt: Long, val error: String?)

/**
 * Full backup / staged restore / safety copies / CSV export for the Room database "electro_pos.db".
 *
 * Restore is staged: the checked database is parked as a pending file, the app restarts, and
 * [applyPendingRestore] (called from AppDatabase.getInstance before Room opens the file) swaps it in.
 */
class BackupRepository(context: Context, private val database: AppDatabase) {

    private val appContext: Context = context.applicationContext ?: context
    private val mutex = Mutex()
    private val prefs: SharedPreferences get() = backupPrefs(appContext)

    // ------------------------------------------------------------------ status

    fun lastBackupInfo(): LastBackupInfo? {
        val time = prefs.getLong(KEY_LAST_BACKUP_AT, 0L)
        return if (time > 0L) LastBackupInfo(time, prefs.getString(KEY_LAST_BACKUP_NAME, null)) else null
    }

    fun lastRestoreInfo(): LastRestoreInfo? {
        val p = prefs
        val at = p.getLong(KEY_LAST_RESTORE_AT, 0L)
        if (at <= 0L) return null
        return LastRestoreInfo(
            appliedAt = at,
            source = p.getString(KEY_LAST_RESTORE_SOURCE, null) ?: "backup file",
            backupCreatedAt = p.getLong(KEY_LAST_RESTORE_BACKUP_CREATED, 0L),
            error = p.getString(KEY_LAST_RESTORE_ERROR, null)
        )
    }

    fun lastExportTime(): Long = prefs.getLong(KEY_LAST_EXPORT_AT, 0L)

    fun hasPendingRestore(): Boolean = Companion.hasPendingRestore(appContext)

    /** Relaunches the app and ends this process (used right after [stageRestore]). */
    fun restartApp() {
        Companion.restartApp(appContext)
    }

    // ------------------------------------------------------------------ full backup

    /** Writes a full backup zip to a SAF document the user created (CreateDocument("application/zip")). */
    suspend fun createBackup(uri: Uri): Result<BackupResult> = withContext(Dispatchers.IO) {
        mutex.withLock {
            runCatching {
                val written = try {
                    val out = appContext.contentResolver.openOutputStream(uri)
                        ?: throw BackupException("Could not open the chosen location for writing.")
                    val counting = CountingOutputStream(out)
                    val manifest = counting.use { writeBackupZip(it, KIND_FULL, "", System.currentTimeMillis()) }
                    verifyWrittenBackup(uri, manifest)
                    Pair(manifest, counting.count)
                } catch (e: Exception) {
                    deleteDocumentQuietly(uri)
                    throw friendly(e)
                }
                val name = displayName(uri)
                prefs.edit()
                    .putLong(KEY_LAST_BACKUP_AT, written.first.createdAt)
                    .putString(KEY_LAST_BACKUP_NAME, name)
                    .apply()
                BackupResult(name, written.second, written.first)
            }
        }
    }

    /** Same zip as [createBackup], written to any stream (used by tests). */
    internal suspend fun writeFullBackupTo(out: OutputStream): BackupManifest = withContext(Dispatchers.IO) {
        mutex.withLock { writeBackupZip(out, KIND_FULL, "", System.currentTimeMillis()) }
    }

    // ------------------------------------------------------------------ restore

    /** Reads and fully validates a backup chosen with OpenDocument. Nothing on the phone changes yet. */
    suspend fun inspectBackup(uri: Uri): Result<BackupInspection> = withContext(Dispatchers.IO) {
        mutex.withLock {
            runCatching {
                val label = displayName(uri) ?: "Selected file"
                inspectLocked(label) {
                    appContext.contentResolver.openInputStream(uri)
                        ?: throw BackupException("Could not open the selected file.")
                }
            }
        }
    }

    /** Validates one of the safety copies listed by [listSafetyCopies]. */
    suspend fun inspectSafetyCopy(path: String): Result<BackupInspection> = withContext(Dispatchers.IO) {
        mutex.withLock {
            runCatching {
                val file = requireSafetyCopyFile(path)
                val label = readSafetyCopy(file)?.let { "Safety copy (${it.reason})" } ?: "Safety copy"
                inspectLocked(label) { FileInputStream(file) }
            }
        }
    }

    internal suspend fun inspectBackupStream(label: String, open: () -> InputStream): Result<BackupInspection> =
        withContext(Dispatchers.IO) {
            mutex.withLock { runCatching { inspectLocked(label, open) } }
        }

    /** Throws away a checked-but-not-confirmed backup. */
    suspend fun discardInspection(inspection: BackupInspection) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val candidate = File(inspection.candidatePath)
            if (candidate.absoluteFile == candidateFile(appContext).absoluteFile) deleteDbFiles(candidate)
        }
    }

    /**
     * After the user confirmed: saves a safety copy of the CURRENT data, then parks the checked database
     * as the pending restore. Call [restartApp] right after success; the swap happens on next start.
     */
    suspend fun stageRestore(inspection: BackupInspection): Result<Unit> = withContext(Dispatchers.IO) {
        mutex.withLock {
            runCatching {
                val candidate = File(inspection.candidatePath)
                if (candidate.absoluteFile != candidateFile(appContext).absoluteFile ||
                    !candidate.isFile || candidate.length() != inspection.dbSizeBytes || !looksLikeSqlite(candidate)
                ) {
                    throw BackupException("The checked backup is no longer available. Please choose the file again.")
                }
                try {
                    createSafetyCopyLocked("Before restore")
                } catch (e: Exception) {
                    throw BackupException(
                        "Restore cancelled: could not save a safety copy of the current data first. " +
                            (friendly(e).message ?: ""), e
                    )
                }
                val pending = pendingRestoreFile(appContext)
                try {
                    pending.parentFile?.mkdirs()
                    deleteDbFiles(pending)
                    writePendingInfo(inspection)
                    if (!candidate.renameTo(pending)) {
                        copyFile(candidate, pending)
                        deleteDbFiles(candidate)
                    }
                    if (!looksLikeSqlite(pending)) throw IOException("prepared file is not a database")
                } catch (e: Exception) {
                    Log.e(TAG, "stageRestore failed", e)
                    deleteDbFiles(pending)
                    pendingInfoFile(appContext).delete()
                    throw BackupException("Could not prepare the restore, so nothing was changed. ${friendly(e).message ?: ""}", e)
                }
                // The current data is about to be replaced: stop syncing it (nothing is sent, no deletions)
                SyncLocal.unlink(database)
                Unit
            }
        }
    }

    // ------------------------------------------------------------------ safety copies

    /** Stores a copy of the current database inside app storage (newest [MAX_SAFETY_COPIES] are kept). */
    suspend fun createSafetyCopy(reason: String): Result<SafetyCopy> = withContext(Dispatchers.IO) {
        mutex.withLock {
            runCatching {
                try {
                    createSafetyCopyLocked(reason)
                } catch (e: Exception) {
                    throw friendly(e)
                }
            }
        }
    }

    /** Newest first. */
    suspend fun listSafetyCopies(): List<SafetyCopy> = withContext(Dispatchers.IO) {
        safetyCopyFiles(safetyDir(appContext))
            .sortedByDescending { stampOf(it) }
            .mapNotNull { readSafetyCopy(it) }
    }

    /** Saves a safety copy out to a SAF document (CreateDocument). */
    suspend fun exportSafetyCopy(path: String, uri: Uri): Result<String?> = withContext(Dispatchers.IO) {
        mutex.withLock {
            runCatching {
                val file = requireSafetyCopyFile(path)
                try {
                    val out = appContext.contentResolver.openOutputStream(uri)
                        ?: throw BackupException("Could not open the chosen location for writing.")
                    out.use { o -> FileInputStream(file).use { it.copyTo(o, BUFFER) } }
                } catch (e: Exception) {
                    deleteDocumentQuietly(uri)
                    throw friendly(e)
                }
                displayName(uri)
            }
        }
    }

    // ------------------------------------------------------------------ CSV export

    /** One zip with products/customers/suppliers/sales/purchases CSV files (UTF-8 with BOM, for Excel). */
    suspend fun exportCsv(uri: Uri): Result<CsvExportResult> = withContext(Dispatchers.IO) {
        mutex.withLock {
            runCatching {
                val counts = try {
                    val out = appContext.contentResolver.openOutputStream(uri)
                        ?: throw BackupException("Could not open the chosen location for writing.")
                    out.use { writeCsvZip(it) }
                } catch (e: Exception) {
                    deleteDocumentQuietly(uri)
                    throw friendly(e)
                }
                prefs.edit().putLong(KEY_LAST_EXPORT_AT, System.currentTimeMillis()).apply()
                CsvExportResult(displayName(uri), counts)
            }
        }
    }

    internal suspend fun writeCsvExportTo(out: OutputStream): Map<String, Int> = withContext(Dispatchers.IO) {
        mutex.withLock { writeCsvZip(out) }
    }

    // ================================================================== internals

    private fun currentSchemaVersion(): Int = database.openHelper.readableDatabase.version

    private fun liveDatabaseFile(db: SupportSQLiteDatabase): File {
        val path = db.path
        if (path.isNullOrBlank() || path == ":memory:") {
            throw BackupException("This database is not stored in a file, so it can't be backed up.")
        }
        return File(path)
    }

    /** Consistent copy of the live database (main file + any WAL) into [target]. */
    private fun snapshotLiveDatabase(target: File) {
        val db = database.openHelper.writableDatabase
        val liveFile = liveDatabaseFile(db)
        val liveWal = File(liveFile.path + "-wal")
        ensureFreeSpace(target.parentFile ?: appContext.cacheDir, liveFile.length() * 2 + liveWal.length() + SPACE_MARGIN)
        checkpointLive(db)
        deleteDbFiles(target)
        // Holding the write lock: nobody can commit (and so nobody can auto-checkpoint) while we copy.
        db.beginTransactionNonExclusive()
        try {
            copyFile(liveFile, target)
            if (liveWal.isFile && liveWal.length() > 0L) copyFile(liveWal, File(target.path + "-wal"))
        } finally {
            db.endTransaction()
        }
    }

    private fun checkpointLive(db: SupportSQLiteDatabase) {
        for (attempt in 0 until 4) {
            val busy = try {
                db.query("PRAGMA wal_checkpoint(TRUNCATE)").use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
            } catch (e: Exception) {
                Log.w(TAG, "wal_checkpoint failed: ${e.message}")
                0
            }
            if (busy == 0) return
            try { Thread.sleep(150L * (attempt + 1)) } catch (_: InterruptedException) { return }
        }
        Log.w(TAG, "WAL checkpoint stayed busy; the WAL file is copied with the database instead")
    }

    private fun writeBackupZip(out: OutputStream, kind: String, reason: String, createdAt: Long): BackupManifest {
        val work = File(appContext.cacheDir, "backup_work/" + System.nanoTime())
        work.mkdirs()
        try {
            val snapshot = File(work, DB_NAME)
            snapshotLiveDatabase(snapshot)
            val facts = finalizeAndInspect(snapshot)
            val manifest = BackupManifest(
                appId = appContext.packageName,
                appVersion = appVersionName(),
                kind = kind,
                reason = reason,
                createdAt = createdAt,
                dbSchemaVersion = facts.version,
                shopName = facts.shopName,
                counts = facts.counts,
                dbSizeBytes = snapshot.length(),
                dbSha256 = sha256(snapshot),
                device = deviceName()
            )
            ZipOutputStream(BufferedOutputStream(NonClosingOutputStream(out), BUFFER)).use { zip ->
                zip.putNextEntry(ZipEntry(MANIFEST_ENTRY))
                zip.write(manifest.toJson().toByteArray(Charsets.UTF_8))
                zip.closeEntry()
                zip.putNextEntry(ZipEntry(DB_ENTRY))
                FileInputStream(snapshot).use { it.copyTo(zip, BUFFER) }
                zip.closeEntry()
            }
            return manifest
        } finally {
            work.deleteRecursively()
        }
    }

    /** Reads the document back and checks the database checksum. Skipped if the provider can't be read. */
    private fun verifyWrittenBackup(uri: Uri, manifest: BackupManifest) {
        val input = try {
            appContext.contentResolver.openInputStream(uri)
        } catch (e: Exception) {
            Log.w(TAG, "Backup written but can't be read back for verification: ${e.message}")
            null
        } ?: return
        var manifestFound = false
        var dbSha: String? = null
        try {
            ZipInputStream(BufferedInputStream(input, BUFFER)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    when (entry.name) {
                        MANIFEST_ENTRY -> manifestFound = true
                        DB_ENTRY -> dbSha = sha256(zip)
                    }
                    zip.closeEntry()
                }
            }
        } catch (e: IOException) {
            throw BackupException("The backup file could not be verified after saving. Please try again.", e)
        }
        if (!manifestFound || dbSha != manifest.dbSha256) {
            throw BackupException("The backup file could not be verified after saving. Please try again.")
        }
    }

    private fun inspectLocked(label: String, open: () -> InputStream): BackupInspection {
        val candidate = candidateFile(appContext)
        candidate.parentFile?.mkdirs()
        deleteDbFiles(candidate)
        try {
            val manifest = open().use { extractBackup(it, candidate) }
            val before = finalizeAndInspect(candidate)
            val current = currentSchemaVersion()
            if (before.version < 1) {
                throw BackupException("The database inside this file is empty or is not an ElectroPOS database.")
            }
            if (before.version > current) {
                throw BackupException(
                    "This backup was made by a newer version of ElectroPOS (database version ${before.version}; " +
                        "this app supports up to $current). Update the app first, then restore."
                )
            }
            val missing = REQUIRED_TABLES.filterNot { it in before.tables }
            if (missing.isNotEmpty()) {
                throw BackupException("This backup is incomplete (missing: ${missing.joinToString()}). It can't be restored.")
            }
            // Open it exactly like the app will (migrations + schema check) so a bad file can never
            // leave the app unable to start after the restart.
            dryRunOpenWithRoom(candidate)
            val after = finalizeAndInspect(candidate)
            if (after.version != current) {
                throw BackupException("This backup could not be upgraded to the current app version.")
            }
            return BackupInspection(
                sourceLabel = label,
                manifest = manifest,
                schemaVersion = before.version,
                currentSchemaVersion = current,
                counts = after.counts,
                shopName = after.shopName.ifBlank { manifest.shopName },
                dbSizeBytes = candidate.length(),
                candidatePath = candidate.absolutePath
            )
        } catch (e: Exception) {
            deleteDbFiles(candidate)
            if (e !is BackupException) Log.w(TAG, "Backup check failed", e)
            throw friendly(e)
        }
    }

    /** Streams the zip: manifest.json is read into memory, the database goes to [candidate]. */
    private fun extractBackup(input: InputStream, candidate: File): BackupManifest {
        var manifestText: String? = null
        var dbSha: String? = null
        try {
            ZipInputStream(BufferedInputStream(input, BUFFER)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (!entry.isDirectory) {
                        when (entry.name) {
                            MANIFEST_ENTRY -> manifestText = readLimited(zip, MAX_MANIFEST_BYTES).toString(Charsets.UTF_8)
                            DB_ENTRY -> dbSha = copyLimited(zip, candidate, MAX_DB_BYTES)
                        }
                    }
                    zip.closeEntry()
                }
            }
        } catch (e: BackupException) {
            throw e
        } catch (e: IOException) {
            throw BackupException("This file is damaged or is not a valid backup .zip.", e)
        } catch (e: IllegalArgumentException) {
            throw BackupException("This file is damaged or is not a valid backup .zip.", e)
        }
        val text = manifestText
        if (text == null || dbSha == null) {
            throw BackupException("This file is not an ElectroPOS backup. Choose a .zip made with \"Create backup\".")
        }
        val manifest = BackupManifest.fromJson(text)
        if (manifest.dbSha256.isNotBlank() && !manifest.dbSha256.equals(dbSha, ignoreCase = true)) {
            throw BackupException("This backup file is damaged (checksum does not match). It can't be restored.")
        }
        return manifest
    }

    private class DbFacts(val version: Int, val tables: Set<String>, val counts: Map<String, Long>, val shopName: String)

    /**
     * Opens a private copy, folds any WAL into the main file (journal_mode=DELETE so it is one
     * self-contained file), runs quick_check and reads version / tables / counts.
     */
    private fun finalizeAndInspect(file: File): DbFacts {
        if (!looksLikeSqlite(file)) {
            throw BackupException("The database inside the backup is damaged or is not an ElectroPOS database.")
        }
        val db = try {
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE)
        } catch (e: Exception) {
            throw BackupException("The database inside the backup could not be opened.", e)
        }
        try {
            runPragma(db, "PRAGMA wal_checkpoint(TRUNCATE)")
            runPragma(db, "PRAGMA journal_mode=DELETE")
            val check = try {
                db.rawQuery("PRAGMA quick_check", null).use { c -> if (c.moveToFirst()) c.getString(0) else null }
            } catch (e: Exception) {
                null
            }
            if (!"ok".equals(check, ignoreCase = true)) {
                throw BackupException("The database inside the backup is damaged (integrity check failed).")
            }
            val version = db.version
            val tables = HashSet<String>()
            db.rawQuery("SELECT name FROM sqlite_master WHERE type = 'table'", null).use { c ->
                while (c.moveToNext()) c.getString(0)?.let { tables.add(it) }
            }
            val counts = LinkedHashMap<String, Long>()
            for (table in COUNT_TABLES) {
                if (table in tables) counts[table] = DatabaseUtils.queryNumEntries(db, table)
            }
            val shopName = if ("shop_profile" in tables) {
                try {
                    db.rawQuery("SELECT shopName FROM shop_profile ORDER BY id LIMIT 1", null).use { c ->
                        if (c.moveToFirst()) c.getString(0) ?: "" else ""
                    }
                } catch (e: Exception) {
                    ""
                }
            } else ""
            return DbFacts(version, tables, counts, shopName)
        } catch (e: BackupException) {
            throw e
        } catch (e: Exception) {
            throw BackupException("The database inside the backup could not be read.", e)
        } finally {
            try { db.close() } catch (_: Exception) { }
            deleteSidecars(file)
        }
    }

    /**
     * Opens the candidate with Room (same migrations, no destructive fallback) and closes it again.
     * Opened by name, like the app opens electro_pos.db, so it lives in the databases folder.
     */
    private fun dryRunOpenWithRoom(file: File) {
        check(file.absoluteFile == candidateFile(appContext).absoluteFile) { "dry run only works on the candidate" }
        var roomDb: AppDatabase? = null
        try {
            roomDb = Room.databaseBuilder(appContext, AppDatabase::class.java, CANDIDATE_DB_NAME)
                .addMigrations(*discoverMigrations())
                .build()
            val sdb = roomDb.openHelper.writableDatabase
            sdb.query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() }
        } catch (e: Exception) {
            throw BackupException(
                "This backup can't be opened by this version of ElectroPOS (${e.message ?: e.javaClass.simpleName}).", e
            )
        } finally {
            try { roomDb?.close() } catch (_: Exception) { }
            deleteSidecars(file)
        }
    }

    private fun createSafetyCopyLocked(reason: String): SafetyCopy {
        val dir = safetyDir(appContext)
        dir.mkdirs()
        val live = liveDatabaseFile(database.openHelper.writableDatabase)
        ensureFreeSpace(dir, live.length() + SPACE_MARGIN)
        // The file-name key only orders the copies (newest = largest). It always grows, even if the phone's
        // clock was set back, so the copy made right now is never the one pruned.
        val newestKey = safetyCopyFiles(dir).maxOfOrNull { stampOf(it) } ?: 0L
        var key = maxOf(System.currentTimeMillis(), newestKey + 1)
        var file = File(dir, "$SAFETY_PREFIX$key.zip")
        while (file.exists()) {
            key += 1
            file = File(dir, "$SAFETY_PREFIX$key.zip")
        }
        val tmp = File(dir, file.name + ".tmp")
        try {
            val cleanReason = reason.trim().ifBlank { "Manual" }.take(80)
            val manifest = FileOutputStream(tmp).use { fos ->
                val m = writeBackupZip(fos, KIND_SAFETY, cleanReason, System.currentTimeMillis())
                fos.flush()
                fos.fd.sync()
                m
            }
            if (!tmp.renameTo(file)) throw IOException("Could not save the safety copy.")
            pruneSafetyCopies(dir)
            return SafetyCopy(file.absolutePath, manifest.createdAt, manifest.reason, file.length(), manifest.dbSchemaVersion, manifest.counts)
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    private fun pruneSafetyCopies(dir: File) {
        dir.listFiles()?.filter { it.name.endsWith(".tmp") }?.forEach { it.delete() }
        safetyCopyFiles(dir)
            .sortedByDescending { stampOf(it) }
            .drop(MAX_SAFETY_COPIES)
            .forEach { it.delete() }
    }

    private fun safetyCopyFiles(dir: File): List<File> =
        dir.listFiles()?.filter { it.isFile && it.name.startsWith(SAFETY_PREFIX) && it.name.endsWith(".zip") }.orEmpty()

    private fun stampOf(file: File): Long =
        file.name.removePrefix(SAFETY_PREFIX).removeSuffix(".zip").toLongOrNull() ?: file.lastModified()

    private fun readSafetyCopy(file: File): SafetyCopy? {
        return try {
            ZipFile(file).use { zf ->
                val entry = zf.getEntry(MANIFEST_ENTRY)
                if (entry == null) {
                    null
                } else {
                    val text = zf.getInputStream(entry).use { readLimited(it, MAX_MANIFEST_BYTES) }.toString(Charsets.UTF_8)
                    val m = BackupManifest.fromJson(text)
                    SafetyCopy(
                        path = file.absolutePath,
                        createdAt = if (m.createdAt > 0L) m.createdAt else stampOf(file),
                        reason = m.reason.ifBlank { "Safety copy" },
                        sizeBytes = file.length(),
                        schemaVersion = m.dbSchemaVersion,
                        counts = m.counts
                    )
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Unreadable safety copy ${file.name}: ${e.message}")
            null
        }
    }

    private fun requireSafetyCopyFile(path: String): File {
        val file = File(path)
        val dir = safetyDir(appContext)
        val inside = try {
            file.canonicalFile.parentFile == dir.canonicalFile
        } catch (e: IOException) {
            false
        }
        if (!inside || !file.isFile) throw BackupException("That safety copy no longer exists.")
        return file
    }

    private fun writePendingInfo(inspection: BackupInspection) {
        val info = JSONObject()
        info.put("source", inspection.sourceLabel)
        info.put("backupCreatedAt", inspection.createdAt)
        info.put("stagedAt", System.currentTimeMillis())
        FileOutputStream(pendingInfoFile(appContext)).use { fos ->
            fos.write(info.toString().toByteArray(Charsets.UTF_8))
            fos.flush()
            fos.fd.sync()
        }
    }

    private fun writeCsvZip(out: OutputStream): Map<String, Int> {
        val db = database.openHelper.readableDatabase
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        fun text(c: Cursor, i: Int) = if (c.isNull(i)) "" else excelSafeText(c.getString(i) ?: "")
        fun id(c: Cursor, i: Int) = if (c.isNull(i)) "" else excelSafeId(c.getString(i) ?: "")
        fun money(c: Cursor, i: Int) = if (c.isNull(i)) "" else String.format(Locale.US, "%.2f", c.getDouble(i))
        fun whole(c: Cursor, i: Int) = if (c.isNull(i)) "" else c.getLong(i).toString()
        fun yesNo(c: Cursor, i: Int) = if (!c.isNull(i) && c.getLong(i) != 0L) "Yes" else "No"
        fun date(c: Cursor, i: Int): String {
            if (c.isNull(i)) return ""
            val ms = c.getLong(i)
            return if (ms <= 0L) "" else dateFormat.format(Date(ms))
        }

        val counts = LinkedHashMap<String, Int>()
        ZipOutputStream(BufferedOutputStream(NonClosingOutputStream(out), BUFFER)).use { zip ->
            counts["products"] = writeCsvEntry(
                zip, db, "products.csv",
                listOf("Name", "SKU", "Barcode", "Category", "Brand", "Stock", "Purchase Price", "Retail Price", "Active"),
                "SELECT name, sku, barcode, categoryName, brandName, currentStock, purchasePrice, retailPrice, isActive " +
                    "FROM products ORDER BY name COLLATE NOCASE"
            ) { c -> listOf(text(c, 0), id(c, 1), id(c, 2), text(c, 3), text(c, 4), whole(c, 5), money(c, 6), money(c, 7), yesNo(c, 8)) }

            counts["customers"] = writeCsvEntry(
                zip, db, "customers.csv",
                listOf("Code", "Name", "Phone", "Due"),
                "SELECT customerCode, name, phone, currentDue FROM customers ORDER BY name COLLATE NOCASE"
            ) { c -> listOf(id(c, 0), text(c, 1), id(c, 2), money(c, 3)) }

            counts["suppliers"] = writeCsvEntry(
                zip, db, "suppliers.csv",
                listOf("Code", "Name", "Phone", "Due"),
                "SELECT supplierCode, name, phone, currentDue FROM suppliers ORDER BY name COLLATE NOCASE"
            ) { c -> listOf(id(c, 0), text(c, 1), id(c, 2), money(c, 3)) }

            counts["sales"] = writeCsvEntry(
                zip, db, "sales.csv",
                listOf("Invoice No", "Date", "Customer", "Customer Phone", "Total", "Paid", "Due", "Payment Status", "Status"),
                "SELECT invoiceNumber, saleDate, customerName, customerPhone, totalAmount, paidAmount, dueAmount, paymentStatus, status " +
                    "FROM sales ORDER BY saleDate DESC"
            ) { c -> listOf(id(c, 0), date(c, 1), text(c, 2), id(c, 3), money(c, 4), money(c, 5), money(c, 6), text(c, 7), text(c, 8)) }

            counts["purchases"] = writeCsvEntry(
                zip, db, "purchases.csv",
                listOf("Purchase No", "Date", "Supplier", "Supplier Invoice", "Total", "Paid", "Due", "Payment Status", "Status"),
                "SELECT purchaseNumber, purchaseDate, supplierName, supplierInvoiceNumber, totalAmount, paidAmount, dueAmount, paymentStatus, status " +
                    "FROM purchases ORDER BY purchaseDate DESC"
            ) { c -> listOf(id(c, 0), date(c, 1), text(c, 2), id(c, 3), money(c, 4), money(c, 5), money(c, 6), text(c, 7), text(c, 8)) }
        }
        return counts
    }

    private fun writeCsvEntry(
        zip: ZipOutputStream,
        db: SupportSQLiteDatabase,
        name: String,
        header: List<String>,
        sql: String,
        row: (Cursor) -> List<String>
    ): Int {
        zip.putNextEntry(ZipEntry(name))
        zip.write(UTF8_BOM)
        val line = StringBuilder()
        appendCsvLine(line, header)
        zip.write(line.toString().toByteArray(Charsets.UTF_8))
        var count = 0
        db.query(sql).use { c ->
            while (c.moveToNext()) {
                line.setLength(0)
                appendCsvLine(line, row(c))
                zip.write(line.toString().toByteArray(Charsets.UTF_8))
                count++
            }
        }
        zip.closeEntry()
        return count
    }

    private fun displayName(uri: Uri): String? {
        val name = try {
            appContext.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null
            }
        } catch (e: Exception) {
            null
        }
        return name ?: uri.lastPathSegment
    }

    private fun deleteDocumentQuietly(uri: Uri) {
        try {
            if (DocumentsContract.isDocumentUri(appContext, uri)) {
                DocumentsContract.deleteDocument(appContext.contentResolver, uri)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not remove incomplete file: ${e.message}")
        }
    }

    private fun appVersionName(): String = try {
        @Suppress("DEPRECATION")
        val info = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
        info.versionName ?: ""
    } catch (e: Exception) {
        ""
    }

    private fun deviceName(): String = listOfNotNull(Build.MANUFACTURER, Build.MODEL).joinToString(" ").trim()

    private fun ensureFreeSpace(dir: File, neededBytes: Long) {
        dir.mkdirs()
        val free = dir.usableSpace
        if (free in 1 until neededBytes) {
            throw BackupException(
                "Not enough free storage on this phone (about ${formatSize(neededBytes)} needed). Free up some space and try again."
            )
        }
    }

    private class CountingOutputStream(out: OutputStream) : FilterOutputStream(out) {
        var count = 0L
            private set

        override fun write(b: Int) {
            out.write(b)
            count++
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            out.write(b, off, len)
            count += len
        }
    }

    /** Lets a ZipOutputStream be closed (finishing the zip) without closing the caller's stream. */
    private class NonClosingOutputStream(out: OutputStream) : FilterOutputStream(out) {
        override fun write(b: ByteArray, off: Int, len: Int) {
            out.write(b, off, len)
        }

        override fun close() {
            out.flush()
        }
    }

    companion object {
        private const val TAG = "BackupRepository"

        const val DB_NAME = "electro_pos.db"
        const val ZIP_MIME = "application/zip"
        /** For ActivityResultContracts.OpenDocument (Drive and file managers label .zip differently). */
        val OPEN_MIME_TYPES = arrayOf("application/zip", "application/x-zip-compressed", "application/x-zip", "application/octet-stream")

        const val BACKUP_FORMAT = "electropos-backup"
        const val BACKUP_FORMAT_VERSION = 1
        const val KIND_FULL = "FULL"
        const val KIND_SAFETY = "SAFETY"
        const val MAX_SAFETY_COPIES = 5

        internal const val MANIFEST_ENTRY = "manifest.json"
        internal const val DB_ENTRY = "electro_pos.db"
        internal const val CANDIDATE_DB_NAME = "electro_pos_restore_check.db"

        /** Tables that existed since schema version 1; a file without them is not ours. */
        val REQUIRED_TABLES = listOf("products", "customers", "sales", "shop_profile")
        val COUNT_TABLES = listOf("products", "customers", "suppliers", "sales", "purchases")

        private const val PREFS_NAME = "electropos_backup"
        private const val KEY_LAST_BACKUP_AT = "last_backup_at"
        private const val KEY_LAST_BACKUP_NAME = "last_backup_name"
        private const val KEY_LAST_EXPORT_AT = "last_export_at"
        private const val KEY_LAST_RESTORE_AT = "last_restore_at"
        private const val KEY_LAST_RESTORE_SOURCE = "last_restore_source"
        private const val KEY_LAST_RESTORE_BACKUP_CREATED = "last_restore_backup_created_at"
        private const val KEY_LAST_RESTORE_ERROR = "last_restore_error"

        private const val SAFETY_PREFIX = "safety-"
        private const val BUFFER = 64 * 1024
        private const val SPACE_MARGIN = 5L * 1024 * 1024
        private const val MAX_MANIFEST_BYTES = 1_048_576L
        private const val MAX_DB_BYTES = 4_294_967_296L

        private val SQLITE_HEADER = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)
        private val UTF8_BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        private val DIGITS_ONLY = Regex("^\\+?[0-9]+$")
        private val DATE_LIKE = Regex("^[0-9]{1,4}[-/.][0-9]{1,2}([-/.][0-9]{1,4})?$")

        private val pendingLock = Any()
        @Volatile
        private var pendingRestoreHandled = false

        // -------------------------------------------------------------- startup hook

        /**
         * Call before Room opens electro_pos.db (inside AppDatabase.getInstance, before databaseBuilder).
         * If a restore was staged, moves the pending database into place and deletes the old -wal/-shm.
         * Runs at most once per process, so calling it on every getInstance() is harmless.
         * Never throws. Returns true when a restore was applied.
         */
        @JvmStatic
        fun applyPendingRestore(context: Context): Boolean = synchronized(pendingLock) {
            if (pendingRestoreHandled) {
                false
            } else {
                pendingRestoreHandled = true
                val ctx = context.applicationContext ?: context
                try {
                    applyPendingRestoreNow(ctx)
                } catch (t: Throwable) {
                    Log.e(TAG, "Applying the pending restore failed; keeping the current database", t)
                    try {
                        backupPrefs(ctx).edit()
                            .putLong(KEY_LAST_RESTORE_AT, System.currentTimeMillis())
                            .putString(KEY_LAST_RESTORE_ERROR, t.message ?: t.javaClass.simpleName)
                            .commit()
                    } catch (_: Throwable) {
                    }
                    false
                }
            }
        }

        private fun applyPendingRestoreNow(context: Context): Boolean {
            // A checked backup the user never confirmed (app was closed): no longer needed.
            deleteDbFiles(candidateFile(context))
            val pending = pendingRestoreFile(context)
            if (!pending.isFile) return false
            val infoFile = pendingInfoFile(context)
            val info = try {
                if (infoFile.isFile) JSONObject(infoFile.readText(Charsets.UTF_8)) else JSONObject()
            } catch (e: Exception) {
                JSONObject()
            }
            if (!looksLikeSqlite(pending)) {
                deleteDbFiles(pending)
                infoFile.delete()
                backupPrefs(context).edit()
                    .putLong(KEY_LAST_RESTORE_AT, System.currentTimeMillis())
                    .putString(KEY_LAST_RESTORE_ERROR, "The prepared restore file was damaged; nothing was changed.")
                    .commit()
                return false
            }
            val dbFile = context.getDatabasePath(DB_NAME)
            val dbDir = dbFile.parentFile
            dbDir?.mkdirs()
            val incoming = File(dbDir, "$DB_NAME.restoring")
            deleteDbFiles(incoming)
            copyFile(pending, incoming)
            // The restored file may be linked to a cloud shop (even another phone's): the next open forgets that
            SyncSchema.restoreMarker(context.noBackupFilesDir).let { marker ->
                marker.parentFile?.mkdirs()
                marker.writeText(System.currentTimeMillis().toString())
            }
            // Old WAL/SHM/journal belong to the old database: SQLite would replay them onto the new file.
            deleteSidecars(dbFile)
            if (!incoming.renameTo(dbFile)) {
                dbFile.delete()
                if (!incoming.renameTo(dbFile)) {
                    copyFile(incoming, dbFile)
                    incoming.delete()
                }
            }
            pending.delete()
            infoFile.delete()
            backupPrefs(context).edit()
                .putLong(KEY_LAST_RESTORE_AT, System.currentTimeMillis())
                .putString(KEY_LAST_RESTORE_SOURCE, info.optString("source", "backup file"))
                .putLong(KEY_LAST_RESTORE_BACKUP_CREATED, info.optLong("backupCreatedAt", 0L))
                .remove(KEY_LAST_RESTORE_ERROR)
                .commit()
            Log.i(TAG, "Pending restore applied from ${info.optString("source")}")
            return true
        }

        /** Tests only: lets [applyPendingRestore] run again in the same process. */
        internal fun resetPendingRestoreGuardForTests() {
            synchronized(pendingLock) { pendingRestoreHandled = false }
        }

        fun hasPendingRestore(context: Context): Boolean = pendingRestoreFile(context).isFile

        /** Relaunches the launcher activity in a fresh task and ends this process. */
        fun restartApp(context: Context) {
            val app = context.applicationContext ?: context
            try {
                val component = app.packageManager.getLaunchIntentForPackage(app.packageName)?.component
                if (component != null) {
                    val intent = Intent.makeRestartActivityTask(component)
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    app.startActivity(intent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Could not relaunch the app", e)
            }
            Runtime.getRuntime().exit(0)
        }

        // -------------------------------------------------------------- names & formatting

        fun defaultBackupFileName(now: Long = System.currentTimeMillis()): String = "ElectroPOS-backup-${fileStamp(now)}.zip"

        fun defaultExportFileName(now: Long = System.currentTimeMillis()): String = "ElectroPOS-export-${fileStamp(now)}.zip"

        fun fileStamp(time: Long): String = SimpleDateFormat("yyyy-MM-dd-HHmm", Locale.US).format(Date(time))

        fun formatDateTime(time: Long): String =
            if (time <= 0L) "Unknown date" else SimpleDateFormat("dd MMM yyyy, h:mm a", Locale.getDefault()).format(Date(time))

        fun formatSize(bytes: Long): String = when {
            bytes < 0L -> "?"
            bytes < 1024L -> "$bytes B"
            bytes < 1024L * 1024L -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
            bytes < 1024L * 1024L * 1024L -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
            else -> String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
        }

        /** "120 products · 45 customers · ..." */
        fun summarizeCounts(counts: Map<String, Long>): String {
            val labels = listOf(
                "products" to ("product" to "products"),
                "customers" to ("customer" to "customers"),
                "suppliers" to ("supplier" to "suppliers"),
                "sales" to ("sale" to "sales"),
                "purchases" to ("purchase" to "purchases")
            )
            return labels.mapNotNull { (key, words) ->
                counts[key]?.let { n -> "$n ${if (n == 1L) words.first else words.second}" }
            }.joinToString(" · ")
        }

        // -------------------------------------------------------------- CSV helpers

        /** RFC 4180 quoting. */
        internal fun csvEscape(value: String): String {
            val needsQuotes = value.any { it == ',' || it == '"' || it == '\n' || it == '\r' } ||
                value.startsWith(" ") || value.endsWith(" ")
            return if (needsQuotes) "\"" + value.replace("\"", "\"\"") + "\"" else value
        }

        /** Codes/phones/barcodes: keep leading zeros and long digit runs as text in Excel. */
        internal fun excelSafeId(value: String): String {
            if (value.isEmpty()) return value
            val digits = DIGITS_ONLY.matches(value)
            val mangledByExcel = (digits && (value.startsWith("0") || value.startsWith("+") || value.length > 11)) ||
                DATE_LIKE.matches(value)
            return if (mangledByExcel) "=\"$value\"" else excelSafeText(value)
        }

        /** Free text: stop Excel from treating it as a formula. */
        internal fun excelSafeText(value: String): String {
            if (value.isEmpty()) return value
            return if (value[0] in "=+-@\t\r") "'$value" else value
        }

        private fun appendCsvLine(sb: StringBuilder, fields: List<String>) {
            fields.forEachIndexed { index, field ->
                if (index > 0) sb.append(',')
                sb.append(csvEscape(field))
            }
            sb.append("\r\n")
        }

        // -------------------------------------------------------------- migrations

        /** Every Migration exposed by AppDatabase.Companion (MIGRATION_1_2 ... ), so new ones are picked up. */
        internal fun discoverMigrations(): Array<Migration> {
            val found = try {
                AppDatabase.Companion::class.java.methods
                    .filter { it.parameterTypes.isEmpty() && Migration::class.java.isAssignableFrom(it.returnType) }
                    .mapNotNull { method ->
                        try {
                            method.invoke(AppDatabase.Companion) as? Migration
                        } catch (e: Exception) {
                            null
                        }
                    }
            } catch (e: Exception) {
                Log.w(TAG, "Could not list migrations: ${e.message}")
                emptyList()
            }
            return found.distinctBy { it.startVersion to it.endVersion }.toTypedArray()
        }

        // -------------------------------------------------------------- files

        private fun backupPrefs(context: Context): SharedPreferences =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // noBackupFilesDir: kept out of Android auto-backup (its 25 MB quota) on purpose.
        private fun restoreDir(context: Context) = File(context.noBackupFilesDir, "restore")
        // The checked-but-not-yet-confirmed backup. It sits next to electro_pos.db because Room opens it by name.
        private fun candidateFile(context: Context): File = context.getDatabasePath(CANDIDATE_DB_NAME)
        private fun pendingRestoreFile(context: Context) = File(restoreDir(context), "pending_restore.db")
        private fun pendingInfoFile(context: Context) = File(restoreDir(context), "pending_restore.json")
        private fun safetyDir(context: Context) = File(context.noBackupFilesDir, "safety_copies")

        private fun looksLikeSqlite(file: File): Boolean {
            if (!file.isFile || file.length() < 512L) return false
            return try {
                FileInputStream(file).use { input ->
                    val header = ByteArray(SQLITE_HEADER.size)
                    var read = 0
                    while (read < header.size) {
                        val n = input.read(header, read, header.size - read)
                        if (n < 0) break
                        read += n
                    }
                    read == header.size && header.contentEquals(SQLITE_HEADER)
                }
            } catch (e: IOException) {
                false
            }
        }

        private fun deleteSidecars(dbFile: File) {
            for (suffix in arrayOf("-wal", "-shm", "-journal")) {
                val f = File(dbFile.path + suffix)
                if (f.exists()) f.delete()
            }
        }

        private fun deleteDbFiles(dbFile: File) {
            deleteSidecars(dbFile)
            if (dbFile.exists()) dbFile.delete()
        }

        private fun copyFile(src: File, dst: File) {
            dst.parentFile?.mkdirs()
            FileInputStream(src).use { input ->
                FileOutputStream(dst).use { output ->
                    input.copyTo(output, BUFFER)
                    output.flush()
                    output.fd.sync()
                }
            }
        }

        private fun readLimited(input: InputStream, limit: Long): ByteArray {
            val out = ByteArrayOutputStream()
            val buf = ByteArray(8 * 1024)
            var total = 0L
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
                if (total > limit) throw BackupException("This file is not an ElectroPOS backup (manifest too large).")
                out.write(buf, 0, n)
            }
            return out.toByteArray()
        }

        /** Copies [input] to [dst] (size-capped) and returns its SHA-256. */
        private fun copyLimited(input: InputStream, dst: File, limit: Long): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val buf = ByteArray(BUFFER)
            var total = 0L
            FileOutputStream(dst).use { out ->
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > limit) throw BackupException("The database inside this file is too large to restore.")
                    digest.update(buf, 0, n)
                    out.write(buf, 0, n)
                }
                out.flush()
                out.fd.sync()
            }
            return digest.digest().toHex()
        }

        private fun sha256(file: File): String = FileInputStream(file).use { sha256(it) }

        private fun sha256(input: InputStream): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val buf = ByteArray(BUFFER)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
            return digest.digest().toHex()
        }

        private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

        private fun runPragma(db: SQLiteDatabase, sql: String) {
            try {
                db.rawQuery(sql, null).use { it.moveToFirst() }
            } catch (e: Exception) {
                Log.w(TAG, "$sql failed: ${e.message}")
            }
        }

        /** Turns low-level errors into messages a shop owner can act on. */
        private fun friendly(e: Throwable): Exception {
            if (e is BackupException) return e
            val msg = e.message.orEmpty()
            if (msg.contains("ENOSPC") || msg.contains("No space left", ignoreCase = true) || msg.contains("SQLITE_FULL")) {
                return BackupException("Not enough free storage on this phone. Free up some space and try again.", e)
            }
            if (e is SecurityException) {
                return BackupException("ElectroPOS is not allowed to use that file. Please choose it again.", e)
            }
            if (e is IOException) {
                return BackupException("Could not read or write the file (${msg.ifBlank { e.javaClass.simpleName }}).", e)
            }
            return BackupException("Something went wrong: ${msg.ifBlank { e.javaClass.simpleName }}", e)
        }
    }
}
