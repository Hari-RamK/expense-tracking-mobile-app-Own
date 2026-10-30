@file:UseSerializers(LocalDateSerializer::class, LocalTimeSerializer::class)

package app.fintrack.core.model

import app.fintrack.core.parser.ParsedMessage
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import java.time.LocalDate
import java.time.LocalTime

@Serializable
enum class AccountKind { BANK, CREDIT_CARD }

@Serializable
enum class Direction { DEBIT, CREDIT }

/**
 * Which report block a ledger entry belongs to. Only the spend blocks count as spending.
 * TRANSFER, CARD_BILL, LENT, UNDECIDED and UNCLASSIFIED_CREDIT never count as spending or income.
 */
@Serializable
enum class Block {
    CC_SPEND, BANK_SPEND, REGULAR, EMI,
    LENT, UNDECIDED,
    TRANSFER, CARD_BILL,
    INCOME, LENT_REPAYMENT, UNCLASSIFIED_CREDIT,
    EXCLUDED,
}

val SPEND_BLOCKS = setOf(Block.CC_SPEND, Block.BANK_SPEND, Block.REGULAR, Block.EMI)

@Serializable
enum class PersonState { SPEND, LENT, UNDECIDED }

@Serializable
enum class ReasonSource { USER, LEARNED, SMS_MERCHANT, SMS_PAYEE, NONE }

@Serializable
enum class RegularKind { REGULAR, EMI }

@Serializable
data class OwnAccount(
    val id: Long,
    val label: String,
    /** Institution key as used by parser-spec/senders.json (KOTAK, CUB, FEDERAL, ...). */
    val institution: String,
    val kind: AccountKind,
    /** Digit suffixes banks print for this account (e.g. "2126"). */
    val suffixes: List<String>,
    /** Extra words that identify the bank in SMS text (e.g. "kotak", "kkbk"). */
    val aliases: List<String> = emptyList(),
    val active: Boolean = true,
) {
    val displayLabel: String get() = label
}

/** An item the owner defines: rent, SIP, subscription, EMI. Matched to SMS by keyword. */
@Serializable
data class RegularItem(
    val id: Long,
    val name: String,
    val kind: RegularKind,
    val expectedPaise: Long,
    val dueDay: Int? = null,
    /** Case-insensitive words matched against payee, narration and policy number. */
    val keywords: List<String> = emptyList(),
    val emiTotalMonths: Int? = null,
    /** Instalments already paid before the app started tracking. */
    val emiPaidBefore: Int = 0,
)

@Serializable
data class OwnerProfile(
    val names: List<String> = emptyList(),
    val vpas: List<String> = emptyList(),
    val accounts: List<OwnAccount> = emptyList(),
    val regularItems: List<RegularItem> = emptyList(),
    /** Payees that are card-bill payment channels (CRED). */
    val cardBillPayees: List<String> = listOf("CRED"),
    /** Person payments at or above this amount wait for the owner's decision. */
    val personThresholdPaise: Long = 100_000,
    /** Normalised person names the owner marked "usually spend". */
    val usuallySpendPayees: Set<String> = emptySet(),
    /** Normalised payee -> reason the owner chose before. */
    val payeeReasons: Map<String, String> = emptyMap(),
)

data class RawSms(
    val id: Long,
    val sender: String?,
    val body: String,
    /** Epoch millis the phone received it. Never used as the transaction time. */
    val receivedAtMillis: Long,
)

@Serializable
data class LedgerEntry(
    val id: Long,
    val accountId: Long?,
    /** Shown when the account is not (yet) one of the owner's, e.g. "Kotak ••2126". */
    val accountHint: String?,
    val direction: Direction,
    val amountPaise: Long,
    val date: LocalDate,
    val time: LocalTime?,
    val block: Block,
    val reason: String?,
    val reasonSource: ReasonSource,
    val counterpartyName: String?,
    val counterpartyVpa: String?,
    val counterpartyAcctSuffix: String?,
    val ref: String?,
    val balancePaise: Long?,
    val method: String,
    val senderBrand: String?,
    val transferGroupId: Long? = null,
    val personState: PersonState? = null,
    /** For CARD_BILL: the card this payment settles. */
    val settlesAccountId: Long? = null,
    /** For LENT_REPAYMENT: the lent entry it repays. */
    val settlesEntryId: Long? = null,
    val regularItemId: Long? = null,
    val isSalary: Boolean = false,
    val needsReview: Boolean = false,
    /** Machine-readable explanation, e.g. LEG_NOT_SEEN, TRANSFER_CANDIDATE:12, LENT_REPAYMENT:7, ASSIGN_ACCOUNT. */
    val hint: String? = null,
    val userEdited: Boolean = false,
    val manual: Boolean = false,
    val parsed: ParsedMessage? = null,
    val sourceMessageIds: List<Long> = emptyList(),
)

@Serializable
data class TransferGroup(val id: Long, val confidence: Int, val auto: Boolean)

/** A non-transaction message: card statement due, premium due, auto-debit advance notice. Repeats collapse by [key]. */
@Serializable
data class Notice(
    val key: String,
    val eventKind: String,
    val amountPaise: Long,
    val minDuePaise: Long?,
    val dueDate: LocalDate?,
    val payee: String?,
    val policyRef: String?,
    val accountId: Long?,
    val accountHint: String?,
    val lastSeenMillis: Long,
)

@Serializable
data class AuditRecord(val entryId: Long?, val action: String, val detail: String, val atMillis: Long)

/** Everything the engine needs to resume, and what a backup contains. */
@Serializable
data class LedgerSnapshot(
    val profile: OwnerProfile,
    val entries: List<LedgerEntry>,
    val notices: List<Notice>,
    val groups: List<TransferGroup>,
    val seenMessageHashes: Set<String>,
)
