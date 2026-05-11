package pl.detailing.crm.protocol.rule

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.protocol.domain.ProtocolRule
import pl.detailing.crm.protocol.infrastructure.ProtocolRuleEntity
import pl.detailing.crm.protocol.infrastructure.ProtocolRuleRepository
import pl.detailing.crm.protocol.infrastructure.ProtocolTemplateRepository
import pl.detailing.crm.shared.*
import java.time.Instant

@Service
class CreateProtocolRuleHandler(
    private val protocolRuleRepository: ProtocolRuleRepository,
    private val protocolTemplateRepository: ProtocolTemplateRepository
) {

    @Transactional
    suspend fun handle(command: CreateProtocolRuleCommand): CreateProtocolRuleResult =
        withContext(Dispatchers.IO) {
            protocolTemplateRepository.findByIdAndStudioId(command.templateId.value, command.studioId.value)
                ?: throw ValidationException("Protocol template not found")

            when (command.triggerType) {
                ProtocolTriggerType.SERVICE_SPECIFIC -> {
                    if (command.serviceIds.isEmpty()) {
                        throw ValidationException("At least one serviceId is required for SERVICE_SPECIFIC rules")
                    }
                }
                ProtocolTriggerType.GLOBAL_ALWAYS -> {
                    if (command.serviceIds.isNotEmpty()) {
                        throw ValidationException("serviceIds must be empty for GLOBAL_ALWAYS rules")
                    }
                }
            }

            val rule = ProtocolRule(
                id = ProtocolRuleId.random(),
                studioId = command.studioId,
                templateId = command.templateId,
                triggerType = command.triggerType,
                stage = command.stage,
                serviceIds = command.serviceIds,
                displayOrder = command.displayOrder,
                createdBy = command.userId,
                updatedBy = command.userId,
                createdAt = Instant.now(),
                updatedAt = Instant.now()
            )

            protocolRuleRepository.save(ProtocolRuleEntity.fromDomain(rule))
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
    val displayOrder: Int
)

data class CreateProtocolRuleResult(
    val rule: ProtocolRule
)
