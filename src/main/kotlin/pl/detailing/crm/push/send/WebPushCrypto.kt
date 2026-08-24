package pl.detailing.crm.push.send

import java.math.BigInteger
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPrivateKeySpec
import java.security.spec.ECPublicKeySpec
import java.time.Instant
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Web Push message encryption (RFC 8291, `aes128gcm` per RFC 8188) and VAPID
 * authorization (RFC 8292) implemented on the bare JDK.
 *
 * Deliberately dependency-free: everything needed — P-256 ECDH, HMAC-SHA256,
 * AES-128-GCM, ECDSA — ships with the JDK, and the one missing brick (HKDF)
 * is four lines of HMAC. Pulling in a web-push library would drag a second
 * BouncyCastle variant onto a classpath that already pins bcprov-jdk18on for
 * PAdES sealing, and a JAR conflict in document signing is a far worse trade
 * than owning ~150 lines of well-specified crypto.
 *
 * Key formats match the de-facto standard emitted by `npx web-push
 * generate-vapid-keys` and by the browser's PushSubscription:
 *  - public keys: base64url of the uncompressed P-256 point (65 bytes, 0x04-led)
 *  - VAPID private key: base64url of the raw 32-byte scalar
 *  - auth secret: base64url of 16 random bytes
 */
object WebPushCrypto {

    private val B64_URL_DEC = Base64.getUrlDecoder()
    private val B64_URL_ENC = Base64.getUrlEncoder().withoutPadding()
    private val RANDOM = SecureRandom()

    private val P256_PARAMS: ECParameterSpec = AlgorithmParameters.getInstance("EC").run {
        init(ECGenParameterSpec("secp256r1"))
        getParameterSpec(ECParameterSpec::class.java)
    }

    // ─── VAPID (RFC 8292) ────────────────────────────────────────────────────

    /**
     * Builds the `Authorization: vapid t=<jwt>, k=<key>` header value for a push
     * endpoint. The JWT audience is the ORIGIN of the endpoint (scheme + host),
     * never the full URL — FCM rejects anything longer.
     */
    fun vapidAuthorizationHeader(
        endpoint: String,
        publicKeyB64: String,
        privateKeyB64: String,
        subject: String,
        expiresAt: Instant = Instant.now().plusSeconds(12 * 3600)
    ): String {
        val uri = URI(endpoint)
        val audience = buildString {
            append(uri.scheme).append("://").append(uri.host)
            if (uri.port != -1) append(":").append(uri.port)
        }

        val header = """{"typ":"JWT","alg":"ES256"}"""
        val claims = """{"aud":"$audience","exp":${expiresAt.epochSecond},"sub":"$subject"}"""
        val signingInput = b64(header.toByteArray(StandardCharsets.UTF_8)) + "." +
            b64(claims.toByteArray(StandardCharsets.UTF_8))

        val signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(privateKeyFromRawScalar(privateKeyB64))
            update(signingInput.toByteArray(StandardCharsets.US_ASCII))
            sign()
        }

