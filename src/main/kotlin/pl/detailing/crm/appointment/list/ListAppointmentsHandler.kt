package pl.detailing.crm.appointment.list

import pl.detailing.crm.shared.pii.Pii
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Service
import pl.detailing.crm.appointment.domain.AdjustmentType
import pl.detailing.crm.appointment.domain.AppointmentStatus
import pl.detailing.crm.appointment.infrastructure.AppointmentColorRepository
import pl.detailing.crm.appointment.infrastructure.AppointmentRepository
import pl.detailing.crm.customer.infrastructure.CustomerRepository
import pl.detailing.crm.service.infrastructure.ServicePackageItemRepository
import pl.detailing.crm.service.infrastructure.ServiceRepository
import pl.detailing.crm.service.list.PackageItemDto
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.smscampaigns.domain.SmsTriggerType
import pl.detailing.crm.smscampaigns.infrastructure.SmsLogEntity
import pl.detailing.crm.smscampaigns.infrastructure.SmsLogJpaRepository
import pl.detailing.crm.smscampaigns.infrastructure.SmsLogStatus
import pl.detailing.crm.vehicle.infrastructure.VehicleRepository
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

@Service
class ListAppointmentsHandler(
    private val appointmentRepository: AppointmentRepository,
    private val customerRepository: CustomerRepository,
    private val vehicleRepository: VehicleRepository,
    private val appointmentColorRepository: AppointmentColorRepository,
    private val smsLogRepository: SmsLogJpaRepository,
    private val serviceRepository: ServiceRepository,
    private val packageItemRepository: ServicePackageItemRepository
) {
    suspend fun handle(command: ListAppointmentsCommand): ListAppointmentsResult =
        withContext(Dispatchers.IO) {
            // Create pageable with zero-based indexing (Spring Data uses 0-based, but we accept 1-based from API)
            val pageRequest = PageRequest.of(
                command.page - 1, // Convert to 0-based
                command.pageSize,
                Sort.by(Sort.Direction.DESC, "startDateTime")
            )

            // Execute query with filters and pagination at database level
            val warsawZone = ZoneId.of("Europe/Warsaw")
            val page = if (command.scheduledDate != null) {
                val startOfDay = command.scheduledDate.atStartOfDay(warsawZone).toInstant()
                val endOfDay = command.scheduledDate.plusDays(1).atStartOfDay(warsawZone).toInstant()
                appointmentRepository.findAppointmentsWithFiltersAndScheduledDate(
                    studioId = command.studioId.value,
                    customerId = command.customerId,
                    status = command.status,
                    searchTerm = command.searchTerm?.takeIf { it.isNotBlank() },
                    includePiiSearch = command.includePiiSearch,
                    startOfDay = startOfDay,
                    endOfDay = endOfDay,
                    pageable = pageRequest
                )
            } else {
                appointmentRepository.findAppointmentsWithFilters(
                    studioId = command.studioId.value,
                    customerId = command.customerId,
                    status = command.status,
                    searchTerm = command.searchTerm?.takeIf { it.isNotBlank() },
                    includePiiSearch = command.includePiiSearch,
                    pageable = pageRequest
                )
            }

            val appointments = page.content

            // Collect all IDs to fetch in batch
            val customerIds = appointments.map { it.customerId }.distinct()
            val vehicleIds = appointments.mapNotNull { it.vehicleId }.distinct()
            val colorIds = appointments.map { it.appointmentColorId }.distinct()
            val appointmentIds = appointments.map { it.id }

            // Batch fetch related entities
            val customers = customerRepository.findAllById(customerIds).associateBy { it.id }
            val vehicles = vehicleRepository.findAllById(vehicleIds).associateBy { it.id }
            val colors = appointmentColorRepository.findAllById(colorIds).associateBy { it.id }
            val smsLogsByAppointment = smsLogRepository.findAllByAppointmentIdIn(appointmentIds)
                .groupBy { it.appointmentId }

            // Batch-fetch series counts for recurring appointments
            val localSeriesCounts = mutableMapOf<UUID, Long>()
            appointments.mapNotNull { it.recurrenceSeriesId }.distinct().forEach { sid ->
                localSeriesCounts[sid] = appointmentRepository.countBySeriesId(sid)
            }

            // Map to list items
            val items = appointments.map { appointment ->
                val customer = customers[appointment.customerId]
                val vehicle = vehicles[appointment.vehicleId]
                val color = colors[appointment.appointmentColorId]

                val domain = appointment.toDomain()
                val totalNet = domain.calculateTotalNet()
                val totalGross = domain.calculateTotalGross()
                val totalVat = domain.calculateTotalVat()

                AppointmentListItem(
                    id = appointment.id.toString(),
                    customerId = appointment.customerId.toString(),
                    vehicleId = appointment.vehicleId?.toString(),
                    customer = CustomerInfo(
                        firstName = customer?.firstName ?: "",
                        lastName = customer?.lastName ?: "",
                        phone = customer?.phone ?: "",
                        email = customer?.email ?: ""
                    ),
                    vehicle = vehicle?.let {
                        VehicleInfo(
                            brand = it.brand,
                            model = it.model,
                            year = it.yearOfProduction,
                            licensePlate = it.licensePlate
                        )
                    },
                    services = buildServiceLineItems(appointment.lineItems),
                    schedule = ScheduleInfo(
                        isAllDay = appointment.isAllDay,
                        startDateTime = appointment.startDateTime,
                        endDateTime = appointment.endDateTime
                    ),
                    appointmentTitle = appointment.appointmentTitle,
                    appointmentColor = AppointmentColorInfo(
                        id = appointment.appointmentColorId.toString(),
                        name = color?.name ?: "Unknown",
                        hexColor = color?.hexColor ?: "#808080"
                    ),
                    status = appointment.status,
                    note = appointment.note,
                    totalNet = totalNet.amountInCents,
                    totalGross = totalGross.amountInCents,
                    totalVat = totalVat.amountInCents,
                    createdAt = appointment.createdAt,
                    updatedAt = appointment.updatedAt,
                    smsInfo = buildSmsInfo(
                        smsLogs = smsLogsByAppointment[appointment.id] ?: emptyList(),
                        sendReminderSms = appointment.sendReminderSms
                    ),
                    recurrenceInfo = appointment.recurrenceSeriesId?.let { sid ->
                        RecurrenceInfo(
                            seriesId = sid.toString(),
                            recurrenceIndex = appointment.recurrenceIndex ?: 0,
                            totalInSeries = localSeriesCounts[sid] ?: 0L,
                            isDetached = appointment.isDetached
                        )
                    }
                )
            }

            ListAppointmentsResult(
                items = items,
                total = page.totalElements.toInt(),
                page = command.page, // Return original 1-based page number
                pageSize = command.pageSize,
                totalPages = page.totalPages
            )
        }

    fun buildServiceLineItems(lineItems: List<pl.detailing.crm.appointment.infrastructure.AppointmentLineItemEntity>): List<ServiceLineItemInfo> {
        val serviceIds = lineItems.mapNotNull { it.serviceId }.distinct()
        if (serviceIds.isEmpty()) return lineItems.map { mapLineItem(it, false, null) }

        val packageServiceIds = serviceRepository.findAllById(serviceIds)
            .filter { it.isPackage }
            .map { it.id }
            .toSet()

        val packageItemsByServiceId: Map<UUID, List<PackageItemDto>> = if (packageServiceIds.isNotEmpty()) {
            packageItemRepository.findByPackageIdIn(packageServiceIds.toList())
                .groupBy({ it.packageId }, { PackageItemDto(it.serviceId.toString(), it.serviceName, it.position) })
        } else emptyMap()

        return lineItems.map { lineItem ->
            val sid = lineItem.serviceId
            val isPkg = sid != null && sid in packageServiceIds
            val items = if (isPkg && sid != null) packageItemsByServiceId[sid]?.sortedBy { it.position } else null
            mapLineItem(lineItem, isPkg, items)
        }
    }

    private fun mapLineItem(
        lineItem: pl.detailing.crm.appointment.infrastructure.AppointmentLineItemEntity,
        isPackage: Boolean,
        packageItems: List<PackageItemDto>?
    ): ServiceLineItemInfo {
        val adjustmentValue = when (lineItem.adjustmentType) {
            AdjustmentType.PERCENT -> lineItem.adjustmentValue / 100.0
            else -> lineItem.adjustmentValue.toDouble()
        }
        return ServiceLineItemInfo(
            id = lineItem.id.toString(),
            serviceId = lineItem.serviceId?.toString(),
            serviceName = lineItem.serviceName,
            basePriceNet = lineItem.basePriceNet,
            vatRate = lineItem.vatRate,
            adjustment = PriceAdjustmentInfo(
                type = lineItem.adjustmentType,
                value = adjustmentValue
            ),
            note = lineItem.customNote,
            finalPriceNet = lineItem.finalPriceNet,
            finalPriceGross = lineItem.finalPriceGross,
            isPackage = isPackage,
            packageItems = packageItems
        )
    }
}

