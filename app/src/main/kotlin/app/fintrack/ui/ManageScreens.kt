@file:OptIn(ExperimentalLayoutApi::class)

package app.fintrack.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.fintrack.core.model.AccountKind
import app.fintrack.core.model.OwnAccount
import app.fintrack.core.model.RegularItem
import app.fintrack.core.model.RegularKind
import app.fintrack.core.report.formatInr
import app.fintrack.data.AppSnapshot

private fun csv(text: String) = text.split(',', ';', '\n').map { it.trim() }.filter { it.isNotEmpty() }

@Composable
fun AccountsScreen(snapshot: AppSnapshot, vm: AppViewModel) {
    var editing by remember { mutableStateOf<OwnAccount?>(null) }
    var adding by remember { mutableStateOf(false) }
    val today = vm.today()
    val report = remember(snapshot.revision) { vm.report(snapshot, app.fintrack.core.report.Period.month(today)) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text("Accounts", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Button(onClick = { adding = true }) { Text("+ Add") }
        }
        Muted("Your bank accounts and cards. The app never asks for a bank login, PIN, CVV or OTP.")
        Spacer(Modifier.height(8.dp))
        if (snapshot.profile.accounts.isEmpty()) Callout("Add each account you receive bank SMS for. The last 4 digits as they appear in the SMS (for example 2126) tell the app which account a message is about.", Palette.AmberTint, Palette.Amber)
        snapshot.profile.accounts.forEach { a ->
            val last = snapshot.entries.filter { it.accountId == a.id }.maxOfOrNull { it.date }
            val summary = report.accountSummaries.firstOrNull { it.accountId == a.id }
            AmountRow(
                title = a.label + if (!a.active) " (closed)" else "",
                amount = summary?.let { (if (it.netChangePaise >= 0) "+" else "") + formatInr(it.netChangePaise) } ?: "",
                subtitle = (if (a.kind == AccountKind.CREDIT_CARD) "Credit card" else "Bank") + " · " + a.institution + " · ••" + a.suffixes.joinToString("/") +
                    (last?.let { " · last message $it" } ?: " · no messages yet"),
                onClick = { editing = a },
            )
        }
    }
    if (adding) AccountDialog(null, snapshot, vm) { adding = false }
    editing?.let { AccountDialog(it, snapshot, vm) { editing = null } }
}

