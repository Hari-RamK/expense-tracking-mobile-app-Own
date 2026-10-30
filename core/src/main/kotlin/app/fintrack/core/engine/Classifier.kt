package app.fintrack.core.engine

import app.fintrack.core.model.AccountKind
import app.fintrack.core.model.Block
import app.fintrack.core.model.Direction
import app.fintrack.core.model.OwnAccount
import app.fintrack.core.model.OwnerProfile
import app.fintrack.core.model.PersonState
import app.fintrack.core.model.ReasonSource
import app.fintrack.core.model.RegularItem
import app.fintrack.core.model.RegularKind
import app.fintrack.core.parser.ParsedMessage

private val NON_ALNUM = Regex("[^A-Z0-9 ]")
private val SPACES = Regex("\\s+")

/** Upper-case, punctuation-free form used to compare names and payees. */
fun normalizeName(text: String?): String =
    (text ?: "").uppercase().replace(NON_ALNUM, " ").replace(SPACES, " ").trim()

private val BUSINESS_WORDS = setOf(
    "STORE", "STORES", "MART", "SHOP", "TRADERS", "TRADING", "ENTERPRISES", "FOODS", "RESTAURANT", "HOTEL", "CAFE",
    "PHARMACY", "MEDICAL", "MEDICALS", "SUPERMARKET", "BAKERY", "SERVICES", "SOLUTIONS", "PVT", "LTD", "LIMITED",
    "TECHNOLOGIES", "BANK", "PAYMENTS", "RETAIL", "MOBILES", "ELECTRONICS", "FUEL", "PETROL", "TRAVELS", "AGENCIES",
    "CENTRE", "CENTER", "CLINIC", "HOSPITAL", "COLLEGE", "SCHOOL", "BOARD", "DEPARTMENT", "INDUSTRIES", "STUDIO",
)

/**
 * Heuristic: a person has 2-4 purely alphabetic words, none of them business words, and no merchant-style UPI id.
 * It only chooses a default; the owner can always change the result.
 */
fun looksLikePerson(name: String?, vpa: String? = null): Boolean {
    val words = normalizeName(name).split(" ").filter { it.isNotEmpty() }
    if (words.size !in 2..4) return false
    if (words.any { w -> !w.all(Char::isLetter) || w in BUSINESS_WORDS }) return false
    val v = vpa?.lowercase().orEmpty()
    return !(v.contains("qr") || v.contains("merchant") || v.contains("paytm-"))
}

enum class OwnKind { ACCOUNT, VPA, NAME }

data class AccountResolution(val account: OwnAccount?, val ambiguous: Boolean, val hint: String?)

private fun OwnAccount.suffixMatches(digits: String): Boolean =
    suffixes.any { it.endsWith(digits) || digits.endsWith(it) }

/** True/false when the message names an institution, null when it does not say. */
private fun OwnAccount.institutionMatches(key: String?, bankText: String?): Boolean? {
    val words = normalizeName(bankText).split(" ").filter { it.isNotEmpty() }
    if (key == null && words.isEmpty()) return null
    if (key != null && key.equals(institution, ignoreCase = true)) return true
    val tokens = (aliases + institution).map { normalizeName(it) }.filter { it.isNotEmpty() }
    return words.any { it in tokens }
}

fun resolveAccount(parsed: ParsedMessage, profile: OwnerProfile): AccountResolution {
    val suffix = parsed.accountSuffix
    val label = listOfNotNull(parsed.institution ?: parsed.bank, suffix?.let { "••$it" }).joinToString(" ").ifBlank { null }
    if (suffix == null) return AccountResolution(null, false, label)
    // A short suffix (some banks print only 2 digits) is too weak to assign an account by itself.
    if (suffix.length < 4 && parsed.institution == null && parsed.bank.isNullOrBlank()) {
        return AccountResolution(null, false, label)
    }
    var candidates = profile.accounts.filter { it.active && it.suffixMatches(suffix) }
    if (parsed.accountKindHint == "CREDIT_CARD") candidates = candidates.filter { it.kind == AccountKind.CREDIT_CARD }
    val narrowed = candidates.filter { it.institutionMatches(parsed.institution, parsed.bank) != false }
    return when {
        narrowed.size == 1 -> AccountResolution(narrowed.single(), false, label)
        narrowed.size > 1 -> AccountResolution(null, true, label)
        else -> AccountResolution(null, false, label)
    }
}

/** Is the other side of this message one of the owner's own accounts, UPI ids or names? */
fun ownCounterparty(parsed: ParsedMessage, profile: OwnerProfile, ownAccountId: Long?): OwnKind? {
    parsed.counterpartyAcctSuffix?.let { cp ->
        if (cp.length >= 4 && profile.accounts.any { it.id != ownAccountId && it.active && it.suffixes.any { s -> s == cp || s.endsWith(cp) } }) {
            return OwnKind.ACCOUNT
        }
    }
    parsed.counterpartyVpa?.let { v ->
        if (profile.vpas.any { it.equals(v, ignoreCase = true) }) return OwnKind.VPA
    }
    val name = normalizeName(parsed.counterpartyName)
    if (name.isNotEmpty() && profile.names.any { normalizeName(it) == name }) return OwnKind.NAME
    return null
}

