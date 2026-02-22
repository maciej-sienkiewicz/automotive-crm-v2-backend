package pl.detailing.crm.protocol.rule

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.protocol.infrastructure.ProtocolRuleRepository
import pl.detailing.crm.shared.NotFoundException
import pl.detailing.crm.shared.ProtocolRuleId
import pl.detailing.crm.shared.StudioId

@Service
class DeleteProtocolRuleHandler(
    private val protocolRuleRepository: ProtocolRuleRepository
) {

    @Transactional
    suspend fun handle(command: DeleteProtocolRuleCommand) =
        withContext(Dispatchers.IO) {
            val entity = protocolRuleRepository.findByIdAndStudioId(
                command.ruleId.value,
                command.studioId.value
            ) ?: throw NotFoundException("Protocol rule not found: ${command.ruleId}")

            protocolRuleRepository.delete(entity)
        }
}

data class DeleteProtocolRuleCommand(
    val ruleId: ProtocolRuleId,
    val studioId: StudioId
)
