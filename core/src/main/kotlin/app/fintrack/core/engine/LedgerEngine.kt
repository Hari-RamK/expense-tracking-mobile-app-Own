package app.fintrack.core.engine

import app.fintrack.core.model.AuditRecord
import app.fintrack.core.model.Block
import app.fintrack.core.model.Direction
import app.fintrack.core.model.LedgerEntry
import app.fintrack.core.model.Notice
import app.fintrack.core.model.OwnerProfile
import app.fintrack.core.model.PersonState
import app.fintrack.core.model.RawSms
import app.fintrack.core.model.ReasonSource
import app.fintrack.core.model.TransferGroup
import app.fintrack.core.parser.ParseResult
import app.fintrack.core.parser.ParsedMessage
import app.fintrack.core.parser.SmsParser
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.abs

enum class IngestStatus { DUPLICATE_MESSAGE, IGNORED, UNPARSED, NOTICE, ADDED, MERGED }

/** What the app must persist after one message. Entries are upserts keyed by id. */
data class IngestResult(
    val status: IngestStatus,
    val reason: String? = null,
    val entries: List<LedgerEntry> = emptyList(),
    val groups: List<TransferGroup> = emptyList(),
    val notice: Notice? = null,
    val audit: List<AuditRecord> = emptyList(),
    val messageHash: String,
)

/**
 * The ledger brain: parse -> classify -> de-duplicate -> match transfers.
 * Pure Kotlin with in-memory state; the app loads state from its database at start and persists each [IngestResult].
 * Every operation is idempotent: re-feeding the same message never changes totals.
 */
