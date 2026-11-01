package dev.brainfence.domain.feedback

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Runs shared/fixtures/feedback.json, which the macOS and Windows test suites
 * load too. See shared/CONTRACTS.md (Feedback).
 */
class FeedbackContractTest {

    private fun fixture(): JSONObject {
        val dir = System.getProperty("brainfence.fixtures") ?: "../../shared/fixtures"
        return JSONObject(File(dir, "feedback.json").readText())
    }

    @Test
    fun maxLengthMatches() {
        assertEquals(fixture().getInt("max_length"), Feedback.MAX_LENGTH)
    }

    @Test
    fun normalize() {
        val cases = fixture().getJSONArray("cases")
        assertTrue(cases.length() > 0)
        for (i in 0 until cases.length()) {
            val case = cases.getJSONObject(i)
            val name = case.getString("name")
            val message = case.optJSONObject("repeat")?.let {
                it.getString("text").repeat(it.getInt("times")) + it.getString("suffix")
            } ?: case.getString("message")
            val actual = Feedback.normalize(message)
            when {
                case.has("expect_length") -> {
                    assertNotNull(name, actual)
                    assertEquals(name, message.trim(), actual)
                    assertEquals(name, case.getInt("expect_length"), actual!!.codePointCount(0, actual.length))
                }
                case.isNull("expect") -> assertEquals(name, null, actual)
                else -> assertEquals(name, case.getString("expect"), actual)
            }
        }
    }
}
