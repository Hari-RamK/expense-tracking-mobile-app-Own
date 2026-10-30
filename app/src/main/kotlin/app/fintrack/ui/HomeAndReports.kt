package app.fintrack.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.fintrack.core.model.LedgerEntry
import app.fintrack.core.report.DueRow
import app.fintrack.core.report.DueStatus
import app.fintrack.core.report.Period
import app.fintrack.core.report.PeriodKind
import app.fintrack.core.report.PeriodReport
import app.fintrack.core.report.RegularRow
import app.fintrack.core.report.RegularStatus
import app.fintrack.core.report.formatInr
import app.fintrack.data.AppSnapshot
import app.fintrack.sms.hasSmsPermission
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun HomeScreen(vm: AppViewModel, snapshot: AppSnapshot, onOpenReview: () -> Unit, onOpenEntry: (Long) -> Unit, onOpenAccounts: () -> Unit, requestSms: () -> Unit) {
    val context = LocalContext.current
    val today = vm.today()
    val day = remember(snapshot.revision, today) { vm.report(snapshot, Period.day(today)) }
    val month = remember(snapshot.revision, today) { vm.report(snapshot, Period.month(today)) }
    val busy by vm.busy.collectAsStateWithLifecycle()
    val smsOk = hasSmsPermission(context)

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("Overview", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Muted("$today · transfers are never counted as spending or income")
        Spacer(Modifier.height(12.dp))

        if (snapshot.profile.accounts.isEmpty()) {
            Callout("Start by adding your accounts and your name. Tap here.", Palette.AmberTint, Palette.Amber, onClick = onOpenAccounts)
            Spacer(Modifier.height(8.dp))
        }
        if (!smsOk) {
            Callout("SMS access is off, so nothing can be read. Tap to allow. Only messages from banks and apps are read, never chats.", Palette.RedTint, Palette.Red, onClick = requestSms)
            Spacer(Modifier.height(8.dp))
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatCard("Today", formatInr(day.totals.totalSpentPaise), Modifier.weight(1f), hint = "${day.totals.spendCount} transactions")
            StatCard("This month", formatInr(month.totals.totalSpentPaise), Modifier.weight(1f), Palette.Ink, hint = "spent")
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatCard("Income", formatInr(month.totals.incomePaise), Modifier.weight(1f), Palette.Green, hint = "this month")
            StatCard("Net cash flow", (if (month.totals.netCashFlowPaise >= 0) "+" else "") + formatInr(month.totals.netCashFlowPaise), Modifier.weight(1f), Palette.Teal, hint = "income − spending")
        }

        month.cardsDueTomorrow.forEach { due ->
            Spacer(Modifier.height(8.dp))
            Callout("Due tomorrow: ${due.accountLabel} ${formatInr(due.stillDuePaise, true)}", Palette.RedTint, Palette.Red)
        }
        if (month.needsReviewCount > 0) {
            Spacer(Modifier.height(8.dp))
            Callout("${month.needsReviewCount} item(s) need your decision. Tap to review.", Palette.AmberTint, Palette.Amber, onClick = onOpenReview)
        }
        if (snapshot.unparsedCount > 0) {
            Spacer(Modifier.height(8.dp))
            Callout("${snapshot.unparsedCount} message(s) were not understood. Tap to review.", Palette.AmberTint, Palette.Amber, onClick = onOpenReview)
        }
        if (month.lentOutstandingPaise > 0) {
            Spacer(Modifier.height(8.dp))
            Callout("Lent and not yet repaid: ${formatInr(month.lentOutstandingPaise)}", Palette.TealTint, Palette.Teal)
        }

        SectionTitle("Account overview")
        if (month.accountSummaries.isEmpty()) Muted("No bank accounts yet.")
        month.accountSummaries.forEach { a ->
            AmountRow(a.label, (if (a.netChangePaise >= 0) "+" else "") + formatInr(a.netChangePaise), subtitle = "Net change this month")
        }

        SectionTitle("Recent transactions")
        snapshot.entries.sortedWith(compareByDescending<LedgerEntry> { it.date }.thenByDescending { it.id }).take(10).forEach { e ->
            AmountRow(
                title = e.reason ?: e.counterpartyName ?: blockLabel(e.block),
                amount = signedAmount(e),
                subtitle = "${e.date} · ${blockLabel(e.block)}" + if (e.needsReview) " · needs review" else "",
                onClick = { onOpenEntry(e.id) },
            )
        }
        if (snapshot.entries.isEmpty()) Muted("Nothing yet. Import your SMS from Settings once an account is added.")

        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.importInbox(context) }, enabled = smsOk && !busy) { Text(if (busy) "Reading…" else "Read SMS now") }
        }
        month.disclosures.forEach { Muted("• $it", Modifier.padding(top = 4.dp)) }
    }
}

