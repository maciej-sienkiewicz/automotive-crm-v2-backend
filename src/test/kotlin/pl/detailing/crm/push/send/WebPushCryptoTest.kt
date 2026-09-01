package pl.detailing.crm.push.send

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPair
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPrivateKeySpec
import java.security.spec.ECPublicKeySpec
import java.time.Instant
import java.util.Base64

/**
 * Pins WebPushCrypto to the official RFC 8291 Appendix A test vector: fixed
 * server key pair + fixed salt must reproduce the spec's ciphertext
 * byte-for-byte. If any part of the derivation chain (ECDH → HKDF → AES-GCM,
 * info strings, header layout) drifts, this fails loudly instead of every
 * phone silently discarding undecryptable pushes.
 */
class WebPushCryptoTest {

    private val b64dec = Base64.getUrlDecoder()
    private val b64enc = Base64.getUrlEncoder().withoutPadding()

    // RFC 8291 Appendix A inputs
    private val plaintext = "When I grow up, I want to be a watermelon"
    private val asPublic = "BP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A8"
    private val asPrivate = "yfWPiYE-n46HLnH0KqZOF1fJJU3MYrct3AELtAQ-oRw"
    private val uaPublic = "BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4"
    private val authSecret = "BTBZMqHH6r4Tts7J_aSIgg"
    private val salt = "DGv6ra1nlYgDCS1FRnbzlw"

    // RFC 8291 Appendix A expected full body (header block + ciphertext)
    private val expectedBody =
        "DGv6ra1nlYgDCS1FRnbzlwAAEABBBP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlml" +
            "MoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A_yl95bQpu6cVPTpK" +
            "4Mqgkf1CXztLVBSt2Ks3oZwbuwXPXLWyouBWLVWGNWQexSgSxsj_Qulcy4a-fN"

    @Test
    fun `encryption reproduces the RFC 8291 appendix A vector`() {
        val body = WebPushCrypto.encrypt(
            plaintext = plaintext.toByteArray(StandardCharsets.UTF_8),
            p256dhB64 = uaPublic,
            authB64 = authSecret,
            ephemeral = keyPair(asPublic, asPrivate),
            salt = b64dec.decode(salt)
        )

        assertEquals(expectedBody, b64enc.encodeToString(body))
    }

    @Test
    fun `vapid header carries a verifiable ES256 JWT with the endpoint origin as audience`() {
        val (publicKey, privateKey) = WebPushCrypto.generateVapidKeyPair()

        val header = WebPushCrypto.vapidAuthorizationHeader(
            endpoint = "https://fcm.googleapis.com/fcm/send/abc123",
            publicKeyB64 = publicKey,
            privateKeyB64 = privateKey,
            subject = "mailto:kontakt@detailboost.pl",
            expiresAt = Instant.ofEpochSecond(2_000_000_000)
        )

        assertTrue(header.startsWith("vapid t="))
        assertTrue(header.contains(", k=$publicKey"))

        val jwt = header.removePrefix("vapid t=").substringBefore(", k=")
        val (headerB64, claimsB64, signatureB64) = jwt.split(".")

        val claims = String(b64dec.decode(claimsB64), StandardCharsets.UTF_8)
        assertTrue(claims.contains("\"aud\":\"https://fcm.googleapis.com\""))
        assertTrue(claims.contains("\"exp\":2000000000"))
        assertTrue(claims.contains("\"sub\":\"mailto:kontakt@detailboost.pl\""))

        // The signature must verify against the advertised public key.
        val jose = b64dec.decode(signatureB64)
        assertEquals(64, jose.size)
        val verifier = Signature.getInstance("SHA256withECDSA").apply {
            initVerify(publicKeyFrom(publicKey))
            update("$headerB64.$claimsB64".toByteArray(StandardCharsets.US_ASCII))
        }
        assertTrue(verifier.verify(joseToDer(jose)))
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private val p256: ECParameterSpec = AlgorithmParameters.getInstance("EC").run {
        init(ECGenParameterSpec("secp256r1"))
        getParameterSpec(ECParameterSpec::class.java)
    }

    private fun keyPair(publicB64: String, privateB64: String): KeyPair {
        val factory = KeyFactory.getInstance("EC")
        val publicKey = factory.generatePublic(
            ECPublicKeySpec(pointFrom(b64dec.decode(publicB64)), p256)
        )
        val privateKey = factory.generatePrivate(
            ECPrivateKeySpec(BigInteger(1, b64dec.decode(privateB64)), p256)
        )
        return KeyPair(publicKey, privateKey)
    }

    private fun publicKeyFrom(publicB64: String) =
        KeyFactory.getInstance("EC").generatePublic(
            ECPublicKeySpec(pointFrom(b64dec.decode(publicB64)), p256)
        )

    private fun pointFrom(raw: ByteArray) = ECPoint(
        BigInteger(1, raw.copyOfRange(1, 33)),
        BigInteger(1, raw.copyOfRange(33, 65))
    )

    /** Raw r||s (64 B) → minimal ASN.1 DER, so the JCA verifier can consume it. */
    private fun joseToDer(jose: ByteArray): ByteArray {
        fun derInt(bytes: ByteArray): ByteArray {
            val dropped = bytes.dropWhile { it == 0.toByte() }
            val stripped = if (dropped.isEmpty()) byteArrayOf(0) else dropped.toByteArray()
            val padded = if (stripped[0].toInt() < 0) byteArrayOf(0) + stripped else stripped
            return byteArrayOf(0x02, padded.size.toByte()) + padded
        }

        val r = derInt(jose.copyOfRange(0, 32))
        val s = derInt(jose.copyOfRange(32, 64))
        return byteArrayOf(0x30, (r.size + s.size).toByte()) + r + s
    }
}
