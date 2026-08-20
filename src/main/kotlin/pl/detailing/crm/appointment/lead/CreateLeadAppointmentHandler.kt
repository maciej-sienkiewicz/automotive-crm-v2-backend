package pl.detailing.crm.appointment.lead

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.appointment.create.CreateAppointmentHandler
import pl.detailing.crm.appointment.create.CreateAppointmentResult
import pl.detailing.crm.audit.domain.*
import pl.detailing.crm.leads.appointment.LeadSyncService
import pl.detailing.crm.leads.infrastructure.LeadRepository
import pl.detailing.crm.shared.*
import java.time.Instant

@Service
class CreateLeadAppointmentHandler(
    private val leadRepository: LeadRepository,
    private val createAppointmentHandler: CreateAppointmentHandler,
    private val leadSyncService: LeadSyncService,
    private val auditService: AuditService
) {
    private val log = LoggerFactory.getLogger(CreateLeadAppointmentHandler::class.java)

    @Transactional
    suspend fun handle(command: CreateLeadAppointmentCommand): CreateAppointmentResult =
        withContext(Dispatchers.IO) {
            val base = command.base
            val entity = leadRepository.findById(command.leadId.value)
                .orElseThrow { EntityNotFoundException("Lead nie został znaleziony: ${command.leadId}") }

            if (entity.studioId != base.studioId.value) {
                throw ForbiddenException("Lead nie należy do tego studia")
            }

            if (entity.appointmentId != null) {
                throw ValidationException("Lead ma już powiązaną rezerwację (appointmentId=${entity.appointmentId})")
            }

            val result = createAppointmentHandler.handle(base)

            leadSyncService.linkAppointment(
                leadEntity = entity,
                appointmentId = result.appointmentId.value,
                studioId = base.studioId.value,
                userId = base.userId.value,
                userDisplayName = base.userName ?: ""
            )

            log.info(
                "[LEADS] Appointment created from lead: leadId={}, appointmentId={}, studioId={}",
                entity.id, result.appointmentId.value, entity.studioId
            )

            auditService.log(
                LogAuditCommand(
                    studioId = base.studioId,
                    userId = base.userId,
                    userDisplayName = base.userName ?: "",
                    module = AuditModule.LEAD,
                    entityId = command.leadId.value.toString(),
                    entityDisplayName = entity.customerName,
                    action = AuditAction.LEAD_APPOINTMENT_CREATED,
                    metadata = mapOf(
                        "appointmentId" to result.appointmentId.value.toString(),
                        "startDateTime" to base.schedule.startDateTime.toString()
                    )
                )
            )

            result
        }
}
