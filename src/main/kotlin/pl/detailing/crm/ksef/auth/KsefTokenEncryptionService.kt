package pl.detailing.crm.ksef.auth

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import pl.detailing.crm.ksef.client.KsefApiClient
import pl.detailing.crm.ksef.client.KsefApiException
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.security.cert.CertificateFactory
import java.security.spec.MGF1ParameterSpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource

/**
 * Handles RSA-OAEP-SHA256 encryption of KSeF tokens for authentication.
 *
 * The KSeF API requires the token to be encrypted with the system's public key
 * before submission. The content to encrypt is: "{token}|{challengeTimestampMs}"
 */
@Service
class KsefTokenEncryptionService(private val ksefApiClient: KsefApiClient) {

    private val log = LoggerFactory.getLogger(KsefTokenEncryptionService::class.java)

    companion object {
        private const val KSEF_TOKEN_ENCRYPTION_USAGE = "KsefTokenEncryption"
        private const val BEGIN_CERTIFICATE = "-----BEGIN CERTIFICATE-----\n"
        private const val END_CERTIFICATE = "\n-----END CERTIFICATE-----"
    }

    /**
     * Fetches the KSeF public key certificate and encrypts the token combined with
     * the challenge timestamp using RSA-OAEP-SHA256.
     *
     * @param ksefToken raw KSeF API token
     * @param challengeTimestampMs millisecond timestamp from the auth challenge response
     * @return Base64-encoded encrypted token ready for the auth request
     */
    fun encryptToken(ksefToken: String, challengeTimestampMs: Long): String {
        val certificates = ksefApiClient.getPublicKeyCertificates()

        val encryptionCert = certificates
            .firstOrNull { cert -> cert.usage.contains(KSEF_TOKEN_ENCRYPTION_USAGE) }
            ?: throw KsefApiException(
                "No certificate with usage '$KSEF_TOKEN_ENCRYPTION_USAGE' found in KSeF response. " +
                    "Available usages: ${certificates.flatMap { it.usage }}"
            )

        val pemContent = BEGIN_CERTIFICATE + encryptionCert.certificate + END_CERTIFICATE
        val publicKey = parseCertificatePublicKey(pemContent)

        val plaintext = ("$ksefToken|$challengeTimestampMs").toByteArray(StandardCharsets.UTF_8)
        val encrypted = encryptWithRsaOaep(plaintext, publicKey)

        return Base64.getEncoder().encodeToString(encrypted)
    }

    private fun parseCertificatePublicKey(pemContent: String): java.security.PublicKey {
        val certFactory = CertificateFactory.getInstance("X.509")
        val cert = certFactory.generateCertificate(
            ByteArrayInputStream(pemContent.toByteArray(StandardCharsets.UTF_8))
        ) as java.security.cert.X509Certificate
        return cert.publicKey
    }

    private fun encryptWithRsaOaep(content: ByteArray, publicKey: java.security.PublicKey): ByteArray {
        val oaepParams = OAEPParameterSpec(
            "SHA-256",
            "MGF1",
            MGF1ParameterSpec.SHA256,
            PSource.PSpecified.DEFAULT
        )
        val cipher = Cipher.getInstance("RSA/ECB/OAEPPadding")
        cipher.init(Cipher.ENCRYPT_MODE, publicKey, oaepParams)
        return cipher.doFinal(content)
    }
}
