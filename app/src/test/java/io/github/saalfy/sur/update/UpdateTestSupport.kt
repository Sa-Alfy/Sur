package io.github.saalfy.sur.update

import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import kotlin.random.Random

const val PKG = "io.github.saalfy.sur"
const val KEY_ID = "test-key"

/** A throwaway Ed25519 key pair, used only to sign test manifests (production trusts its own key). */
class TestSigner {
    private val keys = Ed25519KeyPairGenerator().apply { init(Ed25519KeyGenerationParameters(SecureRandom())) }.generateKeyPair()
    val privateKey = keys.private as Ed25519PrivateKeyParameters
    val publicB64: String = Base64.getEncoder().encodeToString((keys.public as Ed25519PublicKeyParameters).encoded)

    fun sign(payload: ByteArray): ByteArray = Ed25519Signer().run {
        init(true, privateKey)
        update(payload, 0, payload.size)
        generateSignature()
    }

    fun envelope(payload: ByteArray, keyId: String = KEY_ID, signature: ByteArray = sign(payload)): ByteArray =
        json(
            mapOf(
                "keyId" to keyId,
                "payload" to Base64.getEncoder().encodeToString(payload),
                "signature" to Base64.getEncoder().encodeToString(signature),
            ),
        ).toByteArray()

    fun envelope(fields: Map<String, Any?>): ByteArray = envelope(json(fields).toByteArray())

    fun parser() = UpdateManifestParser(Ed25519Verifier(mapOf(KEY_ID to publicB64)), PKG)
}

val TEST_APK: ByteArray = Random(42).nextBytes(300 * 1024)
val TEST_APK_SHA: String = sha256(TEST_APK)
const val APK_URL = "https://github.com/Sa-Alfy/Sur/releases/download/v1.1.0/Sur-v1.1.0.apk"

fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

/** A valid schema-1 manifest; override fields per test. A null value removes the field. */
fun manifestFields(
    versionCode: Long = 10,
    apk: ByteArray = TEST_APK,
    overrides: Map<String, Any?> = emptyMap(),
    apkOverrides: Map<String, Any?> = emptyMap(),
): Map<String, Any?> {
    val apkObj = mapOf<String, Any?>("url" to APK_URL, "sha256" to sha256(apk), "size" to apk.size.toLong()) + apkOverrides
    return mapOf<String, Any?>(
        "schema" to 1,
        "packageName" to PKG,
        "channel" to "stable",
        "versionCode" to versionCode,
        "versionName" to "1.1.0",
        "releaseDate" to "2026-11-20",
        "minUpdatableVersionCode" to 1,
        "minSdk" to 26,
        "mandatory" to false,
        "releaseNotes" to "Line one\nLine \"two\"",
        "apk" to apkObj,
    ) + overrides
}

fun json(value: Any?): String = when (value) {
    null -> "null"
    is String -> buildString {
        append('"')
        for (c in value) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                else -> append(c)
            }
        }
        append('"')
    }
    is Number, is Boolean -> value.toString()
    is Map<*, *> -> value.entries.filter { it.value != null }
        .joinToString(",", "{", "}") { json(it.key as String) + ":" + json(it.value) }
    else -> error("unsupported $value")
}

/** OkHttp client whose responses come from [handler]; nothing touches the network. */
fun fakeClient(handler: (Request) -> Response): OkHttpClient =
    OkHttpClient.Builder().addInterceptor(Interceptor { chain -> handler(chain.request()) }).build()

fun respond(request: Request, code: Int, body: ResponseBody = ByteArray(0).toResponseBody(), headers: Map<String, String> = emptyMap()): Response =
    Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("x")
        .apply { headers.forEach { (k, v) -> header(k, v) } }
        .body(body).build()

fun bytesBody(bytes: ByteArray): ResponseBody = bytes.toResponseBody("application/octet-stream".toMediaType())

/** Declares the full length but throws after [failAfter] bytes, like a dropped connection. */
fun brokenBody(bytes: ByteArray, failAfter: Int): ResponseBody = object : ResponseBody() {
    override fun contentType(): MediaType? = null
    override fun contentLength(): Long = bytes.size.toLong()
    override fun source(): BufferedSource {
        val data = Buffer().write(bytes)
        var served = 0L
        return object : ForwardingSource(data) {
            override fun read(sink: Buffer, byteCount: Long): Long {
                if (served >= failAfter) throw IOException("connection reset")
                val n = super.read(sink, minOf(byteCount, failAfter - served))
                if (n > 0) served += n
                return n
            }
        }.buffer()
    }
}
