package pl.detailing.crm.leads.link

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.appointment.infrastructure.AppointmentRepository
import pl.detailing.crm.leads.infrastructure.LeadRepository
import pl.detailing.crm.shared.AlreadyLinkedException
import pl.detailing.crm.shared.EntityNotFoundException
import pl.detailing.crm.shared.ForbiddenException
import pl.detailing.crm.shared.LeadId
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UnprocessableEntityException
import java.time.Instant
import java.util.UUID

data class LinkAppointmentCommand(
    val leadId: LeadId,
    val studioId: StudioId,
    val appointmentId: UUID?
)

@Service
class LinkAppointmentHandler(
    private val leadRepository: LeadRepository,
    private val appointmentRepository: AppointmentRepository
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    suspend fun handle(command: LinkAppointmentCommand) = withContext(Dispatchers.IO) {
        val entity = leadRepository.findById(command.leadId.value)
            .orElseThrow { EntityNotFoundException("Lead nie został znaleziony: ${command.leadId}") }

        if (entity.studioId != command.studioId.value) {
            throw ForbiddenException("Lead nie należy do tego studia")
        }

        if (command.appointmentId == null) {
            entity.appointmentId = null
            entity.updatedAt = Instant.now()
            leadRepository.save(entity)
            log.info("[LEADS] Unlinked appointment from leadId={}", command.leadId.value)
            return@withContext
        }

        // idempotent — already linked to the same appointment
        if (entity.appointmentId == command.appointmentId) return@withContext

        // check target appointment exists in this studio
        appointmentRepository.findByIdAndStudioId(command.appointmentId, command.studioId.value)
            ?: throw UnprocessableEntityException("Rezerwacja nie istnieje: ${command.appointmentId}")

        // check no other lead already has this appointment
        val conflicting = leadRepository.findByAppointmentId(command.appointmentId)
        if (conflicting != null && conflicting.id != command.leadId.value) {
            throw AlreadyLinkedException(
                linkedLeadId = conflicting.id.toString(),
                linkedLeadName = conflicting.customerName
            )
        }

        entity.appointmentId = command.appointmentId
        entity.updatedAt = Instant.now()
        leadRepository.save(entity)
        log.info("[LEADS] Linked appointmentId={} to leadId={}", command.appointmentId, command.leadId.value)
    }
}
