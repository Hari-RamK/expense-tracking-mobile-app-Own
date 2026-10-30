package app.fintrack.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.fintrack.data.AppSnapshot
import app.fintrack.sms.hasSmsPermission
import java.time.LocalDate

private fun csvList(text: String) = text.split(',', ';', '\n').map { it.trim() }.filter { it.isNotEmpty() }

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit, hint: String? = null) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label)
            if (hint != null) Muted(hint)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
fun SettingsScreen(
    snapshot: AppSnapshot, vm: AppViewModel, onOpenRegular: () -> Unit, requestSms: () -> Unit, requestNotifications: () -> Unit,
    notificationsAllowed: Boolean,
) {
    val context = LocalContext.current
    val s by vm.settings.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val smsOk = hasSmsPermission(context)
    val profile = snapshot.profile

    var names by remember(profile.names) { mutableStateOf(profile.names.joinToString(", ")) }
    var vpas by remember(profile.vpas) { mutableStateOf(profile.vpas.joinToString(", ")) }
    var payees by remember(profile.cardBillPayees) { mutableStateOf(profile.cardBillPayees.joinToString(", ")) }
    var threshold by remember(profile.personThresholdPaise) { mutableStateOf("%d".format(profile.personThresholdPaise / 100)) }
    var limit by remember(s.dailyLimitPaise) { mutableStateOf(if (s.dailyLimitPaise > 0) (s.dailyLimitPaise / 100).toString() else "") }

    var passphraseFor by remember { mutableStateOf<String?>(null) }
    var pendingPassphrase by remember { mutableStateOf<CharArray?>(null) }
    var confirmErase by remember { mutableStateOf(false) }

    val csvLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri -> uri?.let { vm.exportCsv(context, it) } }
    val backupLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val p = pendingPassphrase
        if (uri != null && p != null) vm.backupTo(context, uri, p)
        pendingPassphrase = null
    }
    val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val p = pendingPassphrase
        if (uri != null && p != null) vm.restoreFrom(context, uri, p)
        pendingPassphrase = null
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

        SectionTitle("Permissions")
        Muted("SMS: read only messages from banks, card issuers and apps (sender names with letters). Chats with people are never opened. Notifications: for your summaries.")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
            if (smsOk) OutlinedButton(onClick = {}, enabled = false) { Text("SMS access: on") } else Button(onClick = requestSms) { Text("Allow SMS access") }
            if (notificationsAllowed) OutlinedButton(onClick = {}, enabled = false) { Text("Notifications: on") } else Button(onClick = requestNotifications) { Text("Allow notifications") }
        }

        SectionTitle("About you")
        Muted("Used to recognise money moving between your own accounts. Your name exactly as it appears in UPI messages.")
        OutlinedTextField(value = names, onValueChange = { names = it }, label = { Text("Your name(s), comma separated") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = vpas, onValueChange = { vpas = it }, label = { Text("Your own UPI ids") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = payees, onValueChange = { payees = it }, label = { Text("Card-bill payment apps, e.g. CRED") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = threshold, onValueChange = { threshold = it.filter(Char::isDigit) }, label = { Text("Ask me about payments to people from (₹)") }, modifier = Modifier.fillMaxWidth())
        Muted("Below this, a payment to a person counts as spending. At or above, you decide: lent or spent. Lent is never decided for you.")
        Button(onClick = {
            vm.saveProfile {
                it.copy(
                    names = csvList(names), vpas = csvList(vpas), cardBillPayees = csvList(payees).ifEmpty { listOf("CRED") },
                    personThresholdPaise = (threshold.toLongOrNull() ?: 1000L) * 100,
                )
            }
            vm.say("Saved.")
        }, modifier = Modifier.padding(top = 6.dp)) { Text("Save") }

        SectionTitle("Regular payments")
        OutlinedButton(onClick = onOpenRegular) { Text("Rent, SIP, subscriptions, EMI (${profile.regularItems.size})") }

        SectionTitle("Notifications")
        SwitchRow("Daily summary at night", s.dailyEnabled, { v -> vm.updateSettings { it.copy(dailyEnabled = v) } }, "Spending for the day, sent once.")
        SwitchRow("Weekly summary (Sunday night)", s.weeklyEnabled, { v -> vm.updateSettings { it.copy(weeklyEnabled = v) } })
        SwitchRow("Monthly summary (last day)", s.monthlyEnabled, { v -> vm.updateSettings { it.copy(monthlyEnabled = v) } })
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Time: %02d:%02d".format(s.notifyHour, s.notifyMinute), modifier = Modifier.weight(1f))
            TextButton(onClick = { vm.updateSettings { it.copy(notifyHour = (it.notifyHour + 23) % 24) } }) { Text("hour −") }
            TextButton(onClick = { vm.updateSettings { it.copy(notifyHour = (it.notifyHour + 1) % 24) } }) { Text("hour +") }
            TextButton(onClick = { vm.updateSettings { it.copy(notifyMinute = (it.notifyMinute + 55) % 60) } }) { Text("min −") }
            TextButton(onClick = { vm.updateSettings { it.copy(notifyMinute = (it.notifyMinute + 5) % 60) } }) { Text("min +") }
        }
        OutlinedTextField(value = limit, onValueChange = { limit = it.filter(Char::isDigit) }, label = { Text("Daily spending limit alert (₹, empty = off)") }, modifier = Modifier.fillMaxWidth())
        TextButton(onClick = { vm.updateSettings { it.copy(dailyLimitPaise = (limit.toLongOrNull() ?: 0L) * 100) } }) { Text("Save limit") }
        SwitchRow("Show amounts on the lock screen", s.showAmountsOnLockScreen, { v -> vm.updateSettings { it.copy(showAmountsOnLockScreen = v) } }, "Off by default. When off, the lock screen shows no amounts.")

        SectionTitle("Privacy")
        SwitchRow("Lock the app with fingerprint / screen lock", s.appLockEnabled, { v -> vm.updateSettings { it.copy(appLockEnabled = v) } })
        SwitchRow("Hide from recent apps and block screenshots", s.secureScreen, { v -> vm.updateSettings { it.copy(secureScreen = v) } })
        Muted("Data is stored only on this phone, in an encrypted database. This app has no internet permission, so nothing can be sent anywhere.")

        SectionTitle("Messages")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Read the last ${s.importDays} days", modifier = Modifier.weight(1f))
            TextButton(onClick = { vm.updateSettings { it.copy(importDays = (it.importDays - 30).coerceAtLeast(30)) } }) { Text("−30") }
            TextButton(onClick = { vm.updateSettings { it.copy(importDays = (it.importDays + 30).coerceAtMost(720)) } }) { Text("+30") }
        }
        Button(onClick = { vm.importInbox(context) }, enabled = smsOk && !busy) { Text(if (busy) "Reading…" else "Read SMS now") }
        Muted("Safe to repeat: messages already read are ignored.")
        if (snapshot.overlay.muted.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text("Muted senders", fontWeight = FontWeight.SemiBold)
            snapshot.overlay.muted.forEach { brand ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(brand, modifier = Modifier.weight(1f))
                    TextButton(onClick = { vm.saveOverlay { o -> o.copy(muted = o.muted - brand) } }) { Text("Unmute") }
                }
            }
        }

        SectionTitle("Your data")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { csvLauncher.launch("fintrack-${LocalDate.now()}.csv") }) { Text("Export CSV") }
            OutlinedButton(onClick = { passphraseFor = "backup" }) { Text("Backup") }
            OutlinedButton(onClick = { passphraseFor = "restore" }) { Text("Restore") }
        }
        Muted("CSV is plain text. Backups are encrypted with your passphrase and work on any phone.")
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = { confirmErase = true }) { Text("Erase everything", color = MaterialTheme.colorScheme.error) }

        HorizontalDivider(Modifier.padding(vertical = 16.dp))
        Muted("FinTrack 0.1.0 · GPL-3.0-or-later · nothing is sent to any server: the app has no network access.")
        Spacer(Modifier.height(24.dp))
    }

    passphraseFor?.let { mode ->
        PassphraseDialog(
            title = if (mode == "backup") "Choose a backup passphrase" else "Passphrase of the backup",
            confirmTwice = mode == "backup",
            onDismiss = { passphraseFor = null },
            onConfirm = { p ->
                pendingPassphrase = p
                passphraseFor = null
                if (mode == "backup") backupLauncher.launch("fintrack-backup-${LocalDate.now()}.ftb") else restoreLauncher.launch(arrayOf("*/*"))
            },
        )
    }
    if (confirmErase) {
        AlertDialog(
            onDismissRequest = { confirmErase = false },
            title = { Text("Erase everything?") },
            text = { Text("All transactions, accounts, settings and rules on this phone are deleted. This cannot be undone. Make a backup first if you may want them.") },
            confirmButton = { TextButton(onClick = { vm.eraseAll(); confirmErase = false }) { Text("Erase", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirmErase = false }) { Text("Cancel") } },
        )
    }
}
