package pl.detailing.crm.leads.acknowledge

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.audit.domain.AuditAction
import pl.detailing.crm.audit.domain.AuditModule
import pl.detailing.crm.audit.domain.AuditService
import pl.detailing.crm.audit.domain.FieldChange
import pl.detailing.crm.audit.domain.LogAuditCommand
import pl.detailing.crm.auth.UserPrincipal
import pl.detailing.crm.leads.infrastructure.LeadRepository
import pl.detailing.crm.shared.EntityNotFoundException
import pl.detailing.crm.shared.ForbiddenException
import pl.detailing.crm.shared.LeadId
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import java.util.UUID

@Service
class AcknowledgeLeadActivityHandler(
    private val leadRepository: LeadRepository,
    private val auditService: AuditService
) {
    @Transactional
    suspend fun handle(leadId: LeadId, studioId: StudioId, principal: UserPrincipal) = withContext(Dispatchers.IO) {
        val entity = leadRepository.findById(leadId.value)
            .orElseThrow { EntityNotFoundException("Lead nie został znaleziony: $leadId") }

        if (entity.studioId != studioId.value) {
            throw ForbiddenException("Lead nie należy do tego studia")
        }

        auditService.logSync(LogAuditCommand(
            studioId = studioId,
            userId = principal.userId,
            userDisplayName = principal.fullName,
            module = AuditModule.LEAD,
            entityId = entity.id.toString(),
            entityDisplayName = entity.customerName ?: entity.contactIdentifier,
            action = AuditAction.LEAD_ACKNOWLEDGED,
        ))

        entity.newActivityAt = null
        leadRepository.save(entity)
    }
}
