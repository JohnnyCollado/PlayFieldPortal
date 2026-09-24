package com.playfieldportal.core.data.repository

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import com.playfieldportal.core.data.datastore.pfpDataStore
import com.playfieldportal.core.domain.model.AudioChannel
import com.playfieldportal.core.ui.sound.AudioLevels
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * The user's master and per-channel volume levels — the ONE place the preference keys are built
 * and the ONE place the taper is applied.
 *
 * Replaces the `sound_menu_enabled` boolean, which was the launcher's entire volume model until
 * ambience arrived: a one-shot menu tick only ever needs on or off, but a background loop needs a
 * level. **Master at 0 is the mute**, which is why no separate toggle survives — a switch and a
 * slider that both mean "silent" drift apart the moment one is changed without the other.
 *
 * Every level defaults to [DEFAULT_LEVEL] (full), so an install that has never opened the screen
 * sounds exactly as it did before this existed.
 *
 * All values are flows. See [AudioLevels] for why a snapshot is the caller's decision rather than
 * this store's.
 */
@Singleton
class AudioLevelStore @Inject constructor(
    @ApplicationContext private val context: Context,
) : AudioLevels {

    override fun gainFor(channel: AudioChannel): Flow<Float> =
        context.pfpDataStore.data
            .map { prefs ->
                AudioLevels.taper(
                    masterPercent = prefs.readLevel(masterKey),
                    channelPercent = prefs.readLevel(channel.levelKey),
                )
            }
            .distinctUntilChanged()

    override val masterPercent: Flow<Float> =
        context.pfpDataStore.data.map { it.readLevel(masterKey) }.distinctUntilChanged()

    override fun percentFor(channel: AudioChannel): Flow<Float> =
        context.pfpDataStore.data.map { it.readLevel(channel.levelKey) }.distinctUntilChanged()

    /** Writes the master level, clamped. The settings screen's only master seam. */
    suspend fun setMaster(percent: Float) {
        context.pfpDataStore.edit { it[masterKey] = percent.coerceIn(0f, 1f) }
    }

    /** Writes [channel]'s level, clamped. */
    suspend fun setChannel(channel: AudioChannel, percent: Float) {
        context.pfpDataStore.edit { it[channel.levelKey] = percent.coerceIn(0f, 1f) }
    }

    /** "Reset Sound to Defaults" — every level back to full, master included. */
    suspend fun resetAll() {
        context.pfpDataStore.edit { prefs ->
            prefs[masterKey] = DEFAULT_LEVEL
            for (channel in AudioChannel.entries) prefs[channel.levelKey] = DEFAULT_LEVEL
        }
    }

    // A missing key means "never set", which is full — NOT zero. Getting this wrong would ship a
    // silent launcher to every install that has not opened the screen.
    private fun Preferences.readLevel(key: Preferences.Key<Float>): Float =
        (this[key] ?: DEFAULT_LEVEL).coerceIn(0f, 1f)

    companion object {
        /** Full volume. Every channel and the master start here. */
        const val DEFAULT_LEVEL = 1f

        /** The key names come from [AudioChannel] so this file cannot invent a different one. */
        val masterKey = floatPreferencesKey(AudioChannel.MASTER_PREFERENCE_KEY)

        val AudioChannel.levelKey: Preferences.Key<Float>
            get() = floatPreferencesKey(preferenceKey)
    }
}

/** Hilt binding: core-ui sees [AudioLevels]; the implementation lives here. */
@Module
@InstallIn(SingletonComponent::class)
abstract class AudioLevelModule {
    @Binds
    @Singleton
    abstract fun bindAudioLevels(impl: AudioLevelStore): AudioLevels
}
