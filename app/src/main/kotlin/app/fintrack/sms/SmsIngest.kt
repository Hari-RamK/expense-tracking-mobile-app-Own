package app.fintrack.sms

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Telephony
import androidx.core.content.ContextCompat
import app.fintrack.FinTrackApp
import app.fintrack.core.engine.IngestStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Privacy rule: only messages from alphanumeric sender headers (banks, card issuers, UPI apps) are read.
 * Messages from phone numbers, i.e. conversations with people, are never opened or stored.
 */
fun isInstitutionSender(address: String?): Boolean = !address.isNullOrBlank() && address.any { it.isLetter() }

fun hasSmsPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED

/** Receives new SMS while the app is closed and feeds them to the ledger. */
class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        val grouped = parts.groupBy { it.originatingAddress }
        val pending = goAsync()
        val app = context.applicationContext as FinTrackApp
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                var changed = false
                for ((address, pieces) in grouped) {
                    if (!isInstitutionSender(address)) continue
                    val body = pieces.joinToString("") { it.messageBody.orEmpty() }
                    val status = app.container.repository.ingestSms(address, body, pieces.first().timestampMillis)
                    if (status == IngestStatus.ADDED || status == IngestStatus.MERGED || status == IngestStatus.NOTICE) changed = true
                }
                if (changed) app.container.notifier.afterNewData()
            } finally {
                pending.finish()
            }
        }
    }
}

data class ImportResult(val read: Int, val added: Int, val merged: Int, val notices: Int, val unparsed: Int, val ignored: Int, val duplicates: Int)

/** Reads the SMS inbox (READ_SMS) for institution senders and feeds the ledger. Idempotent: re-importing changes nothing. */
object InboxImporter {
    suspend fun import(context: Context, sinceMillis: Long, onProgress: (Int) -> Unit = {}): ImportResult {
        val app = context.applicationContext as FinTrackApp
        val messages = mutableListOf<Triple<String?, String, Long>>()
        val uri = Uri.parse("content://sms/inbox")
        context.contentResolver.query(
            uri, arrayOf("address", "body", "date"), "date >= ?", arrayOf(sinceMillis.toString()), "date ASC",
        )?.use { c ->
            while (c.moveToNext()) {
                val address = c.getString(0)
                if (!isInstitutionSender(address)) continue
                messages += Triple(address, c.getString(1).orEmpty(), c.getLong(2))
            }
        }
        val counts = app.container.repository.ingestMany(messages, onProgress)
        app.container.notifier.afterNewData()
        fun n(s: IngestStatus) = counts[s] ?: 0
        return ImportResult(
            read = messages.size, added = n(IngestStatus.ADDED), merged = n(IngestStatus.MERGED), notices = n(IngestStatus.NOTICE),
            unparsed = n(IngestStatus.UNPARSED), ignored = n(IngestStatus.IGNORED), duplicates = n(IngestStatus.DUPLICATE_MESSAGE),
        )
    }
}
