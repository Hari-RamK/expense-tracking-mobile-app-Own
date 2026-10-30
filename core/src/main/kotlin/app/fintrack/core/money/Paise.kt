package app.fintrack.core.money

/**
 * Parses a decimal rupee amount into integer paise without any floating point.
 * "1,00,000.5" -> 10000050. More than two decimals are truncated, never rounded.
 */
fun parsePaise(text: String): Long {
    val cleaned = text.replace(",", "")
    val whole = cleaned.substringBefore('.')
    val fraction = if ('.' in cleaned) cleaned.substringAfter('.') else ""
    require(whole.isNotEmpty() && whole.all { it in '0'..'9' } && fraction.all { it in '0'..'9' }) {
        "Bad amount: $text"
    }
    val fractionPaise = (fraction + "00").take(2).toLong()
    return Math.addExact(Math.multiplyExact(whole.toLong(), 100L), fractionPaise)
}
