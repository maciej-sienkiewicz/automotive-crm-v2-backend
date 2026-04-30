package pl.detailing.crm.customer.consent

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import pl.detailing.crm.customer.consent.infrastructure.ConsentDefinitionRepository
import pl.detailing.crm.customer.consent.infrastructure.ConsentTemplateRepository
import pl.detailing.crm.customer.consent.infrastructure.CustomerConsentRepository
import pl.detailing.crm.shared.MarketingChannel
import java.util.UUID

/**
 * Checks whether a customer has a valid signed marketing consent for a given channel.
 *
 * Logic:
 *  - If the studio has no active consent definition covering the channel → sending is allowed.
 *  - If at least one definition covers the channel → the customer must have a valid consent
 *    for at least one of those definitions.
 *  - "Valid" means: signed the current active template, or signed any older template when
 *    requiresResign=false.
 *
 * When sending is blocked, a WARN is logged with enough context to diagnose the issue.
 * Callers are responsible for early-returning after a false result.
 */
@Service
class MarketingConsentChecker(
    private val consentDefinitionRepository: ConsentDefinitionRepository,
    private val consentTemplateRepository: ConsentTemplateRepository,
    private val customerConsentRepository: CustomerConsentRepository
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * @param customerId  customer to check
     * @param studioId    studio/tenant scope
     * @param channel     EMAIL or SMS
     * @param context     short description of the caller, used in the log (e.g. "WelcomeEmail visit=abc")
     * @return true if sending is allowed, false if blocked due to missing consent
     */
    fun canSend(customerId: UUID, studioId: UUID, channel: MarketingChannel, context: String): Boolean {
        val definitions = consentDefinitionRepository.findActiveByStudioId(studioId)
            .filter { channel in it.marketingChannels }

        if (definitions.isEmpty()) return true

        val hasValidConsent = definitions.any { definition ->
            customerHasValidConsent(customerId, definition.id, studioId)
        }

        if (!hasValidConsent) {
            logger.warn(
                "Marketing consent missing — message blocked | channel={} customerId={} studioId={} context={}",
                channel, customerId, studioId, context
            )
        }

        return hasValidConsent
    }

    private fun customerHasValidConsent(customerId: UUID, definitionId: UUID, studioId: UUID): Boolean {
        val activeTemplate = consentTemplateRepository.findActiveByDefinitionIdAndStudioId(
            definitionId, studioId
        ) ?: return false

        val signedActive = customerConsentRepository.findLatestByCustomerAndTemplate(
            customerId, activeTemplate.id, studioId
        )
        if (signedActive != null && signedActive.revokedAt == null) return true

        if (!activeTemplate.requiresResign) {
            val allTemplateIds = consentTemplateRepository
                .findAllByDefinitionIdAndStudioId(definitionId, studioId)
                .map { it.id }

            return customerConsentRepository
                .findAllByCustomerIdAndStudioId(customerId, studioId)
                .any { it.templateId in allTemplateIds && it.revokedAt == null }
        }

        return false
    }
}
