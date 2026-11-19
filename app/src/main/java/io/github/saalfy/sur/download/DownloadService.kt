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
import io.github.saalfy.sur.SurApplication
import io.github.saalfy.sur.downloader.AudioDownloader
import io.github.saalfy.sur.downloader.DownloadError
import io.github.saalfy.sur.downloader.DownloadException
import io.github.saalfy.sur.downloader.audioFileName
import io.github.saalfy.sur.downloader.mp4.Mp4Remux
import io.github.saalfy.sur.downloader.mp4.Mp4TagWriter
import io.github.saalfy.sur.downloader.mp4.hasReadableAudio
import io.github.saalfy.sur.downloader.mp4.looksLikeMp4
import io.github.saalfy.sur.downloader.newpipe.NewPipeAudioDownloader
import io.github.saalfy.sur.data.info.applyDownloadOverlay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.coroutineContext

class DownloadService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var currentJob: Job? = null
    @Volatile private var isDownloading = false
    /** Latest start id; stopSelf(id) then never stops a service that has a newer start pending. */
    private var lastStartId = 0

    internal var downloader: AudioDownloader = NewPipeAudioDownloader()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        if (intent == null) return START_NOT_STICKY

        if (intent.action == ACTION_CANCEL) {
            cancelCurrentDownload()
            return START_NOT_STICKY
        }

        val link = intent.getStringExtra(EXTRA_LINK) ?: intent.dataString
        if (link.isNullOrBlank()) {
            if (!isDownloading) stopSelf(startId)
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
        DownloadManager.cancelActive()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf(lastStartId)
    }

    private fun startDownload(link: String) {
        isDownloading = true
        val token = DownloadManager.begin()
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
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, initialNotification, foregroundServiceType)
        } catch (t: Exception) {
            // R2-05: e.g. ForegroundServiceStartNotAllowedException. Without this the state
            // stayed Resolving forever and every later start was rejected as Busy.
            Log.e("SurDownload", "startForeground failed", t)
            isDownloading = false
            DownloadManager.update(token, DownloadState.Error(DownloadError.Failed, technicalSummary(t)))
            stopSelf(lastStartId)
            return
        }

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
                if (DownloadManager.isOwner(token)) updateNotification(
                    title = info.title,
                    text = info.artist,
                    progress = 0,
                    maxProgress = 100,
                    indeterminate = false,
                )

                // 3. Download to cacheDir
                // Only one download runs at a time, so any older temp file was left by a run that
                // was killed (process death skips the finally below).
                withContext(Dispatchers.IO) { deleteStaleDownloadTemps(cacheDir) }
                val timestamp = System.currentTimeMillis()
                val raw = File(cacheDir, "download_${timestamp}_raw.m4a")
                val remuxed = File(cacheDir, "download_${timestamp}_remux.m4a")
                rawFile = raw
                remuxedFile = remuxed

                DownloadManager.update(token, DownloadState.Downloading(0f, info.title, info.artist))
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
                            // R2-11: a late callback from a cancelled or replaced run is dropped.
                            DownloadManager.update(token, DownloadState.Downloading(progressRatio, info.title, info.artist)) {
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
                }

                // 4. Mp4Remux
                DownloadManager.update(token, DownloadState.Saving) {
                    updateNotification(
                        title = info.title,
                        text = info.artist,
                        progress = 100,
                        maxProgress = 100,
                        indeterminate = false,
                    )
                }
                try {
                    // Capture the launch job: currentJob is nulled by
                    // cancelCurrentDownload() right after cancel(), so
                    // polling currentJob here would miss the cancel.
                    val downloadJob = coroutineContext[Job]
                    withContext(Dispatchers.IO) {
                        Mp4Remux.remuxAudio(raw, remuxed) { downloadJob?.isCancelled == true }
                    }
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    // remuxAudio throws InterruptedException when
                    // isCancelled() is true; treat that as cancellation,
                    // not as a remux failure to fall back from.
                    if (t is InterruptedException) throw CancellationException("Remux cancelled").apply { initCause(t) }
                    Log.w("SurDownload", "Mp4Remux failed, falling back to raw M4A stream", t)
                    raw.copyTo(remuxed, overwrite = true)
                }

                // 4b. Reject non-MP4 output (e.g. an HTML error page saved
                // as .m4a). The raw fallback above is still allowed: a
                // valid raw m4a passes this check and is saved.
                // R2-16: the header alone does not prove the audio is usable, so also ask the
                // platform extractor for an audio track and its first sample.
                if (!looksLikeMp4(remuxed) || !withContext(Dispatchers.IO) { hasReadableAudio(remuxed) }) {
                    throw DownloadException(DownloadError.Failed)
                }

                // 5. Fetch thumbnail (never fails the download, but must not swallow CE)
                val coverJpeg = try {
                    withContext(Dispatchers.IO) {
                        downloader.fetchThumbnail(info)
                    }
                } catch (t: Throwable) {
                    // R2-13: CancellationException must propagate so the coroutine
                    // can reach the CE handler and not report Done after cancel.
                    if (t is CancellationException) throw t
                    Log.w("SurDownload", "fetchThumbnail threw unexpectedly", t)
                    null
                }

                // 6. Mp4TagWriter (with fallback)
                try {
                    withContext(Dispatchers.IO) {
                        Mp4TagWriter.write(remuxed, info.title, info.artist, null, coverJpeg)
                    }
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    Log.w("SurDownload", "Mp4TagWriter.write failed", t)
                }

                // 7. AudioFileSaver.save
                val fileName = audioFileName(info.title, info.artist)
                val saved = AudioFileSaver(this@DownloadService)
                    .save(remuxed, fileName, info.title, info.artist, null)

                if (saved.songId > 0) {
                    val container = (applicationContext as? SurApplication)?.container
                    if (container != null) {
                        try {
                            var key: String? = null
                            for (attempt in 0 until 5) {
                                key = container.songKeyResolver.resolveKey(saved.songId)
                                if (key != null) break
                                delay(200)
                            }
                            if (key == null) {
                                Log.w("SurDownload", "Song info/cover overlay skipped: could not resolve song key for songId=${saved.songId} after 5 attempts")
                            }
                            if (key != null) {
                                // R2-01: user-owned metadata and cover win over download data.
                                applyDownloadOverlay(
                                    repository = container.songInfoRepository,
                                    covers = container.coverStore,
                                    key = key,
                                    title = info.title,
                                    artist = info.artist,
                                    thumbnail = coverJpeg,
                                )
                                container.artworkLoader.evict(saved.songId)
                            }
                        } catch (t: Throwable) {
                            // R2-03: CE must not be swallowed here — the outer CE handler resets
                            // DownloadManager state; swallowing it would let Done be reported
                            // after a cancellation.
                            if (t is CancellationException) throw t
                            Log.w("SurDownload", "Failed to apply song info overlay for download", t)
                        }
                    }
                }

                // R2-03: Done only while this run still owns the state (not after a cancel).
                DownloadManager.update(token, DownloadState.Done(saved.songId, info.title))
            } catch (e: CancellationException) {
                DownloadManager.update(token, DownloadState.Idle)
                throw e
            } catch (e: DownloadException) {
                Log.e("SurDownload", "download failed", e)
                DownloadManager.update(token, DownloadState.Error(e.error, technicalSummary(e.cause ?: e)))
            } catch (t: Throwable) {
                Log.e("SurDownload", "download failed", t)
                DownloadManager.update(token, DownloadState.Error(DownloadError.Failed, technicalSummary(t)))
            } finally {
                rawFile?.delete()
                remuxedFile?.delete()
                if (currentJob === coroutineContext[Job]) {
                    isDownloading = false
                    currentJob = null
                    ServiceCompat.stopForeground(this@DownloadService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    stopSelf(lastStartId)
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

/** Short "Class: message" chain (max 3 causes, 300 chars) for the error card. */
internal fun technicalSummary(t: Throwable): String {
    val parts = generateSequence(t) { it.cause }.take(3).map { e ->
        val detail = (e as? DownloadException)?.let { (it.error as? DownloadError.ExtractionFailed)?.detail }
        "${e.javaClass.simpleName}: ${detail ?: e.message ?: "no message"}"
    }.toList()
    return parts.joinToString(" <- ").take(300)
}

private val DOWNLOAD_TEMP_NAME = Regex("""^download_\d+_(raw|remux)\.m4a$""")

/** Deletes temp files of earlier download runs from [dir]; returns how many were removed. */
internal fun deleteStaleDownloadTemps(dir: File): Int =
    dir.listFiles { f -> f.isFile && DOWNLOAD_TEMP_NAME.matches(f.name) }
        ?.count { it.delete() }
        ?: 0