fun buildSmsInfo(smsLogs: List<SmsLogEntity>, sendReminderSms: Boolean): AppointmentSmsInfo {
    val logByTrigger = smsLogs.associateBy { it.triggerType }

    val confirmationLog = logByTrigger[SmsTriggerType.BOOKING_CONFIRMATION]
    val confirmationSms = confirmationLog?.let {
        SmsStatusInfo(
            status = if (it.status == SmsLogStatus.SENT) AppointmentSmsStatus.SENT else AppointmentSmsStatus.FAILED,
            sentAt = it.sentAt
        )
    }

    val reminderLog = logByTrigger[SmsTriggerType.PRE_VISIT]
    val reminderStatus = when {
        reminderLog != null -> if (reminderLog.status == SmsLogStatus.SENT) AppointmentSmsStatus.SENT else AppointmentSmsStatus.FAILED
        sendReminderSms -> AppointmentSmsStatus.PENDING
        else -> null
    }
    val reminderEditable = reminderStatus != AppointmentSmsStatus.SENT

    return AppointmentSmsInfo(
        confirmationSms = confirmationSms,
        reminderSms = ReminderSmsStatusInfo(
            requested = sendReminderSms,
            status = reminderStatus,
            sentAt = reminderLog?.sentAt,
            editable = reminderEditable
        )
    )
}

