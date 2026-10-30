package app.fintrack.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.fintrack.FinTrackApp
import app.fintrack.core.engine.LedgerEngine
import app.fintrack.core.model.Block
import app.fintrack.core.model.Direction
import app.fintrack.core.model.LedgerEntry
import app.fintrack.core.model.OwnerProfile
import app.fintrack.core.model.PersonState
import app.fintrack.core.money.parsePaise
import app.fintrack.core.report.PeriodReport
import app.fintrack.core.report.Period
import app.fintrack.core.report.ReportBuilder
import app.fintrack.data.AppSnapshot
import app.fintrack.data.Backup
import app.fintrack.data.CsvExport
import app.fintrack.data.ParserOverlay
import app.fintrack.data.SettingsState
import app.fintrack.data.StoredMessage
import app.fintrack.data.WrongPassphraseException
import app.fintrack.notify.Scheduler
import app.fintrack.sms.InboxImporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId

/** Parses "1,234.50" into paise, or null when it is not a valid non-negative amount. */
fun parseRupees(text: String): Long? = runCatching { parsePaise(text.trim()) }.getOrNull()

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as FinTrackApp
    private val container = app.container

    val repository get() = container.repository
    val settings: StateFlow<SettingsState> get() = container.settings.state
    val openError: StateFlow<String?> get() = container.openError
    val zone: ZoneId get() = container.settings.zone

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    fun today(): LocalDate = LocalDate.now(zone)
    fun say(text: String?) { _message.value = text }

    init {
        viewModelScope.launch { container.ensureLoaded() }
    }

    fun report(snapshot: AppSnapshot, period: Period): PeriodReport =
        ReportBuilder(snapshot.profile, snapshot.entries, snapshot.notices).build(period, today())

    private fun launchEdit(op: (LedgerEngine) -> Unit) {
        viewModelScope.launch { repository.edit { op(it) } }
    }

    // ---- entry actions ------------------------------------------------------------------------------------

    fun setReason(id: Long, reason: String) = launchEdit { it.setReason(id, reason) }
    fun setBlock(id: Long, block: Block) = launchEdit { it.setBlock(id, block) }
    fun setPerson(id: Long, state: PersonState, usuallySpend: Boolean) = launchEdit { it.setPersonState(id, state, usuallySpend) }
    fun assignCard(id: Long, cardId: Long) = launchEdit { it.assignCard(id, cardId) }
    fun assignAccount(id: Long, accountId: Long) = launchEdit { it.assignAccount(id, accountId) }
    fun markRepayment(id: Long, lentId: Long) = launchEdit { it.markLentRepayment(id, lentId) }
    fun confirmTransfer(a: Long, b: Long) = launchEdit { it.confirmTransfer(a, b) }
    fun unlinkTransfer(id: Long) = launchEdit { it.unlinkTransfer(id) }
    fun deleteManual(id: Long) = launchEdit { it.deleteManual(id) }

    fun addManual(accountId: Long?, debit: Boolean, amountPaise: Long, date: LocalDate, block: Block, reason: String) {
        viewModelScope.launch {
            repository.edit {
                it.addManual(
                    accountId,
                    if (debit) Direction.DEBIT else Direction.CREDIT,
                    amountPaise, date, block, reason.ifBlank { null },
                )
            }
        }
    }

    // ---- profile -------------------------------------------------------------------------------------------

    fun saveProfile(change: (OwnerProfile) -> OwnerProfile) {
        viewModelScope.launch { repository.updateProfile(change(repository.state.value.profile)) }
    }

    fun saveOverlay(change: (ParserOverlay) -> ParserOverlay) {
        viewModelScope.launch { repository.updateOverlay(change) }
    }

    fun updateSettings(change: (SettingsState) -> SettingsState) {
        container.settings.update(change)
        Scheduler.schedule(getApplication())
    }

    // ---- messages ---------------------------------------------------------------------------------------------

    private val _unparsed = MutableStateFlow<List<StoredMessage>>(emptyList())
    val unparsed: StateFlow<List<StoredMessage>> = _unparsed.asStateFlow()

    fun loadUnparsed() { viewModelScope.launch { _unparsed.value = repository.unparsed() } }

    fun muteSender(brand: String, id: Long) {
        viewModelScope.launch {
            repository.updateOverlay { it.copy(muted = (it.muted + brand).distinct()) }
            repository.dismissMessage(id)
            _unparsed.value = repository.unparsed()
        }
    }

    fun dismissMessage(id: Long) {
        viewModelScope.launch { repository.dismissMessage(id); _unparsed.value = repository.unparsed() }
    }

    fun importInbox(context: Context) {
        viewModelScope.launch {
            _busy.value = true
            try {
                val days = settings.value.importDays
                val since = System.currentTimeMillis() - days * 86_400_000L
                val r = InboxImporter.import(context, since)
                _message.value = "Read ${r.read} messages: ${r.added} new transactions, ${r.merged} merged, ${r.notices} notices, " +
                    "${r.unparsed} not understood, ${r.duplicates} already seen."
            } catch (e: SecurityException) {
                _message.value = "SMS permission is not granted."
            } finally {
                _busy.value = false
            }
        }
    }

    // ---- data: export, backup, erase -------------------------------------------------------------------------

    fun exportCsv(context: Context, uri: Uri) {
        viewModelScope.launch {
            val snap = repository.state.value
            withContext(Dispatchers.IO) {
                context.contentResolver.openOutputStream(uri)?.use { it.write(CsvExport.build(snap.profile, snap.entries).toByteArray()) }
            }
            _message.value = "Exported ${snap.entries.size} transactions."
        }
    }

    fun backupTo(context: Context, uri: Uri, passphrase: CharArray) {
        viewModelScope.launch {
            _busy.value = true
            try {
                val (snapshot, overlay) = repository.snapshotForBackup()
                val bytes = withContext(Dispatchers.Default) { Backup.encrypt(snapshot, overlay, passphrase) }
                withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } }
                _message.value = "Encrypted backup saved."
            } finally {
                _busy.value = false
            }
        }
    }

    fun restoreFrom(context: Context, uri: Uri, passphrase: CharArray) {
        viewModelScope.launch {
            _busy.value = true
            try {
                val bytes = withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }
                    ?: error("Could not read the file")
                val (snapshot, overlay) = withContext(Dispatchers.Default) { Backup.decrypt(bytes, passphrase) }
                repository.restore(snapshot, overlay)
                _message.value = "Restored ${snapshot.entries.size} transactions."
            } catch (e: WrongPassphraseException) {
                _message.value = "Wrong passphrase, or this is not a Buho FinTrack backup."
            } finally {
                _busy.value = false
            }
        }
    }

    fun eraseAll() {
        viewModelScope.launch {
            repository.eraseAll()
            container.settings.clear()
            Scheduler.cancel(getApplication())
            _message.value = "All data erased."
        }
    }

    fun resetBrokenDatabase() {
        container.destroyDatabase()
        viewModelScope.launch { container.ensureLoaded() }
    }

    fun entry(snapshot: AppSnapshot, id: Long): LedgerEntry? = snapshot.entries.firstOrNull { it.id == id }
}
