package pl.detailing.crm.customer.delete

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.audit.domain.AuditAction
import pl.detailing.crm.audit.domain.AuditModule
import pl.detailing.crm.audit.domain.AuditService
import pl.detailing.crm.audit.domain.FieldChange
import pl.detailing.crm.audit.domain.LogAuditCommand
import pl.detailing.crm.customer.infrastructure.CustomerRepository
import pl.detailing.crm.customer.notes.CustomerNoteRepository
import pl.detailing.crm.leads.infrastructure.LeadRepository
import pl.detailing.crm.shared.NotFoundException
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import pl.detailing.crm.shared.VehicleStatus
import pl.detailing.crm.vehicle.infrastructure.VehicleOwnerRepository
import pl.detailing.crm.vehicle.infrastructure.VehicleRepository
import pl.detailing.crm.visit.infrastructure.VisitRepository
import java.time.Instant
import java.util.UUID

/**
 * Usunięcie klienta = WYMAZANIE DANYCH OSOBOWYCH, nie skasowanie wiersza.
 *
 * RODO każe usunąć to, co wskazuje człowieka; prawo księgowe i dowodowe każe
 * zachować to, co dokumentuje pracę. Te dwa obowiązki godzi anonimizacja:
 *
 *  ZNIKA: imię, nazwisko, e-mail, telefon, adresy, dane firmy, notatki o kliencie,
 *  dane kontaktowe na leadach i osobach wydających auto przy wizytach, powiązania
 *  klient↔pojazd.
 *
 *  ZOSTAJE: wiersz klienta (zanonimizowany, isActive=false — statystyki i historia
 *  wizyt wciąż mają do czego się przypiąć), wizyty z kwotami i usługami, podpisane
 *  dokumenty i zgody (to one bronią studio w sporze — usunięcie ich działałoby
 *  przeciwko obu stronom), pojazdy (fakt „robiliśmy Passata" nie jest daną osobową;
 *  pojazd bez żadnego właściciela idzie do archiwum, jak przy ręcznym usuwaniu).
 *
 * Celowo bez okresu karencji i bez „przywróć": wymazanie danych osobowych ma być
 * nieodwracalne — kopia zapasowa „na wszelki wypadek" byłaby dokładnie tym,
 * czego RODO zakazuje.
 */
@Service
class DeleteCustomerHandler(
    private val customerRepository: CustomerRepository,
    private val noteRepository: CustomerNoteRepository,
    private val vehicleOwnerRepository: VehicleOwnerRepository,
    private val vehicleRepository: VehicleRepository,
    private val leadRepository: LeadRepository,
    private val visitRepository: VisitRepository,
    private val auditService: AuditService
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    fun handle(studioId: StudioId, customerId: UUID, userId: UserId, userName: String) {
        val customer = customerRepository.findByIdAndStudioId(customerId, studioId.value)
            ?: throw NotFoundException("Nie znaleziono klienta")
        // Idempotencja: drugie kliknięcie (dwie karty, ponowienie żądania) nie ma
        // czego wymazywać i nie jest błędem.
        if (!customer.isActive) return

        val now = Instant.now()

        // ── Pojazdy: zerwij powiązania; osierocony pojazd do archiwum ─────────
        val ownerships = vehicleOwnerRepository.findByCustomerId(customerId)
        vehicleOwnerRepository.deleteAll(ownerships)
        var archivedVehicles = 0
        ownerships.map { it.id.vehicleId }.distinct().forEach { vehicleId ->
            val vehicle = vehicleRepository.findByIdAndStudioId(vehicleId, studioId.value)
                ?: return@forEach
            // Zapytanie po usunięciu wierszy — JPA flushuje zmiany przed selectem,
            // więc widzi stan bez tego klienta.
            if (vehicleOwnerRepository.findByVehicleId(vehicleId).isEmpty() && vehicle.deletedAt == null) {
                vehicle.status = VehicleStatus.ARCHIVED
                vehicle.deletedAt = now
                vehicle.updatedBy = userId.value
                vehicle.updatedAt = now
                vehicleRepository.save(vehicle)
                archivedVehicles++
            }
        }

        // ── Notatki: wolny tekst o człowieku — do usunięcia w całości ─────────
        noteRepository.deleteAll(
            noteRepository.findByCustomerIdAndStudioIdOrderByCreatedAtDesc(customerId, studioId.value)
        )

        // ── Leady i wizyty: wiersze zostają, dane osobowe znikają ─────────────
        val anonymizedLeads = leadRepository.anonymizeByCustomer(studioId.value, customerId, ANONYMIZED_CONTACT)
        val scrubbedVisits = visitRepository.scrubContactPersonByCustomer(customerId, studioId.value)

        // ── Sam klient: marker zamiast tożsamości ─────────────────────────────
        customer.firstName = "Klient"
        customer.lastName = "usunięty"
        customer.email = null
        customer.phone = null
        customer.homeAddressStreet = null
        customer.homeAddressCity = null
        customer.homeAddressPostalCode = null
        customer.homeAddressCountry = null
        customer.companyName = null
        customer.companyNip = null
        customer.companyRegon = null
        customer.companyAddressStreet = null
        customer.companyAddressCity = null
        customer.companyAddressPostalCode = null
        customer.companyAddressCountry = null
        customer.isActive = false
        customer.updatedBy = userId.value
        customer.updatedAt = now
        customerRepository.save(customer)

        // Bez nazwiska w dzienniku — wpisanie go tutaj przeczyłoby wymazaniu.
        auditService.logSync(
            LogAuditCommand(
                studioId = studioId,
                userId = userId,
                userDisplayName = userName,
                module = AuditModule.CUSTOMER,
                entityId = customerId.toString(),
                entityDisplayName = null,
                action = AuditAction.DELETE,
                changes = listOf(FieldChange("personalData", "present", "erased"))
            )
        )

        log.info(
            "[CUSTOMER] Customer {} anonymized (studio {}): {} ownership(s) removed, {} vehicle(s) archived, {} lead(s) anonymized, {} visit(s) scrubbed",
            customerId, studioId.value, ownerships.size, archivedVehicles, anonymizedLeads, scrubbedVisits
        )
    }

    companion object {
        /** Kolumna kontaktu na leadzie jest NOT NULL — zostaje jawny marker, nie adres. */
        const val ANONYMIZED_CONTACT = "dane-usuniete"
    }
}
