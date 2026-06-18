package pl.detailing.crm.leads.acknowledge

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.leads.infrastructure.LeadRepository
import pl.detailing.crm.shared.EntityNotFoundException
import pl.detailing.crm.shared.ForbiddenException
import pl.detailing.crm.shared.LeadId
import pl.detailing.crm.shared.StudioId

@Service
class AcknowledgeLeadActivityHandler(
    private val leadRepository: LeadRepository
) {
    @Transactional
    suspend fun handle(leadId: LeadId, studioId: StudioId) = withContext(Dispatchers.IO) {
        val entity = leadRepository.findById(leadId.value)
            .orElseThrow { EntityNotFoundException("Lead nie został znaleziony: $leadId") }

        if (entity.studioId != studioId.value) {
            throw ForbiddenException("Lead nie należy do tego studia")
        }

        entity.newActivityAt = null
        leadRepository.save(entity)
    }
}
