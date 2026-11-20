package io.github.saalfy.sur.update

import io.github.saalfy.sur.update.ManifestRejectedException.Reason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.LocalDate
import java.util.Base64

class UpdateManifestParserTest {
    private val signer = TestSigner()
    private val parser = signer.parser()

    private fun parse(fields: Map<String, Any?>) = parser.parse(signer.envelope(fields))

    private fun assertRejected(expected: Reason, block: () -> Unit) {
        try {
            block()
            fail("expected rejection $expected")
        } catch (e: ManifestRejectedException) {
            assertEquals(e.message, expected, e.reason)
        }
    }

    @Test
    fun validManifest_parsesAllFields() {
        val m = parse(manifestFields(overrides = mapOf("mandatory" to true)))
        assertEquals(1, m.schema)
        assertEquals(PKG, m.packageName)
        assertEquals("stable", m.channel)
        assertEquals(10L, m.versionCode)
        assertEquals("1.1.0", m.versionName)
        assertEquals(LocalDate.of(2026, 11, 20), m.releaseDate)
        assertEquals(1L, m.minUpdatableVersionCode)
        assertEquals(26, m.minSdk)
        assertTrue(m.mandatory)
        assertEquals("Line one\nLine \"two\"", m.releaseNotes)
        assertEquals(APK_URL, m.apkUrl.toString())
        assertEquals(TEST_APK_SHA, m.apkSha256)
        assertEquals(TEST_APK.size.toLong(), m.apkSize)
    }

    @Test
    fun optionalFields_haveSafeDefaults() {
        val m = parse(manifestFields(overrides = mapOf("mandatory" to null, "releaseNotes" to null, "minUpdatableVersionCode" to null, "minSdk" to null)))
        assertFalse(m.mandatory)
        assertEquals("", m.releaseNotes)
        assertEquals(1L, m.minUpdatableVersionCode)
    }

    @Test
    fun uppercaseChecksum_isNormalised() {
        val m = parse(manifestFields(apkOverrides = mapOf("sha256" to TEST_APK_SHA.uppercase())))
        assertEquals(TEST_APK_SHA, m.apkSha256)
    }

    @Test
    fun tamperedPayload_failsSignature() {
        val payload = json(manifestFields()).toByteArray()
        val sig = signer.sign(payload)
        val tampered = json(manifestFields(versionCode = 99)).toByteArray()
        assertRejected(Reason.BAD_SIGNATURE) { parser.parse(signer.envelope(tampered, signature = sig)) }
    }

    @Test
    fun signatureFromAnotherKey_isRejected() {
        val other = TestSigner()
        assertRejected(Reason.BAD_SIGNATURE) { parser.parse(other.envelope(manifestFields())) }
    }

    @Test
    fun unknownKeyId_isRejected() {
        val payload = json(manifestFields()).toByteArray()
        assertRejected(Reason.UNKNOWN_KEY) { parser.parse(signer.envelope(payload, keyId = "someone-else")) }
    }

    @Test
    fun truncatedSignature_isRejected() {
        val payload = json(manifestFields()).toByteArray()
        assertRejected(Reason.BAD_SIGNATURE) {
            parser.parse(signer.envelope(payload, signature = signer.sign(payload).copyOf(63)))
        }
    }

    @Test
    fun malformedInputs_areRejected() {
        assertRejected(Reason.MALFORMED) { parser.parse("not json".toByteArray()) }
        assertRejected(Reason.MALFORMED) { parser.parse("[]".toByteArray()) }
        assertRejected(Reason.MALFORMED) { parser.parse("""{"keyId":"test-key","payload":"%%%","signature":"AA=="}""".toByteArray()) }
        assertRejected(Reason.MALFORMED) { parser.parse("""{"keyId":"test-key","signature":"AA=="}""".toByteArray()) }
        assertRejected(Reason.MALFORMED) { parser.parse(byteArrayOf(0xC3.toByte(), 0x28)) }
        // Signed, but the payload itself is not JSON.
        assertRejected(Reason.MALFORMED) { parser.parse(signer.envelope("hello".toByteArray())) }
        // Signed, but without the apk object.
        assertRejected(Reason.MALFORMED) { parse(manifestFields(overrides = mapOf("apk" to null))) }
    }

