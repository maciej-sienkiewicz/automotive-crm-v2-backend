package pl.detailing.crm.visit.transitions.confirm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import pl.detailing.crm.appointment.domain.AppointmentStatus
import pl.detailing.crm.appointment.infrastructure.AppointmentRepository
import pl.detailing.crm.customer.infrastructure.CustomerRepository
import pl.detailing.crm.leads.appointment.LeadSyncService
import pl.detailing.crm.push.notify.PushMessages
import pl.detailing.crm.shared.*
import pl.detailing.crm.visit.infrastructure.VisitRepository
import java.time.Instant

/**
 * Handler for confirming a DRAFT visit and making it active.
 *
 * This operation:
 * - Validates that all mandatory protocols are signed
 * - Changes visit status from DRAFT to IN_PROGRESS
 * - Updates appointment status from CONFIRMED to CONVERTED
 * - Makes the visit immutable (cannot be cancelled anymore)
 */
@Service
class ConfirmVisitHandler(
    private val visitRepository: VisitRepository,
    private val appointmentRepository: AppointmentRepository,
    private val leadSyncService: LeadSyncService,
    private val transactionTemplate: TransactionTemplate,
    private val customerRepository: CustomerRepository,
    private val eventPublisher: ApplicationEventPublisher
) {
    private val log = LoggerFactory.getLogger(ConfirmVisitHandler::class.java)

    @Transactional
    suspend fun handle(command: ConfirmVisitCommand): ConfirmVisitResult =
        withContext(Dispatchers.IO) {
            // Load visit
            val visitEntity = visitRepository.findByIdAndStudioId(
                command.visitId.value,
                command.studioId.value
            ) ?: throw EntityNotFoundException("Wizyta nie została znaleziona")

            // Validate visit is in DRAFT status (check directly on entity to avoid lazy loading issues)
            if (visitEntity.status != VisitStatus.DRAFT) {
                throw ValidationException("Potwierdzić można tylko wizyty o statusie DRAFT. Aktualny status: ${visitEntity.status}")
            }

            // Visit, appointment and lead move together (TransactionTemplate — the body
            // of a `@Transactional suspend` function on Dispatchers.IO escapes the
            // interceptor-managed transaction; see AuditLogWriter). Split, a confirmed
            // visit could sit against an appointment still short of CONVERTED, and the
            // check-in flow and the delete guard then disagree about the same booking.
            transactionTemplate.execute {
                // Update visit status to IN_PROGRESS
                visitEntity.status = VisitStatus.IN_PROGRESS
                visitEntity.updatedBy = command.userId.value
                visitEntity.updatedAt = Instant.now()
                visitRepository.save(visitEntity)

                // Update appointment status to CONVERTED and sync linked lead
                if (visitEntity.appointmentId != null) {
                    val appointmentEntity = appointmentRepository.findByIdAndStudioId(
                        visitEntity.appointmentId!!,
                        command.studioId.value
                    )
                    if (appointmentEntity != null) {
                        appointmentEntity.status = AppointmentStatus.CONVERTED
                        appointmentEntity.updatedBy = command.userId.value
                        appointmentEntity.updatedAt = Instant.now()
                        appointmentRepository.save(appointmentEntity)

                        leadSyncService.markCompleted(
                            appointmentId = visitEntity.appointmentId!!,
                            visitId = command.visitId.value,
                            studioId = command.studioId.value,
                            userId = command.userId.value,
                            userDisplayName = command.userName ?: ""
                        )
                    }
                }
            }

            // „Przyjęto pojazd" pada TU, a nie przy utworzeniu szkicu: szkic przyjęcia
            // bywa porzucany w połowie, a potwierdzonej wizyty nie da się już anulować.
            // Po transactionTemplate.execute, więc zmiana statusu jest już zatwierdzona.
            // Zebranie treści to dodatek - jego błąd gubi powiadomienie, nie przyjęcie.
            runCatching {
                val customer = customerRepository.findByIdAndStudioId(visitEntity.customerId, command.studioId.value)
                eventPublisher.publishEvent(
                    VehicleCheckedInEvent(
                        source = this,
                        studioId = command.studioId,
                        visitId = command.visitId,
                        visitNumber = visitEntity.visitNumber,
                        checkedInByUserId = command.userId,
                        brandModel = PushMessages.brandModel(visitEntity.brandSnapshot, visitEntity.modelSnapshot),
                        licensePlate = visitEntity.licensePlateSnapshot,
                        customerName = customer?.let { PushMessages.personName(it.firstName, it.lastName, it.companyName) }
                    )
                )
            }.onFailure { log.warn("[push] Nie udalo sie przygotowac powiadomienia o przyjeciu pojazdu: {}", it.message) }

            // No separate audit entry: DRAFT → IN_PROGRESS is always the tail of the
            // check-in flow, and the flow already logged VISIT_CREATED ("Rozpoczęto
            // wizytę") seconds earlier. A second "Potwierdzono wizytę" row for the same
            // business action was pure feed noise.

            ConfirmVisitResult(visitId = command.visitId)
        }
}

data class ConfirmVisitCommand(
    val visitId: VisitId,
    val studioId: StudioId,
    val userId: UserId,
    val userName: String? = null
)

data class ConfirmVisitResult(
    val visitId: VisitId
)
