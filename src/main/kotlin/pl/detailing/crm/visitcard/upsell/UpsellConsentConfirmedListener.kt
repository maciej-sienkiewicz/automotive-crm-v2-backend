package pl.detailing.crm.visitcard.upsell

import org.slf4j.LoggerFactory
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.smscampaigns.consent.SmsConsentConfirmedEvent
import pl.detailing.crm.visitcard.upsell.infrastructure.UpsellSuggestionStatus
import pl.detailing.crm.visitcard.upsell.infrastructure.VisitUpsellSuggestionRepository
import java.time.Instant

/**
 * When the customer confirms a consent SMS with "TAK", all REQUESTED upsell
 * suggestions on that visit become CONFIRMED — their pending service items were
 * just approved by [pl.detailing.crm.smscampaigns.consent.SmsConsentService].
 *
 * Runs in the same transaction as the approval (REQUIRED propagation), so the
 * suggestion status can never diverge from the service item status.
 */
@Component
class UpsellConsentConfirmedListener(
    private val suggestionRepository: VisitUpsellSuggestionRepository
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @EventListener
    @Transactional(propagation = Propagation.REQUIRED)
    fun onConsentConfirmed(event: SmsConsentConfirmedEvent) {
        val requested = suggestionRepository.findAllByVisitIdAndStudioIdAndStatus(
            event.visitId, event.studioId, UpsellSuggestionStatus.REQUESTED
        )
        if (requested.isEmpty()) return

        val now = Instant.now()
        requested.forEach {
            it.status = UpsellSuggestionStatus.CONFIRMED
            it.confirmedAt = now
        }
        suggestionRepository.saveAll(requested)

        logger.info(
            "Upsell suggestions confirmed via SMS TAK | visit={} count={}",
            event.visitId, requested.size
        )
    }
}
