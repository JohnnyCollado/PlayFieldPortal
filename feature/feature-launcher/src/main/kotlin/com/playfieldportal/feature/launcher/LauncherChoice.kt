package com.playfieldportal.feature.launcher

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.playfieldportal.core.data.datastore.pfpDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What the user said an unidentified app is, in Import PC Games ▸ Unknown Windows Emulators
 * (setup-wizard plan section 6). Declared in cycle order.
 */
enum class LauncherChoice(val label: String, val type: PcLauncherType?) {
    NOT_A_LAUNCHER("Not a launcher", null),
    GAMEHUB("GameHub", PcLauncherType.GAMEHUB),
    GAMEHUB_LITE("GameHub Lite", PcLauncherType.GAMEHUB_LITE),
    BANNERHUB("BannerHub", PcLauncherType.BANNERHUB_V6),
    WINLATOR("Winlator", PcLauncherType.WINLATOR),
    GAMENATIVE("GameNative", PcLauncherType.GAMENATIVE);

    companion object {
        /** The value after [current]; an undecided row starts at the first. */
        fun next(current: LauncherChoice?): LauncherChoice =
            if (current == null) entries.first() else entries[(current.ordinal + 1) % entries.size]
    }
}

/** One install: a choice survives updates but not a reinstall signed by someone else. */
data class LauncherChoiceKey(val packageName: String, val signerSha256: String?) {
    // Hex case never splits a key.
    private val normalisedSigner = signerSha256?.lowercase().orEmpty()

    override fun equals(other: Any?): Boolean =
        other is LauncherChoiceKey && other.packageName == packageName && other.normalisedSigner == normalisedSigner

    override fun hashCode(): Int = 31 * packageName.hashCode() + normalisedSigner.hashCode()

    internal fun encoded(): String = "$packageName$SEPARATOR$normalisedSigner"

    internal companion object {
        const val SEPARATOR = "|"
    }
}

/** Every stored choice, immutable. Stored as a string set of `package|signer|CHOICE` entries. */
data class LauncherChoices(val byKey: Map<LauncherChoiceKey, LauncherChoice>) {

    fun choiceFor(packageName: String, signerSha256: String?): LauncherChoice? =
        byKey[LauncherChoiceKey(packageName, signerSha256)]

    fun with(packageName: String, signerSha256: String?, choice: LauncherChoice): LauncherChoices =
        LauncherChoices(byKey + (LauncherChoiceKey(packageName, signerSha256) to choice))

    /** Packages the user named as a launcher — outside the catalog, they are verified this way. */
    fun chosenLauncherPackages(): Set<String> =
        byKey.filterValues { it != LauncherChoice.NOT_A_LAUNCHER }.keys.mapTo(mutableSetOf()) { it.packageName }

    fun encode(): Set<String> =
        byKey.mapTo(mutableSetOf()) { (key, choice) -> "${key.encoded()}${LauncherChoiceKey.SEPARATOR}${choice.name}" }

    companion object {
        val EMPTY = LauncherChoices(emptyMap())

        /** Tolerant: a malformed entry, or a choice a later build retired, is skipped. */
        fun decode(stored: Set<String>): LauncherChoices = LauncherChoices(
            stored.mapNotNull { entry ->
                val parts = entry.split(LauncherChoiceKey.SEPARATOR)
                if (parts.size != 3 || parts[0].isBlank()) return@mapNotNull null
                val choice = LauncherChoice.entries.firstOrNull { it.name == parts[2] } ?: return@mapNotNull null
                LauncherChoiceKey(parts[0], parts[1].ifBlank { null }) to choice
            }.toMap(),
        )
    }
}

private val KEY_LAUNCHER_CHOICES = stringSetPreferencesKey("pc_launcher_user_choices")

/** The user's launcher choices. A choice outranks every automatic rule (plan section 6). */
@Singleton
class LauncherChoiceRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    val choices: Flow<LauncherChoices> =
        context.pfpDataStore.data.map { LauncherChoices.decode(it[KEY_LAUNCHER_CHOICES].orEmpty()) }

    suspend fun snapshot(): LauncherChoices = choices.first()

    suspend fun set(packageName: String, signerSha256: String?, choice: LauncherChoice) {
        context.pfpDataStore.edit { prefs ->
            prefs[KEY_LAUNCHER_CHOICES] =
                LauncherChoices.decode(prefs[KEY_LAUNCHER_CHOICES].orEmpty())
                    .with(packageName, signerSha256, choice)
                    .encode()
        }
    }
}
