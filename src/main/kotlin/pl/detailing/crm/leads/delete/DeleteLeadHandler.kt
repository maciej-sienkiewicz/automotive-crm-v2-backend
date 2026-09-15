package pl.detailing.crm.leads.delete

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.appointment.infrastructure.AppointmentRepository
import pl.detailing.crm.audit.domain.AuditAction
import pl.detailing.crm.audit.domain.AuditModule
import pl.detailing.crm.audit.domain.AuditService
import pl.detailing.crm.audit.domain.FieldChange
import pl.detailing.crm.audit.domain.LogAuditCommand
import pl.detailing.crm.comms.infrastructure.CommThreadRepository
import pl.detailing.crm.leads.infrastructure.LeadRepository
import pl.detailing.crm.leads.infrastructure.LeadServiceItemRepository
import pl.detailing.crm.leads.infrastructure.LeadStatusHistoryRepository
import pl.detailing.crm.leads.infrastructure.LeadTagRepository
import pl.detailing.crm.shared.ConflictException
import pl.detailing.crm.shared.NotFoundException
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import java.time.Instant
import java.util.UUID

/**
 * Usunięcie leada — pomyłka, duplikat, test.
 *
 * Kasujemy naprawdę, a nie oznaczamy jako usunięty. Lead nie jest dokumentem
 * księgowym: nie ma obowiązku przechowywania, a „usunięty, ale nadal liczony
 * w statystykach" byłby gorszy niż brak przycisku. Historia statusów i tagi znikają
 * razem z nim, bo bez leada nie znaczą nic.
 *
 * Czego NIE ruszamy: samej korespondencji. Wątek zostaje w skrzynce, traci tylko
 * powiązanie — i można go oznaczyć jako lead ponownie, co jest zwykle powodem, dla
 * którego ktoś kasuje pierwszy.
 *
 * Lead z rezerwacją: decyzję, czy rezerwacja idzie razem z nim, podejmuje użytkownik
 * w interfejsie ([deleteAppointment]) — samo odrzucenie żądania zostawiało go
 * w martwym punkcie, bez informacji, co ma najpierw zrobić. Lead z WIZYTĄ dalej nie
 * daje się usunąć: wizyta to wykonana praca i dokumenty, nie zapytanie.
 */
@Service
class DeleteLeadHandler(
    private val leadRepository: LeadRepository,
    private val itemRepository: LeadServiceItemRepository,
    private val tagRepository: LeadTagRepository,
    private val historyRepository: LeadStatusHistoryRepository,
    private val threadRepository: CommThreadRepository,
    private val appointmentRepository: AppointmentRepository,
    private val auditService: AuditService,
    private val attachmentRepository: pl.detailing.crm.leads.attachment.LeadAttachmentRepository,
    private val intentRepository: pl.detailing.crm.leads.similar.LeadServiceIntentRepository,
    private val matchesRepository: pl.detailing.crm.leads.similar.LeadSimilarMatchesRepository,
    private val feedbackRepository: pl.detailing.crm.leads.similar.VisitMatchFeedbackRepository,
    private val decisionRepository: pl.detailing.crm.leads.similar.pricing.LeadMatchDecisionRepository,
    private val visionFactsRepository: pl.detailing.crm.leads.similar.vision.LeadAttachmentFactsRepository,
    private val anchorOutcomeRepository: pl.detailing.crm.leads.similar.feedback.AnchorOutcomeRepository
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    fun handle(
        studioId: StudioId,
        leadId: UUID,
        userId: UUID,
        userName: String,
        deleteAppointment: Boolean = false
    ) {
        val lead = leadRepository.findByIdAndStudioId(leadId, studioId.value)
            ?: throw NotFoundException("Nie znaleziono leada")

        if (lead.visitId != null) {
            throw ConflictException(
                "Ten lead ma już wizytę — usuń najpierw wizytę w module wizyt"
            )
        }

        lead.appointmentId?.let { appointmentId ->
            if (deleteAppointment) {
                softDeleteAppointment(studioId, appointmentId, userId, userName)
            }
            // Bez usuwania: rezerwacja zostaje w kalendarzu jako samodzielny termin.
            // Powiązanie jest jednokierunkowe (lead → rezerwacja), więc po skasowaniu
            // leada nic nie wisi.
        }

        lead.threadId?.let { threadId ->
            threadRepository.findByIdAndStudioId(threadId, studioId.value)?.let { thread ->
                thread.leadId = null
                threadRepository.save(thread)
            }
        }

        historyRepository.deleteAll(historyRepository.findByLeadIdOrderByCreatedAtAsc(leadId))
        itemRepository.deleteByLeadId(leadId)
        tagRepository.deleteByLeadId(leadId)
        // Warstwa AI leada idzie razem z nim: intencja, dobór, dziennik decyzji,
        // opinie o dopasowaniach, wskazania załączników i opisy zdjęć klienta.
        // Zostawienie któregokolwiek z tych wierszy to dane osobowe bez właściciela —
        // do przebudowy „Podobnych zleceń" wyciekały tu wszystkie cztery pierwsze.
        attachmentRepository.deleteAll(attachmentRepository.findByLeadIdOrderByReceivedAtAsc(leadId))
        intentRepository.findById(leadId).ifPresent { intentRepository.delete(it) }
        matchesRepository.findById(leadId).ifPresent { matchesRepository.delete(it) }
        feedbackRepository.deleteAll(feedbackRepository.findByLeadId(leadId))
        decisionRepository.deleteByLeadId(leadId)
        visionFactsRepository.deleteAll(visionFactsRepository.findByLeadId(leadId))
        anchorOutcomeRepository.deleteAll(anchorOutcomeRepository.findByLeadId(leadId))
        leadRepository.delete(lead)

        log.info(
            "[LEADS] Lead {} deleted from studio {} (appointment {})",
            leadId, studioId.value,
            if (deleteAppointment && lead.appointmentId != null) "deleted too" else "kept"
        )
    }

    /**
     * Miękkie usunięcie rezerwacji — to samo, co robi kosz w kalendarzu, w tej samej
     * transakcji co usunięcie leada: nie może być stanu „lead zniknął, rezerwacja
     * została", skoro użytkownik wybrał usunięcie obu.
     *
     * Rezerwacji może już nie być (usunięta wcześniej z kalendarza, wskaźnik wisiał)
     * — wtedy nie ma czego kasować i nie jest to błąd.
     */
    private fun softDeleteAppointment(studioId: StudioId, appointmentId: UUID, userId: UUID, userName: String) {
        val appointment = appointmentRepository.findByIdAndStudioId(appointmentId, studioId.value) ?: return
        appointment.deletedAt = Instant.now()
        appointment.updatedBy = userId
        appointment.updatedAt = Instant.now()
        appointmentRepository.save(appointment)

        auditService.logSync(
            LogAuditCommand(
                studioId = studioId,
                userId = UserId(userId),
                userDisplayName = userName,
                module = AuditModule.APPOINTMENT,
                entityId = appointmentId.toString(),
                entityDisplayName = appointment.appointmentTitle,
                action = AuditAction.APPOINTMENT_DELETED,
                changes = listOf(FieldChange("deletedWith", "lead", "deleted"))
            )
        )
    }
}
