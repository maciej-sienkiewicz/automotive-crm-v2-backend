package pl.detailing.crm.visit.get

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.shared.*
import pl.detailing.crm.visit.infrastructure.*
import pl.detailing.crm.customer.infrastructure.CustomerRepository
import pl.detailing.crm.vehicle.infrastructure.VehicleRepository
import pl.detailing.crm.vehicle.infrastructure.VehicleOwnerRepository
import pl.detailing.crm.appointment.infrastructure.AppointmentColorRepository

@Service
class GetVisitDetailHandler(
    private val visitRepository: VisitRepository,
    private val customerRepository: CustomerRepository,
    private val vehicleRepository: VehicleRepository,
    private val vehicleOwnerRepository: VehicleOwnerRepository,
    private val journalEntryRepository: VisitJournalEntryRepository,
    private val documentRepository: VisitDocumentRepository,
    private val appointmentColorRepository: AppointmentColorRepository
) {

    @Transactional(readOnly = true)
    suspend fun handle(command: GetVisitDetailCommand): GetVisitDetailResult {
        // 1. Find visit with studio isolation
        val visitEntity = visitRepository.findByIdAndStudioId(
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
        val customerVisits = visitRepository.findByCustomerIdAndStudioId(
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

        // Count unique vehicles for this customer
        val vehiclesCount = vehicleOwnerRepository.findByCustomerId(customer.id.value).size

        val customerStats = CustomerStats(
            totalVisits = totalVisits,
            totalSpent = totalSpent,
            vehiclesCount = vehiclesCount
        )

        return GetVisitDetailResult(
            visit = visit,
            vehicle = vehicle,
            customer = customer,
            appointmentColor = appointmentColor,
            journalEntries = journalEntries,
            documents = documents,
            customerStats = customerStats
        )
    }
}
