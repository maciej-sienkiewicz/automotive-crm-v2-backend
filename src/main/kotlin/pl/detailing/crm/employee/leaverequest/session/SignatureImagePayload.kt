package pl.detailing.crm.employee.leaverequest.session

import pl.detailing.crm.shared.ValidationException
import java.util.Base64

/**
 * Obraz podpisu z żądania: PNG w base64 BEZ prefiksu `data:` (kontrakt, jak `public-signing`).
 * Prefiks kanwy tolerujemy — odrzucenie podpisu przez to, że przeglądarka dokleiła
 * `data:image/png;base64,`, byłoby karaniem użytkownika za szczegół techniczny.
 */
object SignatureImagePayload {
    private const val MAX_BASE64_LENGTH = 14 * 1024 * 1024

    fun decode(raw: String?, field: String, missingMessage: String): ByteArray {
        val payload = raw?.trim()?.let { if (it.startsWith("data:")) it.substringAfter(",", "") else it }
        if (payload.isNullOrBlank()) throw ValidationException(missingMessage, field = field)
        if (payload.length > MAX_BASE64_LENGTH) throw ValidationException("Obraz podpisu jest za duży", field = field)
        return runCatching { Base64.getDecoder().decode(payload) }
            .getOrElse { throw ValidationException("Obraz podpisu nie jest poprawnym base64", field = field) }
    }
}
