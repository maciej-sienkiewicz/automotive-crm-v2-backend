package pl.detailing.crm.smscampaigns

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.smscampaigns.domain.SmsAutomationConfig
import pl.detailing.crm.smscampaigns.domain.SmsAutomationConfigRepository
import pl.detailing.crm.smscredits.SmsCreditService
import pl.detailing.crm.subscription.entitlement.domain.PlanKey

/**
 * Makes the communication module usable the moment it is paid for.
 *
 * Buying the module used to hand the studio a switch that still refused to send:
 * every message rule ships disabled with no text, and the credit balance starts at
 * zero, so the first attempt failed on a template and the second on credits. Both
 * are things we can simply provide, so we do — the purchase guide promises exactly
 * this, and this is where the promise is kept.
 *
 * Idempotent throughout: the payment webhook may fire more than once per order.
 */
@Service
class CommunicationOnboardingService(
    private val smsAutomationConfigRepository: SmsAutomationConfigRepository,
    private val smsCreditService: SmsCreditService
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    companion object {
        /** Starter SMS pack per plan. Mirrored by STARTER_CREDITS on the frontend. */
        val STARTER_CREDITS: Map<PlanKey, Int> = mapOf(
            PlanKey.BASIC to 30,
            PlanKey.FULL to 150
        )

        private const val STARTER_DESCRIPTION = "Pakiet startowy do modułu komunikacji"
    }

    @Transactional
    fun onCommunicationEnabled(studioId: StudioId, planKey: PlanKey) {
        enableAllTemplates(studioId)

        val amount = STARTER_CREDITS[planKey] ?: 0
        smsCreditService.grantStarterCredits(studioId, amount, "$STARTER_DESCRIPTION (${planKey.name})")
    }

    /**
     * Switches every message on, filling any blank body from the shipped defaults.
     *
     * A rule only sends when it is enabled AND has text, so flipping the flag alone
     * would reproduce the silent failure this exists to remove. Studios that already
     * wrote their own text keep it.
     */
    private fun enableAllTemplates(studioId: StudioId) {
        val defaults = SmsAutomationConfig.defaultFor(studioId)
        val current = smsAutomationConfigRepository.findByStudioId(studioId) ?: defaults

        val enabled = current.copy(
            preVisit = current.preVisit.copy(
                enabled = true,
                messageTemplate = current.preVisit.messageTemplate.ifBlank { defaults.preVisit.messageTemplate }
            ),
            postVisit = current.postVisit.copy(
                enabled = true,
                messageTemplate = current.postVisit.messageTemplate.ifBlank { defaults.postVisit.messageTemplate }
            ),
            delayedReminder = current.delayedReminder.copy(
                enabled = true,
                messageTemplate = current.delayedReminder.messageTemplate.ifBlank { defaults.delayedReminder.messageTemplate }
            ),
            bookingConfirmation = current.bookingConfirmation.copy(
                enabled = true,
                messageTemplate = current.bookingConfirmation.messageTemplate.ifBlank { defaults.bookingConfirmation.messageTemplate }
            ),
            rescheduleConfirmation = current.rescheduleConfirmation.copy(
                enabled = true,
                messageTemplate = current.rescheduleConfirmation.messageTemplate.ifBlank { defaults.rescheduleConfirmation.messageTemplate }
            ),
            visitReadyForPickup = current.visitReadyForPickup.copy(
                enabled = true,
                messageTemplate = current.visitReadyForPickup.messageTemplate.ifBlank { defaults.visitReadyForPickup.messageTemplate }
            ),
            visitCardLink = current.visitCardLink.copy(
                enabled = true,
                messageTemplate = current.visitCardLink.messageTemplate.ifBlank { defaults.visitCardLink.messageTemplate }
            ),
            reservationCardLink = current.reservationCardLink.copy(
                enabled = true,
                messageTemplate = current.reservationCardLink.messageTemplate.ifBlank { defaults.reservationCardLink.messageTemplate }
            ),
            upsellConsent = current.upsellConsent.copy(
                enabled = true,
                messageTemplate = current.upsellConsent.messageTemplate.ifBlank { defaults.upsellConsent.messageTemplate }
            ),
            signatureRequest = current.signatureRequest.copy(
                enabled = true,
                messageTemplate = current.signatureRequest.messageTemplate.ifBlank { defaults.signatureRequest.messageTemplate }
            )
        )

        smsAutomationConfigRepository.save(enabled)
        logger.info("Studio={} — all SMS templates enabled with the communication module", studioId)
    }
}
