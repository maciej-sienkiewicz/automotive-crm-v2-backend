package pl.detailing.crm.leads.userquote.delete

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.leads.infrastructure.LeadRepository
import pl.detailing.crm.leads.userquote.infrastructure.LeadUserQuoteRepository
import pl.detailing.crm.shared.*

data class DeleteUserQuoteCommand(
    val leadId: LeadId,
    val studioId: StudioId
)

@Service
class DeleteUserQuoteHandler(
    private val leadRepository: LeadRepository,
    private val userQuoteRepository: LeadUserQuoteRepository
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    suspend fun handle(command: DeleteUserQuoteCommand) =
        withContext(Dispatchers.IO) {
            val leadEntity = leadRepository.findById(command.leadId.value)
                .orElseThrow { EntityNotFoundException("Lead nie został znaleziony: ${command.leadId}") }

            if (leadEntity.studioId != command.studioId.value) {
                throw ForbiddenException("Lead nie należy do tego studia")
            }

            userQuoteRepository.deleteByLeadId(command.leadId.value)

            log.info("[LEADS] User quote deleted: leadId={}", command.leadId)
        }
}
