package io.github.saalfy.sur.download

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.StatFs
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.github.saalfy.sur.R
import io.github.saalfy.sur.downloader.AudioDownloader
import io.github.saalfy.sur.downloader.DownloadError
import io.github.saalfy.sur.downloader.DownloadException
import io.github.saalfy.sur.downloader.audioFileName
import io.github.saalfy.sur.downloader.mp4.Mp4Remux
import io.github.saalfy.sur.downloader.mp4.Mp4TagWriter
import io.github.saalfy.sur.downloader.newpipe.NewPipeAudioDownloader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.coroutineContext

class DownloadService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var currentJob: Job? = null
    @Volatile private var isDownloading = false

    internal var downloader: AudioDownloader = NewPipeAudioDownloader()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) return START_NOT_STICKY

        if (intent.action == ACTION_CANCEL) {
            cancelCurrentDownload()
            return START_NOT_STICKY
        }

        val link = intent.getStringExtra(EXTRA_LINK) ?: intent.dataString
        if (link.isNullOrBlank()) {
            if (!isDownloading) stopSelf()
            return START_NOT_STICKY
        }

        if (isDownloading || currentJob?.isActive == true) {
            DownloadManager.emitEvent(DownloadEvent.Busy)
            return START_NOT_STICKY
        }

        startDownload(link)
        return START_NOT_STICKY
    }

    private fun cancelCurrentDownload() {
        currentJob?.cancel()
        currentJob = null
        isDownloading = false
        DownloadManager.updateState(DownloadState.Idle)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startDownload(link: String) {
        isDownloading = true
        DownloadManager.updateState(DownloadState.Resolving)
        createNotificationChannel()

        val initialNotification = buildNotification(
            title = getString(R.string.download_resolving),
            text = null,
            progress = 0,
            maxProgress = 0,
            indeterminate = true,
        )

        val foregroundServiceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, initialNotification, foregroundServiceType)

        val job = serviceScope.launch {
            var rawFile: File? = null
            var remuxedFile: File? = null
            try {
                // 1. Resolve
                val info = withContext(Dispatchers.IO) {
                    downloader.resolve(link)
                }

                // 2. StatFs check (need 2.2x the stream size free)
                val streamSize = info.contentLength
                    ?: ((info.bitrateKbps * 1000L / 8) * info.durationSeconds)
                val neededBytes = (streamSize * 2.2).toLong().coerceAtLeast(1024L)
                val freeBytes = StatFs(cacheDir.path).availableBytes
                if (freeBytes < neededBytes) {
                    throw DownloadException(DownloadError.NotEnoughStorage(neededBytes))
                }

                // Initial determinate notification with video title
                updateNotification(
                    title = info.title,
                    text = info.artist,
                    progress = 0,
                    maxProgress = 100,
                    indeterminate = false,
                )

                // 3. Download to cacheDir
                val timestamp = System.currentTimeMillis()
                val raw = File(cacheDir, "download_${timestamp}_raw.m4a")
                val remuxed = File(cacheDir, "download_${timestamp}_remux.m4a")
                rawFile = raw
                remuxedFile = remuxed

                DownloadManager.updateState(DownloadState.Downloading(0f))
                var lastProgressPct = -1
                withContext(Dispatchers.IO) {
                    downloader.download(info, raw) { done, total ->
                        val progressRatio = if (total != null && total > 0) {
                            (done.toDouble() / total.toDouble()).toFloat().coerceIn(0f, 1f)
                        } else {
                            0f
                        }
                        val pct = (progressRatio * 100).toInt()
                        if (pct != lastProgressPct) {
                            lastProgressPct = pct
                            DownloadManager.updateState(DownloadState.Downloading(progressRatio))
                            updateNotification(
                                title = info.title,
                                text = info.artist,
                                progress = pct,
                                maxProgress = 100,
                                indeterminate = false,
                            )
                        }
                    }
                }

                // 4. Mp4Remux
                DownloadManager.updateState(DownloadState.Saving)
                updateNotification(
                    title = info.title,
                    text = info.artist,
                    progress = 100,
                    maxProgress = 100,
                    indeterminate = false,
                )
                try {
                    withContext(Dispatchers.IO) {
                        Mp4Remux.remuxAudio(raw, remuxed)
                    }
                } catch (t: Throwable) {
                    Log.w("SurDownload", "Mp4Remux failed, falling back to raw M4A stream", t)
                    raw.copyTo(remuxed, overwrite = true)
                }

                // 5. Mp4TagWriter (with fallback)
                try {
                    withContext(Dispatchers.IO) {
                        Mp4TagWriter.write(remuxed, info.title, info.artist, DOWNLOAD_ALBUM)
                    }
                } catch (t: Throwable) {
                    Log.w("SurDownload", "Mp4TagWriter.write failed", t)
                }

                // 6. AudioFileSaver.save
                val fileName = audioFileName(info.title, info.artist)
                val saved = AudioFileSaver(this@DownloadService)
                    .save(remuxed, fileName, info.title, info.artist, DOWNLOAD_ALBUM)

                DownloadManager.updateState(DownloadState.Done(saved.songId, info.title))
            } catch (e: CancellationException) {
                if (currentJob === coroutineContext[Job]) {
                    DownloadManager.updateState(DownloadState.Idle)
                }
                throw e
            } catch (e: DownloadException) {
                Log.e("SurDownload", "download failed", e)
                if (currentJob === coroutineContext[Job]) {
                    DownloadManager.updateState(DownloadState.Error(e.error))
                }
            } catch (t: Throwable) {
                Log.e("SurDownload", "download failed", t)
                if (currentJob === coroutineContext[Job]) {
                    DownloadManager.updateState(DownloadState.Error(DownloadError.Failed))
                }
            } finally {
                rawFile?.delete()
                remuxedFile?.delete()
                if (currentJob === coroutineContext[Job]) {
                    isDownloading = false
                    currentJob = null
                    ServiceCompat.stopForeground(this@DownloadService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
        currentJob = job
    }

    private fun buildNotification(
        title: String,
        text: String?,
        progress: Int,
        maxProgress: Int,
        indeterminate: Boolean,
    ): Notification {
        val cancelIntent = Intent(this, DownloadService::class.java).apply {
            action = ACTION_CANCEL
        }
        val cancelPendingIntent = PendingIntent.getService(
            this,
            0,
            cancelIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .apply { if (!text.isNullOrBlank()) setContentText(text) }
            .setSmallIcon(R.drawable.ic_music_note)
            .setProgress(maxProgress, progress, indeterminate)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(R.drawable.ic_close, getString(R.string.cancel), cancelPendingIntent)
            .build()
    }

    private fun updateNotification(
        title: String,
        text: String?,
        progress: Int,
        maxProgress: Int,
        indeterminate: Boolean,
    ) {
        val canPost = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (canPost) {
            val notification = buildNotification(title, text, progress, maxProgress, indeterminate)
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.download_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.download_channel_description)
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_LINK = "link"
        const val ACTION_CANCEL = "io.github.saalfy.sur.download.action.CANCEL"
        private const val NOTIFICATION_ID = 4001
        private const val CHANNEL_ID = "sur_download_channel"
        private const val DOWNLOAD_ALBUM = "Sur downloads"

        fun start(context: Context, link: String) {
            val intent = Intent(context, DownloadService::class.java).apply {
                putExtra(EXTRA_LINK, link)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun cancel(context: Context) {
            val intent = Intent(context, DownloadService::class.java).apply {
                action = ACTION_CANCEL
            }
            context.startService(intent)
        }
    }
}
