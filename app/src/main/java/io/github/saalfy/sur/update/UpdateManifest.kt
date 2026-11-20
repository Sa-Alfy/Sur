package io.github.saalfy.sur.update

import com.grack.nanojson.JsonObject
import com.grack.nanojson.JsonParser
import com.grack.nanojson.JsonParserException
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.Base64

/**
 * A verified stable release, as described by the signed update manifest (schema 1).
 * See docs/APP_UPDATE_SYSTEM.md for the exact format.
 */
data class UpdateManifest(
    val schema: Int,
    val packageName: String,
    val channel: String,
    val versionCode: Long,
    val versionName: String,
    val releaseDate: LocalDate,
    /** Installed versions below this cannot update in-app (they must reinstall by hand). */
    val minUpdatableVersionCode: Long,
    val minSdk: Int,
    val mandatory: Boolean,
    /** Plain text; shown to the user, never used for decisions. */
    val releaseNotes: String,
    val apkUrl: HttpUrl,
    /** Lowercase hex. */
    val apkSha256: String,
    val apkSize: Long,
)

/** Public keys trusted to sign update manifests. To rotate: ship the new key in a release first. */
object UpdateKeys {
    const val KEY_ID_2026 = "sur-update-2026-1"

    val TRUSTED: Map<String, String> = mapOf(
        // Private half is kept offline by the maintainer; never in the repo, CI or the APK.
        KEY_ID_2026 to "LOJv8ATAlQxYMUC+XmLDCdFgF+B6rJbH/oW6q2AgCtU=",
    )
}

/** Ed25519 signature check against a fixed set of trusted public keys. */
class Ed25519Verifier(trustedKeys: Map<String, String>) {
    private val keys: Map<String, ByteArray> = trustedKeys.mapValues { (id, b64) ->
        Base64.getDecoder().decode(b64).also {
            require(it.size == Ed25519PublicKeyParameters.KEY_SIZE) { "bad public key length for $id" }
        }
    }

    fun knows(keyId: String): Boolean = keyId in keys

    fun verify(keyId: String, message: ByteArray, signature: ByteArray): Boolean {
        val key = keys[keyId] ?: return false
        if (signature.size != SIGNATURE_SIZE) return false
        return try {
            val signer = Ed25519Signer()
            signer.init(false, Ed25519PublicKeyParameters(key, 0))
            signer.update(message, 0, message.size)
            signer.verifySignature(signature)
        } catch (_: RuntimeException) {
            false
        }
    }

    private companion object {
        const val SIGNATURE_SIZE = 64
    }
}

class ManifestRejectedException(val reason: Reason, message: String) : Exception(message) {
    enum class Reason {
        TOO_LARGE, MALFORMED, UNKNOWN_KEY, BAD_SIGNATURE, UNSUPPORTED_SCHEMA, WRONG_PACKAGE,
        UNSUPPORTED_CHANNEL, INVALID_VERSION, INVALID_URL, INSECURE_URL, MISSING_CHECKSUM,
        INVALID_CHECKSUM, INVALID_SIZE, INVALID_FIELD,
    }
}

/**
 * Parses and verifies a signed update envelope:
 * `{"keyId": "...", "payload": "<base64 manifest JSON>", "signature": "<base64 Ed25519>"}`.
 * The signature covers the exact payload bytes; nothing in the payload is read before it verifies.
 */
