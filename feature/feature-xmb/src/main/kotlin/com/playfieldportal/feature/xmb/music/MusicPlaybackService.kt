package com.playfieldportal.feature.xmb.music

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.IBinder
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import javax.inject.Inject

/**
 * Foreground service that keeps the in-app [MusicPlayerController] playing while the user is
 * outside PFP, surfacing a media-style notification (play/pause, prev, next, stop) backed by a
 * [MediaSession] for lockscreen/headset controls. It does not own the player — it observes the
 * shared singleton controller and mirrors its state.
 */
@AndroidEntryPoint
class MusicPlaybackService : Service() {

    @Inject lateinit var controller: MusicPlayerController

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var mediaSession: MediaSession
    private var started = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        mediaSession = MediaSession(this, "PFPMusic").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() { this@MusicPlaybackService.controller.playPause() }
                override fun onPause() { this@MusicPlaybackService.controller.playPause() }
                override fun onSkipToNext() { this@MusicPlaybackService.controller.next() }
                override fun onSkipToPrevious() { this@MusicPlaybackService.controller.prev() }
                override fun onSeekTo(pos: Long) { this@MusicPlaybackService.controller.seekTo(pos.toInt()) }
                override fun onStop() { stopPlayback() }
            })
            isActive = true
        }
        // Mirror controller state into the notification + session; when nothing is loaded, end.
        controller.state.onEach { state ->
            if (state.track == null) stopPlayback() else if (started) update(state)
        }.launchIn(scope)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY_PAUSE -> controller.playPause()
            ACTION_NEXT       -> controller.next()
            ACTION_PREV       -> controller.prev()
            ACTION_STOP       -> { stopPlayback(); return START_NOT_STICKY }
        }
        // Always promote to foreground first — startForegroundService requires startForeground
        // within ~5s or the system kills us with a crash.
        val state = controller.state.value
        startForeground(NOTIF_ID, buildNotification(state))
        started = true
        if (state.track == null) stopPlayback() else update(state)
        return START_NOT_STICKY
    }

    private fun stopPlayback() {
        controller.stop()
        mediaSession.isActive = false
        started = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun update(state: MusicPlaybackState) {
        mediaSession.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, state.track?.displayTitle ?: "")
                .putString(MediaMetadata.METADATA_KEY_ARTIST, state.track?.artist ?: "")
                .putString(MediaMetadata.METADATA_KEY_ALBUM, state.track?.album ?: "")
                .putLong(MediaMetadata.METADATA_KEY_DURATION, state.durationMs.toLong())
                .build()
        )
        mediaSession.setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_SKIP_TO_NEXT or
                        PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_SEEK_TO or
                        PlaybackState.ACTION_STOP
                )
                .setState(
                    if (state.isPlaying) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                    state.positionMs.toLong(), 1f,
                )
                .build()
        )
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIF_ID, buildNotification(state))
    }

    private fun buildNotification(state: MusicPlaybackState): Notification {
        val contentIntent = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE)
        }
        val playPauseIcon =
            if (state.isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play

        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(state.track?.displayTitle ?: "")
            .setContentText(listOfNotNull(state.track?.artist, state.track?.album).joinToString(" · "))
            .setContentIntent(contentIntent)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setOngoing(state.isPlaying)
            .addAction(action(android.R.drawable.ic_media_previous, "Previous", ACTION_PREV))
            .addAction(action(playPauseIcon, "Play/Pause", ACTION_PLAY_PAUSE))
            .addAction(action(android.R.drawable.ic_media_next, "Next", ACTION_NEXT))
            .addAction(action(android.R.drawable.ic_menu_close_clear_cancel, "Stop", ACTION_STOP))
            .setStyle(
                Notification.MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .build()
    }

    private fun action(icon: Int, title: String, intentAction: String): Notification.Action {
        val pi = PendingIntent.getService(
            this, intentAction.hashCode(),
            Intent(this, MusicPlaybackService::class.java).setAction(intentAction),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Action.Builder(icon, title, pi).build()
    }

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Music Playback", NotificationManager.IMPORTANCE_LOW)
            .apply { setShowBadge(false); description = "Now-playing controls" }
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(channel)
    }

    override fun onDestroy() {
        scope.cancel()
        mediaSession.release()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "pfp_music_playback"
        private const val NOTIF_ID = 5123
        const val ACTION_SHOW = "com.playfieldportal.music.SHOW"
        const val ACTION_PLAY_PAUSE = "com.playfieldportal.music.PLAY_PAUSE"
        const val ACTION_NEXT = "com.playfieldportal.music.NEXT"
        const val ACTION_PREV = "com.playfieldportal.music.PREV"
        const val ACTION_STOP = "com.playfieldportal.music.STOP"

        /** Promote the currently-playing controller to a foreground notification. */
        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, MusicPlaybackService::class.java).setAction(ACTION_SHOW),
            )
        }
    }
}
