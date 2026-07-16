package pl.detailing.crm.visitcard

import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.VisitId
import pl.detailing.crm.visitcard.infrastructure.VisitCardTokenEntity
import pl.detailing.crm.visitcard.infrastructure.VisitCardTokenRepository
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID

@Service
class VisitCardTokenService(
    private val tokenRepository: VisitCardTokenRepository
) {
    companion object {
        private val SECURE_RANDOM = SecureRandom()
    }

    /**
     * Return the existing card token for the visit, creating one on first use.
     * The token is stable for the lifetime of the visit so the customer's link never breaks.
     */
    fun getOrCreateToken(studioId: StudioId, visitId: VisitId): String {
        tokenRepository.findByVisitIdAndStudioId(visitId.value, studioId.value)?.let { return it.token }

        val entity = VisitCardTokenEntity(
            id = UUID.randomUUID(),
            studioId = studioId.value,
            visitId = visitId.value,
            token = generateSecureToken()
        )
        return try {
            tokenRepository.save(entity).token
        } catch (e: DataIntegrityViolationException) {
            // Concurrent first request created the token — reuse it
            tokenRepository.findByVisitIdAndStudioId(visitId.value, studioId.value)?.token ?: throw e
        }
    }

    fun findByToken(token: String): VisitCardTokenEntity? =
        if (token.isBlank() || token.length > 64) null else tokenRepository.findByToken(token)

    private fun generateSecureToken(): String {
        val bytes = ByteArray(32)
        SECURE_RANDOM.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}
