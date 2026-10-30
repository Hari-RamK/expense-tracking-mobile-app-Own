@file:OptIn(ExperimentalLayoutApi::class)

package app.fintrack.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.fintrack.core.model.AccountKind
import app.fintrack.core.model.Block
import app.fintrack.core.model.PersonState
import app.fintrack.data.AppSnapshot

private val editableBlocks = listOf(
    Block.CC_SPEND, Block.BANK_SPEND, Block.REGULAR, Block.EMI, Block.INCOME,
    Block.TRANSFER, Block.CARD_BILL, Block.UNCLASSIFIED_CREDIT, Block.EXCLUDED,
)

/** Everything the owner can change about one transaction. Each action is written to the audit log. */
@Composable
fun EntryEditorDialog(id: Long, snapshot: AppSnapshot, vm: AppViewModel, onDismiss: () -> Unit) {
    val e = snapshot.entries.firstOrNull { it.id == id }
    if (e == null) { onDismiss(); return }
    val profile = snapshot.profile
    var reason by remember(e.id, e.reason) { mutableStateOf(e.reason ?: "") }
    var usuallySpend by remember(e.id) { mutableStateOf(false) }
    val accountLabel = profile.accounts.firstOrNull { it.id == e.accountId }?.label ?: e.accountHint ?: "Unknown account"
    val hint = e.hint.orEmpty()

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        dismissButton = if (e.manual) {
            { TextButton(onClick = { vm.deleteManual(e.id); onDismiss() }) { Text("Delete") } }
        } else null,
        title = { Text(signedAmount(e)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Muted("$accountLabel · ${e.date}${e.time?.let { " $it" } ?: ""}")
                Muted(blockLabel(e.block) + (e.counterpartyName?.let { " · $it" } ?: ""))
                e.counterpartyVpa?.let { Muted("UPI id: $it") }
                e.ref?.let { Muted("UPI ref: $it") }
                if (e.sourceMessageIds.size > 1) Muted("Seen in ${e.sourceMessageIds.size} messages (counted once)")
                hintText(e)?.let { Callout(it, Palette.AmberTint, Palette.Amber) }

                OutlinedTextField(
                    value = reason, onValueChange = { reason = it }, label = { Text("Reason") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                OutlinedButton(onClick = { vm.setReason(e.id, reason.trim()) }, enabled = reason.isNotBlank() && reason != e.reason) {
                    Text("Save reason (remembered for this payee)")
                }

                if (e.personState != null) {
                    Text("Payment to a person", style = androidx.compose.material3.MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { vm.setPerson(e.id, PersonState.SPEND, usuallySpend) }, enabled = e.personState != PersonState.SPEND) { Text("Spent") }
                        Button(onClick = { vm.setPerson(e.id, PersonState.LENT, false) }, enabled = e.personState != PersonState.LENT) { Text("Lent") }
                        OutlinedButton(onClick = { vm.setPerson(e.id, PersonState.UNDECIDED, false) }, enabled = e.personState != PersonState.UNDECIDED) { Text("Undecided") }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = usuallySpend, onCheckedChange = { usuallySpend = it })
                        Text("Usually spend with this person")
                    }
                }

                if (hint.startsWith("TRANSFER_CANDIDATE:")) {
                    val other = hint.substringAfter(':').toLongOrNull()
                    if (other != null) Button(onClick = { vm.confirmTransfer(e.id, other) }) { Text("Confirm transfer with #$other") }
                }
                if (hint.startsWith("LENT_REPAYMENT:")) {
                    val lent = hint.substringAfter(':').toLongOrNull()
                    if (lent != null) Button(onClick = { vm.markRepayment(e.id, lent) }) { Text("Yes, repayment of what I lent") }
                }
                if (e.transferGroupId != null) OutlinedButton(onClick = { vm.unlinkTransfer(e.id) }) { Text("Not a transfer: unlink") }

                if (e.block == Block.CARD_BILL) {
                    Text("This payment paid", style = androidx.compose.material3.MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        profile.accounts.filter { it.kind == AccountKind.CREDIT_CARD && it.active }.forEach { card ->
                            val selected = e.settlesAccountId == card.id
                            if (selected) Button(onClick = {}) { Text(card.label) }
                            else OutlinedButton(onClick = { vm.assignCard(e.id, card.id) }) { Text(card.label) }
                        }
                    }
                }

                if (e.accountId == null && !e.manual) {
                    Text("Which of your accounts?", style = androidx.compose.material3.MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        profile.accounts.filter { it.active }.forEach { a ->
                            OutlinedButton(onClick = { vm.assignAccount(e.id, a.id) }) { Text(a.label) }
                        }
                    }
                    if (profile.accounts.isEmpty()) Muted("Add your accounts first (Accounts tab).")
                }

                Spacer(Modifier.height(4.dp))
                Text("Count this as", style = androidx.compose.material3.MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    editableBlocks.forEach { b ->
                        AssistChip(onClick = { vm.setBlock(e.id, b) }, label = { Text(blockLabel(b) + if (b == e.block) " ✓" else "") })
                    }
                }
            }
        },
    )
}
