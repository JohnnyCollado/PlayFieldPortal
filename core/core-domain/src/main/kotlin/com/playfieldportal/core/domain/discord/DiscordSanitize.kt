package com.playfieldportal.core.domain.discord

/**
 * Sanitizes untrusted Discord-sourced strings before they're displayed or broadcast (plan §8:
 * "untrusted in, sanitized out"). Used at the ingestion boundary for inbound friend/user names and
 * activity, and for outbound presence.
 *
 * - [text] strips control and bidirectional-override characters (anti-spoofing — a display name
 *   can't hijack layout or impersonate another user via RTL overrides), collapses whitespace, and
 *   clamps length.
 * - [avatarUrl] restricts avatars to Discord's CDN over https, so a spoofed URL can never point the
 *   image loader at an arbitrary host.
 */
object DiscordSanitize {
    /** Max lengths: display names are short; activity mirrors Discord's 128-char activity limit. */
    const val NAME_MAX = 80
    const val ACTIVITY_MAX = 128

    fun text(raw: String, maxLen: Int): String {
        val stripped = buildString(raw.length) {
            for (c in raw) {
                if (c.isISOControl() || c.code in BIDI_OVERRIDES) continue
                append(c)
            }
        }
        return stripped.replace(WHITESPACE, " ").trim().take(maxLen)
    }

    /** Returns [raw] only if it's an https URL on an allowlisted Discord CDN host; else null. */
    fun avatarUrl(raw: String?): String? {
        val url = raw?.trim().orEmpty()
        if (!url.lowercase().startsWith("https://")) return null
        val host = url.substring("https://".length).substringBefore('/').substringBefore(':').lowercase()
        return if (host in ALLOWED_AVATAR_HOSTS) url else null
    }

    private val WHITESPACE = Regex("\\s+")

    // LRM/RLM/ALM + the LRE..PDI embedding/override/isolate format characters (Unicode category Cf,
    // which isISOControl() misses). Kept as code points so no invisible characters live in source.
    private val BIDI_OVERRIDES = setOf(
        0x200E, 0x200F, 0x061C,
        0x202A, 0x202B, 0x202C, 0x202D, 0x202E,
        0x2066, 0x2067, 0x2068, 0x2069,
    )

    private val ALLOWED_AVATAR_HOSTS = setOf("cdn.discordapp.com", "media.discordapp.net")
}
