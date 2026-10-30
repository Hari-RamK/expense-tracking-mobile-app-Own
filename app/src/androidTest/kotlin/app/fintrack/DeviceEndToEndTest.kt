package app.fintrack

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.fintrack.core.engine.IngestStatus
import app.fintrack.core.model.AccountKind
import app.fintrack.core.model.Block
import app.fintrack.core.model.OwnAccount
import app.fintrack.core.model.OwnerProfile
import app.fintrack.core.model.RegularItem
import app.fintrack.core.model.RegularKind
import app.fintrack.core.report.Period
import app.fintrack.core.report.ReportBuilder
import app.fintrack.data.Backup
import app.fintrack.data.Database
import app.fintrack.data.KeyVault
import app.fintrack.data.Repository
import app.fintrack.data.Settings
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDate

/**
 * Runs on a real Android runtime: the Keystore-wrapped key, the SQLCipher database, the engine, restart persistence
 * and encrypted backup. All data is fake. It deliberately leaves its data in place so the screens can be inspected.
 */
@RunWith(AndroidJUnit4::class)
class DeviceEndToEndTest {
    private lateinit var context: Context

    private val profile = OwnerProfile(
        names = listOf("ARUN KUMAR S"),
        vpas = listOf("arun.kumar@example"),
        accounts = listOf(
            OwnAccount(1, "HDFC ••7345", "HDFC", AccountKind.BANK, listOf("7345"), listOf("hdfc")),
            OwnAccount(2, "Kotak ••2126", "KOTAK", AccountKind.BANK, listOf("2126"), listOf("kotak", "kkbk")),
            OwnAccount(3, "Federal ••3268", "FEDERAL", AccountKind.BANK, listOf("3268"), listOf("federal")),
            OwnAccount(4, "CUB ••1756", "CUB", AccountKind.BANK, listOf("1756"), listOf("cub")),
            OwnAccount(5, "TMB ••4160", "TMB", AccountKind.BANK, listOf("4160"), listOf("tmb")),
            OwnAccount(6, "ICICI CC ••9000", "ICICI", AccountKind.CREDIT_CARD, listOf("9000"), listOf("icici")),
        ),
        regularItems = listOf(
            RegularItem(1, "Term insurance", RegularKind.REGULAR, 199_900, keywords = listOf("K0000001")),
            RegularItem(2, "Rent", RegularKind.REGULAR, 1_400_000, dueDay = 3, keywords = listOf("LANDLORD")),
        ),
    )

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
    }

    private fun openRepository(): Repository {
        val settings = Settings(context)
        return Repository(Database(context, KeyVault(context).databasePassphrase()), settings)
    }

    private val today: LocalDate get() = LocalDate.now()

    private fun d(day: Int) = "%02d-%02d-%02d".format(day, today.monthValue, today.year % 100)

    @Test
    fun fullJourney() = runBlocking {
        context.deleteDatabase("fintrack.db")
        var repo = openRepository()
        repo.load()
        repo.updateProfile(profile)

        val month = today.withDayOfMonth(1)
        val ts = System.currentTimeMillis()
        // Salary, own-account transfer pair (same UPI ref, two banks), a card spend, CRED payment, a person payment.
        val results = listOf(
            repo.ingestSms("VM-HDFCBK-S", "Salary Credited!\nINR 45,333.00 to HDFC Bank A/c XX7345\nBal: INR 45,483.86", ts),
            repo.ingestSms("JD-KOTAKB-S", "Received Rs.5498.00 in your Kotak Bank AC 2126 from ARUN KUMAR S on ${d(1)}.UPI Ref:219279920003", ts),
            repo.ingestSms(
                "JM-TMBANK-S",
                "Your A/c No.XXXX4160 is debited with Rs.5,498.00 on ${d(1).substring(0, 6)}${today.year} 10:56 AM and KKBK A/c linked to arun.kumar@example is credited (UPI Ref No.219279920003).Current AVBL bal is Rs.0.50 - TMB",
                ts,
            ),
            repo.ingestSms("AD-ICICIB-S", "ICICI Bank Credit Card XX9000 debited for INR 290.00 on 02-Oct-26 for UPI-223643760005-SWASTIK. To dispute call 1", ts),
            repo.ingestSms("AX-KOTAKB-S", "Sent Rs.9261.34 from Kotak Bank A/c X2126 to CRED on ${d(1)}. UPI Ref 630382500001. Not done by you?", ts),
            repo.ingestSms("VM-FEDBNK-T", "Debited Rs 135.00 from a/c X3268 on 29Oct26 16:44 via UPI to RAVI TEJA M. Ref 630255820006.Bal Rs 15720.22. Not you?Call 1 -Federal Bank", ts),
            repo.ingestSms("VA-MONVEW-S", "Your next EMI is due on 05 November", ts),
            repo.ingestSms("AX-XXXXXX-S", "482913 is your OTP for login. Do not share it.", ts),
        )
        assertEquals(IngestStatus.ADDED, results[0])
        assertEquals(IngestStatus.ADDED, results[1])
        assertEquals("matching reference merges into one transfer", IngestStatus.ADDED, results[2])
        assertEquals(IngestStatus.IGNORED, results[7])

        // Reading the same message again changes nothing.
        assertEquals(IngestStatus.DUPLICATE_MESSAGE, repo.ingestSms("JD-KOTAKB-S", "Received Rs.5498.00 in your Kotak Bank AC 2126 from ARUN KUMAR S on ${d(1)}.UPI Ref:219279920003", ts))

        val snap = repo.state.value
        val transfer = snap.entries.filter { it.block == Block.TRANSFER }
        assertEquals(2, transfer.size)
        assertTrue("both legs linked into one group", transfer.all { it.transferGroupId != null } && transfer.map { it.transferGroupId }.distinct().size == 1)
        val report = ReportBuilder(snap.profile, snap.entries, snap.notices).build(Period.month(month), today)
        assertEquals(4_533_300, report.totals.incomePaise)
        assertEquals("transfer counted once", 549_800, report.transfersPaise)

        // Restart: a brand-new Repository over the same encrypted file sees everything.
        val countBefore = repo.state.value.entries.size
        repo = openRepository()
        repo.load()
        assertEquals(countBefore, repo.state.value.entries.size)
        assertEquals(profile.accounts.size, repo.state.value.profile.accounts.size)
        assertEquals("seen messages are remembered across restarts", IngestStatus.DUPLICATE_MESSAGE,
            repo.ingestSms("VM-FEDBNK-T", "Debited Rs 135.00 from a/c X3268 on 29Oct26 16:44 via UPI to RAVI TEJA M. Ref 630255820006.Bal Rs 15720.22. Not you?Call 1 -Federal Bank", ts))

        // The database file is not plain SQLite.
        val dbFile: File = context.getDatabasePath("fintrack.db")
        val header = dbFile.inputStream().use { it.readNBytes(16) }
        assertFalse("database must be encrypted", String(header, Charsets.ISO_8859_1).startsWith("SQLite format 3"))

        // Encrypted backup round-trips and rejects a wrong passphrase.
        val (snapshot, overlay) = repo.snapshotForBackup()
        val bytes = Backup.encrypt(snapshot, overlay, "correct horse battery".toCharArray())
        val (restored, _) = Backup.decrypt(bytes, "correct horse battery".toCharArray())
        assertEquals(snapshot.entries.size, restored.entries.size)
        var rejected = false
        try { Backup.decrypt(bytes, "wrong passphrase".toCharArray()) } catch (e: app.fintrack.data.WrongPassphraseException) { rejected = true }
        assertTrue(rejected)
    }
}
