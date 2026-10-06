package io.github.saalfy.sur.playback

import android.content.ComponentName
import android.content.Context
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.media3.common.C
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import io.github.saalfy.sur.data.Song
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Connects a [MediaController] to [PlaybackService] while the owner is started.
 * Released in onStop so the service can stop itself when nothing is playing.
 */
class PlayerConnection(context: Context) : DefaultLifecycleObserver {

    private val appContext = context.applicationContext
    private var controllerFuture: ListenableFuture<MediaController>? = null

    private val _controller = MutableStateFlow<MediaController?>(null)

    /** The connected controller, or null while disconnected. The only source of player state for the UI. */
    val controller: StateFlow<MediaController?> = _controller.asStateFlow()

    override fun onStart(owner: LifecycleOwner) {
        val token = SessionToken(appContext, ComponentName(appContext, PlaybackService::class.java))
        val future = MediaController.Builder(appContext, token).buildAsync()
        controllerFuture = future
        future.addListener(
            { if (controllerFuture === future) _controller.value = runCatching { future.get() }.getOrNull() },
            ContextCompat.getMainExecutor(appContext),
        )
    }

    override fun onStop(owner: LifecycleOwner) {
        _controller.value = null
        controllerFuture?.let(MediaController::releaseFuture)
        controllerFuture = null
    }

    fun playAll(songs: List<Song>, startIndex: Int) {
        val future = controllerFuture ?: return
        future.addListener(
            {
                val controller = runCatching { future.get() }.getOrNull() ?: return@addListener
                controller.setMediaItems(songs.map(Song::toMediaItem), startIndex, C.TIME_UNSET)
                controller.prepare()
                controller.play()
            },
            ContextCompat.getMainExecutor(appContext),
        )
    }
}
