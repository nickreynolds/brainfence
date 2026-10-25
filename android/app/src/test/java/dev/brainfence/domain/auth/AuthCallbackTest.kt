package dev.brainfence.domain.auth

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Runs shared/fixtures/auth-callback.json, which the macOS and Windows test
 * suites load too. See shared/CONTRACTS.md (Email links).
 */
class AuthCallbackTest {

    @Test
    fun links() {
        val dir = System.getProperty("brainfence.fixtures") ?: "../../shared/fixtures"
        val cases = JSONObject(File(dir, "auth-callback.json").readText()).getJSONArray("cases")
        assertTrue(cases.length() > 0)
        for (i in 0 until cases.length()) {
            val case = cases.getJSONObject(i)
            val url = case.getString("url")
            val expected = case.optJSONObject("result")
            val actual = when (val link = AuthCallback.parse(url)) {
                null -> null
                is AuthCallback.Code -> mapOf("kind" to "code", "code" to link.code)
                is AuthCallback.Tokens -> mapOf("kind" to "tokens", "access_token" to link.accessToken, "refresh_token" to link.refreshToken)
                is AuthCallback.Error -> mapOf("kind" to "error", "message" to link.message)
                AuthCallback.None -> mapOf("kind" to "none")
            }
            assertEquals(url, expected?.let { e -> e.keys().asSequence().associateWith { e.getString(it) } }, actual)
        }
    }
}
