package com.playfieldportal.core.data.video

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.playfieldportal.core.domain.model.Video
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/** An installed app able to handle ACTION_VIEW for video — a candidate external video player. */
data class VideoPlayerApp(
    val packageName: String,
    val label: String,
)

/**
 * Builds and launches the external-player intent for a video. Mirrors [MusicIntentResolver]:
 * uses the video's SAF `content://` uri with a temporary read grant, only pins to a user-chosen
 * installed package, and falls back to the system chooser — it never builds arbitrary components,
 * never exposes file paths, and never throws.
 */
@Singleton
class VideoIntentResolver @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun buildViewIntent(video: Video, playerPackage: String?): Intent {
        val uri = Uri.parse(video.uri)
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, video.mimeType ?: "video/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (!playerPackage.isNullOrBlank()) setPackage(playerPackage)
        }
    }

    /**
     * Launches [video] in an external player, optionally pinned to [playerPackage]. Returns a
     * user-readable error on failure, or null on success. A pinned player that can't handle it
     * retries once via the chooser.
     */
    fun launch(video: Video, playerPackage: String?): String? {
        val intent = buildViewIntent(video, playerPackage)
        return try {
            context.startActivity(intent)
            Timber.i("Launched video \"${video.displayTitle}\" (player=${playerPackage ?: "system"})")
            null
        } catch (e: Exception) {
            if (intent.`package` != null) {
                Timber.w(e, "Pinned video player failed, retrying with chooser")
                return launchChooser(video)
            }
            Timber.e(e, "No app could play \"${video.displayTitle}\"")
            "No video player could open this file. Install a player or pick one in Settings → Video."
        }
    }

    /** Shows the system chooser for [video] ("Ask Every Time"). */
    fun launchChooser(video: Video): String? {
        val chooser = Intent.createChooser(buildViewIntent(video, null), "Play video with…")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(chooser)
            null
        } catch (e: Exception) {
            Timber.e(e, "No app could play \"${video.displayTitle}\"")
            "No video player is installed."
        }
    }

    /** Installed apps that can handle ACTION_VIEW for video, de-duplicated by package and sorted. */
    fun availablePlayers(): List<VideoPlayerApp> {
        val pm = context.packageManager
        val probe = Intent(Intent.ACTION_VIEW).apply { setDataAndType(Uri.parse("content://media/x"), "video/*") }
        return pm.queryIntentActivities(probe, 0)
            .asSequence()
            .mapNotNull { it.activityInfo }
            .filter { it.packageName != context.packageName }
            .distinctBy { it.packageName }
            .map { info ->
                VideoPlayerApp(
                    packageName = info.packageName,
                    label = runCatching { info.loadLabel(pm).toString() }.getOrDefault(info.packageName),
                )
            }
            .sortedBy { it.label.lowercase() }
            .toList()
    }
}
