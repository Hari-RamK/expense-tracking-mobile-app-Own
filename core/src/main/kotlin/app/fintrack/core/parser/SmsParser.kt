package app.fintrack.core.parser

import app.fintrack.core.money.parsePaise
import kotlinx.serialization.Serializable

/** Fields extracted from one SMS. Anything the message does not state stays null; nothing is invented. */
@Serializable
data class ParsedMessage(
    val templateId: String,
    val eventKind: String,
    val direction: String?,
    val method: String,
    val isTransaction: Boolean,
    val amountPaise: Long,
    val currency: String = "INR",
    val accountKindHint: String? = null,
    val typeHint: String? = null,
    val accountSuffix: String? = null,
    val counterpartyAcctSuffix: String? = null,
    val bank: String? = null,
    val counterpartyName: String? = null,
    val counterpartyVpa: String? = null,
    val counterpartyBankCode: String? = null,
    val ref: String? = null,
    val umn: String? = null,
    val policyRef: String? = null,
    val description: String? = null,
    val balancePaise: Long? = null,
    val minDuePaise: Long? = null,
    /** Transaction date as written in the message (ISO yyyy-MM-dd). Null when the message has none. */
    val date: String? = null,
    /** For due/auto-debit notices: the date the payment is expected, not a transaction date. */
    val dueDate: String? = null,
    /** 24-hour HH:mm, only when the message states a time. */
    val time: String? = null,
    /** Identity of a repeated reminder, so the same notice sent on several days collapses into one item. */
    val noticeKey: String? = null,
    val senderBrand: String? = null,
    val institution: String? = null,
    val institutionKind: String? = null,
) {
    fun toFieldMap(): Map<String, Any?> = mapOf(
        "result" to "PARSED",
        "templateId" to templateId, "eventKind" to eventKind, "direction" to direction, "method" to method,
        "isTransaction" to isTransaction, "amountPaise" to amountPaise, "currency" to currency,
        "accountKindHint" to accountKindHint, "typeHint" to typeHint, "accountSuffix" to accountSuffix,
        "counterpartyAcctSuffix" to counterpartyAcctSuffix, "bank" to bank,
        "counterpartyName" to counterpartyName, "counterpartyVpa" to counterpartyVpa,
        "counterpartyBankCode" to counterpartyBankCode, "ref" to ref, "umn" to umn, "policyRef" to policyRef,
        "description" to description, "balancePaise" to balancePaise, "minDuePaise" to minDuePaise,
        "date" to date, "dueDate" to dueDate, "time" to time, "noticeKey" to noticeKey,
        "senderBrand" to senderBrand, "institution" to institution, "institutionKind" to institutionKind,
    )
}

sealed interface ParseResult {
    data class Parsed(val message: ParsedMessage) : ParseResult

    /** Recognised as not a transaction (OTP, promotion, muted sender). Nothing is stored beyond a hash. */
    data class Ignored(val reason: String, val senderBrand: String? = null) : ParseResult

    /** A template matched but a field was invalid (e.g. impossible date), or no template matched. Kept for review, never dropped. */
    data class Unparsed(val reason: String? = null, val senderBrand: String? = null) : ParseResult

    /** More than one template matched. A spec bug the fixtures must catch. */
    data class Ambiguous(val templateIds: List<String>) : ParseResult

    fun toFieldMap(): Map<String, Any?> = when (this) {
        is Parsed -> message.toFieldMap()
        is Ignored -> mapOf("result" to "IGNORED", "reason" to reason, "senderBrand" to senderBrand)
        is Unparsed -> mapOf("result" to "UNPARSED", "reason" to reason, "senderBrand" to senderBrand)
        is Ambiguous -> mapOf("result" to "AMBIGUOUS", "templateIds" to templateIds)
    }
}

data class SenderHeader(val prefix: String, val brand: String, val kind: String?)

class SmsParser(private val spec: ParserSpec = ParserSpec.loadBundled()) {

    private class CompiledTemplate(val spec: TemplateSpec, val regex: Regex, val groupNames: List<String>)

    private val templates: List<CompiledTemplate> = spec.templates.templates.map { t ->
        val pattern = expandMacros(t.regex)
        CompiledTemplate(t, Regex(pattern, RegexOption.IGNORE_CASE), GROUP_NAME.findAll(pattern).map { it.groupValues[1] }.toList())
    }

    private val ignores: List<Pair<IgnoreSpec, Regex>> =
        spec.templates.ignore.map { it to Regex(expandMacros(it.regex), RegexOption.IGNORE_CASE) }

