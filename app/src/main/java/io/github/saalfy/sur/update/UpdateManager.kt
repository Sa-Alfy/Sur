package io.github.saalfy.sur.update

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Persisted updater state. Small key/value data only; no files. */
interface UpdateStore {
    /** User setting: check on app start. Manual checks always work. */
    var autoCheck: Boolean
    var lastSuccessfulCheckMs: Long
    var lastAttemptMs: Long
    /** Raw signed envelope of the last fetched manifest; re-verified on every load. */
    var cachedEnvelope: String?
    /** Highest versionCode the user was already told about (non-mandatory notice shows once). */
    var notifiedVersionCode: Long
    /** versionCode handed to the system installer; used to report "updated" after restart. */
    var pendingInstallVersionCode: Long
}

/** What the APK file on disk claims to be, read by the platform before handing it to the installer. */
data class ApkIdentity(
    val packageName: String,
    val versionCode: Long,
    /** SHA-256 of the signing certificates, or null when Android can't report them for an archive. */
    val signerSha256: Set<String>?,
)

/** Android-specific facts, behind an interface so the state machine is testable on the JVM. */
interface UpdatePlatform {
    val installedVersionCode: Long
    val installedVersionName: String
    val sdkInt: Int
    val packageName: String
    /** SHA-256 of the installed app's signing certificates, or null if unknown. */
    val installedSignerSha256: Set<String>?
    fun canRequestPackageInstalls(): Boolean
    /** null if the archive can't be read. */
    fun inspectApk(file: File): ApkIdentity?
}

sealed interface UpdateStatus {
    data object Idle : UpdateStatus
    data object Checking : UpdateStatus
    data object UpToDate : UpdateStatus
    data class Available(val manifest: UpdateManifest) : UpdateStatus
    data class InstalledTooOld(val manifest: UpdateManifest) : UpdateStatus
    data class DeviceUnsupported(val manifest: UpdateManifest) : UpdateStatus
    data class Downloading(val manifest: UpdateManifest, val bytes: Long, val total: Long) : UpdateStatus
    data class DownloadInterrupted(val manifest: UpdateManifest, val failure: NetworkFailure, val bytes: Long) : UpdateStatus
    data class DownloadFailed(val manifest: UpdateManifest, val problem: DownloadProblem) : UpdateStatus
    data class ReadyToInstall(val manifest: UpdateManifest) : UpdateStatus
    data class InstallPermissionRequired(val manifest: UpdateManifest) : UpdateStatus
    data class InstallCancelled(val manifest: UpdateManifest) : UpdateStatus
    data class InstallFailed(val manifest: UpdateManifest) : UpdateStatus
}

/** Last manual check failure; the status keeps showing what was known before. */
data class CheckError(val network: NetworkFailure?, val rejected: ManifestRejectedException.Reason?)

enum class DownloadProblem { CHECKSUM_MISMATCH, SIZE_MISMATCH, WRONG_PACKAGE, WRONG_SIGNER, NOT_ENOUGH_SPACE, STORAGE }

/** What the UI should do when the user taps Install. */
sealed interface InstallStep {
    data class Launch(val file: File) : InstallStep
    data object NeedsPermission : InstallStep
    data object NotReady : InstallStep
}

/**
 * Update state machine: check (with cooldown), download, verify, hand over to the installer.
 * Never blocks startup; every failure lands in a status, never a crash. Playback and music
 * downloads are untouched: separate client, separate files, separate state.
 */
