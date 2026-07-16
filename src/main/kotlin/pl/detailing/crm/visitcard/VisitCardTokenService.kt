package pl.detailing.crm.visitcard

import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import pl.detailing.crm.shared.AppointmentId
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
     * Return the card token for a visit, creating one on first use.
     *
     * When the visit originates from a reservation that already has a card token
     * (the link was sent to the customer at booking time), that token is reused —
     * the customer keeps a single link that follows the reservation into the visit.
     */
    fun getOrCreateToken(studioId: StudioId, visitId: VisitId, appointmentId: AppointmentId? = null): String {
        tokenRepository.findByVisitIdAndStudioId(visitId.value, studioId.value)?.let { return it.token }
        appointmentId?.let { apptId ->
            tokenRepository.findByAppointmentIdAndStudioId(apptId.value, studioId.value)?.let { return it.token }
        }

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

    /**
     * Return the card token for a reservation (appointment), creating one on first
     * use. The same link keeps working after check-in — resolution finds the visit
     * created from this appointment.
     */
    fun getOrCreateTokenForAppointment(studioId: StudioId, appointmentId: AppointmentId): String {
        tokenRepository.findByAppointmentIdAndStudioId(appointmentId.value, studioId.value)?.let { return it.token }

        val entity = VisitCardTokenEntity(
            id = UUID.randomUUID(),
            studioId = studioId.value,
            visitId = null,
            appointmentId = appointmentId.value,
            token = generateSecureToken()
        )
        return try {
            tokenRepository.save(entity).token
        } catch (e: DataIntegrityViolationException) {
            tokenRepository.findByAppointmentIdAndStudioId(appointmentId.value, studioId.value)?.token ?: throw e
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
