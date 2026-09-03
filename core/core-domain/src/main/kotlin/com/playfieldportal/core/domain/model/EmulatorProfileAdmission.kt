package com.playfieldportal.core.domain.model

/**
 * Decides whether an [EmulatorProfile] that came from outside the app may be used.
 *
 * A profile is not inert data. It chooses the `ComponentName` an intent is aimed at, the extras it
 * carries, and — for `CUSTOM_COMMAND` — a command string; and the package it names then receives
 * `grantUriPermission(packageName, romUri, READ)` at launch. A `.pfpbackup` is an untrusted file,
 * so the profiles inside one are the most valuable thing it carries.
 *
 * This is a whitelist rather than a blacklist on purpose. The set of shapes a legitimate profile
 * takes is small and known (the bundled profiles are the reference), while the set of harmful ones
 * is open-ended.
 */
object EmulatorProfileAdmission {

    /** One profile that was turned away, with the reason, so a restore can report it. */
    data class Refusal(val id: String, val name: String, val reason: String)

    data class Result(
        val admitted: List<EmulatorProfile>,
        val refused: List<Refusal>,
    )

    /**
     * Intent flags a profile may ask for. Deliberately excludes every URI-grant flag: the resolver
     * adds `FLAG_GRANT_READ_URI_PERMISSION` itself when a ROM URI is actually needed, and a profile
     * asking for a write grant has no legitimate reason to.
     */
    private val ALLOWED_INTENT_FLAGS = setOf("NEW_TASK", "CLEAR_TOP", "CLEAR_TASK")

    /**
     * Android package naming, tightened: at least two dot-separated segments, each starting with a
     * letter. This rejects the empty string, whitespace, and the double-dot and leading/trailing-dot
     * forms that some parsers accept.
     */
    private val PACKAGE_NAME = Regex("""[a-zA-Z][a-zA-Z0-9_]*(\.[a-zA-Z][a-zA-Z0-9_]*)+""")

    fun admit(
        profiles: List<EmulatorProfile>,
        selfPackage: String? = null,
    ): Result {
        val admitted = mutableListOf<EmulatorProfile>()
        val refused = mutableListOf<Refusal>()

        profiles.forEach { profile ->
            val reason = reasonToRefuse(profile, selfPackage)
            if (reason == null) admitted += profile else refused += Refusal(profile.id, profile.name, reason)
        }
        return Result(admitted, refused)
    }

    private fun reasonToRefuse(profile: EmulatorProfile, selfPackage: String?): String? {
        // CUSTOM_COMMAND executes a resolved string. No bundled profile uses it, and nothing
        // arriving from a file should be able to introduce one.
        if (profile.intentType == IntentType.CUSTOM_COMMAND || profile.customCommand != null) {
            return "carries a custom command"
        }
        if (!PACKAGE_NAME.matches(profile.packageName)) {
            return "package name '${profile.packageName}' is not a valid Android package"
        }
        if (selfPackage != null && profile.packageName == selfPackage) {
            return "targets this app's own package"
        }
        // A COMPONENT intent without an activity is either broken or an invitation to resolve
        // against whatever the system picks.
        if (profile.intentType == IntentType.COMPONENT && profile.activityClass.isNullOrBlank()) {
            return "is a component intent with no activity class"
        }
        profile.intentFlags.firstOrNull { it !in ALLOWED_INTENT_FLAGS }?.let {
            return "requests unsupported intent flag '$it'"
        }
        return null
    }
}