class LedgerEngine(
    private val parser: SmsParser,
    profile: OwnerProfile,
    entries: List<LedgerEntry> = emptyList(),
    notices: List<Notice> = emptyList(),
    groups: List<TransferGroup> = emptyList(),
    seenMessageHashes: Set<String> = emptySet(),
    private val zone: ZoneId = ZoneId.of("Asia/Kolkata"),
    private val clock: () -> Long = System::currentTimeMillis,
    /** Date-only legs of a transfer may be this many days apart and still be matched. */
    private val transferWindowDays: Long = 2,
) {
    var profile: OwnerProfile = profile
        private set

    private val entryList = entries.toMutableList()
    private val noticeMap = notices.associateBy { it.key }.toMutableMap()
    private val groupList = groups.toMutableList()
    private val seen = seenMessageHashes.toMutableSet()

    val entries: List<LedgerEntry> get() = entryList.toList()
    val notices: List<Notice> get() = noticeMap.values.toList()
    val groups: List<TransferGroup> get() = groupList.toList()

    fun entry(id: Long): LedgerEntry? = entryList.firstOrNull { it.id == id }

    /** Lets a message be processed again, e.g. an unparsed one after a template or sender rule was added. */
    fun forgetMessage(hash: String) {
        seen.remove(hash)
    }

    // ---- ingestion -----------------------------------------------------------------------------

    fun ingest(sms: RawSms): IngestResult {
        val hash = messageHash(sms, zone)
        if (!seen.add(hash)) return IngestResult(IngestStatus.DUPLICATE_MESSAGE, messageHash = hash)
        return when (val result = parser.parse(sms.body, sms.sender)) {
            is ParseResult.Ignored -> IngestResult(IngestStatus.IGNORED, result.reason, messageHash = hash)
            is ParseResult.Unparsed -> IngestResult(IngestStatus.UNPARSED, result.reason, messageHash = hash)
            is ParseResult.Ambiguous -> IngestResult(IngestStatus.UNPARSED, "AMBIGUOUS_TEMPLATES", messageHash = hash)
            is ParseResult.Parsed ->
                if (result.message.isTransaction) ingestTransaction(sms, result.message, hash) else ingestNotice(sms, result.message, hash)
        }
    }

    private fun ingestNotice(sms: RawSms, parsed: ParsedMessage, hash: String): IngestResult {
        val key = parsed.noticeKey ?: "${parsed.eventKind}|${parsed.amountPaise}|${parsed.dueDate}"
        val resolution = resolveAccount(parsed, profile)
        val notice = Notice(
            key = key,
            eventKind = parsed.eventKind,
            amountPaise = parsed.amountPaise,
            minDuePaise = parsed.minDuePaise,
            dueDate = parsed.dueDate?.let(LocalDate::parse),
            payee = parsed.counterpartyName,
            policyRef = parsed.policyRef,
            accountId = resolution.account?.id,
            accountHint = resolution.hint,
            lastSeenMillis = sms.receivedAtMillis,
        )
        noticeMap[key] = notice
        // A new statement may let an unassigned card-bill payment be matched.
        val changed = entryList.filter { it.block == Block.CARD_BILL && it.settlesAccountId == null && !it.userEdited }
            .mapNotNull { e -> replace(e, autoAssignCard(e)).takeIf { it.settlesAccountId != null } }
        return IngestResult(IngestStatus.NOTICE, entries = changed, notice = notice, messageHash = hash)
    }

    private fun ingestTransaction(sms: RawSms, parsed: ParsedMessage, hash: String): IngestResult {
        val date = parsed.date?.let(LocalDate::parse) ?: Instant.ofEpochMilli(sms.receivedAtMillis).atZone(zone).toLocalDate()
        val skeleton = LedgerEntry(
            id = 0,
            accountId = null,
            accountHint = null,
            direction = Direction.valueOf(requireNotNull(parsed.direction)),
            amountPaise = parsed.amountPaise,
            date = date,
            time = parsed.time?.let(LocalTime::parse),
            block = Block.EXCLUDED,
            reason = null,
            reasonSource = ReasonSource.NONE,
            counterpartyName = parsed.counterpartyName,
            counterpartyVpa = parsed.counterpartyVpa,
            counterpartyAcctSuffix = parsed.counterpartyAcctSuffix,
            ref = parsed.ref,
            balancePaise = parsed.balancePaise,
            method = parsed.method,
            senderBrand = parsed.senderBrand,
            parsed = parsed,
            sourceMessageIds = listOf(sms.id),
        )
        val classified = reclassify(skeleton)
        val trail = mutableListOf<AuditRecord>()

        val duplicate = findDuplicate(classified)
        if (duplicate != null) {
            val merged = reclassify(mergeInto(duplicate, classified), force = false)
            replace(duplicate, merged)
            trail += audit(merged.id, "MERGED_DUPLICATE", "same ${if (classified.ref != null) "reference" else "event"} from ${sms.sender ?: "unknown sender"}")
            val linked = tryLink(merged)
            return IngestResult(IngestStatus.MERGED, entries = (listOf(merged) + linked.entries).distinctBy { it.id }.map { latest(it.id) }, groups = linked.groups, audit = trail, messageHash = hash)
        }

        var entry = classified.copy(id = nextEntryId())
        if (entry.ref != null && entryList.any { it.ref == entry.ref && it.direction == entry.direction && sameAccount(it, entry) }) {
            entry = entry.copy(needsReview = true, hint = "REF_CONFLICT")
            trail += audit(entry.id, "REF_CONFLICT", "same reference as an existing entry but a different amount")
        }
        entryList += entry
        trail += audit(entry.id, "ADDED", "block=${entry.block}")
        val linked = tryLink(entry)
        return IngestResult(IngestStatus.ADDED, entries = (listOf(entry) + linked.entries).distinctBy { it.id }.map { latest(it.id) }, groups = linked.groups, audit = trail + linked.audit, messageHash = hash)
    }

    // ---- classification --------------------------------------------------------------------------

    /** Re-derives account, block and reason from the stored parsed fields and the current profile. */
    private fun reclassify(e: LedgerEntry, force: Boolean = true): LedgerEntry {
        val parsed = e.parsed ?: return e
        if (e.userEdited && !force) return e
        val resolution = resolveAccount(parsed, profile)
        val base = classify(parsed, resolution.account, profile)
        val c = applyPersonRule(base, e.amountPaise, normalizeName(e.counterpartyName), profile)
        val needsAccount = resolution.account == null && c.block != Block.TRANSFER
        var out = e.copy(
            accountId = resolution.account?.id,
            accountHint = resolution.hint,
            block = c.block,
            reason = if (e.reasonSource == ReasonSource.USER) e.reason else c.reason,
            reasonSource = if (e.reasonSource == ReasonSource.USER) ReasonSource.USER else c.reasonSource,
            personState = c.personState,
            regularItemId = c.regularItem?.id,
            isSalary = c.isSalary,
            needsReview = c.needsReview || needsAccount,
            hint = c.hint ?: if (needsAccount) "ASSIGN_ACCOUNT" else null,
        )
        if (out.block == Block.CARD_BILL && out.settlesAccountId == null) out = autoAssignCard(out)
        if (out.block == Block.UNCLASSIFIED_CREDIT) out = suggestRepayment(out)
        return out
    }

    /** A credit from someone you lent to is offered as a repayment. It stays unclassified until you confirm. */
    private fun suggestRepayment(e: LedgerEntry): LedgerEntry {
        val who = normalizeName(e.counterpartyName)
        if (who.isEmpty()) return e
        val open = entryList.firstOrNull { l ->
            l.block == Block.LENT && normalizeName(l.counterpartyName) == who &&
                l.amountPaise > entryList.filter { it.block == Block.LENT_REPAYMENT && it.settlesEntryId == l.id }.sumOf { it.amountPaise }
        } ?: return e
        return e.copy(hint = "LENT_REPAYMENT:${open.id}")
    }

    /** CRED-style payments are assigned to a card when they equal an open statement's total or minimum due. */
    private fun autoAssignCard(e: LedgerEntry): LedgerEntry {
        val match = noticeMap.values.firstOrNull { n ->
            n.eventKind == "STATEMENT_DUE" && n.accountId != null && n.dueDate != null &&
                (n.amountPaise == e.amountPaise || n.minDuePaise == e.amountPaise) &&
                e.date >= n.dueDate.minusDays(45) && e.date <= n.dueDate.plusDays(3)
        } ?: return e
        return e.copy(settlesAccountId = match.accountId, needsReview = false, hint = "AUTO_ASSIGNED_CARD")
    }

    // ---- duplicates ------------------------------------------------------------------------------

    private fun sameAccount(a: LedgerEntry, b: LedgerEntry): Boolean =
        if (a.accountId != null || b.accountId != null) a.accountId == b.accountId else a.accountHint == b.accountHint

    /**
     * Identity rules. An exact reference (same account, direction and amount) is the same event.
     * Without a reference, only a second source (different sender) of the same event on the same day is merged;
     * two identical purchases from one sender stay two purchases. Equal amount and time alone never merge.
     */
    private fun findDuplicate(e: LedgerEntry): LedgerEntry? {
        if (e.ref != null) {
            return entryList.firstOrNull {
                it.ref == e.ref && it.direction == e.direction && sameAccount(it, e) && it.amountPaise == e.amountPaise
            }
        }
        return entryList.firstOrNull {
            it.ref == null && sameAccount(it, e) && it.direction == e.direction && it.amountPaise == e.amountPaise &&
                it.date == e.date && normalizeName(it.counterpartyName) == normalizeName(e.counterpartyName) &&
                it.senderBrand != null && e.senderBrand != null && it.senderBrand != e.senderBrand &&
                timesCompatible(it.time, e.time)
        }
    }

    private fun timesCompatible(a: LocalTime?, b: LocalTime?): Boolean =
        a == null || b == null || abs(ChronoUnit.MINUTES.between(a, b)) <= 2

    private fun mergeInto(existing: LedgerEntry, incoming: LedgerEntry): LedgerEntry = existing.copy(
        time = existing.time ?: incoming.time,
        balancePaise = existing.balancePaise ?: incoming.balancePaise,
        counterpartyName = existing.counterpartyName ?: incoming.counterpartyName,
        counterpartyVpa = existing.counterpartyVpa ?: incoming.counterpartyVpa,
        counterpartyAcctSuffix = existing.counterpartyAcctSuffix ?: incoming.counterpartyAcctSuffix,
        sourceMessageIds = (existing.sourceMessageIds + incoming.sourceMessageIds).distinct(),
        parsed = existing.parsed ?: incoming.parsed,
    )

    // ---- transfer matching -----------------------------------------------------------------------

    private data class Link(val entries: List<LedgerEntry>, val groups: List<TransferGroup>, val audit: List<AuditRecord>)

    private val linkable = setOf(Block.TRANSFER, Block.UNCLASSIFIED_CREDIT)

    private fun cpMatchesAccount(e: LedgerEntry, other: LedgerEntry): Boolean {
        val cp = e.counterpartyAcctSuffix ?: return false
        val account = profile.accounts.firstOrNull { it.id == other.accountId } ?: return false
        return cp.length >= 4 && account.suffixes.any { it == cp || it.endsWith(cp) }
    }

    private fun score(e: LedgerEntry, other: LedgerEntry): Int? {
        val refEqual = e.ref != null && e.ref == other.ref
        val amountEqual = e.amountPaise == other.amountPaise
        if (!amountEqual && !refEqual) return null
        val days = abs(ChronoUnit.DAYS.between(e.date, other.date))
        if (days > transferWindowDays && !refEqual) return null
        var s = 0
        if (refEqual) s += 100
        if (cpMatchesAccount(e, other) || cpMatchesAccount(other, e)) s += 40
        if (amountEqual) s += 20 else s = minOf(s, 85)
        s += when (days) { 0L -> 20; 1L -> 10; else -> 0 }
        if (e.time != null && other.time != null && abs(ChronoUnit.MINUTES.between(e.time, other.time)) <= 10) s += 5
        val ownSignal = listOf(e, other).any { x -> x.parsed?.let { ownCounterparty(it, profile, x.accountId) } != null }
        if (ownSignal) s += 10
        return s
    }

    /**
     * Links a debit and a credit between two of the owner's accounts.
     * 90+ links automatically, 50-89 is proposed to the owner, below 50 nothing happens.
     * Equal amounts alone score 20 and can never reach either threshold.
     */
    private fun tryLink(entry: LedgerEntry): Link {
        val e = latest(entry.id)
        if (e.block !in linkable || e.transferGroupId != null || e.accountId == null || e.userEdited) return Link(emptyList(), emptyList(), emptyList())
        val pool = entryList.filter {
            it.id != e.id && it.transferGroupId == null && it.block in linkable && it.direction != e.direction &&
                it.accountId != null && it.accountId != e.accountId
        }
        val scored = pool.mapNotNull { o -> score(e, o)?.let { o to it } }
        if (scored.isEmpty()) return Link(emptyList(), emptyList(), emptyList())
        val top = scored.maxOf { it.second }
        val tied = scored.count { it.second == top }
        val best = scored.first { it.second == top }
        val finalScore = if (tied > 1) top - 30 else top
        return when {
            finalScore >= 90 -> {
                val group = TransferGroup(nextGroupId(), finalScore, auto = true)
                groupList += group
                val a = replace(e, e.copy(block = Block.TRANSFER, transferGroupId = group.id, needsReview = false, hint = null))
                val b = replace(best.first, best.first.copy(block = Block.TRANSFER, transferGroupId = group.id, needsReview = false, hint = null))
                Link(listOf(a, b), listOf(group), listOf(audit(a.id, "TRANSFER_LINKED", "with ${b.id} score=$finalScore")))
            }
            finalScore >= 50 -> {
                val a = replace(e, e.copy(needsReview = true, hint = "TRANSFER_CANDIDATE:${best.first.id}"))
                val b = replace(best.first, best.first.copy(needsReview = true, hint = "TRANSFER_CANDIDATE:${e.id}"))
                Link(listOf(a, b), emptyList(), listOf(audit(a.id, "TRANSFER_PROPOSED", "with ${b.id} score=$finalScore")))
            }
            else -> Link(emptyList(), emptyList(), emptyList())
        }
    }

    // ---- owner actions ---------------------------------------------------------------------------

    private fun edit(id: Long, action: String, detail: String, change: (LedgerEntry) -> LedgerEntry): IngestResult {
        val old = entry(id) ?: return IngestResult(IngestStatus.IGNORED, "NO_SUCH_ENTRY", messageHash = "")
        val updated = replace(old, change(old).copy(userEdited = true, needsReview = false))
        return IngestResult(IngestStatus.MERGED, entries = listOf(updated), audit = listOf(audit(id, action, detail)), messageHash = "")
    }

    fun setBlock(id: Long, block: Block) = edit(id, "SET_BLOCK", "to $block") { it.copy(block = block, hint = null) }

    fun setReason(id: Long, reason: String, learn: Boolean = true): IngestResult {
        val result = edit(id, "SET_REASON", reason) { it.copy(reason = reason, reasonSource = ReasonSource.USER) }
        val e = result.entries.firstOrNull()
        if (learn && e != null) {
            val key = normalizeName(e.counterpartyName)
            if (key.isNotEmpty()) profile = profile.copy(payeeReasons = profile.payeeReasons + (key to reason))
        }
        return result
    }

    /** Owner's decision for a payment to a person. Lent is only ever chosen here, never automatically. */
    fun setPersonState(id: Long, state: PersonState, usuallySpend: Boolean = false): IngestResult {
        val result = edit(id, "SET_PERSON_STATE", state.name) {
            it.copy(personState = state, block = when (state) {
                PersonState.SPEND -> Block.BANK_SPEND
                PersonState.LENT -> Block.LENT
                PersonState.UNDECIDED -> Block.UNDECIDED
            }, hint = null)
        }
        val e = result.entries.firstOrNull()
        if (usuallySpend && state == PersonState.SPEND && e != null) {
            profile = profile.copy(usuallySpendPayees = profile.usuallySpendPayees + normalizeName(e.counterpartyName))
        }
        return result
    }

    fun assignCard(id: Long, cardAccountId: Long) =
        edit(id, "ASSIGN_CARD", "card $cardAccountId") { it.copy(block = Block.CARD_BILL, settlesAccountId = cardAccountId, hint = null) }

    fun assignAccount(id: Long, accountId: Long) =
        edit(id, "ASSIGN_ACCOUNT", "account $accountId") { it.copy(accountId = accountId, hint = null) }

    fun markLentRepayment(id: Long, lentEntryId: Long) =
        edit(id, "LENT_REPAYMENT", "repays $lentEntryId") { it.copy(block = Block.LENT_REPAYMENT, settlesEntryId = lentEntryId, hint = null) }

    /** Owner confirms (or creates) a link between two entries, e.g. a proposed transfer. */
    fun confirmTransfer(idA: Long, idB: Long): IngestResult {
        val a = entry(idA) ?: return IngestResult(IngestStatus.IGNORED, "NO_SUCH_ENTRY", messageHash = "")
        val b = entry(idB) ?: return IngestResult(IngestStatus.IGNORED, "NO_SUCH_ENTRY", messageHash = "")
        require(a.direction != b.direction) { "A transfer links one debit and one credit" }
        val group = TransferGroup(nextGroupId(), 100, auto = false)
        groupList += group
        val ua = replace(a, a.copy(block = Block.TRANSFER, transferGroupId = group.id, needsReview = false, hint = null, userEdited = true))
        val ub = replace(b, b.copy(block = Block.TRANSFER, transferGroupId = group.id, needsReview = false, hint = null, userEdited = true))
        return IngestResult(IngestStatus.MERGED, entries = listOf(ua, ub), groups = listOf(group),
            audit = listOf(audit(idA, "TRANSFER_CONFIRMED", "with $idB")), messageHash = "")
    }

    fun unlinkTransfer(id: Long): IngestResult {
        val e = entry(id) ?: return IngestResult(IngestStatus.IGNORED, "NO_SUCH_ENTRY", messageHash = "")
        val gid = e.transferGroupId ?: return IngestResult(IngestStatus.IGNORED, "NOT_LINKED", messageHash = "")
        val members = entryList.filter { it.transferGroupId == gid }.map { m ->
            replace(m, m.copy(transferGroupId = null, userEdited = true, needsReview = true, hint = "LEG_NOT_SEEN"))
        }
        return IngestResult(IngestStatus.MERGED, entries = members, audit = listOf(audit(id, "TRANSFER_UNLINKED", "group $gid")), messageHash = "")
    }

    fun addManual(
        accountId: Long?, direction: Direction, amountPaise: Long, date: LocalDate, block: Block, reason: String?,
    ): IngestResult {
        val e = LedgerEntry(
            id = nextEntryId(), accountId = accountId, accountHint = null, direction = direction, amountPaise = amountPaise,
            date = date, time = null, block = block, reason = reason, reasonSource = ReasonSource.USER,
            counterpartyName = null, counterpartyVpa = null, counterpartyAcctSuffix = null, ref = null, balancePaise = null,
            method = "MANUAL", senderBrand = null, userEdited = true, manual = true,
        )
        entryList += e
        return IngestResult(IngestStatus.ADDED, entries = listOf(e), audit = listOf(audit(e.id, "MANUAL_ADDED", "block=$block")), messageHash = "")
    }

    fun deleteManual(id: Long): Boolean {
        val e = entry(id) ?: return false
        if (!e.manual) return false
        return entryList.remove(e)
    }

    // ---- profile changes ---------------------------------------------------------------------------

    /**
     * Applies a new profile (new account, names, items, threshold) and re-derives every entry the owner has not edited.
     * Returns the entries whose classification changed so the app can persist them.
     */
    fun updateProfile(newProfile: OwnerProfile): IngestResult {
        profile = newProfile
        val changed = mutableListOf<LedgerEntry>()
        for (e in entryList.toList()) {
            if (e.userEdited || e.parsed == null) continue
            val r = reclassify(e).copy(transferGroupId = e.transferGroupId)
            val keepLink = if (e.transferGroupId != null) r.copy(block = Block.TRANSFER, needsReview = false, hint = null) else r
            if (keepLink != e) changed += replace(e, keepLink)
        }
        val groups = mutableListOf<TransferGroup>()
        val trail = mutableListOf<AuditRecord>()
        for (e in entryList.filter { it.block in linkable && it.transferGroupId == null }) {
            val link = tryLink(e)
            changed += link.entries
            groups += link.groups
            trail += link.audit
        }
        return IngestResult(IngestStatus.MERGED, entries = changed.map { latest(it.id) }.distinctBy { it.id }, groups = groups, audit = trail, messageHash = "")
    }

    // ---- helpers -----------------------------------------------------------------------------------

    private fun latest(id: Long): LedgerEntry = entryList.first { it.id == id }

    private fun replace(old: LedgerEntry, new: LedgerEntry): LedgerEntry {
        val i = entryList.indexOfFirst { it.id == old.id }
        if (i >= 0) entryList[i] = new
        return new
    }

    private fun nextEntryId(): Long = (entryList.maxOfOrNull { it.id } ?: 0L) + 1
    private fun nextGroupId(): Long = (groupList.maxOfOrNull { it.id } ?: 0L) + 1
    private fun audit(entryId: Long?, action: String, detail: String) = AuditRecord(entryId, action, detail, clock())

    companion object {
        /**
         * Stable fingerprint of a message, so reprocessing the same SMS is a no-op. It includes the local day of
         * receipt: the same text arriving on another day (a daily reminder) is a new message, while a live receive
         * and a later inbox re-read of one message land on the same day and match.
         */
        fun messageHash(sms: RawSms, zone: ZoneId): String {
            val day = Instant.ofEpochMilli(sms.receivedAtMillis).atZone(zone).toLocalDate()
            val text = (sms.sender ?: "") + "|" + day + "|" + sms.body.replace(Regex("\\s+"), " ").trim()
            return MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
        }
    }
}