@Composable
private fun AccountDialog(existing: OwnAccount?, snapshot: AppSnapshot, vm: AppViewModel, onDismiss: () -> Unit) {
    var label by remember { mutableStateOf(existing?.label ?: "") }
    var institution by remember { mutableStateOf(existing?.institution ?: "") }
    var kind by remember { mutableStateOf(existing?.kind ?: AccountKind.BANK) }
    var suffixes by remember { mutableStateOf(existing?.suffixes?.joinToString(", ") ?: "") }
    var aliases by remember { mutableStateOf(existing?.aliases?.joinToString(", ") ?: "") }
    var active by remember { mutableStateOf(existing?.active ?: true) }
    val known = remember { listOf("KOTAK", "CUB", "FEDERAL", "TMB", "HDFC", "ICICI", "SBI", "AXIS", "AIRTEL_PAYMENTS_BANK") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "Add account" else "Edit account") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = label, onValueChange = { label = it }, label = { Text("Name, e.g. Kotak ••2126") }, singleLine = true)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = kind == AccountKind.BANK, onClick = { kind = AccountKind.BANK }, label = { Text("Bank account") })
                    FilterChip(selected = kind == AccountKind.CREDIT_CARD, onClick = { kind = AccountKind.CREDIT_CARD }, label = { Text("Credit card") })
                }
                OutlinedTextField(value = institution, onValueChange = { institution = it.uppercase().replace(' ', '_') }, label = { Text("Bank key") }, singleLine = true)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    known.forEach { k -> FilterChip(selected = institution == k, onClick = { institution = k }, label = { Text(k) }) }
                }
                OutlinedTextField(value = suffixes, onValueChange = { suffixes = it }, label = { Text("Digits shown in SMS, e.g. 2126") }, singleLine = true)
                OutlinedTextField(value = aliases, onValueChange = { aliases = it }, label = { Text("Other names in SMS (optional)") }, singleLine = true)
                if (existing != null) {
                    FilterChip(selected = !active, onClick = { active = !active }, label = { Text(if (active) "Mark closed / inactive" else "Closed (tap to reopen)") })
                }
            }
        },
        confirmButton = {
            TextButton(enabled = label.isNotBlank() && institution.isNotBlank() && csv(suffixes).isNotEmpty(), onClick = {
                val id = existing?.id ?: ((snapshot.profile.accounts.maxOfOrNull { it.id } ?: 0L) + 1)
                val account = OwnAccount(id, label.trim(), institution.trim(), kind, csv(suffixes), csv(aliases), active)
                vm.saveProfile { p -> p.copy(accounts = p.accounts.filter { it.id != id } + account) }
                onDismiss()
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun RegularItemsScreen(snapshot: AppSnapshot, vm: AppViewModel) {
    var editing by remember { mutableStateOf<RegularItem?>(null) }
    var adding by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text("Regular & EMI", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Button(onClick = { adding = true }) { Text("+ Add") }
        }
        Muted("Things you pay every month: rent, SIP, subscriptions, EMIs. A keyword (a payee name or policy number) lets a matching SMS fill in the real amount and date.")
        Spacer(Modifier.height(8.dp))
        snapshot.profile.regularItems.forEach { item ->
            AmountRow(
                item.name, formatInr(item.expectedPaise, true),
                subtitle = (if (item.kind == RegularKind.EMI) "EMI" else "Regular") + (item.dueDay?.let { " · day $it" } ?: "") +
                    (if (item.keywords.isNotEmpty()) " · matches: ${item.keywords.joinToString()}" else " · no keyword (will not match SMS)"),
                onClick = { editing = item },
            )
        }
        if (snapshot.profile.regularItems.isEmpty()) Muted("None yet.")
    }
    if (adding) RegularDialog(null, snapshot, vm) { adding = false }
    editing?.let { RegularDialog(it, snapshot, vm) { editing = null } }
}

@Composable
private fun RegularDialog(existing: RegularItem?, snapshot: AppSnapshot, vm: AppViewModel, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var kind by remember { mutableStateOf(existing?.kind ?: RegularKind.REGULAR) }
    var amount by remember { mutableStateOf(existing?.let { "%d.%02d".format(it.expectedPaise / 100, it.expectedPaise % 100) } ?: "") }
    var day by remember { mutableStateOf(existing?.dueDay?.toString() ?: "") }
    var keywords by remember { mutableStateOf(existing?.keywords?.joinToString(", ") ?: "") }
    var total by remember { mutableStateOf(existing?.emiTotalMonths?.toString() ?: "") }
    var before by remember { mutableStateOf(existing?.emiPaidBefore?.toString() ?: "0") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "Add item" else "Edit item") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name, e.g. Rent") }, singleLine = true)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = kind == RegularKind.REGULAR, onClick = { kind = RegularKind.REGULAR }, label = { Text("Regular") })
                    FilterChip(selected = kind == RegularKind.EMI, onClick = { kind = RegularKind.EMI }, label = { Text("EMI") })
                }
                OutlinedTextField(value = amount, onValueChange = { amount = it }, label = { Text("Expected amount (₹)") }, singleLine = true)
                OutlinedTextField(value = day, onValueChange = { day = it.filter(Char::isDigit).take(2) }, label = { Text("Usual day of month (optional)") }, singleLine = true)
                OutlinedTextField(value = keywords, onValueChange = { keywords = it }, label = { Text("Match words in SMS, comma separated") }, singleLine = true)
                if (kind == RegularKind.EMI) {
                    OutlinedTextField(value = total, onValueChange = { total = it.filter(Char::isDigit) }, label = { Text("Total months") }, singleLine = true)
                    OutlinedTextField(value = before, onValueChange = { before = it.filter(Char::isDigit) }, label = { Text("Instalments already paid") }, singleLine = true)
                }
            }
        },
        confirmButton = {
            val paise = parseRupees(amount)
            TextButton(enabled = name.isNotBlank() && paise != null, onClick = {
                val id = existing?.id ?: ((snapshot.profile.regularItems.maxOfOrNull { it.id } ?: 0L) + 1)
                val item = RegularItem(
                    id, name.trim(), kind, paise!!, day.toIntOrNull()?.coerceIn(1, 31), csv(keywords),
                    if (kind == RegularKind.EMI) total.toIntOrNull() else null, before.toIntOrNull() ?: 0,
                )
                vm.saveProfile { p -> p.copy(regularItems = p.regularItems.filter { it.id != id } + item) }
                onDismiss()
            }) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (existing != null) TextButton(onClick = { vm.saveProfile { p -> p.copy(regularItems = p.regularItems.filter { it.id != existing.id }) }; onDismiss() }) { Text("Delete") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

@Composable
fun PassphraseDialog(title: String, confirmTwice: Boolean, onDismiss: () -> Unit, onConfirm: (CharArray) -> Unit) {
    var one by remember { mutableStateOf("") }
    var two by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Muted("The backup is encrypted with this passphrase. If you forget it, the backup cannot be opened. It is not stored anywhere.")
                OutlinedTextField(value = one, onValueChange = { one = it }, label = { Text("Passphrase") }, singleLine = true,
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation())
                if (confirmTwice) OutlinedTextField(value = two, onValueChange = { two = it }, label = { Text("Repeat passphrase") }, singleLine = true,
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation())
            }
        },
        confirmButton = {
            TextButton(enabled = one.length >= 8 && (!confirmTwice || one == two), onClick = { onConfirm(one.toCharArray()) }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
