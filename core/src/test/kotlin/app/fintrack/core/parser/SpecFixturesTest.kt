package app.fintrack.core.parser

import app.fintrack.core.money.parsePaise
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.TestFactory
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Runs the shared parser-spec/fixtures.json, the same file parser-spec/run.mjs uses.
 * Passing here and there proves the Kotlin parser and the language-neutral spec agree.
 */
class SpecFixturesTest {
    private val fixtures: JsonObject = Json.parseToJsonElement(
        checkNotNull(javaClass.getResourceAsStream("/parser-spec/fixtures.json")).use { it.readBytes().toString(Charsets.UTF_8) },
    ).jsonObject
    private val parser = SmsParser()

    @TestFactory
    fun messageFixtures(): List<DynamicTest> = fixtures.getValue("cases").jsonArray.map { case ->
        val c = case.jsonObject
        val id = c.getValue("id").jsonPrimitive.content
        dynamicTest(id) {
            val sender = c["sender"]?.jsonPrimitive?.content
            val actual = parser.parse(c.getValue("body").jsonPrimitive.content, sender).toFieldMap()
            for ((key, expected) in c.getValue("expect").jsonObject) {
                assertJsonEquals(expected, actual[key], "$id.$key")
            }
        }
    }

    @TestFactory
    fun paiseCases(): List<DynamicTest> = fixtures.getValue("paiseCases").jsonArray.map { row ->
        val (text, expected) = row.jsonArray.map { it.jsonPrimitive }
        dynamicTest("paise $text") { assertEquals(expected.longOrNull, parsePaise(text.content)) }
    }

    @TestFactory
    fun timeCases(): List<DynamicTest> = fixtures.getValue("timeCases").jsonArray.map { row ->
        val (text, expected) = row.jsonArray.map { it.jsonPrimitive.content }
        dynamicTest("time $text") { assertEquals(expected, to24h(text)) }
    }

    @TestFactory
    fun senderCases(): List<DynamicTest> = fixtures.getValue("senderCases").jsonArray.map { row ->
        val (header, expected) = row.jsonArray
        val text = header.jsonPrimitive.content
        dynamicTest("sender '$text'") {
            val actual = SmsParser.parseSender(text)
            if (expected is JsonNull) {
                assertNull(actual)
            } else {
                val e = expected.jsonObject
                assertEquals(e.getValue("prefix").jsonPrimitive.content, actual?.prefix)
                assertEquals(e.getValue("brand").jsonPrimitive.content, actual?.brand)
                assertEquals(e.getValue("kind").let { if (it is JsonNull) null else it.jsonPrimitive.content }, actual?.kind)
            }
        }
    }

    @TestFactory
    fun repeatedNoticesCollapseToOneKey(): List<DynamicTest> = fixtures.getValue("noticeDedup").jsonArray.map { group ->
        val g = group.jsonObject
        dynamicTest(g.getValue("id").jsonPrimitive.content) {
            val sender = g.getValue("sender").jsonPrimitive.content
            fun keys(field: String) = g.getValue(field).jsonArray.map {
                (parser.parse(it.jsonPrimitive.content, sender) as ParseResult.Parsed).message.noticeKey
            }
            val same = keys("same").toSet()
            assertEquals(1, same.size, "same bodies must share one key: $same")
            assertTrue(same.single() != null)
            keys("different").forEach { assertTrue(it != null && it !in same, "different notice shares a key: $it") }
        }
    }

    private fun assertJsonEquals(expected: kotlinx.serialization.json.JsonElement, actual: Any?, label: String) {
        when {
            expected is JsonNull -> assertNull(actual, label)
            expected is JsonPrimitive && expected.isString -> assertEquals(expected.content, actual, label)
            expected is JsonPrimitive && expected.booleanOrNull != null -> assertEquals(expected.booleanOrNull, actual, label)
            expected is JsonPrimitive && expected.longOrNull != null -> assertEquals(expected.longOrNull, actual, label)
            expected is JsonArray -> assertEquals(expected.map { it.jsonPrimitive.content }, actual, label)
            else -> error("Unsupported expectation for $label: $expected")
        }
    }
}
