package pl.detailing.crm.leads.lostreason

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.audit.domain.AuditAction
import pl.detailing.crm.audit.domain.AuditModule
import pl.detailing.crm.audit.domain.AuditService
import pl.detailing.crm.audit.domain.FieldChange
import pl.detailing.crm.audit.domain.LogAuditCommand
import pl.detailing.crm.leads.infrastructure.LeadRepository
import pl.detailing.crm.shared.EntityNotFoundException
import pl.detailing.crm.shared.ForbiddenException
import pl.detailing.crm.shared.LeadId
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import java.time.Instant

data class UpdateLostReasonCommand(
    val leadId: LeadId,
    val studioId: StudioId,
    val requestingUserId: UserId,
    val requestingUserName: String?,
    val lostReason: String?
)

@Service
class UpdateLostReasonHandler(
    private val leadRepository: LeadRepository,
    private val auditService: AuditService
) {
    private val log = LoggerFactory.getLogger(UpdateLostReasonHandler::class.java)

    @Transactional
    suspend fun handle(command: UpdateLostReasonCommand) = withContext(Dispatchers.IO) {
        val entity = leadRepository.findById(command.leadId.value)
            .orElseThrow { EntityNotFoundException("Lead nie został znaleziony: ${command.leadId}") }

        if (entity.studioId != command.studioId.value) {
            throw ForbiddenException("Lead nie należy do tego studia")
        }

        val trimmed = command.lostReason?.trim()?.ifBlank { null }
        if (trimmed != null && trimmed.length > 500) {
            throw IllegalArgumentException("Powód utraty nie może przekraczać 500 znaków")
        }

        val old = entity.lostReason
        entity.lostReason = trimmed
        entity.updatedAt = Instant.now()
        leadRepository.save(entity)

        log.info("[LEADS] Updated lost reason: leadId={}", entity.id)

        auditService.log(LogAuditCommand(
            studioId = command.studioId,
            userId = command.requestingUserId,
            userDisplayName = command.requestingUserName ?: "",
            module = AuditModule.LEAD,
            entityId = command.leadId.value.toString(),
            entityDisplayName = entity.customerName,
            action = AuditAction.LEAD_LOST_REASON_UPDATED,
            changes = listOf(FieldChange("lostReason", old, trimmed))
        ))
    }
}
