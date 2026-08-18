package pl.detailing.crm.mailbox.infrastructure

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-256-GCM at-rest encryption for mailbox credentials.
 *
 * Ciphertext format: "ENC:" + Base64(iv(12B) || ciphertext||tag). The prefix makes both
 * operations idempotent, so a value can be passed through encrypt/decrypt more than once
 * during migrations without corrupting it.
 */
@Service
class MailboxEncryptionService(
    @Value("\${mailbox.encryption.secret-key:}") private val secretKey: String
) {

    private val log = LoggerFactory.getLogger(javaClass)

    private val random = SecureRandom()

    /** Derived lazily so an app without a configured mailbox module still starts. */
    private val key: SecretKeySpec by lazy {
        if (secretKey.isBlank()) {
            throw IllegalStateException(
                "Brak klucza szyfrowania skrzynek pocztowych. Ustaw zmienną środowiskową " +
                    "MAILBOX_ENCRYPTION_KEY (property 'mailbox.encryption.secret-key') i zrestartuj " +
                    "aplikację — bez niej nie można zapisywać ani odczytywać haseł do skrzynek."
            )
        }
        // SHA-256 of the property gives a valid 256-bit key regardless of the property length.
        val digest = MessageDigest.getInstance("SHA-256").digest(secretKey.toByteArray(Charsets.UTF_8))
        SecretKeySpec(digest, "AES")
    }

    fun isEncrypted(value: String): Boolean = value.startsWith(PREFIX)

    fun encrypt(plainText: String): String {
        if (isEncrypted(plainText)) return plainText

        val iv = ByteArray(IV_LENGTH).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_LENGTH_BITS, iv))
        val encrypted = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))

        val payload = ByteArray(iv.size + encrypted.size)
        iv.copyInto(payload, 0)
        encrypted.copyInto(payload, iv.size)
        return PREFIX + Base64.getEncoder().encodeToString(payload)
    }

    fun decrypt(cipherText: String): String {
        if (!isEncrypted(cipherText)) return cipherText

        val payload = try {
            Base64.getDecoder().decode(cipherText.removePrefix(PREFIX))
        } catch (ex: IllegalArgumentException) {
            log.error("Nie można zdekodować zaszyfrowanej wartości (uszkodzony Base64)")
            throw IllegalStateException("Zapisane poświadczenia skrzynki są uszkodzone — połącz konto ponownie", ex)
        }
        if (payload.size <= IV_LENGTH) {
            throw IllegalStateException("Zapisane poświadczenia skrzynki są uszkodzone — połącz konto ponownie")
        }

        val iv = payload.copyOfRange(0, IV_LENGTH)
        val body = payload.copyOfRange(IV_LENGTH, payload.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_LENGTH_BITS, iv))
        return try {
            String(cipher.doFinal(body), Charsets.UTF_8)
        } catch (ex: javax.crypto.AEADBadTagException) {
            // Wrong key or tampered ciphertext — never surface the raw crypto error to the API.
            log.error("Weryfikacja tagu AES-GCM nieudana — klucz szyfrowania zmieniony lub dane zmodyfikowane")
            throw IllegalStateException(
                "Nie można odszyfrować poświadczeń skrzynki — klucz szyfrowania został zmieniony. Połącz konto ponownie",
                ex
            )
        }
    }

    private companion object {
        const val PREFIX = "ENC:"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_LENGTH = 12
        const val TAG_LENGTH_BITS = 128
    }
}
