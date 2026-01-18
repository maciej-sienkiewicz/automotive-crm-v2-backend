package pl.detailing.crm.protocol.rule

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import pl.detailing.crm.protocol.domain.ProtocolRule
import pl.detailing.crm.protocol.infrastructure.ProtocolRuleRepository
import pl.detailing.crm.shared.ProtocolStage
import pl.detailing.crm.shared.StudioId

@Service
class GetProtocolRulesHandler(
    private val protocolRuleRepository: ProtocolRuleRepository
) {

    suspend fun handle(studioId: StudioId, stage: ProtocolStage?): GetProtocolRulesResult =
        withContext(Dispatchers.IO) {
            val rules = if (stage != null) {
                protocolRuleRepository.findAllByStudioIdAndStage(studioId.value, stage)
            } else {
                protocolRuleRepository.findAllByStudioId(studioId.value)
            }

            GetProtocolRulesResult(rules.map { it.toDomain() })
        }
}

data class GetProtocolRulesResult(
    val rules: List<ProtocolRule>
)
