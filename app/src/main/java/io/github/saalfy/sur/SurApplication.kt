package io.github.saalfy.sur

import android.app.Application
import android.content.Context
import io.github.saalfy.sur.data.MediaStoreSongRepository
import io.github.saalfy.sur.data.SongRepository

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
    val songRepository: SongRepository = MediaStoreSongRepository(context)
}
