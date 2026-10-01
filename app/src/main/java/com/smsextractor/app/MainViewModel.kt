package com.smsextractor.app

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.smsextractor.app.data.AppDatabase
import com.smsextractor.app.data.SmsReader
import com.smsextractor.app.data.toEntity
import com.smsextractor.app.data.toModel
import com.smsextractor.core.CsvExporter
import com.smsextractor.core.Filtering
import com.smsextractor.core.Importer
import com.smsextractor.core.RawSms
import com.smsextractor.core.SmsParser
import com.smsextractor.core.Totals
import com.smsextractor.core.TotalsCalculator
import com.smsextractor.core.Transaction
import com.smsextractor.core.TransactionFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

data class SenderInfo(val address: String, val smsCount: Int?, val importedCount: Int)

data class UiState(
    val senders: List<SenderInfo> = emptyList(),
    val sendersLoadedFromSms: Boolean = false,
    /** null = "All senders". */
    val selectedSenders: Set<String>? = null,
    val start: LocalDate = LocalDate.now(),
    val end: LocalDate = LocalDate.now(),
    /** The filter used for the table/totals currently shown (set by "Show Transactions"). */
    val applied: TransactionFilter? = null,
    val rows: List<Transaction> = emptyList(),
    val totals: Totals? = null,
    val undated: List<Transaction> = emptyList(),
    val busy: Boolean = false,
    val message: String? = null,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val dao = AppDatabase.get(app).dao()
    private val resolver = app.contentResolver

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    init {
        viewModelScope.launch { refreshSenders(fromSms = false) }
    }

    fun clearMessage() = _state.update { it.copy(message = null) }
    fun showMessage(text: String) = _state.update { it.copy(message = text) }

    fun setSelectedSenders(senders: Set<String>?) = _state.update { it.copy(selectedSenders = senders) }
    fun setStart(d: LocalDate) = _state.update { it.copy(start = d, end = if (it.end.isBefore(d)) d else it.end) }
    fun setEnd(d: LocalDate) = _state.update { it.copy(end = d, start = if (it.start.isAfter(d)) d else it.start) }
    fun setRange(start: LocalDate, end: LocalDate) = _state.update { it.copy(start = start, end = end) }

    /** Reads sender names/numbers from the inbox (call only after READ_SMS was granted). */
    fun loadSendersFromSms() = viewModelScope.launch {
        _state.update { it.copy(busy = true) }
        try {
            refreshSenders(fromSms = true)
        } catch (e: SecurityException) {
            showMessage("SMS permission is not granted. You can paste messages manually instead.")
        } finally {
            _state.update { it.copy(busy = false) }
        }
    }

    private suspend fun refreshSenders(fromSms: Boolean) {
        val inbox: Map<String, Int>? = if (fromSms || _state.value.sendersLoadedFromSms) {
            withContext(Dispatchers.IO) { SmsReader.senders(resolver) }
        } else null
        val imported = withContext(Dispatchers.IO) { dao.senderCounts() }.associate { it.sender to it.count }
        val names = (inbox?.keys.orEmpty() + imported.keys)
        val list = names.map { SenderInfo(it, inbox?.get(it), imported[it] ?: 0) }
            .sortedWith(compareByDescending<SenderInfo> { it.smsCount ?: 0 }.thenByDescending { it.importedCount }.thenBy { it.address })
        _state.update { it.copy(senders = list, sendersLoadedFromSms = it.sendersLoadedFromSms || inbox != null) }
    }

    /** "Import SMS": reads existing messages from the selected senders only. */
    fun importSms() = viewModelScope.launch {
        val selected = _state.value.selectedSenders
        if (selected != null && selected.isEmpty()) {
            showMessage("Please select at least one sender first.")
            return@launch
        }
        _state.update { it.copy(busy = true) }
        try {
            val messages = withContext(Dispatchers.IO) { SmsReader.messages(resolver, selected) }
            saveMessages(messages, "SMS messages read")
            refreshSenders(fromSms = true)
        } catch (e: SecurityException) {
            showMessage("SMS permission is not granted. You can paste messages manually instead.")
        } finally {
            _state.update { it.copy(busy = false) }
        }
    }

    /** Manual paste: messages separated by an empty line. */
    fun importPasted(text: String, senderLabel: String) = viewModelScope.launch {
        val label = senderLabel.trim().ifEmpty { "Pasted" }
        val messages = Importer.splitPasted(text).map { RawSms(label, it, null) }
        if (messages.isEmpty()) {
            showMessage("Nothing to import – paste at least one message.")
            return@launch
        }
        _state.update { it.copy(busy = true) }
        try {
            saveMessages(messages, "pasted messages")
            refreshSenders(fromSms = false)
        } finally {
            _state.update { it.copy(busy = false) }
        }
    }

    private suspend fun saveMessages(messages: List<RawSms>, what: String) {
        val result = withContext(Dispatchers.Default) {
            val existing = dao.keys().toSet()
            Importer.process(messages, existing)
        }
        val inserted = withContext(Dispatchers.IO) {
            dao.insertAll(result.newTransactions.map { it.toEntity() }).count { it != -1L }
        }
        val review = result.newTransactions.count { it.needsReview }
        showMessage(
            "${messages.size} $what. New transactions saved: $inserted. " +
                "Already imported (skipped): ${result.duplicates + (result.newTransactions.size - inserted)}. " +
                "Not transactions (ignored): ${result.notTransactions}." +
                (if (review > 0) " $review need review (tap them to check)." else "")
        )
        if (_state.value.applied != null) recompute(_state.value.applied!!)
    }

    /** "Show Transactions": apply sender + date filters and recalculate the totals. */
    fun showTransactions() = viewModelScope.launch {
        val s = _state.value
        if (s.selectedSenders != null && s.selectedSenders.isEmpty()) {
            showMessage("Please select at least one sender.")
            return@launch
        }
        recompute(TransactionFilter(s.selectedSenders, s.start, s.end))
    }

    private suspend fun recompute(filter: TransactionFilter) {
        val all = withContext(Dispatchers.IO) { dao.all() }.map { it.toModel() }
        val rows = Filtering.apply(all, filter)
        _state.update {
            it.copy(
                applied = filter,
                rows = rows,
                totals = TotalsCalculator.calculate(rows),
                undated = Filtering.undated(all, filter).sortedBy { t -> t.smsTimestamp ?: 0L },
            )
        }
    }

    fun saveEdit(t: Transaction) = viewModelScope.launch {
        withContext(Dispatchers.IO) { dao.update(t.copy(userEdited = true).toEntity()) }
        _state.value.applied?.let { recompute(it) }
        refreshSenders(fromSms = false)
    }

    fun delete(t: Transaction) = viewModelScope.launch {
        withContext(Dispatchers.IO) { dao.delete(t.id) }
        _state.value.applied?.let { recompute(it) }
        refreshSenders(fromSms = false)
    }

    /**
     * Explicit user action: for day/month-only dates, set the year from the SMS received date.
     * Only messages that have an SMS received time get a date; the rest stay for manual review.
     */
    fun confirmYearsFromReceivedDate() = viewModelScope.launch {
        var fixed = 0
        for (t in _state.value.undated) {
            val m = t.partialMonth ?: continue
            val d = t.partialDay ?: continue
            val date = SmsParser.suggestDate(m, d, t.smsTimestamp) ?: continue
            val notes = t.reviewNotes.split("; ").filter { !it.startsWith("Year not written") }
            val updated = t.copy(
                date = date,
                reviewNotes = notes.joinToString("; "),
                needsReview = notes.any { it.isNotBlank() },
                userEdited = true,
            )
            withContext(Dispatchers.IO) { dao.update(updated.toEntity()) }
            fixed++
        }
        _state.value.applied?.let { recompute(it) }
        showMessage("Dates confirmed for $fixed transaction(s) using the SMS received year.")
    }

    fun exportCsv(uri: Uri) = viewModelScope.launch {
        val s = _state.value
        val filter = s.applied ?: run {
            showMessage("Press \"Show Transactions\" first.")
            return@launch
        }
        val csv = CsvExporter.export(s.rows, filter.senders, filter.start, filter.end)
        try {
            withContext(Dispatchers.IO) {
                getApplication<Application>().contentResolver.openOutputStream(uri, "wt")?.use {
                    it.write(csv.toByteArray(Charsets.UTF_8))
                } ?: error("Could not open file")
            }
            showMessage("Exported ${s.rows.size} transaction(s).")
        } catch (e: Exception) {
            showMessage("Export failed. Please try another location.")
        }
    }
}
