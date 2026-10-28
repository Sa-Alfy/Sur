package io.github.saalfy.sur

import android.app.Application
import android.content.Context
import io.github.saalfy.sur.data.AppPreferences
import io.github.saalfy.sur.data.MediaStoreSongRepository
import io.github.saalfy.sur.data.SongRepository
import io.github.saalfy.sur.data.db.SurDatabase
import io.github.saalfy.sur.data.info.CoverStore
import io.github.saalfy.sur.data.info.MediaStoreSongKeyResolver
import io.github.saalfy.sur.data.info.OverlaySongRepository
import io.github.saalfy.sur.data.info.RoomSongInfoRepository
import io.github.saalfy.sur.data.info.SongInfoEntity
import io.github.saalfy.sur.data.info.SongInfoRepository
import io.github.saalfy.sur.data.info.SongKeyResolver
import io.github.saalfy.sur.data.info.SurInfoDatabase
import io.github.saalfy.sur.data.info.lookup.MusicBrainzSongInfoLookup
import io.github.saalfy.sur.data.playlist.PlaylistRepository
import io.github.saalfy.sur.data.playlist.RoomPlaylistRepository
import io.github.saalfy.sur.download.DownloadManager
import io.github.saalfy.sur.ui.artwork.ArtworkLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

class SurApplication : Application() {
    lateinit var container: AppContainer
        private set

    val downloadManager: DownloadManager get() = DownloadManager

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

/** Manual dependency wiring. Kept tiny on purpose; swap implementations here. */
class AppContainer(context: Context) {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val mediaStoreSongs = MediaStoreSongRepository(context, appScope)
    val songKeyResolver: SongKeyResolver = MediaStoreSongKeyResolver(context)
    val coverStore: CoverStore by lazy { CoverStore(context) }

    val songInfoLookup: MusicBrainzSongInfoLookup by lazy {
        val versionName = try {
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            pInfo.versionName ?: "unknown"
        } catch (_: Exception) {
            "unknown"
        }
        MusicBrainzSongInfoLookup(versionName = versionName)
    }

    // Lazy info repository so database is not opened eagerly.
    val songInfoRepository: SongInfoRepository by lazy {
        RoomSongInfoRepository(SurInfoDatabase.build(context).songInfoDao())
    }

    val overlaySongRepository: OverlaySongRepository by lazy {
        OverlaySongRepository(
            delegate = mediaStoreSongs,
            keys = songKeyResolver,
            info = object : SongInfoRepository {
                private val repo by lazy { songInfoRepository }
                override suspend fun get(key: String) = repo.get(key)
                override fun observeAll(): Flow<List<SongInfoEntity>> = flow {
                    emitAll(repo.observeAll())
                }
                override suspend fun apply(
                    key: String,
                    title: String?,
                    artist: String?,
                    album: String?,
                    coverFile: String?,
                    mbRecordingId: String?,
                ) = repo.apply(key, title, artist, album, coverFile, mbRecordingId)
                override suspend fun skip(key: String) = repo.skip(key)
                override suspend fun reset(key: String) = repo.reset(key)
            },
            covers = coverStore,
        ).apply {
            onCoversChanged = { ids ->
                ids.forEach { artworkLoader.evict(it) }
            }
        }
    }

    val songRepository: SongRepository get() = overlaySongRepository

    val artworkLoader: ArtworkLoader by lazy {
        ArtworkLoader(context) { songId -> overlaySongRepository.coverFor(songId) }
    }

    val preferences: AppPreferences by lazy { AppPreferences(context) }

    // Lazy so the playback service starting the process doesn't open the database.
    val playlistRepository: PlaylistRepository by lazy {
        RoomPlaylistRepository(SurDatabase.build(context).playlistDao(), songRepository)
    }

    val downloadManager: DownloadManager get() = DownloadManager
}
