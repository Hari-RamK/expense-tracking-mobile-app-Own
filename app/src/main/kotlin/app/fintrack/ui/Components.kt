package app.fintrack.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.fintrack.core.model.Block
import app.fintrack.core.report.formatInr
import app.fintrack.core.model.Direction
import app.fintrack.core.model.LedgerEntry

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = modifier.padding(top = 20.dp, bottom = 6.dp))
}

@Composable
fun Muted(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f), modifier = modifier)
}

/** A small stat card: label above a big amount, with an optional hint line. */
@Composable
fun StatCard(label: String, value: String, modifier: Modifier = Modifier, accent: Color = MaterialTheme.colorScheme.primary, hint: String? = null) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(label.uppercase(), fontSize = 10.sp, fontWeight = FontWeight.Bold, color = accent)
            Spacer(Modifier.height(4.dp))
            Text(value, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            if (hint != null) Muted(hint)
        }
    }
}

/** A coloured callout for things that need attention. Colour is paired with a text label, never used alone. */
@Composable
fun Callout(text: String, tint: Color, textColor: Color, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    Box(
        modifier
            .fillMaxWidth()
            .background(tint, RoundedCornerShape(10.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(12.dp),
    ) { Text(text, color = textColor, fontSize = 13.sp, fontWeight = FontWeight.Medium) }
}

@Composable
fun AmountRow(title: String, amount: String, modifier: Modifier = Modifier, subtitle: String? = null, amountColor: Color = Color.Unspecified, onClick: (() -> Unit)? = null) {
    Column(modifier.then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 14.sp)
                if (subtitle != null) Muted(subtitle)
            }
            Spacer(Modifier.width(12.dp))
            Text(amount, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = amountColor)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
fun TotalRow(label: String, amount: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Text(label, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        Text(amount, fontWeight = FontWeight.Bold)
    }
}

fun blockLabel(b: Block): String = when (b) {
    Block.CC_SPEND -> "Credit card spend"
    Block.BANK_SPEND -> "Bank spend"
    Block.REGULAR -> "Regular"
    Block.EMI -> "EMI"
    Block.LENT -> "Lent"
    Block.UNDECIDED -> "Undecided (person)"
    Block.TRANSFER -> "Own transfer"
    Block.CARD_BILL -> "Card bill payment"
    Block.INCOME -> "Income"
    Block.LENT_REPAYMENT -> "Lent repayment"
    Block.UNCLASSIFIED_CREDIT -> "Unclassified credit"
    Block.EXCLUDED -> "Excluded"
}

fun signedAmount(e: LedgerEntry, decimals: Boolean = true): String =
    (if (e.direction == Direction.DEBIT) "-" else "+") + formatInr(e.amountPaise, decimals)

/** Plain-language reason a transaction is waiting for the owner. */
fun hintText(e: LedgerEntry): String? = when {
    e.hint == null -> null
    e.hint == "LEG_NOT_SEEN" -> "Looks like a transfer between your accounts. The other side has not been seen yet."
    e.hint!!.startsWith("TRANSFER_CANDIDATE") -> "May be the other side of a transfer. Confirm it, or leave it."
    e.hint!!.startsWith("LENT_REPAYMENT") -> "From someone you lent to. Is this a repayment?"
    e.hint == "ASSIGN_ACCOUNT" -> "The account could not be identified. Choose which of your accounts this is."
    e.hint == "ASSIGN_CARD" -> "A card bill payment. Choose which card it paid."
    e.hint == "DECIDE_LENT_OR_SPEND" -> "A payment to a person. Was it lent, or spent?"
    e.hint == "UNCLASSIFIED_CREDIT" -> "Money received. Choose whether it is income, a refund, a repayment or your own money."
    e.hint == "REF_CONFLICT" -> "The same reference appeared with a different amount. Check both."
    e.hint == "OTHER_INCOME" -> "Counted as income. Change it if it is not."
    e.hint == "AUTO_ASSIGNED_CARD" -> "Matched to a card statement automatically."
    else -> null
}
