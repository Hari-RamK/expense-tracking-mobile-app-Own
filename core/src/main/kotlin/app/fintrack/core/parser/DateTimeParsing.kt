package app.fintrack.core.parser

import java.time.LocalDate

private val MONTHS = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")
private val FORMAT_TOKENS = Regex("dd|MMM|MM|yyyy|yy|[^dMy]+")
private val TIME = Regex("""^(\d{1,2}):(\d{2})(?: ([AP]M))?$""", RegexOption.IGNORE_CASE)

/**
 * Parses [text] using a small format language: dd, MM, MMM (month name), yy (20yy), yyyy and literal separators.
 * Throws on an impossible date (e.g. 31-02-26) so the caller can keep the message for review instead of guessing.
 */
fun parseDate(text: String, format: String): LocalDate {
    var pos = 0
    var day = 0
    var month = 0
    var year = 0
    for (token in FORMAT_TOKENS.findAll(format).map { it.value }) {
        require(pos + token.length <= text.length) { "Date \"$text\" is too short for format $format" }
        val part = text.substring(pos, pos + token.length)
        pos += token.length
        when (token) {
            "dd" -> day = part.toInt()
            "MM" -> month = part.toInt()
            "MMM" -> month = MONTHS.indexOf(part.lowercase()) + 1
            "yy" -> year = 2000 + part.toInt()
            "yyyy" -> year = part.toInt()
        }
    }
    return LocalDate.of(year, month, day)
}

/** "10:56 AM" -> "10:56", "1:05 PM" -> "13:05"; 24-hour input passes through. */
fun to24h(text: String): String {
    val m = requireNotNull(TIME.matchEntire(text)) { "Invalid time \"$text\"" }
    var hour = m.groupValues[1].toInt()
    val meridiem = m.groupValues[3]
    if (meridiem.isNotEmpty()) hour = (hour % 12) + if (meridiem.uppercase() == "PM") 12 else 0
    return "%02d:%s".format(hour, m.groupValues[2])
}