    @Test
    fun oversizedManifest_isRejectedBeforeParsing() {
        assertRejected(Reason.TOO_LARGE) { parser.parse(ByteArray(UpdateManifestParser.MAX_MANIFEST_BYTES + 1) { ' '.code.toByte() }) }
    }

    @Test
    fun unsupportedSchema_isRejected() {
        assertRejected(Reason.UNSUPPORTED_SCHEMA) { parse(manifestFields(overrides = mapOf("schema" to 2))) }
        assertRejected(Reason.MALFORMED) { parse(manifestFields(overrides = mapOf("schema" to null))) }
    }

    @Test
    fun wrongPackage_isRejected() {
        assertRejected(Reason.WRONG_PACKAGE) { parse(manifestFields(overrides = mapOf("packageName" to "com.evil.sur"))) }
    }

    @Test
    fun unsupportedChannel_isRejected() {
        assertRejected(Reason.UNSUPPORTED_CHANNEL) { parse(manifestFields(overrides = mapOf("channel" to "beta"))) }
    }

    @Test
    fun invalidVersions_areRejected() {
        assertRejected(Reason.INVALID_VERSION) { parse(manifestFields(versionCode = 0)) }
        assertRejected(Reason.INVALID_VERSION) { parse(manifestFields(versionCode = 3_000_000_000)) }
        assertRejected(Reason.INVALID_VERSION) { parse(manifestFields(overrides = mapOf("versionCode" to 10.5))) }
        assertRejected(Reason.INVALID_VERSION) { parse(manifestFields(overrides = mapOf("versionCode" to "10"))) }
        assertRejected(Reason.INVALID_VERSION) { parse(manifestFields(overrides = mapOf("versionName" to "1.1 <b>"))) }
        assertRejected(Reason.INVALID_VERSION) { parse(manifestFields(overrides = mapOf("minUpdatableVersionCode" to 11))) }
    }

    @Test
    fun insecureOrForeignUrls_areRejected() {
        assertRejected(Reason.INSECURE_URL) { parse(manifestFields(apkOverrides = mapOf("url" to APK_URL.replace("https", "http")))) }
        val bad = listOf(
            "ftp://github.com/Sa-Alfy/Sur/releases/download/v1/a.apk",
            "https://evil.example/Sa-Alfy/Sur/releases/download/v1/a.apk",
            "https://github.com.evil.example/Sa-Alfy/Sur/releases/download/v1/a.apk",
            "https://github.com/someone/Other/releases/download/v1/a.apk",
            "https://github.com/Sa-Alfy/Sur/releases/download/v1/a.apk?x=1",
            "https://github.com/Sa-Alfy/Sur/releases/download/v1/a.zip",
            "https://user:pw@github.com/Sa-Alfy/Sur/releases/download/v1/a.apk",
            "https://github.com:8443/Sa-Alfy/Sur/releases/download/v1/a.apk",
            "https://github.com/Sa-Alfy/Sur/releases/download/../../x/a.apk",
            "not a url",
        )
        for (url in bad) {
            assertRejected(Reason.INVALID_URL) { parse(manifestFields(apkOverrides = mapOf("url" to url))) }
        }
    }

    @Test
    fun missingOrInvalidChecksum_isRejected() {
        assertRejected(Reason.MISSING_CHECKSUM) { parse(manifestFields(apkOverrides = mapOf("sha256" to null))) }
        assertRejected(Reason.MISSING_CHECKSUM) { parse(manifestFields(apkOverrides = mapOf("sha256" to ""))) }
        assertRejected(Reason.INVALID_CHECKSUM) { parse(manifestFields(apkOverrides = mapOf("sha256" to "abc"))) }
        assertRejected(Reason.INVALID_CHECKSUM) { parse(manifestFields(apkOverrides = mapOf("sha256" to "z".repeat(64)))) }
    }

