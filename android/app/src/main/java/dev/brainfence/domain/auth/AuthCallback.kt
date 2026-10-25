package dev.brainfence.domain.auth

import java.net.URLDecoder

/**
 * An email link that Supabase Auth redirects to brainfence://auth-callback.
 * Shared semantics: shared/CONTRACTS.md (Email links); cases:
 * shared/fixtures/auth-callback.json, which macOS and Windows run too.
 */
sealed interface AuthCallback {
    data class Code(val code: String) : AuthCallback
    data class Tokens(val accessToken: String, val refreshToken: String) : AuthCallback
    data class Error(val message: String) : AuthCallback
    data object None : AuthCallback

    companion object {
        const val SCHEME = "brainfence"
        const val HOST = "auth-callback"
        const val URL = "$SCHEME://$HOST"
        const val CONFIRMED_NOTICE = "Your email is confirmed. Sign in to continue."
        fun errorNotice(message: String) =
            "That link didn't work: $message. Sign up again with the same email to get a new one."

        private val PATTERN = Regex("""^([A-Za-z][A-Za-z0-9+.-]*)://([^/?#]*)[^?#]*(?:\?([^#]*))?(?:#(.*))?$""")

        /** Classifies [url], or null when it isn't an auth callback. */
        fun parse(url: String): AuthCallback? {
            val match = PATTERN.matchEntire(url.trim()) ?: return null
            val (scheme, host, rawQuery, rawFragment) = match.destructured
            if (!scheme.equals(SCHEME, ignoreCase = true) || host != HOST) return null
            val query = formValues(rawQuery)
            val fragment = formValues(rawFragment)
            fun param(key: String) = listOfNotNull(query[key], fragment[key]).firstOrNull { it.isNotEmpty() }

            (param("error_description") ?: param("error"))?.let { return Error(it) }
            query["code"]?.takeIf { it.isNotEmpty() }?.let { return Code(it) }
            val access = fragment["access_token"].orEmpty()
            val refresh = fragment["refresh_token"].orEmpty()
            if (access.isNotEmpty() && refresh.isNotEmpty()) return Tokens(access, refresh)
            return None
        }

        /** `a=1&b=x+y` with form decoding ('+' is a space), first value wins. */
        private fun formValues(text: String): Map<String, String> {
            val out = linkedMapOf<String, String>()
            for (pair in text.split('&')) {
                if (pair.isEmpty()) continue
                val key = URLDecoder.decode(pair.substringBefore('='), "UTF-8")
                val value = if ('=' in pair) URLDecoder.decode(pair.substringAfter('='), "UTF-8") else ""
                out.putIfAbsent(key, value)
            }
            return out
        }
    }
}
