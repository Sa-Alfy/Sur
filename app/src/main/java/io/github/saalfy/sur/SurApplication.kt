package io.github.saalfy.sur

import android.app.Application
import android.content.Context
import io.github.saalfy.sur.data.MediaStoreSongRepository
import io.github.saalfy.sur.data.SongRepository
import io.github.saalfy.sur.data.db.SurDatabase
import io.github.saalfy.sur.data.playlist.PlaylistRepository
import io.github.saalfy.sur.data.playlist.RoomPlaylistRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class SurApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

/** Manual dependency wiring. Kept tiny on purpose; swap implementations here. */
class AppContainer(context: Context) {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val songRepository: SongRepository = MediaStoreSongRepository(context, appScope)

    // Lazy so the playback service starting the process doesn't open the database.
    val playlistRepository: PlaylistRepository by lazy {
        RoomPlaylistRepository(SurDatabase.build(context).playlistDao(), songRepository)
    }
}
