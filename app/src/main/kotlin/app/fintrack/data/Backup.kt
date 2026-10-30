package app.fintrack.data

import app.fintrack.core.model.Direction
import app.fintrack.core.model.LedgerEntry
import app.fintrack.core.model.LedgerSnapshot
import app.fintrack.core.model.OwnerProfile
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

@Serializable
private data class BackupFile(val version: Int, val snapshot: LedgerSnapshot, val overlay: ParserOverlay)

class WrongPassphraseException : Exception("Wrong passphrase or damaged backup")

/**
 * Backup file = "FTB1" + salt(16) + iv(12) + AES-256-GCM(JSON). The key comes from the user's passphrase with
 * PBKDF2-HMAC-SHA256 (310,000 iterations), so a backup opens on any phone and is useless without the passphrase.
 */
object Backup {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val MAGIC = "FTB1".toByteArray()
    private const val ITERATIONS = 310_000

    fun encrypt(snapshot: LedgerSnapshot, overlay: ParserOverlay, passphrase: CharArray): ByteArray {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val plain = json.encodeToString(BackupFile(1, snapshot, overlay)).toByteArray()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, key(passphrase, salt), GCMParameterSpec(128, iv))
        }
        return MAGIC + salt + iv + cipher.doFinal(plain)
    }

    fun decrypt(bytes: ByteArray, passphrase: CharArray): Pair<LedgerSnapshot, ParserOverlay> {
        if (bytes.size < 4 + 16 + 12 + 16 || !bytes.copyOfRange(0, 4).contentEquals(MAGIC)) throw WrongPassphraseException()
        val salt = bytes.copyOfRange(4, 20)
        val iv = bytes.copyOfRange(20, 32)
        val plain = try {
            Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, key(passphrase, salt), GCMParameterSpec(128, iv))
            }.doFinal(bytes, 32, bytes.size - 32)
        } catch (e: Exception) {
            throw WrongPassphraseException()
        }
        val file = json.decodeFromString<BackupFile>(plain.toString(Charsets.UTF_8))
        return file.snapshot to file.overlay
    }

    private fun key(passphrase: CharArray, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(passphrase, salt, ITERATIONS, 256)
        val raw = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        return SecretKeySpec(raw, "AES")
    }
}

/** CSV of the ledger. Plain text: the UI warns before it is saved anywhere. Amounts are exact rupee strings. */
object CsvExport {
    fun rupees(paise: Long): String = "%d.%02d".format(paise / 100, paise % 100)

    private fun cell(s: String?): String {
        val v = s ?: ""
        return if (v.any { it == ',' || it == '"' || it == '\n' }) "\"" + v.replace("\"", "\"\"") + "\"" else v
    }

    fun build(profile: OwnerProfile, entries: List<LedgerEntry>): String = buildString {
        append("id,date,time,account,direction,amount_inr,block,reason,counterparty,upi_ref,needs_review,note\n")
        for (e in entries.sortedWith(compareBy({ it.date }, { it.id }))) {
            val account = profile.accounts.firstOrNull { it.id == e.accountId }?.label ?: e.accountHint
            append(
                listOf(
                    e.id.toString(), e.date.toString(), e.time?.toString(), account,
                    if (e.direction == Direction.DEBIT) "DEBIT" else "CREDIT", rupees(e.amountPaise),
                    e.block.name, e.reason, e.counterpartyName ?: e.counterpartyVpa, e.ref,
                    if (e.needsReview) "yes" else "", e.hint,
                ).joinToString(",") { cell(it) },
            ).append('\n')
        }
    }
}