    private fun expandMacros(pattern: String): String {
        var out = pattern
        repeat(10) {
            if (!MACRO.containsMatchIn(out)) return out
            out = MACRO.replace(out) { m ->
                requireNotNull(spec.templates.macros[m.groupValues[1]]) { "Unknown macro ${m.groupValues[1]}" }
            }
        }
        return out
    }

    fun parse(rawBody: String, sender: String?): ParseResult {
        val header = parseSender(sender)
        if (header != null && header.brand in spec.senders.muted) {
            return ParseResult.Ignored("MUTED_SENDER", header.brand)
        }
        val result = parseBody(rawBody)
        if (header == null) return result
        val known = spec.senders.brands[header.brand]
        return when (result) {
            is ParseResult.Parsed -> ParseResult.Parsed(
                result.message.copy(
                    senderBrand = header.brand,
                    institution = known?.institution,
                    institutionKind = known?.kind,
                ),
            )
            is ParseResult.Ignored -> result.copy(senderBrand = header.brand)
            is ParseResult.Unparsed -> result.copy(senderBrand = header.brand)
            is ParseResult.Ambiguous -> result
        }
    }

    private fun parseBody(rawBody: String): ParseResult {
        val body = rawBody.replace(WHITESPACE, " ").trim()
        val hits = templates.mapNotNull { t -> t.regex.find(body)?.let { t to it } }
        if (hits.size > 1) return ParseResult.Ambiguous(hits.map { it.first.spec.id })
        if (hits.size == 1) {
            val (template, match) = hits.single()
            return try {
                ParseResult.Parsed(build(template.spec, template.groupNames.associateWith { match.groups[it]?.value }))
            } catch (e: IllegalArgumentException) {
                ParseResult.Unparsed("INVALID_FIELD")
            } catch (e: java.time.DateTimeException) {
                ParseResult.Unparsed("INVALID_FIELD")
            }
        }
        ignores.firstOrNull { it.second.containsMatchIn(body) }?.let { return ParseResult.Ignored(it.first.reason) }
        return ParseResult.Unparsed()
    }

    private fun build(t: TemplateSpec, g: Map<String, String?>): ParsedMessage {
        val iso = g["date"]?.let { d -> t.dateFormat?.let { parseDate(d, it).toString() } }
        val base = ParsedMessage(
            templateId = t.id,
            eventKind = t.eventKind,
            direction = t.direction,
            method = t.method,
            isTransaction = t.isTransaction,
            amountPaise = parsePaise(requireNotNull(g["amount"]) { "amount missing in ${t.id}" }),
            accountKindHint = t.accountKindHint,
            typeHint = t.typeHint,
            accountSuffix = g["acct"],
            counterpartyAcctSuffix = g["cpAcct"],
            bank = (g["bank"] ?: g["bankTag"])?.trim(),
            counterpartyName = (g["payee"] ?: g["payer"])?.trim(),
            counterpartyVpa = g["cpVpa"],
            counterpartyBankCode = g["cpBankCode"],
            ref = g["ref"],
            umn = g["umn"],
            policyRef = g["policy"],
            description = g["desc"]?.trim(),
            balancePaise = g["balance"]?.let(::parsePaise),
            minDuePaise = g["minDue"]?.let(::parsePaise),
            date = if (t.dateAs == null) iso else null,
            dueDate = if (t.dateAs == "dueDate") iso else null,
            time = g["time"]?.let(::to24h),
        )
        return base.copy(noticeKey = noticeKey(base))
    }

    private fun noticeKey(m: ParsedMessage): String? {
        if (m.isTransaction) return null
        val who = m.counterpartyName ?: m.policyRef ?: m.accountSuffix ?: ""
        return listOf(m.eventKind, m.amountPaise, m.dueDate ?: "", who).joinToString("|")
    }

    companion object {
        private val WHITESPACE = Regex("\\s+")
        private val MACRO = Regex("\\{\\{(\\w+)\\}\\}")
        private val GROUP_NAME = Regex("\\(\\?<([a-zA-Z][a-zA-Z0-9]*)>")
        private val SENDER = Regex("^([A-Z]{2})-([A-Z0-9]+?)(?:-([A-Z]))?$")

        /** "AX-KOTAKB-S" -> prefix AX, brand KOTAKB, kind S. Phone numbers and free text give null. */
        fun parseSender(sender: String?): SenderHeader? {
            val m = SENDER.find((sender ?: "").trim().uppercase()) ?: return null
            return SenderHeader(m.groupValues[1], m.groupValues[2], m.groups[3]?.value)
        }
    }
}
