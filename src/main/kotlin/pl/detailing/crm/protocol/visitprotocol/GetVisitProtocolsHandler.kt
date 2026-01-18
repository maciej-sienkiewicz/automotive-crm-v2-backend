package pl.detailing.crm.protocol.visitprotocol

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import pl.detailing.crm.protocol.domain.VisitProtocol
import pl.detailing.crm.protocol.infrastructure.VisitProtocolRepository
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.VisitId

@Service
class GetVisitProtocolsHandler(
    private val visitProtocolRepository: VisitProtocolRepository
) {

    suspend fun handle(visitId: VisitId, studioId: StudioId): GetVisitProtocolsResult =
        withContext(Dispatchers.IO) {
            val protocols = visitProtocolRepository.findAllByVisitIdAndStudioId(
                visitId.value,
                studioId.value
            ).map { it.toDomain() }

            GetVisitProtocolsResult(protocols)
        }
}

data class GetVisitProtocolsResult(
    val protocols: List<VisitProtocol>
)
