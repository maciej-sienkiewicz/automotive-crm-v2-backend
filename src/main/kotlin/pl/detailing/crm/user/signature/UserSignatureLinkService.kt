package pl.detailing.crm.user.signature

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Service
import pl.detailing.crm.communication.OutboundCommunicationGateway
import pl.detailing.crm.shared.EntityNotFoundException
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import pl.detailing.crm.shared.ValidationException
import pl.detailing.crm.shared.normalizePolishPhone
import pl.detailing.crm.user.infrastructure.UserRepository
import pl.detailing.crm.visitcard.VisitCardProperties
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID

/**
 * Manages short-lived signing links so a logged-in user can draw their personal
 * signature on their own phone instead of on the desktop.
 *
 * Flow:
 *  1. Employee clicks "Wyślij link na telefon" → POST /api/v1/profile/signature/send-link
 *  2. Backend generates a random token, stores {userId, studioId} in Redis (TTL 30 min),
 *     sends an SMS with the link to the user's registered phone number.
 *  3. User opens /m/sig/{token} on their phone, draws their signature.
 *  4. Phone POSTs to /api/public/user-signature/{token}/submit with the PNG base64.
 *  5. Backend validates the token, calls UserSignatureService.save(), deletes the token.
 *
 * Redis key: profile:sig-link:{token}   → JSON {userId, studioId}   TTL = 30 min
 */
@Service
class UserSignatureLinkService(
    private val userRepository: UserRepository,
    private val userSignatureService: UserSignatureService,
    private val communicationGateway: OutboundCommunicationGateway,
    private val redisTemplate: StringRedisTemplate,
    private val objectMapper: ObjectMapper,
    private val visitCardProperties: VisitCardProperties
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    companion object {
        private val TOKEN_TTL = Duration.ofMinutes(30)
        private const val KEY_PREFIX = "profile:sig-link:"
        private val SECURE_RANDOM = SecureRandom()
    }

    data class SendLinkResult(val expiresAt: Instant)

    /** Generate a token and send the signing link by SMS to the provided phone number. */
    fun sendLink(studioId: StudioId, userId: UserId, phoneNumber: String): SendLinkResult {
        val entity = userRepository.findByIdAndStudioId(userId.value, studioId.value)
            ?: throw EntityNotFoundException("Użytkownik nie został znaleziony")

        val rawPhone = phoneNumber.trim().takeIf { it.isNotBlank() }
            ?: throw ValidationException("Numer telefonu jest wymagany")
        val phone = normalizePolishPhone(rawPhone)

        val token = generateToken()
        val payload = objectMapper.writeValueAsString(
            mapOf("userId" to userId.value.toString(), "studioId" to studioId.value.toString())
        )
        redisTemplate.opsForValue().set(KEY_PREFIX + token, payload, TOKEN_TTL)

        val signingUrl = "${visitCardProperties.frontendBaseUrl.trimEnd('/')}/m/sig/$token"
        val fullName = "${entity.firstName} ${entity.lastName}".trim()
        val message = "Cześć $fullName! Kliknij link, aby narysować swój podpis: $signingUrl (ważny 30 min)"

        val result = communicationGateway.sendTransactionalSms(studioId.value, phone, message)
        if (!result.success) {
            redisTemplate.delete(KEY_PREFIX + token)
            throw ValidationException("Nie udało się wysłać SMS: ${result.errorMessage ?: "błąd dostawcy"}")
        }

        val expiresAt = Instant.now().plus(TOKEN_TTL)
        logger.info("User signature link sent: userId={} phone=…{}", userId, phone.takeLast(3))
        return SendLinkResult(expiresAt = expiresAt)
    }

    /**
     * Validate the link token and save the submitted signature.
     * The token is deleted on success (single-use).
     */
    fun submit(token: String, signatureImageBase64: String) {
        val (userId, studioId) = resolveToken(token)

        userSignatureService.save(studioId, userId, signatureImageBase64)

        redisTemplate.delete(KEY_PREFIX + token)
        logger.info("User signature submitted via phone link: userId={}", userId)
    }

    /** Check if the token is still valid (used by the public page to detect expiry). */
    fun isValid(token: String): Boolean =
        redisTemplate.hasKey(KEY_PREFIX + token) == true

    private fun resolveToken(token: String): Pair<UserId, StudioId> {
        if (token.isBlank() || token.length > 100) {
            throw ValidationException("Nieprawidłowy token")
        }
        val json = redisTemplate.opsForValue().get(KEY_PREFIX + token)
            ?: throw ValidationException("Link wygasł lub jest nieprawidłowy")

        @Suppress("UNCHECKED_CAST")
        val data = objectMapper.readValue(json, Map::class.java) as Map<String, String>
        val userId = UserId(UUID.fromString(data["userId"] ?: error("brak userId w tokenie")))
        val studioId = StudioId(UUID.fromString(data["studioId"] ?: error("brak studioId w tokenie")))
        return userId to studioId
    }

    private fun generateToken(): String {
        val bytes = ByteArray(32)
        SECURE_RANDOM.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}
