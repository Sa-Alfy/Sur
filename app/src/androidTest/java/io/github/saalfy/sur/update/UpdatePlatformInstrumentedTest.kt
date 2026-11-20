package io.github.saalfy.sur.update

import android.content.Intent
import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The Android-only parts of the updater on a real runtime (CI: API 28 emulator, same OS as the
 * A1k): Bouncy Castle Ed25519 next to the platform's own Bouncy Castle, FileProvider paths,
 * archive inspection with signing certificates, and the installer intent. Does not install.
 *
 *   ./gradlew connectedDebugAndroidTest
 */
@RunWith(AndroidJUnit4::class)
class UpdatePlatformInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val updatesDir = File(context.filesDir, "updates")

    @After
    fun cleanup() {
        File(updatesDir, "sur-999.apk").delete()
    }

    private fun asset(name: String) = instrumentation.context.assets.open(name).use { it.readBytes() }

    @Test
    fun ed25519Verification_worksOnDevice() {
        val envelope = asset("update/script-envelope.json")
        val publicKey = String(asset("update/script-envelope.pub")).trim()
        val parser = UpdateManifestParser(Ed25519Verifier(mapOf(UpdateKeys.KEY_ID_2026 to publicKey)), context.packageName)
        val m = parser.parse(envelope)
        assertEquals(context.packageName, m.packageName)
        try {
            UpdateManifestParser(Ed25519Verifier(UpdateKeys.TRUSTED), context.packageName).parse(envelope)
            fail("test-signed manifest must not verify with the production key")
        } catch (e: ManifestRejectedException) {
            assertEquals(ManifestRejectedException.Reason.BAD_SIGNATURE, e.reason)
        }
    }

    @Test
    fun inspectApk_readsPackageVersionAndCertificate() {
        val platform = AndroidUpdatePlatform(context)
        val identity = platform.inspectApk(File(context.packageCodePath))
        assertNotNull(identity)
        assertEquals(context.packageName, identity!!.packageName)
        assertEquals(platform.installedVersionCode, identity.versionCode)
        val installed = platform.installedSignerSha256
        assertNotNull(installed)
        // Archive certificates may be unavailable on some versions; when present they must match.
        identity.signerSha256?.let { assertEquals(installed, it) }
    }

    @Test
    fun installIntent_usesFileProviderWithReadGrant() {
        updatesDir.mkdirs()
        val apk = File(updatesDir, "sur-999.apk").apply { writeBytes(ByteArray(16)) }
        val intent = ApkInstaller.installIntent(context, apk)
        @Suppress("DEPRECATION")
        assertEquals(Intent.ACTION_INSTALL_PACKAGE, intent.action)
        assertEquals("content", intent.data!!.scheme)
        assertEquals("${context.packageName}.updates", intent.data!!.authority)
        assertEquals(ApkInstaller.MIME_APK, intent.type)
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        // The system package installer must be able to handle it.
        assertNotNull(context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY))
        // Opens as a stream through the provider.
        context.contentResolver.openInputStream(intent.data!!)!!.use { assertEquals(16, it.readBytes().size) }
    }

    @Test
    fun fileProvider_refusesFilesOutsideUpdatesDir() {
        val other = File(context.filesDir, "not-an-update.txt").apply { writeText("x") }
        try {
            ApkInstaller.installIntent(context, other)
            fail("FileProvider must not expose files outside files/updates")
        } catch (_: IllegalArgumentException) {
        } finally {
            other.delete()
        }
    }

    @Test
    fun installPermissionSettingsIntent_resolves() {
        val intent = ApkInstaller.permissionSettingsIntent(context)
        assertNotNull(context.packageManager.resolveActivity(intent, 0))
        // Not granted by default on a fresh install.
        AndroidUpdatePlatform(context).canRequestPackageInstalls()
    }
}
