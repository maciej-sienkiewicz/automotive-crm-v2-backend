package pl.detailing.crm.protocol.domain

import pl.detailing.crm.shared.*
import java.time.Instant

/**
 * Protocol rule defines when a protocol template should be required for a visit.
 *
 * Rules can be:
 * - GLOBAL_ALWAYS: Required for all visits at a specific stage
 * - SERVICE_SPECIFIC: Required only if visit includes one or more specific services
 */
data class ProtocolRule(
    val id: ProtocolRuleId,
    val studioId: StudioId,
    val templateId: ProtocolTemplateId,
    val triggerType: ProtocolTriggerType,
    val stage: ProtocolStage,
    val serviceIds: Set<ServiceId>,     // Required when triggerType is SERVICE_SPECIFIC (can be multiple services)
    val isMandatory: Boolean,           // If true, visit cannot proceed without this protocol
    val displayOrder: Int,              // Order in which protocols appear in the UI
    val createdBy: UserId,
    val updatedBy: UserId,
    val createdAt: Instant,
    val updatedAt: Instant
) {
    init {
        if (triggerType == ProtocolTriggerType.SERVICE_SPECIFIC) {
            require(serviceIds.isNotEmpty()) { "At least one Service ID is required for SERVICE_SPECIFIC rules" }
        }
        if (triggerType == ProtocolTriggerType.GLOBAL_ALWAYS) {
            require(serviceIds.isEmpty()) { "Service IDs must be empty for GLOBAL_ALWAYS rules" }
        }
        require(displayOrder >= 0) { "Display order must be non-negative" }
    }

    fun updateOrder(newOrder: Int, updatedBy: UserId): ProtocolRule {
        require(newOrder >= 0) { "Display order must be non-negative" }
        return copy(
            displayOrder = newOrder,
            updatedBy = updatedBy,
            updatedAt = Instant.now()
        )
    }

    fun toggleMandatory(updatedBy: UserId): ProtocolRule {
        return copy(
            isMandatory = !isMandatory,
            updatedBy = updatedBy,
            updatedAt = Instant.now()
        )
    }
}