    @Test
    fun unreasonableSizes_areRejected() {
        for (size in listOf(0L, -1L, 1000L, UpdateManifestParser.MAX_APK_BYTES + 1)) {
            assertRejected(Reason.INVALID_SIZE) { parse(manifestFields(apkOverrides = mapOf("size" to size))) }
        }
        assertRejected(Reason.INVALID_SIZE) { parse(manifestFields(apkOverrides = mapOf("size" to null))) }
    }

    @Test
    fun otherInvalidFields_areRejected() {
        assertRejected(Reason.INVALID_FIELD) { parse(manifestFields(overrides = mapOf("releaseDate" to "20-11-2026"))) }
        assertRejected(Reason.INVALID_FIELD) { parse(manifestFields(overrides = mapOf("mandatory" to "yes"))) }
        assertRejected(Reason.INVALID_FIELD) { parse(manifestFields(overrides = mapOf("releaseNotes" to "x".repeat(10_001)))) }
    }

    @Test
    fun versionComparison_neverDowngrades() {
        val m = parse(manifestFields(versionCode = 10))
        assertEquals(UpdateAvailability.Available(m), evaluateUpdate(m, installedVersionCode = 9, sdkInt = 28))
        assertEquals(UpdateAvailability.UpToDate, evaluateUpdate(m, installedVersionCode = 10, sdkInt = 28))
        assertEquals(UpdateAvailability.UpToDate, evaluateUpdate(m, installedVersionCode = 11, sdkInt = 28))
    }

    @Test
    fun minUpdatableAndMinSdk_blockOffer() {
        val m = parse(manifestFields(versionCode = 10, overrides = mapOf("minUpdatableVersionCode" to 8, "minSdk" to 29)))
        assertEquals(UpdateAvailability.InstalledTooOld(m), evaluateUpdate(m, installedVersionCode = 7, sdkInt = 30))
        assertEquals(UpdateAvailability.DeviceUnsupported(m), evaluateUpdate(m, installedVersionCode = 9, sdkInt = 28))
        assertEquals(UpdateAvailability.Available(m), evaluateUpdate(m, installedVersionCode = 8, sdkInt = 29))
    }

    @Test
    fun productionKey_isAValidEd25519PublicKey() {
        val verifier = Ed25519Verifier(UpdateKeys.TRUSTED)
        assertTrue(verifier.knows(UpdateKeys.KEY_ID_2026))
        assertEquals(32, Base64.getDecoder().decode(UpdateKeys.TRUSTED.getValue(UpdateKeys.KEY_ID_2026)).size)
        // A test signature must never verify against the production key.
        val payload = json(manifestFields()).toByteArray()
        assertFalse(verifier.verify(UpdateKeys.KEY_ID_2026, payload, signer.sign(payload)))
    }

    /**
     * Envelope produced by scripts/update_manifest.py (openssl pkeyutl) with a throwaway key,
     * so the release tooling and the app agree on the exact format.
     */
    @Test
    fun scriptGeneratedEnvelope_verifies() {
        val envelope = javaClass.classLoader!!.getResourceAsStream("update/script-envelope.json")!!.readBytes()
        val publicKey = javaClass.classLoader!!.getResourceAsStream("update/script-envelope.pub")!!.reader().readText().trim()
        val scriptParser = UpdateManifestParser(Ed25519Verifier(mapOf(UpdateKeys.KEY_ID_2026 to publicKey)), PKG)
        val m = scriptParser.parse(envelope)
        assertEquals(PKG, m.packageName)
        assertTrue(m.apkUrl.toString().startsWith("https://github.com/Sa-Alfy/Sur/releases/download/"))
        // Same bytes are rejected by the real production key, since a test key signed them.
        try {
            UpdateManifestParser(Ed25519Verifier(UpdateKeys.TRUSTED), PKG).parse(envelope)
            fail("test-signed envelope must not verify with the production key")
        } catch (e: ManifestRejectedException) {
            assertEquals(Reason.BAD_SIGNATURE, e.reason)
        }
    }
}
