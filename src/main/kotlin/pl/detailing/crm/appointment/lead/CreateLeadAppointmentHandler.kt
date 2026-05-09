package pl.detailing.crm.appointment.lead

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.appointment.create.CreateAppointmentCommand
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
            val entity = leadRepository.findById(command.leadId.value)
                .orElseThrow { EntityNotFoundException("Lead not found: ${command.leadId}") }

            if (entity.studioId != command.studioId.value) {
                throw ForbiddenException("Lead does not belong to this studio")
            }

            if (entity.appointmentId != null) {
                throw ValidationException("Lead already has an appointment linked (appointmentId=${entity.appointmentId})")
            }

            val result = createAppointmentHandler.handle(
                CreateAppointmentCommand(
                    studioId = command.studioId,
                    userId = command.userId,
                    userName = command.userName,
                    customer = command.customer,
                    vehicle = command.vehicle,
                    services = command.services,
                    schedule = command.schedule,
                    appointmentTitle = command.appointmentTitle,
                    appointmentColorId = command.appointmentColorId,
                    note = command.note,
                    sendReminderSms = command.sendReminderSms
                )
            )

            leadSyncService.linkAppointment(
                leadEntity = entity,
                appointmentId = result.appointmentId.value,
                studioId = command.studioId.value,
                userId = command.userId.value,
                userDisplayName = command.userName ?: ""
            )

            log.info(
                "[LEADS] Appointment created from lead: leadId={}, appointmentId={}, studioId={}",
                entity.id, result.appointmentId.value, entity.studioId
            )

            auditService.log(
                LogAuditCommand(
                    studioId = command.studioId,
                    userId = command.userId,
                    userDisplayName = command.userName ?: "",
                    module = AuditModule.LEAD,
                    entityId = command.leadId.value.toString(),
                    entityDisplayName = entity.customerName,
                    action = AuditAction.LEAD_APPOINTMENT_CREATED,
                    metadata = mapOf(
                        "appointmentId" to result.appointmentId.value.toString(),
                        "startDateTime" to command.schedule.startDateTime.toString()
                    )
                )
            )

            result
        }
}
