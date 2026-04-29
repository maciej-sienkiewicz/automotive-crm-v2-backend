package pl.detailing.crm.protocol.infrastructure

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import pl.detailing.crm.customer.consent.infrastructure.ConsentTemplateRepository
import pl.detailing.crm.customer.consent.infrastructure.CustomerConsentRepository
import pl.detailing.crm.protocol.domain.ProtocolRule
import pl.detailing.crm.shared.*
import pl.detailing.crm.visit.infrastructure.VisitRepository

/**
 * Service for resolving which protocols are required for a visit.
 *
 * The resolver applies business rules to determine the documentation requirements:
 * 1. GLOBAL_ALWAYS rules: Protocols required for all visits at a specific stage
 * 2. SERVICE_SPECIFIC rules: Protocols required only if visit includes specific services
 * 3. CUSTOMER_CONSENT_REQUIRED rules: Protocols required only if customer lacks valid consent
 * 4. Deduplication: If multiple services trigger the same protocol, it appears only once
 */
@Service
class ProtocolResolver(
    private val protocolRuleRepository: ProtocolRuleRepository,
    private val visitRepository: VisitRepository,
    private val consentTemplateRepository: ConsentTemplateRepository,
    private val customerConsentRepository: CustomerConsentRepository
) {

    /**
     * Resolve required protocols for a visit at a specific stage.
     *
     * @param visitId The visit ID
     * @param studioId The studio ID (for multi-tenancy)
     * @param stage The protocol stage (CHECK_IN or CHECK_OUT)
     * @return List of unique protocol rules required for this visit, sorted by display order
     */
    suspend fun resolveRequiredProtocols(
        visitId: VisitId,
        studioId: StudioId,
        stage: ProtocolStage
    ): List<ProtocolRule> = withContext(Dispatchers.IO) {
        // Fetch the visit to get service IDs and customer ID
        val visit = visitRepository.findByIdAndStudioIdWithPhotos(visitId.value, studioId.value)
            ?: throw IllegalArgumentException("Visit not found: $visitId")

        val visitDomain = visit.toDomain()

        // Extract unique service IDs from the visit
        val serviceIds = visitDomain.serviceItems
            .filter {
                it.status == VisitServiceStatus.CONFIRMED ||
                it.status == VisitServiceStatus.APPROVED
            }
            .mapNotNull { it.serviceId?.value }
            .distinct()

        // Fetch GLOBAL_ALWAYS rules for this stage
        val globalRules = protocolRuleRepository.findAllByStudioIdAndStageAndTriggerType(
            studioId.value,
            stage,
            ProtocolTriggerType.GLOBAL_ALWAYS
        ).map { it.toDomain() }

        // Fetch SERVICE_SPECIFIC rules for this stage and the visit's services
        val serviceSpecificRules = if (serviceIds.isNotEmpty()) {
            protocolRuleRepository.findAllByStudioIdAndStageAndServiceIdIn(
                studioId.value,
                stage,
                serviceIds
            ).map { it.toDomain() }
        } else {
            emptyList()
        }

        // Fetch CUSTOMER_CONSENT_REQUIRED rules and filter by customer consent status
        val consentRequiredRules = protocolRuleRepository.findAllByStudioIdAndStageAndTriggerType(
            studioId.value,
            stage,
            ProtocolTriggerType.CUSTOMER_CONSENT_REQUIRED
        ).map { it.toDomain() }.filter { rule ->
            val defId = rule.consentDefinitionId ?: return@filter false
            !customerHasValidConsent(visit.customerId, defId.value, studioId.value)
        }

        // Combine and deduplicate by template ID
        val allRules = (globalRules + serviceSpecificRules + consentRequiredRules)
            .distinctBy { it.templateId }
            .sortedBy { it.displayOrder }

        allRules
    }

    /**
     * Returns true if the customer has valid consent for the given definition.
     *
     * Valid means:
     * - Signed the current active template, OR
     * - Signed an older template and the current active template does not require re-sign (OUTDATED but acceptable)
     */
    private fun customerHasValidConsent(
        customerId: java.util.UUID,
        consentDefinitionId: java.util.UUID,
        studioId: java.util.UUID
    ): Boolean {
        val activeTemplate = consentTemplateRepository.findActiveByDefinitionIdAndStudioId(
            consentDefinitionId, studioId
        ) ?: return false

        // Check if customer signed the current active template
        val signedActiveTemplate = customerConsentRepository.findLatestByCustomerAndTemplate(
            customerId, activeTemplate.id, studioId
        )
        if (signedActiveTemplate != null && signedActiveTemplate.revokedAt == null) return true

        // OUTDATED: customer signed an older version and re-sign is not required
        if (!activeTemplate.requiresResign) {
            val allTemplatesForDefinition = consentTemplateRepository
                .findAllByDefinitionIdAndStudioId(consentDefinitionId, studioId)
                .map { it.id }

            val hasOlderConsent = customerConsentRepository
                .findAllByCustomerIdAndStudioId(customerId, studioId)
                .any { it.templateId in allTemplatesForDefinition && it.revokedAt == null }

            if (hasOlderConsent) return true
        }

        return false
    }

    /**
     * Check if any mandatory protocols are missing for a visit at a specific stage.
     *
     * @return true if all mandatory protocols are present and signed, false otherwise
     */
    suspend fun areMandatoryProtocolsSatisfied(
        visitId: VisitId,
        studioId: StudioId,
        stage: ProtocolStage,
        existingProtocols: List<VisitProtocolEntity>
    ): Boolean = withContext(Dispatchers.IO) {
        val requiredRules = resolveRequiredProtocols(visitId, studioId, stage)
        val mandatoryRules = requiredRules.filter { it.isMandatory }

        // Check if all mandatory protocols are present and signed
        mandatoryRules.all { rule ->
            existingProtocols.any { protocol ->
                protocol.templateId == rule.templateId.value &&
                protocol.status == VisitProtocolStatus.SIGNED
            }
        }
    }
}
