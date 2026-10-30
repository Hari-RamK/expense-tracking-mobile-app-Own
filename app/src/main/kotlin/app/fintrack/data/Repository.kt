package app.fintrack.data

import android.content.ContentValues
import app.fintrack.core.engine.IngestResult
import app.fintrack.core.engine.IngestStatus
import app.fintrack.core.engine.LedgerEngine
import app.fintrack.core.model.LedgerEntry
import app.fintrack.core.model.LedgerSnapshot
import app.fintrack.core.model.Notice
import app.fintrack.core.model.OwnerProfile
import app.fintrack.core.model.RawSms
import app.fintrack.core.model.TransferGroup
import app.fintrack.core.parser.BrandSpec
import app.fintrack.core.parser.IgnoreSpec
import app.fintrack.core.parser.ParserSpec
import app.fintrack.core.parser.SmsParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** The owner's own sender and ignore choices, layered on the bundled parser spec. */
@Serializable
data class ParserOverlay(
    val brands: Map<String, BrandSpec> = emptyMap(),
    val muted: List<String> = emptyList(),
    val ignore: List<IgnoreSpec> = emptyList(),
)

data class StoredMessage(val id: Long, val hash: String, val sender: String?, val body: String, val receivedAt: Long)

/** Immutable view of everything the UI needs. Reports are computed from it, never stored. */
data class AppSnapshot(
    val profile: OwnerProfile = OwnerProfile(),
    val entries: List<LedgerEntry> = emptyList(),
    val notices: List<Notice> = emptyList(),
    val overlay: ParserOverlay = ParserOverlay(),
    val unparsedCount: Int = 0,
    val loaded: Boolean = false,
    val revision: Long = 0,
)

/**
 * Owns the engine and the database. Every change goes through here: the engine decides, the result is written in one
 * transaction, and a new [AppSnapshot] is published. Calls are serialised by a mutex because the engine is not thread-safe.
 */
