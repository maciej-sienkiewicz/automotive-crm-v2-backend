package pl.detailing.crm.protocol.infrastructure

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import pl.detailing.crm.customer.consent.infrastructure.ConsentDefinitionRepository
import pl.detailing.crm.customer.consent.infrastructure.ConsentTemplateRepository
import pl.detailing.crm.customer.consent.infrastructure.CustomerConsentRepository
import pl.detailing.crm.shared.*
import pl.detailing.crm.visit.infrastructure.VisitRepository

/**
 * Resolves which documents must be presented during a visit stage (CHECK_IN / CHECK_OUT).
 *
 * Two sources of required documents:
 *  1. ProtocolRules (GLOBAL_ALWAYS, SERVICE_SPECIFIC) — visit-specific PDFs auto-filled with CRM data.
 *  2. Active ConsentDefinitions matching the stage — shown only when the customer lacks a valid consent.
 *
 * Results are deduplicated and sorted by displayOrder.
 */
@Service
class ProtocolResolver(
    private val protocolRuleRepository: ProtocolRuleRepository,
    private val visitRepository: VisitRepository,
    private val consentDefinitionRepository: ConsentDefinitionRepository,
    private val consentTemplateRepository: ConsentTemplateRepository,
    private val customerConsentRepository: CustomerConsentRepository
) {

    suspend fun resolveRequiredProtocols(
        visitId: VisitId,
        studioId: StudioId,
        stage: ProtocolStage
    ): List<ResolvedProtocol> = withContext(Dispatchers.IO) {
        val visit = visitRepository.findByIdAndStudioIdWithPhotos(visitId.value, studioId.value)
            ?: throw IllegalArgumentException("Visit not found: $visitId")

        val visitDomain = visit.toDomain()

        val serviceIds = visitDomain.serviceItems
            .filter {
                it.status == VisitServiceStatus.CONFIRMED ||
                it.status == VisitServiceStatus.APPROVED
            }
            .mapNotNull { it.serviceId?.value }
            .distinct()

        // --- Visit-document protocols (GLOBAL_ALWAYS + SERVICE_SPECIFIC) ---

        val globalRules = protocolRuleRepository.findAllByStudioIdAndStageAndTriggerType(
            studioId.value, stage, ProtocolTriggerType.GLOBAL_ALWAYS
        ).map { rule ->
            ResolvedProtocol.fromVisitDocument(
                templateId = ProtocolTemplateId(rule.templateId),
                isMandatory = rule.isMandatory,
                displayOrder = rule.displayOrder
            )
        }

        val serviceSpecificRules = if (serviceIds.isNotEmpty()) {
            protocolRuleRepository.findAllByStudioIdAndStageAndServiceIdIn(
                studioId.value, stage, serviceIds
            ).map { rule ->
                ResolvedProtocol.fromVisitDocument(
                    templateId = ProtocolTemplateId(rule.templateId),
                    isMandatory = rule.isMandatory,
                    displayOrder = rule.displayOrder
                )
            }
        } else {
            emptyList()
        }

        // --- Consent protocols ---

        val consentProtocols = consentDefinitionRepository
            .findActiveByStudioIdAndStage(studioId.value, stage)
            .mapNotNull { definitionEntity ->
                val definitionId = ConsentDefinitionId(definitionEntity.id)

                // Skip if customer already has a valid or acceptable consent
                if (customerHasValidConsent(visit.customerId, definitionEntity.id, studioId.value)) {
                    return@mapNotNull null
                }

                val activeTemplate = consentTemplateRepository.findActiveByDefinitionIdAndStudioId(
                    definitionEntity.id, studioId.value
                ) ?: return@mapNotNull null  // No template uploaded yet — skip silently

                ResolvedProtocol.fromConsent(
                    consentDefinitionId = definitionId,
                    consentTemplateId = ConsentTemplateId(activeTemplate.id),
                    isMandatory = definitionEntity.isMandatory,
                    displayOrder = definitionEntity.displayOrder
                )
            }

        // Combine, deduplicate by templateId (for visit docs) or consentDefinitionId (for consents),
        // then sort by displayOrder
        val visitDocsByTemplate = (globalRules + serviceSpecificRules)
            .distinctBy { it.templateId }

        val consentsByDefinition = consentProtocols
            .distinctBy { it.consentDefinitionId }

        (visitDocsByTemplate + consentsByDefinition).sortedBy { it.displayOrder }
    }

    private fun customerHasValidConsent(
        customerId: java.util.UUID,
        consentDefinitionId: java.util.UUID,
        studioId: java.util.UUID
    ): Boolean {
        val activeTemplate = consentTemplateRepository.findActiveByDefinitionIdAndStudioId(
            consentDefinitionId, studioId
        ) ?: return false

        val signedActiveTemplate = customerConsentRepository.findLatestByCustomerAndTemplate(
            customerId, activeTemplate.id, studioId
        )
        if (signedActiveTemplate != null && signedActiveTemplate.revokedAt == null) return true

        if (!activeTemplate.requiresResign) {
            val allTemplateIds = consentTemplateRepository
                .findAllByDefinitionIdAndStudioId(consentDefinitionId, studioId)
                .map { it.id }

            val hasOlderConsent = customerConsentRepository
                .findAllByCustomerIdAndStudioId(customerId, studioId)
                .any { it.templateId in allTemplateIds && it.revokedAt == null }

            if (hasOlderConsent) return true
        }

        return false
    }

    suspend fun areMandatoryProtocolsSatisfied(
        visitId: VisitId,
        studioId: StudioId,
        stage: ProtocolStage,
        existingProtocols: List<VisitProtocolEntity>
    ): Boolean = withContext(Dispatchers.IO) {
        val required = resolveRequiredProtocols(visitId, studioId, stage)
        val mandatory = required.filter { it.isMandatory }

        mandatory.all { resolved ->
            existingProtocols.any { protocol ->
                val matchesDoc = resolved.templateId != null &&
                    protocol.templateId == resolved.templateId.value

                val matchesConsent = resolved.consentDefinitionId != null &&
                    protocol.consentDefinitionId == resolved.consentDefinitionId.value

                (matchesDoc || matchesConsent) &&
                    protocol.status == VisitProtocolStatus.SIGNED
            }
        }
    }
}
