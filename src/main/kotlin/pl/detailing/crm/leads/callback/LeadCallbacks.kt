package pl.detailing.crm.leads.callback

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import org.slf4j.LoggerFactory
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.leads.infrastructure.LeadRepository
import pl.detailing.crm.leads.update.LeadStatusService
import pl.detailing.crm.shared.LeadStatus
import pl.detailing.crm.shared.NotFoundException
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import java.time.Instant
import java.util.UUID

/**
 * Telefon do klienta odnotowany na leadzie — patrz V110__lead_callbacks.sql.
 *
 * Notatka jest opcjonalna, bo sam fakt kontaktu bywa całą informacją: „dodzwoniłem
 * się" wystarcza, żeby oś czasu przestała kłamać, a lead zszedł z kolejki zaległości.
 */
@Entity
@Table(
    name = "lead_callbacks",
    indexes = [Index(name = "ix_lead_callbacks_lead", columnList = "lead_id, created_at")]
)
class LeadCallbackEntity(
    @Id
    @Column(name = "id", columnDefinition = "uuid")
    val id: UUID = UUID.randomUUID(),

    @Column(name = "studio_id", nullable = false, columnDefinition = "uuid")
    val studioId: UUID,

    @Column(name = "lead_id", nullable = false, columnDefinition = "uuid")
    val leadId: UUID,

    @Column(name = "note", length = 1000)
    val note: String? = null,

    @Column(name = "called_by", columnDefinition = "uuid")
    val calledBy: UUID? = null,

    @Column(name = "called_by_name", length = 200)
    val calledByName: String? = null,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now()
)

@Repository
interface LeadCallbackRepository : JpaRepository<LeadCallbackEntity, UUID> {
    fun findByLeadIdOrderByCreatedAtAsc(leadId: UUID): List<LeadCallbackEntity>
}

data class RecordLeadCallbackCommand(
    val studioId: StudioId,
    val leadId: UUID,
    val userId: UserId,
    val userName: String,
    val note: String?
)

/**
 * Zapisuje telefon do klienta i wyciąga z niego te same wnioski, co z wysłanego maila.
 *
 * Rozmowa telefoniczna to pełnoprawna odpowiedź studia — tyle że system nie ma jak
 * jej zobaczyć, bo w wątku nic nie przybywa. Bez tego zapisu lead po odbytej rozmowie
 * zostawał „Nowy", w kolejce zaległości i bez czasu pierwszej reakcji, a statystyka
 * czasu odpowiedzi liczyła go jako zapytanie bez odzewu.
 *
 * Skutki są dokładnie te, które wywołuje odpowiedź mailem
 * ([pl.detailing.crm.leads.update.LeadFirstResponseListener]) — jedno zachowanie dla
 * dwóch kanałów, bo z punktu widzenia klienta to ta sama rzecz: ktoś się odezwał.
 */
@Service
class RecordLeadCallbackHandler(
    private val leadRepository: LeadRepository,
    private val callbackRepository: LeadCallbackRepository,
    private val statusService: LeadStatusService
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    fun handle(command: RecordLeadCallbackCommand): LeadCallbackEntity {
        val lead = leadRepository.findByIdAndStudioId(command.leadId, command.studioId.value)
            ?: throw NotFoundException("Nie znaleziono leada")

        val callback = callbackRepository.save(
            LeadCallbackEntity(
                studioId = command.studioId.value,
                leadId = lead.id,
                note = command.note?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_NOTE),
                calledBy = command.userId.value,
                calledByName = command.userName
            )
        )

        // Czas pierwszej reakcji stempluje PIERWSZY kontakt, niezależnie od kanału.
        // Kolejne telefony go nie przesuwają — inaczej statystyka mierzyłaby ostatnią
        // rozmowę zamiast tego, jak szybko studio odpowiedziało na zapytanie.
        if (lead.firstResponseAt == null) {
            lead.firstResponseAt = callback.createdAt
            lead.updatedAt = Instant.now()
            leadRepository.save(lead)
        }

        // Tak jak przy odpowiedzi mailem: przesuwamy wyłącznie z NEW. Leada
        // zarezerwowanego, zamkniętego czy przegranego telefon nie cofa na wcześniejszy etap.
        if (lead.status == LeadStatus.NEW) {
            statusService.transition(
                lead,
                LeadStatus.IN_PROGRESS,
                changedByUserId = command.userId.value,
                changedByName = command.userName
            )
        }

        log.info("[LEADS] Odnotowano telefon do klienta na leadzie {} ({})", lead.id, command.userName)
        return callback
    }

    companion object {
        private const val MAX_NOTE = 1000
    }
}
