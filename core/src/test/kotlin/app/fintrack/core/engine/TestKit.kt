package app.fintrack.core.engine

import app.fintrack.core.model.AccountKind
import app.fintrack.core.model.OwnAccount
import app.fintrack.core.model.OwnerProfile
import app.fintrack.core.model.RawSms
import app.fintrack.core.parser.ParserSpec
import app.fintrack.core.parser.SendersFile
import app.fintrack.core.parser.SmsParser
import app.fintrack.core.parser.TemplateSpec
import app.fintrack.core.parser.TemplatesFile

object TestKit {
    const val HDFC = 1L
    const val KOTAK = 2L
    const val FEDERAL = 3L
    const val CUB = 4L
    const val TMB = 5L
    const val ICICI_CARD = 6L
    const val SBI_CARD = 7L

    /** The owner used throughout the fixtures (all fake). */
    val profile = OwnerProfile(
        names = listOf("ARUN KUMAR S"),
        vpas = listOf("arun.kumar@example"),
        accounts = listOf(
            OwnAccount(HDFC, "HDFC ••7345", "HDFC", AccountKind.BANK, listOf("7345"), listOf("hdfc")),
            OwnAccount(KOTAK, "Kotak ••2126", "KOTAK", AccountKind.BANK, listOf("2126"), listOf("kotak", "kkbk")),
            OwnAccount(FEDERAL, "Federal ••3268", "FEDERAL", AccountKind.BANK, listOf("3268"), listOf("federal")),
            OwnAccount(CUB, "CUB ••1756", "CUB", AccountKind.BANK, listOf("1756"), listOf("cub")),
            OwnAccount(TMB, "TMB ••4160", "TMB", AccountKind.BANK, listOf("4160"), listOf("tmb")),
            OwnAccount(ICICI_CARD, "ICICI CC ••9000", "ICICI", AccountKind.CREDIT_CARD, listOf("9000"), listOf("icici")),
            OwnAccount(SBI_CARD, "SBI CC ••8100", "SBI", AccountKind.CREDIT_CARD, listOf("8100"), listOf("sbi")),
        ),
    )

    const val CLOCK_MILLIS = 1_793_000_000_000L

    fun engine(profile: OwnerProfile = TestKit.profile) =
        LedgerEngine(SmsParser(), profile, clock = { CLOCK_MILLIS })

    private var nextSms = 1L
    fun sms(body: String, sender: String? = null, receivedAt: Long = CLOCK_MILLIS) = RawSms(nextSms++, sender, body, receivedAt)

    /** Tiny synthetic templates so the PRD's salary-split example can be written exactly. */
    fun syntheticParser(): SmsParser {
        fun t(id: String, kind: String, dir: String, regex: String, hint: String? = null) = TemplateSpec(
            id = id, eventKind = kind, direction = dir, method = "TEST", typeHint = hint,
            dateFormat = "dd-MM-yyyy", regex = regex,
        )
        val money = "(?<amount>[\\d,]+(?:\\.\\d{1,2})?)"
        val date = "(?<date>\\d{2}-\\d{2}-\\d{4})"
        return SmsParser(
            ParserSpec(
                TemplatesFile(
                    templates = listOf(
                        t("salary", "SALARY_CREDIT", "CREDIT", "^SALARY Rs $money credited to A/c (?<acct>\\d+) on $date$", "SALARY"),
                        t("out", "UPI_DEBIT", "DEBIT", "^Rs $money debited from A/c (?<acct>\\d+) to A/c (?<cpAcct>\\d+) on $date Ref (?<ref>\\d+)$"),
                        t("in", "UPI_CREDIT", "CREDIT", "^Rs $money credited to A/c (?<acct>\\d+) from A/c (?<cpAcct>\\d+) on $date Ref (?<ref>\\d+)$"),
                        t("buy", "UPI_DEBIT", "DEBIT", "^Rs $money spent from A/c (?<acct>\\d+) at (?<payee>.+?) on $date Ref (?<ref>\\d+)$"),
                        t("buy_noref", "UPI_DEBIT", "DEBIT", "^Rs $money spent from A/c (?<acct>\\d+) at (?<payee>.+?) on $date$"),
                    ),
                ),
                SendersFile(),
            ),
        )
    }

    fun syntheticProfile() = OwnerProfile(
        names = listOf("ARUN KUMAR S"),
        accounts = listOf(
            OwnAccount(1, "A ••1001", "BANKA", AccountKind.BANK, listOf("1001")),
            OwnAccount(2, "B ••1002", "BANKB", AccountKind.BANK, listOf("1002")),
            OwnAccount(3, "C ••1003", "BANKC", AccountKind.BANK, listOf("1003")),
            OwnAccount(4, "D ••1004", "BANKD", AccountKind.BANK, listOf("1004")),
        ),
    )
}
