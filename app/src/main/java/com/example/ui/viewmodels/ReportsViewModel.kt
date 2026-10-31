package com.example.ui.viewmodels

import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.repository.ReportData
import com.example.data.repository.ReportPeriod
import com.example.data.repository.ReportRange
import com.example.data.repository.ReportRanges
import com.example.data.repository.ReportsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ReportsUiState(
    val period: ReportPeriod = ReportPeriod.THIS_MONTH,
    val range: ReportRange = ReportRanges.forPeriod(ReportPeriod.THIS_MONTH),
    val isLoading: Boolean = false,
    val report: ReportData? = null,
    val errorMessage: String? = null,
    val isExporting: Boolean = false,
    /** One-off message for a snackbar (export result). */
    val message: String? = null
)

class ReportsViewModel(
    private val repository: ReportsRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(ReportsUiState())
    val uiState: StateFlow<ReportsUiState> = _uiState.asStateFlow()

    private var loadJob: Job? = null

    fun selectPeriod(period: ReportPeriod) {
        if (period == ReportPeriod.CUSTOM) return // picked with selectCustomRange
        _uiState.update { it.copy(period = period, range = ReportRanges.forPeriod(period)) }
        load()
    }

    /** Dates as given by the Material DateRangePicker (UTC midnight of the picked days). */
    fun selectCustomRange(startUtcMillis: Long, endUtcMillis: Long) {
        val range = ReportRanges.custom(
            ReportRanges.fromPickerUtc(startUtcMillis),
            ReportRanges.fromPickerUtc(endUtcMillis)
        )
        _uiState.update { it.copy(period = ReportPeriod.CUSTOM, range = range) }
        load()
    }

    /** Reloads the current period; relative periods (today, this month...) move on if the date changed. */
    fun refresh() {
        val state = _uiState.value
        if (state.period != ReportPeriod.CUSTOM) {
            val range = ReportRanges.forPeriod(state.period)
            if (range != state.range) _uiState.update { it.copy(range = range) }
        }
        load()
    }

    private fun load() {
        loadJob?.cancel()
        val range = _uiState.value.range
        _uiState.update { it.copy(isLoading = true, errorMessage = null) }
        loadJob = viewModelScope.launch {
            try {
                val report = repository.loadReport(range)
                _uiState.update {
                    if (it.range != range) it else it.copy(isLoading = false, report = report, errorMessage = null)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, errorMessage = e.message ?: "Could not load the report") }
            }
        }
    }

    fun suggestedFileName(): String = ReportsRepository.suggestedFileName(_uiState.value.range)

    fun exportCsv(contentResolver: ContentResolver, uri: Uri) {
        val report = _uiState.value.report?.takeIf { it.range == _uiState.value.range }
        if (report == null) {
            _uiState.update { it.copy(message = "The report is still loading. Try again in a moment.") }
            return
        }
        _uiState.update { it.copy(isExporting = true) }
        viewModelScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val bytes = ReportsRepository.csvBytes(ReportsRepository.buildCsv(report))
                    val stream = runCatching { contentResolver.openOutputStream(uri, "wt") }.getOrNull()
                        ?: contentResolver.openOutputStream(uri)
                        ?: throw IllegalStateException("Could not open the file for writing")
                    stream.use { it.write(bytes) }
                }
            }
            _uiState.update {
                it.copy(
                    isExporting = false,
                    message = result.fold(
                        onSuccess = { "CSV saved (${report.daily.size} day rows)" },
                        onFailure = { e -> "Export failed: ${e.message ?: "unknown error"}" }
                    )
                )
            }
        }
    }

    fun clearMessage() {
        _uiState.update { it.copy(message = null) }
    }
}
