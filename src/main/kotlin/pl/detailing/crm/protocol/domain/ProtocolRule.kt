package pl.detailing.crm.protocol.domain

import pl.detailing.crm.shared.*
import java.time.Instant

/**
 * A protocol rule defines when a visit-document protocol template is shown.
 *
 * - GLOBAL_ALWAYS: shown for every visit at the given stage
 * - SERVICE_SPECIFIC: shown only if the visit includes one of the linked services
 *
 * Consent-based display is managed separately on ConsentDefinition.
 * Signing is always optional — no document blocks visit progression.
 */
data class ProtocolRule(
    val id: ProtocolRuleId,
    val studioId: StudioId,
    val templateId: ProtocolTemplateId,
    val triggerType: ProtocolTriggerType,
    val stage: ProtocolStage,
    val serviceIds: Set<ServiceId>,
    val displayOrder: Int,
    val createdBy: UserId,
    val updatedBy: UserId,
    val createdAt: Instant,
    val updatedAt: Instant
) {
    init {
        if (triggerType == ProtocolTriggerType.SERVICE_SPECIFIC) {
            require(serviceIds.isNotEmpty()) { "At least one serviceId is required for SERVICE_SPECIFIC rules" }
        }
        if (triggerType == ProtocolTriggerType.GLOBAL_ALWAYS) {
            require(serviceIds.isEmpty()) { "serviceIds must be empty for GLOBAL_ALWAYS rules" }
        }
        require(displayOrder >= 0) { "displayOrder must be non-negative" }
    }
}
