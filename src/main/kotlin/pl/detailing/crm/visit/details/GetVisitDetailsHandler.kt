package pl.detailing.crm.visit.details

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import pl.detailing.crm.customer.infrastructure.CustomerRepository
import pl.detailing.crm.shared.*
import pl.detailing.crm.vehicle.infrastructure.VehicleRepository
import pl.detailing.crm.visit.infrastructure.VisitRepository

@Service
class GetVisitDetailsHandler(
    private val visitRepository: VisitRepository,
    private val customerRepository: CustomerRepository,
    private val vehicleRepository: VehicleRepository
) {
    suspend fun handle(command: GetVisitDetailsCommand): GetVisitDetailsResult =
        withContext(Dispatchers.IO) {
            // Load visit
            val visitEntity = visitRepository.findByIdAndStudioId(
                command.visitId.value,
                command.studioId.value
            ) ?: throw EntityNotFoundException("Visit not found")

            val visit = visitEntity.toDomain()

            // Load related entities in parallel
            val customerDeferred = async {
                customerRepository.findByIdAndStudioId(
                    visit.customerId.value,
                    command.studioId.value
                )?.toDomain()
            }

            val vehicleDeferred = async {
                vehicleRepository.findByIdAndStudioId(
                    visit.vehicleId.value,
                    command.studioId.value
                )?.toDomain()
            }

            val customer = customerDeferred.await()
                ?: throw EntityNotFoundException("Customer not found")

            val vehicle = vehicleDeferred.await()
                ?: throw EntityNotFoundException("Vehicle not found")

            GetVisitDetailsResult(
                visit = visit,
                customer = customer,
                vehicle = vehicle
            )
        }
}

/**
 * Command to get visit details
 */
data class GetVisitDetailsCommand(
    val studioId: StudioId,
    val visitId: VisitId
)

/**
 * Result containing visit with full details
 */
data class GetVisitDetailsResult(
    val visit: pl.detailing.crm.visit.domain.Visit,
    val customer: pl.detailing.crm.customer.domain.Customer,
    val vehicle: pl.detailing.crm.vehicle.domain.Vehicle
)
