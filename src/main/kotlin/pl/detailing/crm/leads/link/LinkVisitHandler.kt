package pl.detailing.crm.leads.link

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.leads.infrastructure.LeadRepository
import pl.detailing.crm.shared.AlreadyLinkedException
import pl.detailing.crm.shared.EntityNotFoundException
import pl.detailing.crm.shared.ForbiddenException
import pl.detailing.crm.shared.LeadChangedEvent
import pl.detailing.crm.shared.LeadId
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UnprocessableEntityException
import pl.detailing.crm.visit.infrastructure.VisitRepository
import java.time.Instant
import java.util.UUID

data class LinkVisitCommand(
    val leadId: LeadId,
    val studioId: StudioId,
    val visitId: UUID?
)

@Service
class LinkVisitHandler(
    private val leadRepository: LeadRepository,
    private val visitRepository: VisitRepository,
    private val eventPublisher: ApplicationEventPublisher
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    suspend fun handle(command: LinkVisitCommand) = withContext(Dispatchers.IO) {
        val entity = leadRepository.findById(command.leadId.value)
            .orElseThrow { EntityNotFoundException("Lead nie został znaleziony: ${command.leadId}") }

        if (entity.studioId != command.studioId.value) {
            throw ForbiddenException("Lead nie należy do tego studia")
        }

        if (command.visitId == null) {
            entity.visitId = null
            entity.updatedAt = Instant.now()
            leadRepository.save(entity)
            log.info("[LEADS] Unlinked visit from leadId={}", command.leadId.value)
            eventPublisher.publishEvent(
                LeadChangedEvent(source = this@LinkVisitHandler, studioId = command.studioId, leadId = command.leadId)
            )
            return@withContext
        }

        // idempotent
        if (entity.visitId == command.visitId) return@withContext

        // check target visit exists in this studio
        visitRepository.findByIdAndStudioId(command.visitId, command.studioId.value)
            ?: throw UnprocessableEntityException("Wizyta nie istnieje: ${command.visitId}")

        // check no other lead already has this visit
        val conflicting = leadRepository.findByVisitId(command.visitId)
        if (conflicting != null && conflicting.id != command.leadId.value) {
            throw AlreadyLinkedException(
                linkedLeadId = conflicting.id.toString(),
                linkedLeadName = conflicting.customerName
            )
        }

        entity.visitId = command.visitId
        entity.updatedAt = Instant.now()
        leadRepository.save(entity)
        log.info("[LEADS] Linked visitId={} to leadId={}", command.visitId, command.leadId.value)
        eventPublisher.publishEvent(
            LeadChangedEvent(source = this@LinkVisitHandler, studioId = command.studioId, leadId = command.leadId)
        )
    }
}
