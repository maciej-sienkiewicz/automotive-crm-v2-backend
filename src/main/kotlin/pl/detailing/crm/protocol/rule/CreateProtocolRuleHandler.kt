package pl.detailing.crm.protocol.rule

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.customer.consent.infrastructure.ConsentDefinitionRepository
import pl.detailing.crm.protocol.domain.ProtocolRule
import pl.detailing.crm.protocol.infrastructure.ProtocolRuleEntity
import pl.detailing.crm.protocol.infrastructure.ProtocolRuleRepository
import pl.detailing.crm.protocol.infrastructure.ProtocolTemplateRepository
import pl.detailing.crm.shared.*
import java.time.Instant

@Service
class CreateProtocolRuleHandler(
    private val protocolRuleRepository: ProtocolRuleRepository,
    private val protocolTemplateRepository: ProtocolTemplateRepository,
    private val consentDefinitionRepository: ConsentDefinitionRepository
) {

    @Transactional
    suspend fun handle(command: CreateProtocolRuleCommand): CreateProtocolRuleResult =
        withContext(Dispatchers.IO) {
            // Validate template exists
            protocolTemplateRepository.findByIdAndStudioId(
                command.templateId.value,
                command.studioId.value
            ) ?: throw ValidationException("Protocol template not found")

            // Validate trigger-type-specific constraints
            when (command.triggerType) {
                ProtocolTriggerType.SERVICE_SPECIFIC -> {
                    if (command.serviceIds.isEmpty()) {
                        throw ValidationException("At least one Service ID is required for SERVICE_SPECIFIC rules")
                    }
                }
                ProtocolTriggerType.GLOBAL_ALWAYS -> {
                    if (command.serviceIds.isNotEmpty()) {
                        throw ValidationException("Service IDs must be empty for GLOBAL_ALWAYS rules")
                    }
                }
                ProtocolTriggerType.CUSTOMER_CONSENT_REQUIRED -> {
                    if (command.consentDefinitionId == null) {
                        throw ValidationException("Consent definition ID is required for CUSTOMER_CONSENT_REQUIRED rules")
                    }
                    if (command.serviceIds.isNotEmpty()) {
                        throw ValidationException("Service IDs must be empty for CUSTOMER_CONSENT_REQUIRED rules")
                    }
                    consentDefinitionRepository.findByIdAndStudioId(
                        command.consentDefinitionId.value,
                        command.studioId.value
                    ) ?: throw ValidationException("Consent definition not found")
                }
            }

            val rule = ProtocolRule(
                id = ProtocolRuleId.random(),
                studioId = command.studioId,
                templateId = command.templateId,
                triggerType = command.triggerType,
                stage = command.stage,
                serviceIds = command.serviceIds,
                consentDefinitionId = command.consentDefinitionId,
                isMandatory = command.isMandatory,
                displayOrder = command.displayOrder,
                createdBy = command.userId,
                updatedBy = command.userId,
                createdAt = Instant.now(),
                updatedAt = Instant.now()
            )

            val entity = ProtocolRuleEntity.fromDomain(rule)
            protocolRuleRepository.save(entity)

            CreateProtocolRuleResult(rule)
        }
}

data class CreateProtocolRuleCommand(
    val studioId: StudioId,
    val userId: UserId,
    val templateId: ProtocolTemplateId,
    val triggerType: ProtocolTriggerType,
    val stage: ProtocolStage,
    val serviceIds: Set<ServiceId>,
    val consentDefinitionId: ConsentDefinitionId?,
    val isMandatory: Boolean,
    val displayOrder: Int
)

data class CreateProtocolRuleResult(
    val rule: ProtocolRule
)