class UpdateManifestParser(
    private val verifier: Ed25519Verifier,
    private val expectedPackage: String,
    private val apkHosts: Set<String> = DEFAULT_APK_HOSTS,
    private val apkPathPrefix: String = DEFAULT_APK_PATH_PREFIX,
) {
    @Throws(ManifestRejectedException::class)
    fun parse(envelopeBytes: ByteArray): UpdateManifest {
        if (envelopeBytes.size > MAX_MANIFEST_BYTES) reject(Reason.TOO_LARGE, "manifest is ${envelopeBytes.size} bytes")
        val envelope = parseObject(envelopeBytes, "envelope")
        val keyId = envelope.requireString("keyId")
        val payloadB64 = envelope.requireString("payload")
        val signatureB64 = envelope.requireString("signature")
        if (!verifier.knows(keyId)) reject(Reason.UNKNOWN_KEY, "unknown key id")
        val payload = decodeBase64(payloadB64, "payload")
        val signature = decodeBase64(signatureB64, "signature")
        if (!verifier.verify(keyId, payload, signature)) reject(Reason.BAD_SIGNATURE, "signature does not verify")

        val m = parseObject(payload, "payload")
        val schema = m.requireLong("schema")
        if (schema != SUPPORTED_SCHEMA.toLong()) reject(Reason.UNSUPPORTED_SCHEMA, "schema $schema")
        val packageName = m.requireString("packageName")
        if (packageName != expectedPackage) reject(Reason.WRONG_PACKAGE, "package $packageName")
        val channel = m.requireString("channel")
        if (channel != CHANNEL_STABLE) reject(Reason.UNSUPPORTED_CHANNEL, "channel $channel")

        val versionCode = m.requireLong("versionCode", Reason.INVALID_VERSION)
        if (versionCode !in 1..MAX_VERSION_CODE) reject(Reason.INVALID_VERSION, "versionCode $versionCode")
        val versionName = m.requireString("versionName", Reason.INVALID_VERSION)
        if (!VERSION_NAME.matches(versionName)) reject(Reason.INVALID_VERSION, "versionName")
        val minUpdatable = m.optionalLong("minUpdatableVersionCode", 1, Reason.INVALID_VERSION)
        if (minUpdatable !in 1..versionCode) reject(Reason.INVALID_VERSION, "minUpdatableVersionCode $minUpdatable")
        val minSdk = m.optionalLong("minSdk", 1, Reason.INVALID_FIELD)
        if (minSdk !in 1..1000) reject(Reason.INVALID_FIELD, "minSdk $minSdk")

        val releaseDate = try {
            LocalDate.parse(m.requireString("releaseDate"))
        } catch (_: DateTimeParseException) {
            reject(Reason.INVALID_FIELD, "releaseDate")
        }
        val mandatory = when (val v = m["mandatory"]) {
            null -> false
            is Boolean -> v
            else -> reject(Reason.INVALID_FIELD, "mandatory")
        }
        val notes = when (val v = m["releaseNotes"]) {
            null -> ""
            is String -> v
            else -> reject(Reason.INVALID_FIELD, "releaseNotes")
        }
        if (notes.length > MAX_NOTES_CHARS) reject(Reason.INVALID_FIELD, "releaseNotes too long")

        val apk = m["apk"] as? JsonObject ?: reject(Reason.MALFORMED, "apk missing")
        val url = validateUrl(apk["url"] as? String ?: reject(Reason.INVALID_URL, "apk.url missing"))
        val sha = apk["sha256"] as? String
        if (sha.isNullOrBlank()) reject(Reason.MISSING_CHECKSUM, "apk.sha256 missing")
        if (!SHA256_HEX.matches(sha)) reject(Reason.INVALID_CHECKSUM, "apk.sha256")
        val size = apk.requireLong("size", Reason.INVALID_SIZE)
        if (size !in MIN_APK_BYTES..MAX_APK_BYTES) reject(Reason.INVALID_SIZE, "apk.size $size")

        return UpdateManifest(
            schema = schema.toInt(),
            packageName = packageName,
            channel = channel,
            versionCode = versionCode,
            versionName = versionName,
            releaseDate = releaseDate,
            minUpdatableVersionCode = minUpdatable,
            minSdk = minSdk.toInt(),
            mandatory = mandatory,
            releaseNotes = notes,
            apkUrl = url,
            apkSha256 = sha.lowercase(),
            apkSize = size,
        )
    }

    private fun validateUrl(raw: String): HttpUrl {
        if (raw.startsWith("http://", ignoreCase = true)) reject(Reason.INSECURE_URL, "apk.url is not https")
        if (!raw.startsWith("https://")) reject(Reason.INVALID_URL, "apk.url scheme")
        val url = raw.toHttpUrlOrNull() ?: reject(Reason.INVALID_URL, "apk.url unparsable")
        val ok = url.isHttps &&
            url.host in apkHosts &&
            url.port == 443 &&
            url.username.isEmpty() && url.password.isEmpty() &&
            url.query == null && url.fragment == null &&
            url.encodedPath.startsWith(apkPathPrefix) &&
            url.encodedPath.endsWith(".apk") &&
            url.pathSegments.none { it == ".." || it == "." || it.isEmpty() }
        if (!ok) reject(Reason.INVALID_URL, "apk.url not an allowed release asset")
        return url
    }

    private fun parseObject(bytes: ByteArray, what: String): JsonObject {
        val text = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) {
            reject(Reason.MALFORMED, "$what is not UTF-8")
        }
        return try {
            JsonParser.`object`().from(text)
        } catch (_: JsonParserException) {
            reject(Reason.MALFORMED, "$what is not a JSON object")
        }
    }

    private fun decodeBase64(value: String, what: String): ByteArray = try {
        Base64.getDecoder().decode(value)
    } catch (_: IllegalArgumentException) {
        reject(Reason.MALFORMED, "$what is not base64")
    }

    private fun JsonObject.requireString(key: String, reason: Reason = Reason.MALFORMED): String =
        this[key] as? String ?: reject(reason, "$key missing or not a string")

    private fun JsonObject.requireLong(key: String, reason: Reason = Reason.MALFORMED): Long =
        integerOrNull(this[key]) ?: reject(reason, "$key missing or not an integer")

    private fun JsonObject.optionalLong(key: String, default: Long, reason: Reason): Long =
        if (this[key] == null) default else integerOrNull(this[key]) ?: reject(reason, "$key not an integer")

    /** Whole numbers only; fractions and out-of-range values are rejected. */
    private fun integerOrNull(v: Any?): Long? = when (v) {
        is Int -> v.toLong()
        is Long -> v
        is Short -> v.toLong()
        is Byte -> v.toLong()
        else -> null
    }

    private fun reject(reason: Reason, message: String): Nothing = throw ManifestRejectedException(reason, message)

    companion object {
        const val SUPPORTED_SCHEMA = 1
        const val CHANNEL_STABLE = "stable"
        const val MAX_MANIFEST_BYTES = 64 * 1024
        const val MAX_NOTES_CHARS = 10_000
        const val MIN_APK_BYTES = 256L * 1024
        const val MAX_APK_BYTES = 200L * 1024 * 1024
        const val MAX_VERSION_CODE = 2_100_000_000L
        val DEFAULT_APK_HOSTS = setOf("github.com")
        const val DEFAULT_APK_PATH_PREFIX = "/Sa-Alfy/Sur/releases/download/"
        private val VERSION_NAME = Regex("^[0-9A-Za-z][0-9A-Za-z.+-]{0,31}$")
        private val SHA256_HEX = Regex("^[0-9a-fA-F]{64}$")
    }
}

private typealias Reason = ManifestRejectedException.Reason

/** What the installed app may do with a verified manifest. Pure decision, no I/O. */
sealed interface UpdateAvailability {
    data object UpToDate : UpdateAvailability
    data class Available(val manifest: UpdateManifest) : UpdateAvailability
    /** Installed build is older than [UpdateManifest.minUpdatableVersionCode]. */
    data class InstalledTooOld(val manifest: UpdateManifest) : UpdateAvailability
    /** Device Android version is below [UpdateManifest.minSdk]. */
    data class DeviceUnsupported(val manifest: UpdateManifest) : UpdateAvailability
}

/** Compares versionCode only. Same or older releases are never offered (no downgrade). */
fun evaluateUpdate(manifest: UpdateManifest, installedVersionCode: Long, sdkInt: Int): UpdateAvailability = when {
    manifest.versionCode <= installedVersionCode -> UpdateAvailability.UpToDate
    installedVersionCode < manifest.minUpdatableVersionCode -> UpdateAvailability.InstalledTooOld(manifest)
    sdkInt < manifest.minSdk -> UpdateAvailability.DeviceUnsupported(manifest)
    else -> UpdateAvailability.Available(manifest)
}
