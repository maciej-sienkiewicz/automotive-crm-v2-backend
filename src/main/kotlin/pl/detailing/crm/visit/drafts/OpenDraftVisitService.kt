package pl.detailing.crm.visit.drafts

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.customer.infrastructure.CustomerRepository
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.user.infrastructure.UserRepository
import pl.detailing.crm.visit.infrastructure.VisitEntity
import pl.detailing.crm.visit.infrastructure.VisitRepository
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Jedno nieukończone przyjęcie w kolejce obsługi.
 *
 * Świadomie NIE jest to widok wizyty: kwoty, usług ani dokumentów tu nie ma. Jedyne, co
 * ma powiedzieć, to „czyje to auto, kto je przyjmował i jak długo to wisi" — czyli tyle,
 * ile potrzeba, żeby zdecydować: dokończyć czy anulować.
 */
data class OpenDraftVisitView(
    val visitId: UUID,
    val visitNumber: String,
    val title: String?,
    val appointmentId: UUID,
    val customerId: UUID,
    val customerName: String?,
    /** Telefon i e-mail klienta — okno dokończenia przyjęcia wysyła nimi prośbę o podpis
     *  i potwierdzenie wizyty, dokładnie jak kreator. */
    val customerPhone: String?,
    val customerEmail: String?,
    val vehicleId: UUID,
    val vehicleName: String,
    val licensePlate: String?,
    val createdAt: Instant,
    val createdByName: String?,
    val ageMinutes: Long,
    /** Wisi dłużej niż [DraftVisitProperties.staleAfterHours] — do wyróżnienia w kolejce. */
    val stale: Boolean,
    /** Kiedy zadanie sprzątające anuluje szkic, jeśli nikt go wcześniej nie domknie. */
    val expiresAt: Instant?,
    /**
     * Czy przyjęcie ma zdjęcia i mapę uszkodzeń. Nie do wyświetlenia — okno dokończenia
     * przyjęcia ustawia po tym domyślne załączniki wiadomości do klienta, dokładnie tak
     * jak robi to kreator, który te dane ma jeszcze w formularzu.
     */
    val hasPhotos: Boolean,
    val hasDamageMap: Boolean
)

/**
 * Odczyt kolejki nieukończonych przyjęć.
 *
 * Kolejka jest drugą połową naprawy szczeliny z DRAFT-em: skoro szkic przestaje być
 * dostępny przez szczegóły wizyty i znika z Aktywności, musi mieć własne, jawne miejsce
 * w interfejsie. Inaczej „nie widać go nigdzie" znaczyłoby „nikt się nim nie zajmie",
 * a auto stoi w warsztacie niezależnie od tego, co widzi system.
 */
@Service
class OpenDraftVisitService(
    private val visitRepository: VisitRepository,
    private val customerRepository: CustomerRepository,
    private val userRepository: UserRepository,
    private val properties: DraftVisitProperties
) {

    @Transactional(readOnly = true)
    fun listOpen(studioId: StudioId): List<OpenDraftVisitView> {
        val drafts = visitRepository.findOpenDrafts(studioId.value)
        if (drafts.isEmpty()) return emptyList()

        val now = Instant.now()
        // Jedno zapytanie po nazwy zamiast dwóch na wiersz: kolejka bywa pusta, ale gdy
        // nie jest, jest odświeżana przy każdym wejściu na listę wizyt.
        val contacts = customerContacts(drafts, studioId)
        val userNames = userNames(drafts, studioId)

        return drafts.map { toView(it, now, contacts, userNames) }
    }

    @Transactional(readOnly = true)
    fun findOpenForAppointment(appointmentId: UUID, studioId: StudioId): OpenDraftVisitView? {
        val draft = visitRepository
            .findOpenDraftsByAppointmentId(appointmentId, studioId.value)
            .firstOrNull()
            ?: return null

        return toView(
            entity = draft,
            now = Instant.now(),
            contacts = customerContacts(listOf(draft), studioId),
            userNames = userNames(listOf(draft), studioId)
        )
    }

    private data class CustomerContact(val name: String?, val phone: String?, val email: String?)

    private fun toView(
        entity: VisitEntity,
        now: Instant,
        contacts: Map<UUID, CustomerContact>,
        userNames: Map<UUID, String?>
    ): OpenDraftVisitView {
        val ageMinutes = Duration.between(entity.createdAt, now).toMinutes().coerceAtLeast(0)
        return OpenDraftVisitView(
            visitId = entity.id,
            visitNumber = entity.visitNumber,
            title = entity.title,
            appointmentId = entity.appointmentId,
            customerId = entity.customerId,
            customerName = contacts[entity.customerId]?.name,
            customerPhone = contacts[entity.customerId]?.phone,
            customerEmail = contacts[entity.customerId]?.email,
            vehicleId = entity.vehicleId,
            vehicleName = "${entity.brandSnapshot} ${entity.modelSnapshot}".trim(),
            licensePlate = entity.licensePlateSnapshot,
            createdAt = entity.createdAt,
            createdByName = userNames[entity.createdBy],
            ageMinutes = ageMinutes,
            stale = ageMinutes >= properties.staleAfterHours * 60,
            expiresAt = entity.createdAt
                .plus(Duration.ofHours(properties.expireAfterHours))
                .takeIf { properties.cleanupEnabled },
            hasPhotos = entity.photos.isNotEmpty(),
            hasDamageMap = entity.damageMapFileId != null
        )
    }

    private fun customerContacts(drafts: List<VisitEntity>, studioId: StudioId): Map<UUID, CustomerContact> =
        drafts.map { it.customerId }.distinct().associateWith { customerId ->
            val customer = customerRepository.findByIdAndStudioId(customerId, studioId.value)
            CustomerContact(
                name = customer?.companyName?.takeIf { it.isNotBlank() }
                    ?: listOfNotNull(customer?.firstName, customer?.lastName)
                        .joinToString(" ")
                        .takeIf { it.isNotBlank() },
                phone = customer?.phone?.takeIf { it.isNotBlank() },
                email = customer?.email?.takeIf { it.isNotBlank() }
            )
        }

    private fun userNames(drafts: List<VisitEntity>, studioId: StudioId): Map<UUID, String?> =
        drafts.map { it.createdBy }.distinct().associateWith { userId ->
            userRepository.findByIdAndStudioId(userId, studioId.value)
                ?.let { "${it.firstName} ${it.lastName}".trim().takeIf(String::isNotBlank) }
        }
}
