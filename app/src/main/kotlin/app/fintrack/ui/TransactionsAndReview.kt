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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.fintrack.core.model.Block
import app.fintrack.core.model.LedgerEntry
import app.fintrack.core.model.SPEND_BLOCKS
import app.fintrack.core.parser.SmsParser
import app.fintrack.data.AppSnapshot
import java.time.LocalDate

private enum class Filter(val label: String) {
    ALL("All"), SPEND("Spending"), INCOME("Income"), TRANSFERS("Transfers"), PEOPLE("People"), CARDS("Card bills"), REVIEW("Needs review"),
}

private fun Filter.matches(e: LedgerEntry): Boolean = when (this) {
    Filter.ALL -> true
    Filter.SPEND -> e.block in SPEND_BLOCKS
    Filter.INCOME -> e.block == Block.INCOME || e.block == Block.UNCLASSIFIED_CREDIT
    Filter.TRANSFERS -> e.block == Block.TRANSFER
    Filter.PEOPLE -> e.block == Block.LENT || e.block == Block.UNDECIDED || e.block == Block.LENT_REPAYMENT || e.personState != null
    Filter.CARDS -> e.block == Block.CARD_BILL
    Filter.REVIEW -> e.needsReview
}

@Composable
fun TransactionsScreen(snapshot: AppSnapshot, vm: AppViewModel, initialReviewOnly: Boolean, onOpenEntry: (Long) -> Unit) {
    var query by remember { mutableStateOf("") }
    var filter by remember(initialReviewOnly) { mutableStateOf(if (initialReviewOnly) Filter.REVIEW else Filter.ALL) }
    var accountId by remember { mutableStateOf<Long?>(null) }
    var showAdd by remember { mutableStateOf(false) }

    val rows = remember(snapshot.revision, query, filter, accountId) {
        val q = query.trim().lowercase()
        snapshot.entries.filter { filter.matches(it) && (accountId == null || it.accountId == accountId) }
            .filter { q.isEmpty() || listOfNotNull(it.reason, it.counterpartyName, it.counterpartyVpa, it.ref, it.amountPaise.toString(), it.date.toString()).any { f -> f.lowercase().contains(q) } }
            .sortedWith(compareByDescending<LedgerEntry> { it.date }.thenByDescending { it.id })
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(Modifier.padding(top = 16.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text("Transactions", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            TextButton(onClick = { showAdd = true }) { Text("+ Add") }
        }
        OutlinedTextField(value = query, onValueChange = { query = it }, label = { Text("Search merchant, UPI id, amount, date") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Filter.entries.forEach { f -> FilterChip(selected = filter == f, onClick = { filter = f }, label = { Text(f.label) }) }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(selected = accountId == null, onClick = { accountId = null }, label = { Text("All accounts") })
            snapshot.profile.accounts.forEach { a ->
                FilterChip(selected = accountId == a.id, onClick = { accountId = if (accountId == a.id) null else a.id }, label = { Text(a.label) })
            }
        }
        Muted("${rows.size} transaction(s)", Modifier.padding(vertical = 6.dp))
        LazyColumn(Modifier.fillMaxSize()) {
            items(rows, key = { it.id }) { e ->
                AmountRow(
                    title = e.reason ?: e.counterpartyName ?: blockLabel(e.block),
                    amount = signedAmount(e),
                    subtitle = "${e.date}${e.time?.let { " $it" } ?: ""} · ${snapshot.profile.accounts.firstOrNull { it.id == e.accountId }?.label ?: e.accountHint ?: "?"} · ${blockLabel(e.block)}" +
                        if (e.needsReview) " · needs review" else "",
                    onClick = { onOpenEntry(e.id) },
                )
            }
        }
    }
    if (showAdd) AddManualDialog(snapshot, vm) { showAdd = false }
}

@Composable
private fun AddManualDialog(snapshot: AppSnapshot, vm: AppViewModel, onDismiss: () -> Unit) {
    var amount by remember { mutableStateOf("") }
    var reason by remember { mutableStateOf("") }
    var debit by remember { mutableStateOf(true) }
    var block by remember { mutableStateOf(Block.BANK_SPEND) }
    var accountId by remember { mutableStateOf<Long?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add a transaction") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Muted("For cash payments and anything an SMS cannot tell. Manual entries are marked as yours.")
                OutlinedTextField(value = amount, onValueChange = { amount = it }, label = { Text("Amount (₹)") }, singleLine = true)
                OutlinedTextField(value = reason, onValueChange = { reason = it }, label = { Text("Reason") }, singleLine = true)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = debit, onClick = { debit = true }, label = { Text("Money out") })
                    FilterChip(selected = !debit, onClick = { debit = false }, label = { Text("Money in") })
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(Block.BANK_SPEND, Block.CC_SPEND, Block.REGULAR, Block.INCOME).forEach { b ->
                        FilterChip(selected = block == b, onClick = { block = b }, label = { Text(blockLabel(b)) })
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    snapshot.profile.accounts.forEach { a -> FilterChip(selected = accountId == a.id, onClick = { accountId = a.id }, label = { Text(a.label) }) }
                }
            }
        },
        confirmButton = {
            val paise = parseRupees(amount)
            TextButton(enabled = paise != null && paise > 0, onClick = {
                vm.addManual(accountId, debit, paise!!, LocalDate.now(vm.zone), block, reason); onDismiss()
            }) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Items waiting for the owner, and messages the parser did not understand. Nothing is dropped silently. */
@Composable
fun ReviewScreen(snapshot: AppSnapshot, vm: AppViewModel, onOpenEntry: (Long) -> Unit) {
    val unparsed by vm.unparsed.collectAsState()
    LaunchedEffect(snapshot.revision) { vm.loadUnparsed() }
    val waiting = remember(snapshot.revision) { snapshot.entries.filter { it.needsReview }.sortedByDescending { it.date } }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("Needs your attention", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Muted("Nothing here is counted as spending or income until you decide, except where noted.")
        SectionTitle("Transactions (${waiting.size})")
        if (waiting.isEmpty()) Muted("All clear.")
        waiting.forEach { e ->
            Column(Modifier.padding(bottom = 6.dp)) {
                AmountRow(e.reason ?: e.counterpartyName ?: blockLabel(e.block), signedAmount(e), subtitle = "${e.date} · ${blockLabel(e.block)}", onClick = { onOpenEntry(e.id) })
                hintText(e)?.let { Muted(it) }
            }
        }
        SectionTitle("Messages not understood (${unparsed.size})")
        Muted("Mute a sender you do not want (for example a lender that is not yours), or dismiss one message.")
        unparsed.forEach { m ->
            val brand = SmsParser.parseSender(m.sender)?.brand
            Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Muted("${m.sender ?: "unknown sender"}")
                Text(m.body.take(240), style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (brand != null) OutlinedButton(onClick = { vm.muteSender(brand, m.id) }) { Text("Mute $brand") }
                    OutlinedButton(onClick = { vm.dismissMessage(m.id) }) { Text("Dismiss") }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}
