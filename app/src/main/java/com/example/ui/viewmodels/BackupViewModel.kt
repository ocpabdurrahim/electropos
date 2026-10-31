package com.example.ui.viewmodels

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.repository.BackupException
import com.example.data.repository.BackupInspection
import com.example.data.repository.BackupRepository
import com.example.data.repository.LastBackupInfo
import com.example.data.repository.LastRestoreInfo
import com.example.data.repository.SafetyCopy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class BackupMessage(val text: String, val isError: Boolean)

data class BackupUiState(
    /** Non-null while a file operation runs; shown with a progress bar. */
    val busyText: String? = null,
    val lastBackup: LastBackupInfo? = null,
    val lastRestore: LastRestoreInfo? = null,
    val lastExportAt: Long = 0L,
    val safetyCopies: List<SafetyCopy> = emptyList(),
    /** A checked backup waiting for the user's confirmation. */
    val inspection: BackupInspection? = null,
    /** A restore is staged and will be applied when the app starts again. */
    val pendingRestore: Boolean = false,
    val restarting: Boolean = false,
    val message: BackupMessage? = null
) {
    val isBusy: Boolean get() = busyText != null || restarting
}

class BackupViewModel(
    private val repository: BackupRepository
) : ViewModel() {

    private val _state = MutableStateFlow(BackupUiState())
    val state: StateFlow<BackupUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch { reloadStatus() }
    }

    fun suggestedBackupFileName(): String = BackupRepository.defaultBackupFileName()

    fun suggestedExportFileName(): String = BackupRepository.defaultExportFileName()

    fun createBackup(uri: Uri) = runBusy("Creating backup… keep the app open") {
        repository.createBackup(uri)
            .onSuccess { result ->
                val where = result.displayName?.let { " as $it" } ?: ""
                val size = if (result.sizeBytes > 0) " (${BackupRepository.formatSize(result.sizeBytes)})" else ""
                showSuccess("Backup saved$where$size. ${BackupRepository.summarizeCounts(result.manifest.counts)}")
            }
            .onFailure { showFailure("Backup failed", it) }
        reloadStatus()
    }

    fun inspectBackup(uri: Uri) = runBusy("Checking the backup file…") {
        repository.inspectBackup(uri)
            .onSuccess { inspection -> _state.update { it.copy(inspection = inspection) } }
            .onFailure { showFailure("This file can't be restored", it) }
    }

    fun inspectSafetyCopy(copy: SafetyCopy) = runBusy("Checking the safety copy…") {
        repository.inspectSafetyCopy(copy.path)
            .onSuccess { inspection -> _state.update { it.copy(inspection = inspection) } }
            .onFailure { showFailure("This safety copy can't be restored", it) }
        reloadStatus()
    }

    fun cancelRestore() {
        val inspection = _state.value.inspection ?: return
        _state.update { it.copy(inspection = null) }
        viewModelScope.launch {
            try {
                repository.discardInspection(inspection)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
    }

    /** User confirmed "replace ALL data": safety copy, stage, restart. */
    fun confirmRestore() {
        val inspection = _state.value.inspection ?: return
        _state.update { it.copy(inspection = null) }
        runBusy("Saving a safety copy of the current data…") {
            val staged = repository.stageRestore(inspection)
            if (staged.isSuccess) {
                _state.update { it.copy(restarting = true, pendingRestore = true) }
                delay(1500)
                repository.restartApp()
            } else {
                showFailure("Restore failed", staged.exceptionOrNull())
                reloadStatus()
            }
        }
    }

    /** For a restore that is staged but the restart did not happen. */
    fun restartNow() {
        _state.update { it.copy(restarting = true) }
        viewModelScope.launch {
            delay(300)
            repository.restartApp()
        }
    }

    fun exportCsv(uri: Uri) = runBusy("Exporting CSV files…") {
        repository.exportCsv(uri)
            .onSuccess { result ->
                val where = result.displayName?.let { " as $it" } ?: ""
                val rows = result.rowCounts.entries.joinToString(" · ") { "${it.value} ${it.key}" }
                showSuccess("Export saved$where. $rows")
            }
            .onFailure { showFailure("Export failed", it) }
        reloadStatus()
    }

    fun exportSafetyCopy(path: String, uri: Uri) = runBusy("Saving the safety copy…") {
        repository.exportSafetyCopy(path, uri)
            .onSuccess { name -> showSuccess("Safety copy saved" + (name?.let { " as $it" } ?: "") + ".") }
            .onFailure { showFailure("Could not save the safety copy", it) }
    }

    fun showError(text: String) {
        _state.update { it.copy(message = BackupMessage(text, isError = true)) }
    }

    fun dismissMessage() {
        _state.update { it.copy(message = null) }
    }

    fun refresh() {
        viewModelScope.launch { reloadStatus() }
    }

    private suspend fun reloadStatus() {
        val copies = try {
            repository.listSafetyCopies()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }
        _state.update {
            it.copy(
                lastBackup = repository.lastBackupInfo(),
                lastRestore = repository.lastRestoreInfo(),
                lastExportAt = repository.lastExportTime(),
                safetyCopies = copies,
                pendingRestore = it.pendingRestore || repository.hasPendingRestore()
            )
        }
    }

    private fun runBusy(progressText: String, block: suspend () -> Unit) {
        if (_state.value.isBusy) return
        _state.update { it.copy(busyText = progressText, message = null) }
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showFailure("Something went wrong", e)
            } finally {
                _state.update { it.copy(busyText = null) }
            }
        }
    }

    private fun showSuccess(text: String) {
        _state.update { it.copy(message = BackupMessage(text.trim(), isError = false)) }
    }

    private fun showFailure(prefix: String, error: Throwable?) {
        val detail = when (error) {
            null -> prefix + "."
            is BackupException -> error.message ?: "$prefix."
            else -> "$prefix: ${error.message ?: error.javaClass.simpleName}"
        }
        _state.update { it.copy(message = BackupMessage(detail, isError = true)) }
    }
}
