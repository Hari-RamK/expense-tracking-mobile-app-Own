package app.fintrack.core.money

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PaiseTest {
    @Test
    fun `no floating point error on amounts that binary floats get wrong`() {
        // 0.1 + 0.2 style trap: 1.15 * 100 is 114.99999999999999 in doubles.
        assertEquals(115L, parsePaise("1.15"))
        assertEquals(1999999999999L, parsePaise("19,999,999,999.99"))
    }

    @Test
    fun `extra decimals are truncated not rounded`() {
        assertEquals(1099L, parsePaise("10.999"))
    }

    @Test
    fun `rejects garbage`() {
        assertFailsWith<IllegalArgumentException> { parsePaise("abc") }
        assertFailsWith<IllegalArgumentException> { parsePaise("") }
        assertFailsWith<IllegalArgumentException> { parsePaise("-5") }
    }
}
