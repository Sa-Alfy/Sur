package io.github.saalfy.sur.playback

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import io.github.saalfy.sur.MainActivity

/**
 * Hosts the player. Media3 handles the foreground notification, lock screen and
 * media button controls.
 *
 * onTaskRemoved is intentionally not overridden: Media3's default keeps the service
 * running when swiped from recents only while playback is ongoing in the foreground;
 * otherwise it pauses and stops itself. Activities must release their controllers in
 * onStop (see [PlayerConnection]) or the bound service can't stop.
 */
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
        player.addListener(SkipUnplayableItems(player))

        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(openApp)
            .setCallback(SessionCallback)
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onDestroy() {
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }

    /** A file deleted or unreadable mid-queue: drop that item and continue with the rest. */
    private class SkipUnplayableItems(private val player: ExoPlayer) : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            // 2xxx = IO errors (e.g. file not found), 3xxx = parsing errors (corrupt file).
            if (error.errorCode !in 2000..3999) return
            val index = player.currentMediaItemIndex
            if (index in 0 until player.mediaItemCount) player.removeMediaItem(index)
            if (player.mediaItemCount > 0) player.prepare()
        }
    }

    private object SessionCallback : MediaSession.Callback {
        // Controllers don't send localConfiguration across IPC, so restore the URI here.
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> =
            Futures.immediateFuture(mediaItems.mapTo(mutableListOf()) { it.withPlayableUri() })
    }
}
