package pl.detailing.crm.protocol.infrastructure

import jakarta.persistence.*
import pl.detailing.crm.protocol.domain.ProtocolRule
import pl.detailing.crm.shared.*
import java.time.Instant
import java.util.*

/**
 * JPA entity for ProtocolRule.
 * Defines when a protocol template should be required for a visit.
 */
@Entity
@Table(
    name = "protocol_rules",
    indexes = [
        Index(name = "idx_protocol_rules_studio_stage", columnList = "studio_id, stage"),
        Index(name = "idx_protocol_rules_template", columnList = "template_id"),
        Index(name = "idx_protocol_rules_display_order", columnList = "studio_id, display_order")
    ]
)
class ProtocolRuleEntity(
    @Id
    @Column(name = "id", columnDefinition = "uuid")
    val id: UUID,

    @Column(name = "studio_id", nullable = false, columnDefinition = "uuid")
    val studioId: UUID,

    @Column(name = "template_id", nullable = false, columnDefinition = "uuid")
    val templateId: UUID,

    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_type", nullable = false, length = 50)
    val triggerType: ProtocolTriggerType,

    @Enumerated(EnumType.STRING)
    @Column(name = "stage", nullable = false, length = 50)
    val stage: ProtocolStage,

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(
        name = "protocol_rule_services",
        joinColumns = [JoinColumn(name = "protocol_rule_id")],
        indexes = [
            Index(name = "idx_protocol_rule_services_rule_id", columnList = "protocol_rule_id"),
            Index(name = "idx_protocol_rule_services_service_id", columnList = "service_id")
        ]
    )
    @Column(name = "service_id", columnDefinition = "uuid")
    val serviceIds: MutableSet<UUID> = mutableSetOf(),

    @Column(name = "is_mandatory", nullable = false)
    var isMandatory: Boolean = true,

    @Column(name = "display_order", nullable = false)
    var displayOrder: Int = 0,

    @Column(name = "created_by", nullable = false, columnDefinition = "uuid")
    val createdBy: UUID,

    @Column(name = "updated_by", nullable = false, columnDefinition = "uuid")
    var updatedBy: UUID,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now()
) {
    fun toDomain(): ProtocolRule = ProtocolRule(
        id = ProtocolRuleId(id),
        studioId = StudioId(studioId),
        templateId = ProtocolTemplateId(templateId),
        triggerType = triggerType,
        stage = stage,
        serviceIds = serviceIds.map { ServiceId(it) }.toSet(),
        isMandatory = isMandatory,
        displayOrder = displayOrder,
        createdBy = UserId(createdBy),
        updatedBy = UserId(updatedBy),
        createdAt = createdAt,
        updatedAt = updatedAt
    )

    companion object {
        fun fromDomain(rule: ProtocolRule): ProtocolRuleEntity =
            ProtocolRuleEntity(
                id = rule.id.value,
                studioId = rule.studioId.value,
                templateId = rule.templateId.value,
                triggerType = rule.triggerType,
                stage = rule.stage,
                serviceIds = rule.serviceIds.map { it.value }.toMutableSet(),
                isMandatory = rule.isMandatory,
                displayOrder = rule.displayOrder,
                createdBy = rule.createdBy.value,
                updatedBy = rule.updatedBy.value,
                createdAt = rule.createdAt,
                updatedAt = rule.updatedAt
            )
    }
}
