package pl.detailing.crm.visit.get

import pl.detailing.crm.shared.*
import pl.detailing.crm.visit.domain.*
import pl.detailing.crm.customer.domain.Customer
import pl.detailing.crm.vehicle.domain.Vehicle
import pl.detailing.crm.appointment.infrastructure.AppointmentColorEntity
import java.time.Instant

/**
 * Response for visit detail endpoint
 */
data class VisitDetailResponse(
    val visit: VisitResponse,
    val journalEntries: List<JournalEntryResponse>,
    val documents: List<VisitDocumentResponse>
)

/**
 * Visit response DTO
 */
data class VisitResponse(
    val id: String,
    val visitNumber: String,
    val status: String,
    val scheduledDate: String,
    val estimatedCompletionDate: String?,
    val actualCompletionDate: String?,
    val pickupDate: String?,
    val vehicle: VehicleInfoResponse,
    val customer: CustomerInfoResponse,
    val appointmentColor: AppointmentColorResponse?,
    val services: List<ServiceLineItemResponse>,
    val totalCost: MoneyAmountResponse,
    val mileageAtArrival: Long?,
    val keysHandedOver: Boolean,
    val documentsHandedOver: Boolean,
    val technicalNotes: String?,
    val createdAt: String,
    val updatedAt: String
)

/**
 * Vehicle information response
 */
data class VehicleInfoResponse(
    val id: String,
    val licensePlate: String?,
    val brand: String,
    val model: String,
    val yearOfProduction: Int?,
    val color: String?,
    val currentMileage: Int?
)

/**
 * Customer information response with stats
 */
data class CustomerInfoResponse(
    val id: String,
    val firstName: String,
    val lastName: String,
    val email: String,
    val phone: String,
    val companyName: String?,
    val stats: CustomerStatsResponse
)

/**
 * Customer statistics response
 */
data class CustomerStatsResponse(
    val totalVisits: Int,
    val totalSpent: MoneyAmountResponse,
    val vehiclesCount: Int
)

/**
 * Service line item response
 */
data class ServiceLineItemResponse(
    val id: String,
    val serviceId: String,
    val serviceName: String,
    val basePriceNet: Long,
    val vatRate: Int,
    val adjustment: AdjustmentResponse,
    val note: String?,
    val finalPriceNet: Long,
    val finalPriceGross: Long
)

/**
 * Adjustment response
 */
data class AdjustmentResponse(
    val type: String,
    val value: Long
)

/**
 * Money amount response
 */
data class MoneyAmountResponse(
    val netAmount: Long,
    val grossAmount: Long,
    val currency: String
)

/**
 * Journal entry response
 */
data class JournalEntryResponse(
    val id: String,
    val type: String,
    val content: String,
    val createdBy: String,
    val createdAt: String,
    val isDeleted: Boolean
)

/**
 * Visit document response
 */
data class VisitDocumentResponse(
    val id: String,
    val type: String,
    val fileName: String,
    val fileUrl: String,
    val uploadedAt: String,
    val uploadedBy: String,
    val category: String?
)

/**
 * Command for getting visit details
 */
data class GetVisitDetailCommand(
    val studioId: StudioId,
    val userId: UserId,
    val visitId: VisitId
)

/**
 * Result of getting visit details
 */
data class GetVisitDetailResult(
    val visit: Visit,
    val vehicle: Vehicle,
    val customer: Customer,
    val appointmentColor: AppointmentColorEntity.AppointmentColorDomain?,
    val journalEntries: List<VisitJournalEntry>,
    val documents: List<VisitDocument>,
    val customerStats: CustomerStats
)

/**
 * Customer statistics
 */
data class CustomerStats(
    val totalVisits: Int,
    val totalSpent: Money,
    val vehiclesCount: Int
)

/**
 * Appointment color response
 */
data class AppointmentColorResponse(
    val id: String,
    val name: String,
    val hexColor: String
)
