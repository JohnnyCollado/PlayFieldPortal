package com.playfieldportal.core.data.music

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import com.playfieldportal.core.domain.model.MusicTrack
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/** An installed app able to handle ACTION_VIEW for audio — a candidate default music player. */
data class MusicPlayerApp(
    val packageName: String,
    val label: String,
)

/**
 * Builds and launches the external-player intent for a music track. Playback is owned by the
 * chosen external app so audio keeps playing after the user leaves PFP — PFP never decodes audio
 * itself. [buildIntent] is pure (no side effects) so it can be unit-tested.
 */
@Singleton
class MusicIntentResolver @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /**
     * ACTION_VIEW intent for [track], optionally pinned to [defaultPlayerPackage]. Always grants
     * the target temporary read access to the SAF uri and launches into its own task.
     */
    fun buildIntent(track: MusicTrack, defaultPlayerPackage: String?): Intent {
        val uri = Uri.parse(track.uri)
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, track.mimeType ?: "audio/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            // Pin to the user's chosen player when set. If that player can't actually handle the
            // track, launch() catches the failure and retries with the system chooser. The
            // BUILTIN sentinel ("Play Field Portal") is not a real package, so it stays unpinned.
            if (!defaultPlayerPackage.isNullOrBlank() && defaultPlayerPackage != BUILTIN) {
                setPackage(defaultPlayerPackage)
            }
        }
    }

    /**
     * Launches [track] in the external player. Returns a user-readable error message on failure
     * (no player installed, revoked uri, etc.), or null on success. Never throws.
     */
    fun launch(track: MusicTrack, defaultPlayerPackage: String?): String? {
        val intent = buildIntent(track, defaultPlayerPackage)
        return try {
            context.startActivity(intent)
            Timber.i("Launched music track \"${track.displayTitle}\" (player=${defaultPlayerPackage ?: "system"})")
            null
        } catch (e: Exception) {
            // A pinned player that can't handle it: retry once with the system chooser.
            if (intent.`package` != null) {
                Timber.w(e, "Pinned player failed, retrying with chooser")
                return launch(track, defaultPlayerPackage = null)
            }
            Timber.e(e, "No app could play \"${track.displayTitle}\"")
            "No music player could open this track. Install a player or pick one in Settings → Music."
        }
    }

    /** Installed apps that can handle ACTION_VIEW for audio, de-duplicated by package and sorted. */
    fun availablePlayers(): List<MusicPlayerApp> {
        val pm = context.packageManager
        val probe = Intent(Intent.ACTION_VIEW).apply { setDataAndType(Uri.parse("content://media/x"), "audio/*") }
        return pm.queryIntentActivities(probe, 0)
            .asSequence()
            .mapNotNull { it.activityInfo }
            .filter { it.packageName != context.packageName }
            .distinctBy { it.packageName }
            .map { info ->
                MusicPlayerApp(
                    packageName = info.packageName,
                    label = runCatching { info.loadLabel(pm).toString() }.getOrDefault(info.packageName),
                )
            }
            .sortedBy { it.label.lowercase() }
            .toList()
    }

    companion object {
        /** Sentinel default meaning "Play Field Portal" (in-app player) rather than a real package. */
        const val BUILTIN = "builtin"
    }
}
