package pl.detailing.crm.leads.servicetags

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.leads.infrastructure.LeadRepository
import pl.detailing.crm.leads.services.LeadServiceTagEntity
import pl.detailing.crm.leads.services.LeadServiceTagRepository
import pl.detailing.crm.shared.EntityNotFoundException
import pl.detailing.crm.shared.ForbiddenException
import pl.detailing.crm.shared.LeadId
import pl.detailing.crm.shared.StudioId
import java.util.UUID

data class ServiceTagInput(
    val serviceId: UUID?,
    val serviceName: String
)

data class SetServiceTagsCommand(
    val leadId: LeadId,
    val studioId: StudioId,
    val tags: List<ServiceTagInput>
)

@Service
class SetServiceTagsHandler(
    private val leadRepository: LeadRepository,
    private val leadServiceTagRepository: LeadServiceTagRepository
) {
    private val log = LoggerFactory.getLogger(SetServiceTagsHandler::class.java)

    @Transactional
    suspend fun handle(command: SetServiceTagsCommand): List<LeadServiceTagEntity> =
        withContext(Dispatchers.IO) {
            val entity = leadRepository.findById(command.leadId.value)
                .orElseThrow { EntityNotFoundException("Lead nie został znaleziony: ${command.leadId}") }

            if (entity.studioId != command.studioId.value) {
                throw ForbiddenException("Lead nie należy do tego studia")
            }

            require(command.tags.size <= 20) { "Maksymalnie 20 tagów usług na leadzie" }

            leadServiceTagRepository.deleteByLeadId(command.leadId.value)

            val newTags = command.tags.map { input ->
                LeadServiceTagEntity(
                    leadId = command.leadId.value,
                    studioId = command.studioId.value,
                    serviceId = input.serviceId,
                    serviceName = input.serviceName.trim().take(200)
                )
            }

            val saved = leadServiceTagRepository.saveAll(newTags)

            log.info("[LEADS] Set service tags: leadId={}, count={}", entity.id, saved.size)

            saved
        }
}
