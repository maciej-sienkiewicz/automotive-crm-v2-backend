package pl.detailing.crm.visit.get

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.shared.*
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
        val totalSpent = customerVisits
            .onEach { it.serviceItems.size }  // Force load serviceItems
            .map { it.toDomain() }
            .filter { it.status == VisitStatus.COMPLETED }
            .fold(Money.ZERO) { acc, v -> acc.plus(v.calculateTotalNet()) }

        // Count unique vehicles for this customer (bez pojazdów usuniętych)
        val vehiclesCount = vehicleOwnerRepository.countActiveVehiclesByCustomerId(
            customerId = customer.id.value,
            studioId = command.studioId.value
        ).toInt()

        val customerStats = CustomerStats(
            totalVisits = totalVisits,
            totalSpent = totalSpent,
            vehiclesCount = vehiclesCount
        )

        val doorToDoor = doorToDoorRepository.findByVisitIdAndStudioId(visit.id.value, command.studioId.value)
            ?.toDomain()

        // 8. Resolve the employee who accepted the vehicle (visit creator)
        val acceptedByName = userRepository.findByIdAndStudioId(visit.createdBy.value, command.studioId.value)
            ?.let { "${it.firstName} ${it.lastName}".trim().ifBlank { null } }

        // 9. Rozliczenie wizyty: typ dokumentu z modułu finansów + ewentualna
        // faktura KSeF. Czytane osobno, bo dokument finansowy typu INVOICE może
        // istnieć bez rekordu KSeF (adnotacja bez wysyłki) i odwrotnie.
        val settlementDocuments = financialDocumentRepository
            .findAllByVisitIdAndStudioIdAndDeletedAtIsNull(visit.id.value, command.studioId.value)

        // Faktura ma pierwszeństwo nad pozostałymi dokumentami: gdy wizytę
        // rozliczono dwoma dokumentami (część na fakturę, reszta na paragon),
        // to faktura decyduje o tym, co widzi użytkownik. Priorytet jest wybrany
        // jawnie, bo kolejność stałych w DocumentType stawia RECEIPT przed INVOICE.
        val settlementDocumentType = (
            settlementDocuments.firstOrNull { it.documentType == DocumentType.INVOICE }
                ?: settlementDocuments.firstOrNull()
            )?.documentType?.name

        val revenueInvoiceId = revenueInvoiceRepository
            .findFirstByVisitIdAndStudioIdOrderByCreatedAtAsc(visit.id.value, command.studioId.value)
            ?.id?.toString()

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
