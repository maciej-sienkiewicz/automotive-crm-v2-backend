package pl.detailing.crm.platform

import jakarta.validation.Valid
import jakarta.validation.constraints.NotNull
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import pl.detailing.crm.shared.EntityNotFoundException
import pl.detailing.crm.smscampaigns.infrastructure.SmsAutomationConfigJpaRepository
import pl.detailing.crm.studio.infrastructure.StudioRepository
import java.time.Instant
import java.util.UUID

/**
 * Operator-only studio administration. Lives under `/api/internal/...`, which
 * [pl.detailing.crm.metrics.config.PlatformAccessInterceptor] guards with the
 * `X-Platform-Key` shared secret (fail-closed when the key is not configured) and
 * which [pl.detailing.crm.config.SecurityConfig] keeps outside the session model on
 * purpose: there is no studio to scope to and no operator row in `users`.
 *
 * Today it carries the one decision a studio must never take for itself: confirming
 * that its SMS sender header was registered at SMSAPI after we reviewed the signed
 * authorisation. Until this endpoint existed the confirmation checkbox sat in the
 * studio's own settings, so any owner could send SMS under an arbitrary header.
 */
@RestController
@RequestMapping("/api/internal/studios")
class PlatformStudioAdminController(
    private val studioRepository: StudioRepository,
    private val smsAutomationConfigRepository: SmsAutomationConfigJpaRepository
) {
    private val log = LoggerFactory.getLogger(javaClass)

    data class SmsSenderConfirmationRequest(
        @field:NotNull val confirmed: Boolean?
    )

    data class SmsSenderConfirmationResponse(
        val studioId: String,
        val senderName: String?,
        val confirmed: Boolean
    )

    @PutMapping("/{studioId}/sms-sender-confirmation")
    @Transactional
    fun setSmsSenderConfirmation(
        @PathVariable studioId: UUID,
        @Valid @RequestBody request: SmsSenderConfirmationRequest
    ): ResponseEntity<SmsSenderConfirmationResponse> {
        if (!studioRepository.existsById(studioId)) {
            throw EntityNotFoundException("Studio nie istnieje: $studioId")
        }
        val config = smsAutomationConfigRepository.findByStudioId(studioId)
            ?: throw EntityNotFoundException("Studio $studioId nie zgłosiło jeszcze nazwy nadawcy SMS")

        val confirmed = request.confirmed == true
        if (confirmed && config.smsSenderName.isNullOrBlank()) {
            throw pl.detailing.crm.shared.ValidationException("Studio nie ma ustawionej nazwy nadawcy — nie ma czego potwierdzać")
        }

        config.smsApiNameConfirmed = confirmed
        config.updatedAt = Instant.now()
        smsAutomationConfigRepository.save(config)

        log.info(
            "PLATFORM: SMS sender confirmation set studioId={} senderName={} confirmed={}",
            studioId, config.smsSenderName, confirmed
        )

        return ResponseEntity.ok(
            SmsSenderConfirmationResponse(
                studioId = studioId.toString(),
                senderName = config.smsSenderName,
                confirmed = config.smsApiNameConfirmed
            )
        )
    }
}