/**
 * Command for listing appointments with pagination and filtering
 */
data class ListAppointmentsCommand(
    val studioId: StudioId,
    val page: Int = 1,
    val pageSize: Int = 20,
    val status: AppointmentStatus? = null,
    val searchTerm: String? = null,
    val scheduledDate: LocalDate? = null,
    val customerId: UUID? = null,
    /** Whether search may match personal-data columns; false = oracle-safe. */
    val includePiiSearch: Boolean = false
)

/**
 * Result of listing appointments with pagination metadata
 */
data class ListAppointmentsResult(
    val items: List<AppointmentListItem>,
    val total: Int,
    val page: Int,
    val pageSize: Int,
    val totalPages: Int
)

data class AppointmentListItem(
    val id: String,
    val customerId: String,
    val vehicleId: String?,
    val customer: CustomerInfo,
    val vehicle: VehicleInfo?,
    val services: List<ServiceLineItemInfo>,
    val schedule: ScheduleInfo,
    val appointmentTitle: String?,
    val appointmentColor: AppointmentColorInfo,
    val status: AppointmentStatus,
    val note: String?,
    val totalNet: Long,
    val totalGross: Long,
    val totalVat: Long,
    val createdAt: Instant,
    val updatedAt: Instant,
    val smsInfo: AppointmentSmsInfo,
    val recurrenceInfo: RecurrenceInfo? = null
)

data class RecurrenceInfo(
    val seriesId: String,
    val recurrenceIndex: Int,
    val totalInSeries: Long,
    val isDetached: Boolean
)

enum class AppointmentSmsStatus { PENDING, SENT, FAILED }

data class SmsStatusInfo(
    val status: AppointmentSmsStatus,
    val sentAt: Instant?
)

data class ReminderSmsStatusInfo(
    val requested: Boolean,
    val status: AppointmentSmsStatus?,
    val sentAt: Instant?,
    /** False when reminder has already been sent — field is read-only at that point. */
    val editable: Boolean
)

data class AppointmentSmsInfo(
    /** Non-null when a confirmation SMS was attempted (regardless of success). */
    val confirmationSms: SmsStatusInfo?,
    val reminderSms: ReminderSmsStatusInfo
)

data class CustomerInfo(
    @Pii val firstName: String,
    @Pii val lastName: String,
    @Pii val phone: String,
    @Pii val email: String
)

data class VehicleInfo(
    val brand: String,
    val model: String,
    val year: Int?,
    val licensePlate: String?
)

data class ServiceLineItemInfo(
    val id: String,
    val serviceId: String?,
    val serviceName: String,
    val basePriceNet: Long,
    val vatRate: Int,
    val adjustment: PriceAdjustmentInfo,
    val note: String?,
    val finalPriceNet: Long,
    val finalPriceGross: Long,
    val isPackage: Boolean,
    val packageItems: List<PackageItemDto>?
)

data class PriceAdjustmentInfo(
    val type: AdjustmentType,
    val value: Double  // Double to support decimal percentages like -49.19
)

data class ScheduleInfo(
    val isAllDay: Boolean,
    val startDateTime: Instant,
    val endDateTime: Instant
)

data class AppointmentColorInfo(
    val id: String,
    val name: String,
    val hexColor: String
)