fun matchRegularItem(parsed: ParsedMessage, profile: OwnerProfile): RegularItem? {
    val haystack = listOfNotNull(parsed.counterpartyName, parsed.description, parsed.policyRef, parsed.umn)
        .joinToString(" ").uppercase()
    if (haystack.isBlank()) return null
    return profile.regularItems.firstOrNull { item ->
        item.keywords.any { k -> k.isNotBlank() && haystack.contains(k.trim().uppercase()) }
    }
}

fun deriveReason(parsed: ParsedMessage, profile: OwnerProfile, personPayment: Boolean): Pair<String?, ReasonSource> {
    val key = normalizeName(parsed.counterpartyName)
    profile.payeeReasons[key]?.let { return it to ReasonSource.LEARNED }
    parsed.counterpartyName?.takeIf { it.isNotBlank() }?.let {
        return it to if (personPayment) ReasonSource.SMS_PAYEE else ReasonSource.SMS_MERCHANT
    }
    parsed.description?.takeIf { it.isNotBlank() }?.let { return it to ReasonSource.SMS_MERCHANT }
    parsed.counterpartyVpa?.let { return it to ReasonSource.SMS_PAYEE }
    return null to ReasonSource.NONE
}

data class Classification(
    val block: Block,
    val reason: String?,
    val reasonSource: ReasonSource,
    val personState: PersonState? = null,
    val regularItem: RegularItem? = null,
    val isSalary: Boolean = false,
    val needsReview: Boolean = false,
    val hint: String? = null,
)

fun classify(parsed: ParsedMessage, resolvedAccount: OwnAccount?, profile: OwnerProfile): Classification {
    val direction = Direction.valueOf(requireNotNull(parsed.direction) { "transaction without direction" })
    val own = ownCounterparty(parsed, profile, resolvedAccount?.id)
    val isCard = resolvedAccount?.kind == AccountKind.CREDIT_CARD || parsed.accountKindHint == "CREDIT_CARD"

    if (direction == Direction.CREDIT) {
        if (parsed.typeHint == "SALARY") {
            return Classification(Block.INCOME, "Salary", ReasonSource.SMS_MERCHANT, isSalary = true)
        }
        if (own != null) {
            val (reason, source) = deriveReason(parsed, profile, personPayment = true)
            return Classification(Block.TRANSFER, reason, source, hint = "LEG_NOT_SEEN")
        }
        if (parsed.eventKind == "BANK_CREDIT") {
            val (reason, source) = deriveReason(parsed, profile, personPayment = false)
            return Classification(Block.INCOME, reason ?: "Other income", source, hint = "OTHER_INCOME")
        }
        val (reason, source) = deriveReason(parsed, profile, personPayment = true)
        return Classification(Block.UNCLASSIFIED_CREDIT, reason, source, needsReview = true, hint = "UNCLASSIFIED_CREDIT")
    }

    matchRegularItem(parsed, profile)?.let { item ->
        return Classification(
            if (item.kind == RegularKind.EMI) Block.EMI else Block.REGULAR,
            item.name, ReasonSource.USER, regularItem = item,
        )
    }
    if (isCard) {
        val (reason, source) = deriveReason(parsed, profile, personPayment = false)
        return Classification(Block.CC_SPEND, reason, source)
    }
    if (own != null) {
        val (reason, source) = deriveReason(parsed, profile, personPayment = true)
        return Classification(Block.TRANSFER, reason, source, hint = "LEG_NOT_SEEN")
    }
    val payee = normalizeName(parsed.counterpartyName)
    if (payee.isNotEmpty() && profile.cardBillPayees.any { normalizeName(it) == payee }) {
        val (reason, source) = deriveReason(parsed, profile, personPayment = false)
        return Classification(Block.CARD_BILL, reason, source, needsReview = true, hint = "ASSIGN_CARD")
    }
    if (looksLikePerson(parsed.counterpartyName, parsed.counterpartyVpa)) {
        val (reason, source) = deriveReason(parsed, profile, personPayment = true)
        // The amount is applied by applyPersonRule(); PERSON marks "decide spend vs undecided".
        return Classification(Block.BANK_SPEND, reason, source, personState = PersonState.SPEND, hint = "PERSON")
    }
    val (reason, source) = deriveReason(parsed, profile, personPayment = false)
    return Classification(Block.BANK_SPEND, reason, source)
}

/** Applies the person-payment threshold rule. Lent is never chosen automatically. */
fun applyPersonRule(c: Classification, amountPaise: Long, payee: String, profile: OwnerProfile): Classification {
    if (c.hint != "PERSON") return c
    val spend = payee in profile.usuallySpendPayees || amountPaise < profile.personThresholdPaise
    return if (spend) {
        c.copy(block = Block.BANK_SPEND, personState = PersonState.SPEND, hint = null)
    } else {
        c.copy(block = Block.UNDECIDED, personState = PersonState.UNDECIDED, needsReview = true, hint = "DECIDE_LENT_OR_SPEND")
    }
}