@Composable
fun ReportScreen(vm: AppViewModel, snapshot: AppSnapshot, onOpenEntry: (Long) -> Unit) {
    var period by remember { mutableStateOf(Period.month(vm.today())) }
    val report = remember(snapshot.revision, period) { vm.report(snapshot, period) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("Reports", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(PeriodKind.DAY to "Day", PeriodKind.WEEK to "Week", PeriodKind.MONTH to "Month").forEach { (kind, label) ->
                FilterChip(
                    selected = period.kind == kind, label = { Text(label) },
                    onClick = {
                        val t = vm.today()
                        period = when (kind) { PeriodKind.DAY -> Period.day(t); PeriodKind.WEEK -> Period.week(t); PeriodKind.MONTH -> Period.month(t) }
                    },
                )
            }
        }
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            TextButton(onClick = { period = period.previous() }) { Text("‹ Previous") }
            Text(period.label, modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            TextButton(onClick = { period = period.next() }) { Text("Next ›") }
        }
        ReportBody(report, onOpenEntry)
    }
}

@Composable
private fun ReportBody(r: PeriodReport, onOpenEntry: (Long) -> Unit) {
    val t = r.totals
    val prev = r.previousTotals.totalSpentPaise
    val previousPeriod = r.period.previous()
    // A percentage is only fair between periods of the same length (weeks are clipped to the month).
    val change = if (prev > 0 && previousPeriod.lengthDays == r.period.lengthDays) (t.totalSpentPaise - prev) * 100 / prev else null
    val previousHint = change?.let { if (it <= 0) "${-it}% less than before" else "$it% more than before" }
        ?: "Previous (${previousPeriod.shortLabel}): ${formatInr(prev)}"

    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        StatCard("Total spent", formatInr(t.totalSpentPaise), Modifier.weight(1f), Palette.Ink,
            hint = previousHint)
        StatCard("Income", formatInr(t.incomePaise), Modifier.weight(1f), Palette.Green)
    }
    Spacer(Modifier.height(10.dp))
    StatCard("Net external cash flow", (if (t.netCashFlowPaise >= 0) "+" else "") + formatInr(t.netCashFlowPaise), Modifier.fillMaxWidth(), Palette.Teal,
        hint = "Transfers ${formatInr(r.transfersPaise)} excluded · card bill payments ${formatInr(r.cardBillPaymentsPaise)} excluded")

    SectionTitle("Credit card spends")
    r.ccRows.forEach { AmountRow(it.cellText, formatInr(it.amountPaise, true), subtitle = it.date.toString(), onClick = { onOpenEntry(it.entryId) }) }
    if (r.ccRows.isEmpty()) Muted("None")
    TotalRow("Total", formatInr(t.ccPaise, true))

    SectionTitle("Bank spends")
    r.bankRows.forEach { AmountRow(it.cellText, formatInr(it.amountPaise, true), subtitle = it.date.toString(), onClick = { onOpenEntry(it.entryId) }) }
    if (r.bankRows.isEmpty()) Muted("None")
    TotalRow("Total", formatInr(t.bankPaise, true))

    SectionTitle("Card bills due")
    if (r.cardDues.isEmpty()) Muted("No statements seen.")
    r.cardDues.forEach { DueBlock(it) }

    SectionTitle("Lent")
    r.lentRows.forEach { l ->
        AmountRow(
            "${l.person} · ${l.upiIdOrName}", formatInr(l.outstandingPaise, true),
            subtitle = "From ${l.fromAccount} · ${l.date} · lent ${formatInr(l.amountPaise, true)}" + if (l.repaidPaise > 0) ", repaid ${formatInr(l.repaidPaise, true)}" else "",
            amountColor = if (l.outstandingPaise > 0) Palette.Amber else Palette.Green, onClick = { onOpenEntry(l.entryId) },
        )
    }
    if (r.lentRows.isEmpty()) Muted("Nothing lent")
    if (r.awaitingDecisionCount > 0) Callout("${formatInr(r.awaitingDecisionPaise)} in ${r.awaitingDecisionCount} payment(s) to people is waiting for your decision (not counted in spending or lent).", Palette.AmberTint, Palette.Amber)

    SectionTitle("Monthly regular")
    r.regularRows.forEach { RegularBlock(it) }
    if (r.regularRows.isEmpty()) Muted("No regular items for this period. Add them in Settings > Regular & EMI.")
    TotalRow("Seen in messages", formatInr(t.regularPaise, true))

    SectionTitle("EMI")
    r.emiRows.forEach { RegularBlock(it) }
    if (r.emiRows.isEmpty()) Muted("None")

    SectionTitle("Moved between your accounts")
    AmountRow("Own-account transfers (each counted once)", formatInr(r.transfersPaise, true))
    if (r.unmatchedTransferLegs > 0) Muted("${r.unmatchedTransferLegs} leg(s) have no matching other side yet.")

    if (r.salaryFlow.salaryInPaise > 0) {
        SectionTitle("Salary flow")
        val f = r.salaryFlow
        AmountRow("Salary in", formatInr(f.salaryInPaise, true))
        AmountRow("Moved to your other accounts", "−" + formatInr(f.movedToOwnAccountsPaise, true))
        AmountRow("Spent from the salary account", "−" + formatInr(f.spentPaise, true))
        if (f.lentAndUndecidedPaise > 0) AmountRow("Lent / awaiting decision", "−" + formatInr(f.lentAndUndecidedPaise, true))
        if (f.cardBillsPaise > 0) AmountRow("Card bills paid", "−" + formatInr(f.cardBillsPaise, true))
        TotalRow("Left from this salary", formatInr(f.leftPaise, true))
    }

    SectionTitle("By account")
    r.accountSummaries.forEach { a ->
        AmountRow(a.label, (if (a.netChangePaise >= 0) "+" else "") + formatInr(a.netChangePaise, true),
            subtitle = "in ${formatInr(a.incomePaise + a.transfersInPaise)} · out ${formatInr(a.transfersOutPaise)} · spent ${formatInr(a.spentPaise)}")
    }

    if (r.topReasons.isNotEmpty()) {
        SectionTitle("Top reasons")
        r.topReasons.forEach { AmountRow(it.name, formatInr(it.amountPaise, true), subtitle = "${it.count} transaction(s)") }
    }
    if (r.daily.size > 1) {
        SectionTitle("Daily trend")
        val max = r.daily.maxOf { it.spentPaise }.coerceAtLeast(1)
        r.daily.forEach { d ->
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(d.date.dayOfMonth.toString().padStart(2, '0'), fontSize = 12.sp, modifier = Modifier.padding(end = 8.dp))
                Box(Modifier.weight(1f)) {
                    Box(Modifier.fillMaxWidth((d.spentPaise.toFloat() / max).coerceIn(0.02f, 1f)).height(10.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(5.dp)).background(Palette.Teal))
                }
                Text(formatInr(d.spentPaise), fontSize = 12.sp, modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
    if (r.largest.isNotEmpty()) {
        SectionTitle("Largest")
        r.largest.forEach { AmountRow(it.cellText, formatInr(it.amountPaise, true), subtitle = it.date.toString(), onClick = { onOpenEntry(it.entryId) }) }
    }
    if (r.disclosures.isNotEmpty()) {
        SectionTitle("Coverage and disclosures")
        r.disclosures.forEach { Muted("• $it", Modifier.padding(bottom = 4.dp)) }
    }
    Spacer(Modifier.height(24.dp))
}

@Composable
private fun DueBlock(d: DueRow) {
    val (label, tint, color) = when (d.status) {
        DueStatus.PAID -> Triple("Paid", Palette.GreenTint, Palette.Green)
        DueStatus.OVERDUE -> Triple("Overdue", Palette.RedTint, Palette.Red)
        DueStatus.DUE_TODAY -> Triple("Due today", Palette.RedTint, Palette.Red)
        DueStatus.DUE_TOMORROW -> Triple("Due tomorrow", Palette.RedTint, Palette.Red)
        DueStatus.DUE_SOON -> Triple("Due in ${d.daysLeft} days", Palette.AmberTint, Palette.Amber)
        DueStatus.UPCOMING -> Triple("Due in ${d.daysLeft} days", Palette.TealTint, Palette.Teal)
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Callout("$label · ${d.accountLabel}: ${formatInr(d.stillDuePaise, true)} still due on ${d.dueDate}", tint, color)
        Muted(
            "Statement ${formatInr(d.totalDuePaise, true)}" + (d.minDuePaise?.let { " · minimum ${formatInr(it, true)}" } ?: "") +
                (if (d.paidPaise > 0) " · paid ${formatInr(d.paidPaise, true)}" else "") +
                (if (d.awaitingIssuerConfirmation) " (awaiting the card issuer's confirmation)" else ""),
            Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun RegularBlock(r: RegularRow) {
    val status = when (r.status) {
        RegularStatus.SEEN -> "seen"
        RegularStatus.EXPECTED -> "expected" + (r.dueDate?.let { " $it" } ?: "")
        RegularStatus.NO_SMS_YET -> "no SMS yet"
        RegularStatus.NEXT_PERIOD -> "next due ${r.dueDate}"
    }
    AmountRow(
        r.name + (r.progress?.let { " · $it" } ?: ""),
        if (r.seenPaise > 0) formatInr(r.seenPaise, true) else formatInr(r.expectedPaise, true) + " (expected)",
        subtitle = status,
        amountColor = when (r.status) { RegularStatus.SEEN -> Palette.Green; RegularStatus.NO_SMS_YET -> Palette.Amber; else -> Color.Unspecified },
    )
}