class UpdateManager(
    private val scope: CoroutineScope,
    private val checker: UpdateChecker,
    private val parser: UpdateManifestParser,
    private val downloader: ApkDownloader,
    private val store: UpdateStore,
    private val platform: UpdatePlatform,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val _status = MutableStateFlow<UpdateStatus>(UpdateStatus.Idle)
    val status: StateFlow<UpdateStatus> = _status.asStateFlow()

    /** Set when the UI should show a one-off "update available" notice. */
    private val _notice = MutableStateFlow<UpdateManifest?>(null)
    val notice: StateFlow<UpdateManifest?> = _notice.asStateFlow()

    private val _checkError = MutableStateFlow<CheckError?>(null)
    val checkError: StateFlow<CheckError?> = _checkError.asStateFlow()

    /** True once after the app restarts on the version that was handed to the installer. */
    private val _justUpdated = MutableStateFlow(false)
    val justUpdated: StateFlow<Boolean> = _justUpdated.asStateFlow()

    private val lock = Any()
    private var checkJob: Job? = null
    private var downloadJob: Job? = null
    private var mandatoryNoticeShown = false
    private var restored = false

    val installedVersionName: String get() = platform.installedVersionName

    /** Loads the cached manifest (re-verified) and cleans stale files. Cheap, no network. */
    fun restore(): Job = scope.launch {
        synchronized(lock) {
            if (restored) return@launch
            restored = true
        }
        withContext(Dispatchers.IO) {
            val pending = store.pendingInstallVersionCode
            if (pending > 0 && platform.installedVersionCode >= pending) {
                store.pendingInstallVersionCode = 0
                _justUpdated.value = true
            }
            val cached = store.cachedEnvelope?.let { env ->
                try {
                    parser.parse(env.toByteArray(Charsets.UTF_8))
                } catch (_: ManifestRejectedException) {
                    store.cachedEnvelope = null
                    null
                }
            }
            val offer = cached?.takeIf { evaluate(it) is UpdateAvailability.Available }
            downloader.deleteAllExcept(offer)
            if (cached != null && _status.value == UpdateStatus.Idle) {
                applyAvailability(cached, fromAutoCheck = true)
            }
        }
    }

    /** App start: checks only if the cooldown passed. Failures stay quiet. */
    fun checkIfDue(): Job? {
        if (!store.autoCheck) return null
        val now = clock()
        val sinceSuccess = now - store.lastSuccessfulCheckMs
        val sinceAttempt = now - store.lastAttemptMs
        // A clock set backwards makes the difference negative: treat as due.
        val due = (sinceSuccess !in 0 until CHECK_INTERVAL_MS) && (sinceAttempt !in 0 until RETRY_INTERVAL_MS)
        return if (due) check(manual = false) else null
    }

    fun checkNow(): Job? = check(manual = true)

    private val _autoCheck = MutableStateFlow(store.autoCheck)
    val autoCheck: StateFlow<Boolean> = _autoCheck.asStateFlow()

    fun setAutoCheck(enabled: Boolean) {
        store.autoCheck = enabled
        _autoCheck.value = enabled
    }

    private fun check(manual: Boolean): Job? = synchronized(lock) {
        if (checkJob?.isActive == true || downloadJob?.isActive == true) return null
        val before = _status.value
        if (manual) {
            _status.value = UpdateStatus.Checking
            _checkError.value = null
        }
        scope.launch {
            store.lastAttemptMs = clock()
            val error = when (val result = checker.check()) {
                is CheckResult.Fetched -> {
                    store.lastSuccessfulCheckMs = clock()
                    store.cachedEnvelope = String(result.envelope, Charsets.UTF_8)
                    applyAvailability(result.manifest, fromAutoCheck = !manual)
                    withContext(Dispatchers.IO) {
                        downloader.deleteAllExcept(result.manifest.takeIf { evaluate(it) is UpdateAvailability.Available })
                    }
                    null
                }
                is CheckResult.NetworkError -> CheckError(result.failure, null)
                is CheckResult.Rejected -> CheckError(null, result.reason)
            }
            if (error != null && manual) {
                _checkError.value = error
                _status.value = before
            }
        }.also { checkJob = it }
    }

    private suspend fun applyAvailability(m: UpdateManifest, fromAutoCheck: Boolean) {
        when (val a = evaluate(m)) {
            UpdateAvailability.UpToDate -> _status.value = UpdateStatus.UpToDate
            is UpdateAvailability.InstalledTooOld -> _status.value = UpdateStatus.InstalledTooOld(m)
            is UpdateAvailability.DeviceUnsupported -> _status.value = UpdateStatus.DeviceUnsupported(m)
            is UpdateAvailability.Available -> {
                val ready = withContext(Dispatchers.IO) { downloader.verifiedApk(a.manifest) }
                _status.value = if (ready != null) UpdateStatus.ReadyToInstall(m) else UpdateStatus.Available(m)
                if (fromAutoCheck) maybeNotify(m)
            }
        }
    }

    private fun maybeNotify(m: UpdateManifest) {
        val show = if (m.mandatory) !mandatoryNoticeShown else m.versionCode > store.notifiedVersionCode
        if (show) _notice.value = m
    }

    fun noticeShown() {
        val m = _notice.value ?: return
        if (m.mandatory) mandatoryNoticeShown = true
        store.notifiedVersionCode = maxOf(store.notifiedVersionCode, m.versionCode)
        _notice.value = null
    }

    fun clearJustUpdated() {
        _justUpdated.value = false
    }

    /** Starts or resumes the APK download. A second call while one runs does nothing. */
    fun startDownload(): Job? = synchronized(lock) {
        if (downloadJob?.isActive == true) return null
        val m = when (val s = _status.value) {
            is UpdateStatus.Available -> s.manifest
            is UpdateStatus.DownloadInterrupted -> s.manifest
            is UpdateStatus.DownloadFailed -> s.manifest
            is UpdateStatus.InstallFailed -> s.manifest
            else -> return null
        }
        if (evaluate(m) !is UpdateAvailability.Available) return null
        _status.value = UpdateStatus.Downloading(m, downloader.partFile(m).length(), m.apkSize)
        scope.launch {
            val result = downloader.download(m) { bytes, total ->
                _status.update { if (it is UpdateStatus.Downloading) UpdateStatus.Downloading(m, bytes, total) else it }
            }
            _status.value = when (result) {
                is ApkDownloadResult.Verified -> checkIdentity(m, result.file)
                ApkDownloadResult.Busy -> _status.value
                is ApkDownloadResult.Interrupted -> UpdateStatus.DownloadInterrupted(m, result.failure, result.bytesSoFar)
                is ApkDownloadResult.Invalid -> UpdateStatus.DownloadFailed(
                    m,
                    when (result.problem) {
                        ApkDownloadResult.Problem.SIZE_MISMATCH -> DownloadProblem.SIZE_MISMATCH
                        ApkDownloadResult.Problem.CHECKSUM_MISMATCH -> DownloadProblem.CHECKSUM_MISMATCH
                    },
                )
                is ApkDownloadResult.StorageError -> UpdateStatus.DownloadFailed(
                    m,
                    if (result.notEnoughSpace) DownloadProblem.NOT_ENOUGH_SPACE else DownloadProblem.STORAGE,
                )
            }
        }.also { downloadJob = it }
    }

    /**
     * The SHA-256 match proves the file is the one the signed manifest names (integrity). This
     * separately checks what the APK itself declares: package, versionCode and, where Android can
     * report it, the signing certificate. Android's installer still enforces the certificate.
     */
    private suspend fun checkIdentity(m: UpdateManifest, file: File): UpdateStatus {
        val id = withContext(Dispatchers.IO) { platform.inspectApk(file) }
        val problem = when {
            id == null || id.packageName != platform.packageName || id.versionCode != m.versionCode ->
                DownloadProblem.WRONG_PACKAGE
            id.signerSha256 != null && platform.installedSignerSha256 != null &&
                id.signerSha256.intersect(platform.installedSignerSha256!!).isEmpty() ->
                DownloadProblem.WRONG_SIGNER
            else -> null
        }
        if (problem != null) {
            withContext(Dispatchers.IO) { file.delete() }
            return UpdateStatus.DownloadFailed(m, problem)
        }
        return UpdateStatus.ReadyToInstall(m)
    }

    /** Cancels the download and deletes the partial file. */
    fun cancelDownload(): Job = scope.launch {
        val job = synchronized(lock) { downloadJob }
        job?.cancel()
        job?.join()
        val s = _status.value
        val m = s.manifestOrNull() ?: return@launch
        withContext(Dispatchers.IO) { downloader.deletePartial(m) }
        if (s is UpdateStatus.Downloading || s is UpdateStatus.DownloadInterrupted) {
            _status.value = UpdateStatus.Available(m)
        }
    }

    /** Re-verifies the file right before install so a modified file is never handed over. */
    suspend fun prepareInstall(): InstallStep {
        val m = when (val s = _status.value) {
            is UpdateStatus.ReadyToInstall -> s.manifest
            is UpdateStatus.InstallPermissionRequired -> s.manifest
            is UpdateStatus.InstallCancelled -> s.manifest
            else -> return InstallStep.NotReady
        }
        val file = withContext(Dispatchers.IO) { downloader.verifiedApk(m) }
        if (file == null) {
            _status.value = UpdateStatus.Available(m)
            return InstallStep.NotReady
        }
        if (!platform.canRequestPackageInstalls()) {
            _status.value = UpdateStatus.InstallPermissionRequired(m)
            return InstallStep.NeedsPermission
        }
        val identity = checkIdentity(m, file)
        if (identity !is UpdateStatus.ReadyToInstall) {
            _status.value = identity
            return InstallStep.NotReady
        }
        store.pendingInstallVersionCode = m.versionCode
        _status.value = UpdateStatus.ReadyToInstall(m)
        return InstallStep.Launch(file)
    }

    /** Called on resume: the user may have just granted (or not) the install permission. */
    fun recheckInstallPermission() {
        val s = _status.value
        if (s is UpdateStatus.InstallPermissionRequired && platform.canRequestPackageInstalls()) {
            _status.value = UpdateStatus.ReadyToInstall(s.manifest)
        }
    }

    /**
     * Result from the system installer. Success is not reported here: on a self-update Android
     * restarts the app, and [restore] reports it once the new version is actually running.
     */
    fun onInstallerResult(outcome: InstallerOutcome) {
        val m = _status.value.manifestOrNull() ?: return
        when (outcome) {
            InstallerOutcome.Cancelled -> {
                store.pendingInstallVersionCode = 0
                _status.value = UpdateStatus.InstallCancelled(m)
            }
            InstallerOutcome.Failed -> {
                store.pendingInstallVersionCode = 0
                _status.value = UpdateStatus.InstallFailed(m)
            }
            InstallerOutcome.Succeeded -> Unit
        }
    }

    private fun evaluate(m: UpdateManifest) = evaluateUpdate(m, platform.installedVersionCode, platform.sdkInt)

    companion object {
        const val CHECK_INTERVAL_MS = 12L * 60 * 60 * 1000
        const val RETRY_INTERVAL_MS = 60L * 60 * 1000
    }
}

enum class InstallerOutcome { Succeeded, Cancelled, Failed }

fun UpdateStatus.manifestOrNull(): UpdateManifest? = when (this) {
    is UpdateStatus.Available -> manifest
    is UpdateStatus.InstalledTooOld -> manifest
    is UpdateStatus.DeviceUnsupported -> manifest
    is UpdateStatus.Downloading -> manifest
    is UpdateStatus.DownloadInterrupted -> manifest
    is UpdateStatus.DownloadFailed -> manifest
    is UpdateStatus.ReadyToInstall -> manifest
    is UpdateStatus.InstallPermissionRequired -> manifest
    is UpdateStatus.InstallCancelled -> manifest
    is UpdateStatus.InstallFailed -> manifest
    UpdateStatus.Idle, UpdateStatus.Checking, UpdateStatus.UpToDate -> null
}
