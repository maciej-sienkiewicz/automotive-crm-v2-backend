package pl.detailing.crm.calendar

import kotlinx.coroutines.runBlocking
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import pl.detailing.crm.appointment.domain.AppointmentStatus
import pl.detailing.crm.auth.SecurityContextHelper
import pl.detailing.crm.role.domain.Permission
import pl.detailing.crm.role.permission.PermissionCheckService
import pl.detailing.crm.shared.PII_MASK
import pl.detailing.crm.shared.VisitStatus
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import java.util.UUID

@RestController
@RequestMapping("/api/v1/calendar")
class CalendarController(
    private val getCalendarEventsHandler: GetCalendarEventsHandler,
    private val permissionCheckService: PermissionCheckService
) {

    @GetMapping("/events")
    fun getCalendarEvents(
        @RequestParam(required = false) startDate: String?,
        @RequestParam(required = false) endDate: String?,
        @RequestParam(required = false) appointmentStatuses: String?,
        @RequestParam(required = false) visitStatuses: String?,
        @RequestParam(required = false) customerId: String?,
        @RequestParam(required = false) vehicleId: String?
    ): ResponseEntity<Any> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()

        if (startDate.isNullOrBlank()) {
            return@runBlocking badRequest("MISSING_PARAMETER", "startDate is required", "startDate")
        }
        if (endDate.isNullOrBlank()) {
            return@runBlocking badRequest("MISSING_PARAMETER", "endDate is required", "endDate")
        }

        val startInstant = parseInstant(startDate)
            ?: return@runBlocking badRequest("INVALID_DATE_FORMAT", "Invalid startDate: '$startDate'. Expected ISO 8601 with offset, e.g. 2026-04-01T00:00:00Z", "startDate")

        val endInstant = parseInstant(endDate)
            ?: return@runBlocking badRequest("INVALID_DATE_FORMAT", "Invalid endDate: '$endDate'. Expected ISO 8601 with offset, e.g. 2026-05-01T00:00:00Z", "endDate")

        val allowedAppointmentStatuses = listOf(AppointmentStatus.CREATED, AppointmentStatus.ABANDONED, AppointmentStatus.CANCELLED)
        val parsedAppointmentStatuses: List<AppointmentStatus> = when {
            appointmentStatuses == null && visitStatuses != null -> emptyList()
            appointmentStatuses == null -> allowedAppointmentStatuses
            appointmentStatuses.isBlank() -> emptyList()
            else -> {
                val result = mutableListOf<AppointmentStatus>()
                for (part in appointmentStatuses.split(",").map { it.trim() }.filter { it.isNotEmpty() }) {
                    val status = allowedAppointmentStatuses.find { it.name == part }
                        ?: return@runBlocking badRequest(
                            "INVALID_STATUS_VALUE",
                            "Unknown appointmentStatus: '$part'. Allowed: ${allowedAppointmentStatuses.joinToString(", ") { it.name }}",
                            "appointmentStatuses"
                        )
                    result.add(status)
                }
                result
            }
        }

        val allowedVisitStatuses = listOf(VisitStatus.IN_PROGRESS, VisitStatus.READY_FOR_PICKUP, VisitStatus.COMPLETED, VisitStatus.REJECTED, VisitStatus.ARCHIVED)
        val parsedVisitStatuses: List<VisitStatus> = when {
            visitStatuses == null && appointmentStatuses != null -> emptyList()
            visitStatuses == null -> allowedVisitStatuses
            visitStatuses.isBlank() -> emptyList()
            else -> {
                val result = mutableListOf<VisitStatus>()
                for (part in visitStatuses.split(",").map { it.trim() }.filter { it.isNotEmpty() }) {
                    val status = allowedVisitStatuses.find { it.name == part }
                        ?: return@runBlocking badRequest(
                            "INVALID_STATUS_VALUE",
                            "Unknown visitStatus: '$part'. Allowed: ${allowedVisitStatuses.joinToString(", ") { it.name }}",
                            "visitStatuses"
                        )
                    result.add(status)
                }
                result
            }
        }

        val parsedCustomerId: UUID? = if (customerId.isNullOrBlank()) null else {
            parseUuid(customerId)
                ?: return@runBlocking badRequest("INVALID_UUID", "Invalid customerId: '$customerId'. Expected UUID format.", "customerId")
        }

        val parsedVehicleId: UUID? = if (vehicleId.isNullOrBlank()) null else {
            parseUuid(vehicleId)
                ?: return@runBlocking badRequest("INVALID_UUID", "Invalid vehicleId: '$vehicleId'. Expected UUID format.", "vehicleId")
        }

        val query = GetCalendarEventsQuery(
            studioId = principal.studioId,
            startDate = startInstant,
            endDate = endInstant,
            appointmentStatuses = parsedAppointmentStatuses,
            visitStatuses = parsedVisitStatuses,
            customerId = parsedCustomerId,
            vehicleId = parsedVehicleId
        )

        val result = getCalendarEventsHandler.handle(query)
        val mask = !permissionCheckService.hasPermission(principal.userId, principal.studioId, Permission.CUSTOMERS_VIEW_PERSONAL_DATA)

        val appointments = if (mask) result.appointments.map {
            it.copy(customer = it.customer.copy(firstName = PII_MASK, lastName = PII_MASK, phone = PII_MASK, email = PII_MASK))
        } else result.appointments

        val visits = if (mask) result.visits.map {
            it.copy(customer = it.customer.copy(firstName = PII_MASK, lastName = PII_MASK, phone = PII_MASK))
        } else result.visits

        ResponseEntity.ok<Any>(CalendarEventsResponse(
            appointments = appointments,
            visits = visits
        ))
    }

    private fun badRequest(error: String, message: String, field: String): ResponseEntity<Any> =
        ResponseEntity.badRequest().body(CalendarErrorResponse(error, message, field))

    private fun parseInstant(value: String): Instant? = try {
        OffsetDateTime.parse(value).toInstant()
    } catch (_: DateTimeParseException) {
        null
    }

    private fun parseUuid(value: String): UUID? = try {
        UUID.fromString(value)
    } catch (_: IllegalArgumentException) {
        null
    }
}

