package io.github.saalfy.sur.update

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.core.content.pm.PackageInfoCompat
import java.io.File
import java.security.MessageDigest

class AndroidUpdatePlatform(private val context: Context) : UpdatePlatform {
    private val pm: PackageManager get() = context.packageManager

    private val selfInfo: PackageInfo? by lazy {
        runCatching { pm.getPackageInfo(context.packageName, signingFlags()) }.getOrNull()
    }

    override val packageName: String get() = context.packageName
    override val installedVersionCode: Long get() = selfInfo?.let { PackageInfoCompat.getLongVersionCode(it) } ?: 0L
    override val installedVersionName: String get() = selfInfo?.versionName.orEmpty()
    override val sdkInt: Int get() = Build.VERSION.SDK_INT
    override val installedSignerSha256: Set<String>? get() = selfInfo?.let(::signers)

    override fun canRequestPackageInstalls(): Boolean = pm.canRequestPackageInstalls()

    override fun inspectApk(file: File): ApkIdentity? {
        val info = runCatching { pm.getPackageArchiveInfo(file.path, signingFlags()) }.getOrNull() ?: return null
        return ApkIdentity(info.packageName, PackageInfoCompat.getLongVersionCode(info), signers(info))
    }

    @Suppress("DEPRECATION")
    private fun signingFlags(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES

    @Suppress("DEPRECATION")
    private fun signers(info: PackageInfo): Set<String>? {
        val sigs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.let { if (it.hasMultipleSigners()) it.apkContentsSigners else it.signingCertificateHistory }
        } else {
            info.signatures
        }
        if (sigs.isNullOrEmpty()) return null
        return sigs.map { sig ->
            MessageDigest.getInstance("SHA-256").digest(sig.toByteArray()).joinToString("") { "%02x".format(it) }
        }.toSet()
    }

    fun isOnline(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return true
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}

class PrefsUpdateStore(context: Context) : UpdateStore {
    private val prefs = context.applicationContext.getSharedPreferences("sur_updates", Context.MODE_PRIVATE)

    override var autoCheck: Boolean
        get() = prefs.getBoolean("auto_check", true)
        set(v) = prefs.edit { putBoolean("auto_check", v) }
    override var lastSuccessfulCheckMs: Long
        get() = prefs.getLong("last_success_ms", 0)
        set(v) = prefs.edit { putLong("last_success_ms", v) }
    override var lastAttemptMs: Long
        get() = prefs.getLong("last_attempt_ms", 0)
        set(v) = prefs.edit { putLong("last_attempt_ms", v) }
    override var cachedEnvelope: String?
        get() = prefs.getString("cached_envelope", null)
        set(v) = prefs.edit { putString("cached_envelope", v) }
    override var notifiedVersionCode: Long
        get() = prefs.getLong("notified_version_code", 0)
        set(v) = prefs.edit { putLong("notified_version_code", v) }
    override var pendingInstallVersionCode: Long
        get() = prefs.getLong("pending_install_version_code", 0)
        set(v) = prefs.edit { putLong("pending_install_version_code", v) }
}

/** Intents for Android's own installer and the "install unknown apps" setting. No silent install. */
object ApkInstaller {
    const val MIME_APK = "application/vnd.android.package-archive"

    fun authority(context: Context) = "${context.packageName}.updates"

    /** Content URI from our FileProvider; only `files/updates/` is exposed, read-only, per grant. */
    @Suppress("DEPRECATION") // ACTION_INSTALL_PACKAGE still works and is the only intent that reports a result.
    fun installIntent(context: Context, apk: File): Intent {
        val uri = FileProvider.getUriForFile(context, authority(context), apk)
        return Intent(Intent.ACTION_INSTALL_PACKAGE)
            .setDataAndType(uri, MIME_APK)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .putExtra(Intent.EXTRA_RETURN_RESULT, true)
    }

    /** Per-app "Install unknown apps" screen (Android 8+). */
    fun permissionSettingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${context.packageName}".toUri())

    /** RESULT_FIRST_USER (with EXTRA_RETURN_RESULT) means the installer reported a failure. */
    fun outcome(resultCode: Int): InstallerOutcome = when (resultCode) {
        Activity.RESULT_OK -> InstallerOutcome.Succeeded
        Activity.RESULT_CANCELED -> InstallerOutcome.Cancelled
        else -> InstallerOutcome.Failed
    }
}
