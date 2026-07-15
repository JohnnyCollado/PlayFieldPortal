package com.playfieldportal.feature.achievements.provider.localsteam

/**
 * Reads the one key PFP needs from the emu's `steam_settings/configs.user.ini`: the
 * `[user::saves]` `local_save_path` redirect that moves the save folder (and with it the progress
 * `achievements.json`) out of the unreachable Wine prefix into the game folder.
 *
 * Matches the emu's own semantics (verified against gbe_fork): the value may be absolute or
 * relative to the folder holding the steam_api DLL, and surrounding whitespace is trimmed — the
 * live file on the test device carries a trailing space. Untrusted input: a missing key, missing
 * file, or garbage yields null, never an exception.
 */
object GseUserConfig {

    /** An ini file is a handful of lines; anything beyond this is not the file we expect. */
    const val MAX_BYTES = 64 * 1024

    fun localSavePath(iniText: String): String? {
        if (iniText.length > MAX_BYTES) return null
        return iniText.lineSequence()
            .map { it.trim() }
            .filterNot { it.startsWith("#") || it.startsWith(";") }
            .firstOrNull { it.substringBefore('=').trim().equals("local_save_path", ignoreCase = true) }
            ?.substringAfter('=')
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }

    /**
     * A redirect value as folder segments to walk down from the DLL folder, or null when it can't
     * be reached through the granted tree: absolute paths (drive letters, leading slash) point
     * into the Wine prefix, and `..` would escape the folder the user granted — SAF navigation
     * by child name can't follow either, so both are "no reachable save folder", by design.
     */
    fun savePathSegments(raw: String): List<String>? {
        val cleaned = raw.trim().replace('\\', '/')
        if (cleaned.startsWith('/') || DRIVE_PREFIX.containsMatchIn(cleaned)) return null
        val segments = cleaned.split('/').map { it.trim() }.filter { it.isNotEmpty() && it != "." }
        if (segments.isEmpty() || segments.any { it == ".." }) return null
        return segments
    }

    private val DRIVE_PREFIX = Regex("^[A-Za-z]:")
}