        val jwt = "$signingInput." + b64(derToJose(signature))
        return "vapid t=$jwt, k=$publicKeyB64"
    }

    // ─── Payload encryption (RFC 8291 / RFC 8188 aes128gcm) ──────────────────

    /**
     * Encrypts [plaintext] for the subscription identified by the client's
     * [p256dhB64] public key and [authB64] secret. Returns the complete HTTP
     * body: the aes128gcm header block (salt, record size, server public key)
     * followed by the single encrypted record.
     */
    fun encrypt(plaintext: ByteArray, p256dhB64: String, authB64: String): ByteArray {
        val ephemeral: KeyPair = KeyPairGenerator.getInstance("EC")
            .apply { initialize(ECGenParameterSpec("secp256r1"), RANDOM) }
            .generateKeyPair()
        val salt = ByteArray(16).also(RANDOM::nextBytes)
        return encrypt(plaintext, p256dhB64, authB64, ephemeral, salt)
    }

    /**
     * Deterministic variant with injectable ephemeral key pair and salt —
     * exists so the RFC 8291 Appendix A test vector can pin this
     * implementation byte-for-byte (see WebPushCryptoTest).
     */
    internal fun encrypt(
        plaintext: ByteArray,
        p256dhB64: String,
        authB64: String,
        ephemeral: KeyPair,
        salt: ByteArray
    ): ByteArray {
        val clientPublicRaw = B64_URL_DEC.decode(p256dhB64)
        val authSecret = B64_URL_DEC.decode(authB64)
        require(clientPublicRaw.size == 65 && clientPublicRaw[0] == 0x04.toByte()) {
            "p256dh must be an uncompressed P-256 point"
        }
        val serverPublicRaw = encodeUncompressed(ephemeral.public as ECPublicKey)

        val sharedSecret = KeyAgreement.getInstance("ECDH").run {
            init(ephemeral.private)
            doPhase(publicKeyFromUncompressed(clientPublicRaw), true)
            generateSecret()
        }

        // RFC 8291 §3.3-3.4: combine the ECDH secret with the client's auth
        // secret, binding both public keys into the derivation.
        val keyInfo = "WebPush: info".toByteArray(StandardCharsets.US_ASCII) +
            byteArrayOf(0) + clientPublicRaw + serverPublicRaw
        val ikm = hkdf(salt = authSecret, ikm = sharedSecret, info = keyInfo, length = 32)

        val cek = hkdf(salt, ikm, "Content-Encoding: aes128gcm".toByteArray(StandardCharsets.US_ASCII) + byteArrayOf(0), 16)
        val nonce = hkdf(salt, ikm, "Content-Encoding: nonce".toByteArray(StandardCharsets.US_ASCII) + byteArrayOf(0), 12)

        // Single record; 0x02 marks the final record's padding delimiter.
        val ciphertext = Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(cek, "AES"), GCMParameterSpec(128, nonce))
            doFinal(plaintext + byteArrayOf(0x02))
        }

        // RFC 8188 header: salt(16) | rs(4, BE) | idlen(1) | keyid(=server key)
        return ByteBuffer.allocate(16 + 4 + 1 + 65 + ciphertext.size)
            .put(salt)
            .putInt(4096)
            .put(65.toByte())
            .put(serverPublicRaw)
            .put(ciphertext)
            .array()
    }

    /** Generates a fresh VAPID key pair; used only by ops tooling/tests. */
    fun generateVapidKeyPair(): Pair<String, String> {
        val pair = KeyPairGenerator.getInstance("EC")
            .apply { initialize(ECGenParameterSpec("secp256r1"), RANDOM) }
            .generateKeyPair()
        val publicB64 = b64(encodeUncompressed(pair.public as ECPublicKey))
        val privateB64 = b64(fixedLength((pair.private as ECPrivateKey).s, 32))
        return publicB64 to privateB64
    }

    // ─── Internals ───────────────────────────────────────────────────────────

    /** HKDF-SHA256 (RFC 5869), single-block expand — all Web Push outputs ≤ 32 B. */
    private fun hkdf(salt: ByteArray, ikm: ByteArray, info: ByteArray, length: Int): ByteArray {
        val prk = hmacSha256(salt, ikm)
        return hmacSha256(prk, info + byteArrayOf(0x01)).copyOf(length)
    }

    private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            doFinal(data)
        }

    private fun publicKeyFromUncompressed(raw: ByteArray): ECPublicKey {
        val x = BigInteger(1, raw.copyOfRange(1, 33))
        val y = BigInteger(1, raw.copyOfRange(33, 65))
        val spec = ECPublicKeySpec(ECPoint(x, y), P256_PARAMS)
        return KeyFactory.getInstance("EC").generatePublic(spec) as ECPublicKey
    }

    private fun privateKeyFromRawScalar(privateKeyB64: String): ECPrivateKey {
        val s = BigInteger(1, B64_URL_DEC.decode(privateKeyB64))
        val spec = ECPrivateKeySpec(s, P256_PARAMS)
        return KeyFactory.getInstance("EC").generatePrivate(spec) as ECPrivateKey
    }

    private fun encodeUncompressed(key: ECPublicKey): ByteArray =
        byteArrayOf(0x04) + fixedLength(key.w.affineX, 32) + fixedLength(key.w.affineY, 32)

    /** BigInteger → exactly [length] bytes, left-padded, sign byte stripped. */
    private fun fixedLength(value: BigInteger, length: Int): ByteArray {
        val raw = value.toByteArray()
        val out = ByteArray(length)
        if (raw.size > length) {
            raw.copyInto(out, 0, raw.size - length, raw.size)
        } else {
            raw.copyInto(out, length - raw.size, 0, raw.size)
        }
        return out
    }

    /**
     * JCA emits ECDSA signatures as ASN.1 DER; JOSE (and VAPID) require the
     * raw fixed-width r||s concatenation.
     */
    private fun derToJose(der: ByteArray): ByteArray {
        var offset = 3 // SEQUENCE tag, length, INTEGER tag
        if (der[1].toInt() and 0x80 != 0) offset += der[1].toInt() and 0x7F // long-form length
        val rLength = der[offset].toInt()
        val r = der.copyOfRange(offset + 1, offset + 1 + rLength)
        offset += 1 + rLength + 1 // skip r, next INTEGER tag
        val sLength = der[offset].toInt()
        val s = der.copyOfRange(offset + 1, offset + 1 + sLength)
        return fixedLength(BigInteger(1, r), 32) + fixedLength(BigInteger(1, s), 32)
    }

    private fun b64(bytes: ByteArray): String = B64_URL_ENC.encodeToString(bytes)
}