data class CalendarErrorResponse(
    val error: String,
    val message: String,
    val field: String
)

data class CalendarEventsResponse(
    val appointments: List<AppointmentCalendarItem>,
    val visits: List<VisitCalendarItem>
)

data class AppointmentCalendarItem(
    val id: String,
    val appointmentTitle: String?,
    val customerId: String,
    val vehicleId: String?,
    val status: String,
    val customer: AppointmentCustomerInfo,
    val vehicle: AppointmentVehicleInfo?,
    val services: List<AppointmentServiceInfo>,
    val schedule: AppointmentScheduleInfo,
    val appointmentColor: AppointmentColorInfo,
    val totalNet: Long,
    val totalGross: Long,
    val totalVat: Long,
    val note: String?
)

data class AppointmentCustomerInfo(
    val firstName: String,
    val lastName: String,
    val phone: String,
    val email: String
)

data class AppointmentVehicleInfo(
    val brand: String,
    val model: String,
    val year: Int?,
    val licensePlate: String?
)

data class AppointmentServiceInfo(
    val id: String,
    val serviceId: String?,
    val serviceName: String,
    val basePriceNet: Long,
    val vatRate: Int,
    val finalPriceNet: Long,
    val finalPriceGross: Long
)

data class AppointmentScheduleInfo(
    val isAllDay: Boolean,
    val startDateTime: Instant,
    val endDateTime: Instant
)

data class AppointmentColorInfo(
    val id: String,
    val name: String,
    val hexColor: String
)

data class VisitCalendarItem(
    val id: String,
    val title: String?,
    val visitNumber: String,
    val customerId: String,
    val vehicleId: String,
    val status: String,
    val scheduledDate: Instant,
    val estimatedCompletionDate: Instant?,
    val customer: VisitCustomerInfo,
    val vehicle: VisitVehicleInfo,
    val appointmentColor: AppointmentColorInfo?,
    val totalNet: Long,
    val totalGross: Long,
    val technicalNotes: String?
)

data class VisitCustomerInfo(
    val firstName: String,
    val lastName: String,
    val phone: String,
    val companyName: String?
)

data class VisitVehicleInfo(
    val licensePlate: String?,
    val brand: String,
    val model: String,
    val yearOfProduction: Int?
)
