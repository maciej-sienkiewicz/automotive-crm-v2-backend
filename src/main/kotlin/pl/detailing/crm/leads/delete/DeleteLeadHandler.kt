package pl.detailing.crm.leads.delete

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.leads.infrastructure.LeadRepository
import pl.detailing.crm.shared.EntityNotFoundException
import pl.detailing.crm.shared.ForbiddenException

@Service
class DeleteLeadHandler(
    private val leadRepository: LeadRepository
) {
    private val log = LoggerFactory.getLogger(DeleteLeadHandler::class.java)

    @Transactional
    suspend fun handle(command: DeleteLeadCommand): Unit =
        withContext(Dispatchers.IO) {
            // Find lead
            val entity = leadRepository.findById(command.leadId.value)
                .orElseThrow { EntityNotFoundException("Lead not found: ${command.leadId}") }

            // Verify studio ownership
            if (entity.studioId != command.studioId.value) {
                throw ForbiddenException("Lead does not belong to this studio")
            }

            // Delete
            leadRepository.delete(entity)

            log.info("[LEADS] Deleted lead: leadId={}, studioId={}",
                command.leadId.value, command.studioId.value)
        }
}
