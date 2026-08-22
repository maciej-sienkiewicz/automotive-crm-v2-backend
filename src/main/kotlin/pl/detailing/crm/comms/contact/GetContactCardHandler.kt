package pl.detailing.crm.comms.contact

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.appointment.infrastructure.AppointmentRepository
import pl.detailing.crm.customer.infrastructure.CustomerRepository
import pl.detailing.crm.leads.infrastructure.LeadRepository
import pl.detailing.crm.shared.LeadStatus
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.VehicleStatus
import pl.detailing.crm.shared.VisitStatus
import pl.detailing.crm.vehicle.infrastructure.VehicleOwnerRepository
import pl.detailing.crm.vehicle.infrastructure.VehicleRepository
import pl.detailing.crm.visit.infrastructure.VisitRepository
import java.time.Instant

/**
 * Wizytówka kontaktu spod avatara w skrzynce.
 *
 * Pytanie, na które odpowiada, brzmi zawsze tak samo: „przyszedł mail o przeglądzie
 * folii — kto to w ogóle jest, kiedy tu był i czym jeździ?". Odpowiedź musi zmieścić
 * się w chmurce i wystarczyć do napisania sensownej odpowiedzi bez otwierania
 * kartoteki: nazwisko, ostatnia wizyta, auta, trzy ostatnie zlecenia z kwotą.
 *
 * Szukamy po adresie e-mail, bo to jedyne, co mamy pewne — wiadomość przychodzi
 * z adresu, nie z identyfikatora klienta.
 */
@Service
class GetContactCardHandler(
    private val customerRepository: CustomerRepository,
    private val vehicleOwnerRepository: VehicleOwnerRepository,
    private val vehicleRepository: VehicleRepository,
    private val visitRepository: VisitRepository,
    private val leadRepository: LeadRepository,
    private val appointmentRepository: AppointmentRepository
) {

    @Transactional(readOnly = true)
    fun handle(studioId: StudioId, rawEmail: String): ContactCardDto {
        val email = rawEmail.trim().lowercase()

        // Porzucone zapytania liczymy po adresie, nie po kliencie: kontakt bez
        // kartoteki też potrafi mieć za sobą trzy zapytania, z których nic nie wyszło,
        // i to jest wtedy jedyne, co o nim wiemy.
        val abandonedLeads = leadRepository
            .findByStudioIdAndContactIdentifierOrderByCreatedAtDesc(studioId.value, email)
            .count { it.status == LeadStatus.LOST || it.status == LeadStatus.NO_SHOW }

        val customer = customerRepository.findActiveByStudioIdAndEmail(studioId.value, email)
            ?: return ContactCardDto(
                email = email,
                customer = null,
                vehicles = emptyList(),
                recentVisits = emptyList(),
                risk = ContactCardRiskDto(abandonedBookings = 0, abandonedLeads = abandonedLeads)
            )

        val vehicles = vehicleOwnerRepository.findByCustomerId(customer.id)
            .mapNotNull { vehicleRepository.findByIdAndStudioId(it.id.vehicleId, studioId.value) }
            .filter { it.status != VehicleStatus.ARCHIVED }
            .map {
                ContactCardVehicleDto(
                    id = it.id.toString(),
                    brand = it.brand,
                    model = it.model,
                    year = it.yearOfProduction,
                    licensePlate = it.licensePlate
                )
            }

        // Bez szkiców: wizyta, która nie zaczęła się na dobre, nic nie mówi o kliencie.
        val visits = visitRepository
            .findByCustomerIdAndStudioIdExcludingDraft(customer.id, studioId.value)

        val recent = visits.take(MAX_VISITS).map { visit ->
            ContactCardVisitDto(
                id = visit.id.toString(),
                title = visit.title,
                date = visit.scheduledDate,
                status = visit.status.name,
                // Kwota z pozycji, nie z szacunku — po wizycie liczy się to, co zapłacono.
                totalGross = visit.serviceItems.sumOf { it.finalPriceGross },
                vehicleLabel = listOfNotNull(
                    "${visit.brandSnapshot} ${visit.modelSnapshot}".trim().ifBlank { null },
                    visit.licensePlateSnapshot
                ).joinToString(" · ")
            )
        }

        val completed = visits.filter { it.status == VisitStatus.COMPLETED }

        return ContactCardDto(
            email = email,
            customer = ContactCardCustomerDto(
                id = customer.id.toString(),
                fullName = listOfNotNull(customer.firstName, customer.lastName)
                    .joinToString(" ")
                    .ifBlank { email },
                phone = customer.phone,
                completedVisitCount = completed.size,
                totalSpentGross = completed.sumOf { visit ->
                    visit.serviceItems.sumOf { it.finalPriceGross }
                },
                // „Ostatnia wizyta" znaczy ostatnia odbyta. Jutrzejsza rezerwacja to
                // nie jest odpowiedź na pytanie „kiedy on tu był".
                lastVisitAt = completed.maxByOrNull { it.scheduledDate }?.scheduledDate
            ),
            vehicles = vehicles,
            recentVisits = recent,
            risk = ContactCardRiskDto(
                abandonedBookings = appointmentRepository
                    .countAbandonedByCustomer(studioId.value, customer.id).toInt(),
                abandonedLeads = abandonedLeads
            )
        )
    }

    private companion object {
        /** Chmurka ma się dać ogarnąć wzrokiem — trzy ostatnie zlecenia wystarczą. */
        const val MAX_VISITS = 3
    }
}

data class ContactCardDto(
    val email: String,
    /** null = adresu nie ma w kartotece; front proponuje wtedy powiązanie albo założenie. */
    val customer: ContactCardCustomerDto?,
    val vehicles: List<ContactCardVehicleDto>,
    val recentVisits: List<ContactCardVisitDto>,
    val risk: ContactCardRiskDto
)

/**
 * Sygnał „ostrożnie z tym kontaktem" — surowe liczby, bez oceny.
 *
 * Świadomie nie zwracamy gotowego stopnia ryzyka: próg, od którego dwa odwołania
 * są problemem, zależy od tego, jak studio pracuje, a liczby da się pokazać
 * i wyjaśnić. „Podwyższone ryzyko" bez podania czego dotyczy to zarzut, którego
 * nie sposób sprawdzić.
 */
data class ContactCardRiskDto(
    /** Rezerwacje odwołane albo porzucone. Zawsze 0 dla kontaktu spoza kartoteki. */
    val abandonedBookings: Int,
    /** Wcześniejsze zapytania zamknięte jako przegrane albo bez pojawienia się. */
    val abandonedLeads: Int
)

data class ContactCardCustomerDto(
    val id: String,
    val fullName: String,
    val phone: String?,
    val completedVisitCount: Int,
    val totalSpentGross: Long,
    val lastVisitAt: Instant?
)

data class ContactCardVehicleDto(
    val id: String,
    val brand: String,
    val model: String,
    val year: Int?,
    val licensePlate: String?
)

data class ContactCardVisitDto(
    val id: String,
    val title: String?,
    val date: Instant,
    val status: String,
    val totalGross: Long,
    val vehicleLabel: String
)
