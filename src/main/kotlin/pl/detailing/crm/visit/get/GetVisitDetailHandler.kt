package pl.detailing.crm.visit.get

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.shared.*
import pl.detailing.crm.visit.domain.Visit
import pl.detailing.crm.visit.infrastructure.*
import pl.detailing.crm.customer.infrastructure.CustomerRepository
import pl.detailing.crm.vehicle.infrastructure.VehicleRepository
import pl.detailing.crm.vehicle.infrastructure.VehicleOwnerRepository
import pl.detailing.crm.appointment.infrastructure.AppointmentColorRepository
import pl.detailing.crm.doortodoor.infrastructure.DoorToDoorRepository
import pl.detailing.crm.user.infrastructure.UserRepository
import pl.detailing.crm.finance.domain.DocumentType
import pl.detailing.crm.finance.infrastructure.FinancialDocumentRepository
import pl.detailing.crm.ksef.revenue.infrastructure.KsefRevenueInvoiceRepository

@Service
class GetVisitDetailHandler(
    private val visitRepository: VisitRepository,
    private val customerRepository: CustomerRepository,
    private val vehicleRepository: VehicleRepository,
    private val vehicleOwnerRepository: VehicleOwnerRepository,
    private val journalEntryRepository: VisitJournalEntryRepository,
    private val documentRepository: VisitDocumentRepository,
    private val appointmentColorRepository: AppointmentColorRepository,
    private val doorToDoorRepository: DoorToDoorRepository,
    private val userRepository: UserRepository,
    private val financialDocumentRepository: FinancialDocumentRepository,
    private val revenueInvoiceRepository: KsefRevenueInvoiceRepository
) {

    @Transactional(readOnly = true)
    suspend fun handle(command: GetVisitDetailCommand): GetVisitDetailResult {
        // 1. Find visit with studio isolation (including soft-deleted — allows viewing deleted visits)
        val visitEntity = visitRepository.findByIdAndStudioIdIncludingDeleted(
            id = command.visitId.value,
            studioId = command.studioId.value
        ) ?: throw EntityNotFoundException("Visit not found: ${command.visitId}")

        /*
         * DRAFT nie wychodzi tą drogą.
         *
         * Szkic powstaje w kreatorze przyjęcia i żyje do zatwierdzenia wizyty albo do
         * jej anulowania. Wszystkie listy wizyt już go pomijają (patrz zapytania
         * `...ExcludingDraft` w VisitRepository) — szczegóły były jedyną szczeliną, przez
         * którą dawało się do niego wejść: z linku w Aktywności albo z zapamiętanego
         * adresu. Użytkownik dostawał wizytę bez sterowania: „Oczekuje na potwierdzenie
         * i podpisanie dokumentów" i ani jednego przycisku, którym da się to zrobić.
         */
        if (visitEntity.status == VisitStatus.DRAFT && !command.allowDraft) {
            throw VisitNotStartedException(
                visitId = visitEntity.id.toString(),
                visitNumber = visitEntity.visitNumber
            )
        }

        // Force load lazy collections within transaction
        visitEntity.serviceItems.size  // Force load serviceItems
        visitEntity.photos.size  // Force load photos

        val visit = visitEntity.toDomain()

        // 2. Find customer
        val customerEntity = customerRepository.findByIdAndStudioId(
            id = visit.customerId.value,
            studioId = command.studioId.value
        ) ?: throw EntityNotFoundException("Customer not found: ${visit.customerId}")

        val customer = customerEntity.toDomain()

        // 3. Find vehicle
        val vehicleEntity = vehicleRepository.findByIdAndStudioId(
            id = visit.vehicleId.value,
            studioId = command.studioId.value
        ) ?: throw EntityNotFoundException("Vehicle not found: ${visit.vehicleId}")

        val vehicle = vehicleEntity.toDomain()

        // 4. Find appointment color if present
        val appointmentColor = visit.appointmentColorId?.let { colorId ->
            appointmentColorRepository.findByIdAndStudioId(
                id = colorId.value,
                studioId = command.studioId.value
            )?.toDomain()
        }

        // 5. Find journal entries
        val journalEntries = journalEntryRepository.findByVisitId(visit.id.value)
            .map { it.toDomain() }

        // 6. Find documents
        val documents = documentRepository.findByVisitId(visit.id.value)
            .map { it.toDomain() }

        // 7. Calculate customer statistics
        val customerVisits = visitRepository.findByCustomerIdAndStudioIdExcludingDraft(
            customerId = customer.id.value,
            studioId = command.studioId.value
        )

        val totalVisits = customerVisits.size

        // Force load serviceItems for each visit before mapping
        val completedVisits = customerVisits
            .onEach { it.serviceItems.size }  // Force load serviceItems
            .map { it.toDomain() }
            .filter { it.status == VisitStatus.COMPLETED }
        val (totalSpent, totalSpentGross) = customerSpend(completedVisits)

        // Count unique vehicles for this customer (bez pojazdów usuniętych)
        val vehiclesCount = vehicleOwnerRepository.countActiveVehiclesByCustomerId(
            customerId = customer.id.value,
            studioId = command.studioId.value
        ).toInt()

        val customerStats = CustomerStats(
            totalVisits = totalVisits,
            totalSpent = totalSpent,
            vehiclesCount = vehiclesCount,
            totalSpentGross = totalSpentGross
        )

        val doorToDoor = doorToDoorRepository.findByVisitIdAndStudioId(visit.id.value, command.studioId.value)
            ?.toDomain()

        // 8. Resolve the employee who accepted the vehicle (visit creator)
        val acceptedByName = userRepository.findByIdAndStudioId(visit.createdBy.value, command.studioId.value)
            ?.let { "${it.firstName} ${it.lastName}".trim().ifBlank { null } }

        // 9. Rozliczenie wizyty: typ dokumentu z modułu finansów + ewentualna
        // faktura KSeF. Czytane osobno, bo dokument finansowy typu INVOICE może
        // istnieć bez rekordu KSeF (adnotacja bez wysyłki) i odwrotnie.
        // Po poprawce rozliczenia obowiązują tylko dokumenty niezastąpione i nie-korekty —
        // stary paragon zostaje w historii, ale wizyta pokazuje to, co jest teraz.
        val settlementDocuments = financialDocumentRepository
            .findAllByVisitIdAndStudioIdAndDeletedAtIsNull(visit.id.value, command.studioId.value)
            .filter { it.supersededAt == null && it.documentType != DocumentType.CORRECTION }

        // Faktura ma pierwszeństwo nad pozostałymi dokumentami: gdy wizytę
        // rozliczono dwoma dokumentami (część na fakturę, reszta na paragon),
        // to faktura decyduje o tym, co widzi użytkownik. Priorytet jest wybrany
        // jawnie, bo kolejność stałych w DocumentType stawia RECEIPT przed INVOICE.
        val settlementDocumentType = (
            settlementDocuments.firstOrNull { it.documentType == DocumentType.INVOICE }
                ?: settlementDocuments.firstOrNull()
            )?.documentType?.name

        // Podgląd faktury: faktura, która obowiązuje — najpierw ta z dokumentu faktury,
        // inaczej najnowsza nieanulowana i nieodrzucona faktura VAT wizyty (np. faktura do
        // paragonu). Najstarsza (dawna reguła) po poprawce byłaby fakturą wyzerowaną korektą.
        val visitInvoices = revenueInvoiceRepository
            .findByStudioIdAndVisitIdOrderByCreatedAtAsc(command.studioId.value, visit.id.value)
        val linkedInvoiceId = settlementDocuments.firstNotNullOfOrNull { it.ksefRevenueInvoiceId }
        val revenueInvoiceId = (
            visitInvoices.firstOrNull { it.id == linkedInvoiceId }
                ?: visitInvoices.lastOrNull {
                    it.invoiceType == pl.detailing.crm.ksef.revenue.domain.RevenueInvoiceType.VAT &&
                        it.ksefStatus != pl.detailing.crm.ksef.revenue.domain.KsefRevenueStatus.CANCELLED &&
                        it.ksefStatus != pl.detailing.crm.ksef.revenue.domain.KsefRevenueStatus.REJECTED
                }
                ?: visitInvoices.firstOrNull()
            )?.id?.toString()

        val settlement = if (settlementDocumentType == null && revenueInvoiceId == null) null
            else VisitSettlementInfo(
                documentType = settlementDocumentType,
                revenueInvoiceId = revenueInvoiceId
            )

        return GetVisitDetailResult(
            visit = visit,
            vehicle = vehicle,
            customer = customer,
            appointmentColor = appointmentColor,
            journalEntries = journalEntries,
            documents = documents,
            customerStats = customerStats,
            doorToDoor = doorToDoor,
            acceptedByName = acceptedByName,
            settlement = settlement
        )
    }
}

/**
 * Wydatki klienta: suma netto i suma brutto zakończonych wizyt, każda liczona osobno.
 * Brutto sumujemy z brutto wizyt — wcześniej odpowiedź podawała sumę netto jako brutto,
 * a odtworzenie brutto z sumy netto zgubiłoby dokładne kwoty pozycji (CLAUDE.md §1).
 */
internal fun customerSpend(completedVisits: List<Visit>): Pair<Money, Money> =
    completedVisits.fold(Money.ZERO to Money.ZERO) { (net, gross), visit ->
        net.plus(visit.calculateTotalNet()) to gross.plus(visit.calculateTotalGross())
    }