class Repository(private val database: Database, private val settings: Settings) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val mutex = Mutex()
    private lateinit var engine: LedgerEngine
    private var overlay = ParserOverlay()
    private var revision = 0L
    private val _state = MutableStateFlow(AppSnapshot())
    val state: StateFlow<AppSnapshot> = _state.asStateFlow()

    private val db get() = database.writableDatabase

    suspend fun load() = withContext(Dispatchers.IO) {
        mutex.withLock {
            overlay = kvGet(KV_OVERLAY)?.let { json.decodeFromString<ParserOverlay>(it) } ?: ParserOverlay()
            val profile = kvGet(KV_PROFILE)?.let { json.decodeFromString<OwnerProfile>(it) } ?: OwnerProfile()
            engine = newEngine(
                LedgerSnapshot(profile, readEntries(), readNotices(), readGroups(), readHashes()),
            )
            publish()
        }
    }

    private fun newEngine(s: LedgerSnapshot): LedgerEngine {
        val spec = ParserSpec.loadBundled().withOverlay(overlay.brands, overlay.muted, overlay.ignore)
        return LedgerEngine(SmsParser(spec), s.profile, s.entries, s.notices, s.groups, s.seenMessageHashes, zone = settings.zone)
    }

    // ---- ingestion -----------------------------------------------------------------------------------

    /** Returns how the message was handled. Safe to call twice with the same message. */
    suspend fun ingestSms(sender: String?, body: String, receivedAtMillis: Long): IngestStatus = withContext(Dispatchers.IO) {
        mutex.withLock {
            val status = ingestLocked(sender, body, receivedAtMillis)
            publish()
            status
        }
    }

    /** Ingests many messages (inbox import) with one publish at the end. Returns counts by status. */
    suspend fun ingestMany(messages: List<Triple<String?, String, Long>>, onProgress: (Int) -> Unit = {}): Map<IngestStatus, Int> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val counts = mutableMapOf<IngestStatus, Int>()
                messages.forEachIndexed { i, (sender, body, at) ->
                    val st = ingestLocked(sender, body, at)
                    counts[st] = (counts[st] ?: 0) + 1
                    if (i % 25 == 0) onProgress(i)
                }
                publish()
                counts
            }
        }

    private fun ingestLocked(sender: String?, body: String, receivedAt: Long): IngestStatus {
        val probe = RawSms(0, sender, body, receivedAt)
        val hash = LedgerEngine.messageHash(probe, settings.zone)
        val values = ContentValues().apply {
            put("hash", hash); put("sender", sender); put("body", body); put("received_at", receivedAt); put("status", "NEW")
        }
        val rowId = db.insertWithOnConflict("message", null, values, 4 /* CONFLICT_IGNORE */)
        if (rowId == -1L) return IngestStatus.DUPLICATE_MESSAGE
        val result = engine.ingest(RawSms(rowId, sender, body, receivedAt))
        db.beginTransaction()
        try {
            writeResult(result)
            val clearBody = result.status == IngestStatus.IGNORED
            db.execSQL(
                "UPDATE message SET status = ?, reason = ?, body = ? WHERE id = ?",
                arrayOf<Any?>(result.status.name, result.reason, if (clearBody) "" else body, rowId),
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return result.status
    }

    // ---- owner actions ---------------------------------------------------------------------------------

    /** Runs an engine operation (edit, assign, confirm...) and persists whatever it changed, including a learned profile. */
    suspend fun <T> edit(operation: (LedgerEngine) -> T): T = withContext(Dispatchers.IO) {
        mutex.withLock {
            val before = engine.profile
            val result = operation(engine)
            db.beginTransaction()
            try {
                if (result is IngestResult) writeResult(result)
                if (engine.profile != before) saveProfileLocked(engine.profile)
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            publish()
            result
        }
    }

    suspend fun updateProfile(profile: OwnerProfile) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val result = engine.updateProfile(profile)
            db.beginTransaction()
            try {
                saveProfileLocked(profile)
                writeResult(result)
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            publish()
        }
    }

    suspend fun updateOverlay(change: (ParserOverlay) -> ParserOverlay) = withContext(Dispatchers.IO) {
        mutex.withLock {
            overlay = change(overlay)
            kvPut(KV_OVERLAY, json.encodeToString(overlay))
            val snapshot = LedgerSnapshot(engine.profile, engine.entries, engine.notices, engine.groups, readHashes())
            engine = newEngine(snapshot)
            reprocessUnparsedLocked()
            publish()
        }
    }

    suspend fun reprocessUnparsed() = withContext(Dispatchers.IO) { mutex.withLock { reprocessUnparsedLocked(); publish() } }

    private fun reprocessUnparsedLocked() {
        for (m in unparsedLocked()) {
            val raw = RawSms(m.id, m.sender, m.body, m.receivedAt)
            engine.forgetMessage(m.hash)
            val result = engine.ingest(raw)
            if (result.status == IngestStatus.UNPARSED) continue
            db.beginTransaction()
            try {
                writeResult(result)
                db.execSQL(
                    "UPDATE message SET status = ?, reason = ?, body = ? WHERE id = ?",
                    arrayOf<Any?>(result.status.name, result.reason, if (result.status == IngestStatus.IGNORED) "" else m.body, m.id),
                )
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
    }

    suspend fun unparsed(): List<StoredMessage> = withContext(Dispatchers.IO) { mutex.withLock { unparsedLocked() } }

    suspend fun dismissMessage(id: Long) = withContext(Dispatchers.IO) {
        mutex.withLock {
            db.execSQL("UPDATE message SET status = 'DISMISSED', body = '' WHERE id = ?", arrayOf<Any>(id))
            publish()
        }
    }

    private fun unparsedLocked(): List<StoredMessage> = db.rawQuery(
        "SELECT id, hash, sender, body, received_at FROM message WHERE status = 'UNPARSED' ORDER BY received_at DESC LIMIT 200", null,
    ).use { c ->
        buildList { while (c.moveToNext()) add(StoredMessage(c.getLong(0), c.getString(1), c.getString(2), c.getString(3), c.getLong(4))) }
    }

    // ---- backup / restore / erase -----------------------------------------------------------------------

    suspend fun snapshotForBackup(): Pair<LedgerSnapshot, ParserOverlay> = withContext(Dispatchers.IO) {
        mutex.withLock { LedgerSnapshot(engine.profile, engine.entries, engine.notices, engine.groups, readHashes()) to overlay }
    }

    suspend fun restore(snapshot: LedgerSnapshot, restoredOverlay: ParserOverlay) = withContext(Dispatchers.IO) {
        mutex.withLock {
            db.beginTransaction()
            try {
                for (t in listOf("entry", "notice", "transfer_group", "message", "audit", "kv")) db.execSQL("DELETE FROM $t")
                snapshot.entries.forEach { putEntry(it) }
                snapshot.notices.forEach { putNotice(it) }
                snapshot.groups.forEach { putGroup(it) }
                snapshot.seenMessageHashes.forEach { h ->
                    db.execSQL("INSERT OR IGNORE INTO message(hash, sender, body, received_at, status) VALUES (?, NULL, '', 0, 'RESTORED')", arrayOf<Any>(h))
                }
                overlay = restoredOverlay
                kvPut(KV_OVERLAY, json.encodeToString(overlay))
                saveProfileLocked(snapshot.profile)
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            engine = newEngine(snapshot)
            publish()
        }
    }

    /** Wipes every financial record. The key stays, so the app keeps working empty. */
    suspend fun eraseAll() = withContext(Dispatchers.IO) {
        mutex.withLock {
            for (t in listOf("entry", "notice", "transfer_group", "message", "audit", "kv")) db.execSQL("DELETE FROM $t")
            overlay = ParserOverlay()
            engine = newEngine(LedgerSnapshot(OwnerProfile(), emptyList(), emptyList(), emptyList(), emptySet()))
            publish()
        }
    }

    // ---- key/value -----------------------------------------------------------------------------------------

    suspend fun kv(key: String): String? = withContext(Dispatchers.IO) { mutex.withLock { kvGet(key) } }
    suspend fun putKv(key: String, value: String) = withContext(Dispatchers.IO) { mutex.withLock { kvPut(key, value) } }

    private fun kvGet(key: String): String? =
        db.rawQuery("SELECT value FROM kv WHERE key = ?", arrayOf(key)).use { if (it.moveToFirst()) it.getString(0) else null }

    private fun kvPut(key: String, value: String) {
        db.replace("kv", null, ContentValues().apply { put("key", key); put("value", value) })
    }

    // ---- persistence helpers ---------------------------------------------------------------------------------

    private fun saveProfileLocked(p: OwnerProfile) = kvPut(KV_PROFILE, json.encodeToString(p))

    private fun writeResult(r: IngestResult) {
        r.entries.forEach { putEntry(it) }
        r.groups.forEach { putGroup(it) }
        r.notice?.let { putNotice(it) }
        r.audit.forEach { a ->
            db.execSQL(
                "INSERT INTO audit(entry_id, action, detail, at) VALUES (?, ?, ?, ?)",
                arrayOf<Any?>(a.entryId, a.action, a.detail, a.atMillis),
            )
        }
    }

    private fun putEntry(e: LedgerEntry) {
        db.replace("entry", null, ContentValues().apply {
            put("id", e.id); put("date", e.date.toString()); put("json", json.encodeToString(e))
        })
    }

    private fun putNotice(n: Notice) {
        db.replace("notice", null, ContentValues().apply { put("key", n.key); put("json", json.encodeToString(n)) })
    }

    private fun putGroup(g: TransferGroup) {
        db.replace("transfer_group", null, ContentValues().apply { put("id", g.id); put("json", json.encodeToString(g)) })
    }

    private fun readEntries(): List<LedgerEntry> =
        db.rawQuery("SELECT json FROM entry ORDER BY id", null).use { c -> buildList<LedgerEntry> { while (c.moveToNext()) add(json.decodeFromString<LedgerEntry>(c.getString(0))) } }

    private fun readNotices(): List<Notice> =
        db.rawQuery("SELECT json FROM notice", null).use { c -> buildList<Notice> { while (c.moveToNext()) add(json.decodeFromString<Notice>(c.getString(0))) } }

    private fun readGroups(): List<TransferGroup> =
        db.rawQuery("SELECT json FROM transfer_group", null).use { c -> buildList<TransferGroup> { while (c.moveToNext()) add(json.decodeFromString<TransferGroup>(c.getString(0))) } }

    private fun readHashes(): Set<String> =
        db.rawQuery("SELECT hash FROM message WHERE status != 'UNPARSED'", null).use { c -> buildSet { while (c.moveToNext()) add(c.getString(0)) } }

    private fun publish() {
        val unparsed = db.rawQuery("SELECT COUNT(*) FROM message WHERE status = 'UNPARSED'", null).use { it.moveToFirst(); it.getInt(0) }
        _state.value = AppSnapshot(engine.profile, engine.entries, engine.notices, overlay, unparsed, loaded = true, revision = ++revision)
    }

    private companion object {
        const val KV_PROFILE = "profile"
        const val KV_OVERLAY = "overlay"
    }
}
