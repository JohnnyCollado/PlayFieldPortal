package com.playfieldportal.core.common.logging

/**
 * Scrubs sensitive values out of a log line before it is persisted to disk (see
 * [PfpFileLoggingTree]). Log FILES leave the device — users share them for support — so the
 * bar is stricter than logcat: credentials, tokens, account names, and email addresses must
 * never survive into a file a user might send.
 *
 * Kept dependency-free and pure so it's trivially unit-testable.
 */
object LogRedaction {

    // Credential/token-style query params and key=value pairs. Matches URL query strings and
    // plain "password=..." text alike. The value is replaced, the key kept for debuggability.
    // ssid is ScreenScraper's USERNAME param — an account identity, so it goes too.
    private val SECRET_PARAMS = Regex(
        "(?i)\\b(devpassword|sspassword|password|passwd|pwd|apikey|api_key|client_secret|" +
            "clientsecret|access_token|refresh_token|token|secret|ssid|sspass|auth)=([^&\\s\"']+)"
    )

    // Authorization-style headers: "Authorization: Bearer xyz", "Client-ID: xyz", "X-Api-Key: xyz".
    private val SECRET_HEADERS = Regex(
        "(?i)\\b(authorization|client-id|x-api-key|api-key)\\s*[:=]\\s*([^\\s\"',;]+(\\s+[^\\s\"',;]+)?)"
    )

    // Email addresses — account identities have no business in a shareable log.
    private val EMAIL = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")

    fun redact(message: String): String {
        var out = message
        if ('=' in out) out = SECRET_PARAMS.replace(out) { "${it.groupValues[1]}=REDACTED" }
        out = SECRET_HEADERS.replace(out) { "${it.groupValues[1]}: REDACTED" }
        if ('@' in out) out = EMAIL.replace(out, "REDACTED@EMAIL")
        return out
    }
}
