package pl.detailing.crm.appointment

import kotlinx.coroutines.runBlocking
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import pl.detailing.crm.appointment.create.*
import pl.detailing.crm.auth.SecurityContextHelper
import pl.detailing.crm.shared.*
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

@RestController
@RequestMapping("/api/v1/appointments")
class AppointmentController(
    private val createAppointmentHandler: CreateAppointmentHandler
) {

    @PostMapping
    fun createAppointment(@RequestBody request: CreateAppointmentRequest): ResponseEntity<AppointmentCreateResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()

        if (principal.role != UserRole.OWNER && principal.role != UserRole.MANAGER) {
            throw ForbiddenException("Only OWNER and MANAGER can create appointments")
        }

        // Map request to command
        val command = CreateAppointmentCommand(
            studioId = principal.studioId,
            userId = principal.userId,
            customer = when (request.customer.mode) {
                CustomerMode.EXISTING -> {
                    CustomerIdentity.Existing(
                        customerId = CustomerId.fromString(request.customer.id!!)
                    )
                }
                CustomerMode.NEW -> {
                    val newData = request.customer.newData!!
                    CustomerIdentity.New(
                        firstName = newData.firstName,
                        lastName = newData.lastName,
                        phone = newData.phone,
                        email = newData.email,
                        companyName = newData.company?.name,
                        companyNip = newData.company?.nip,
                        companyRegon = newData.company?.regon,
                        companyAddress = newData.company?.address
                    )
                }
            },
            vehicle = when (request.vehicle.mode) {
                VehicleMode.EXISTING -> {
                    VehicleIdentity.Existing(
                        vehicleId = VehicleId.fromString(request.vehicle.id!!)
                    )
                }
                VehicleMode.NEW -> {
                    val newData = request.vehicle.newData!!
                    VehicleIdentity.New(
                        brand = newData.brand,
                        model = newData.model,
                        year = newData.year,
                        licensePlate = newData.licensePlate
                    )
                }
                VehicleMode.NONE -> VehicleIdentity.None
            },
            services = request.services.map { service ->
                ServiceLineItemCommand(
                    serviceId = ServiceId.fromString(service.serviceId),
                    adjustmentType = service.adjustment.type,
                    adjustmentValue = service.adjustment.value,
                    customNote = service.note
                )
            },
            schedule = ScheduleCommand(
                isAllDay = request.schedule.isAllDay,
                startDateTime = LocalDateTime.parse(request.schedule.startDateTime).atZone(ZoneId.systemDefault()).toInstant(),
                endDateTime = LocalDateTime.parse(request.schedule.endDateTime).atZone(ZoneId.systemDefault()).toInstant()
            ),
            appointmentTitle = request.appointmentTitle,
            appointmentColorId = AppointmentColorId.fromString(request.appointmentColorId)
        )

        val result = createAppointmentHandler.handle(command)

        ResponseEntity
            .status(HttpStatus.CREATED)
            .body(AppointmentCreateResponse(
                id = result.appointmentId.toString(),
                customerId = result.customerId.toString(),
                vehicleId = result.vehicleId?.toString(),
                totalNet = result.totalNet.amountInCents,
                totalGross = result.totalGross.amountInCents,
                totalVat = result.totalVat.amountInCents
            ))
    }
}

data class AppointmentCreateResponse(
    val id: String,
    val customerId: String,
    val vehicleId: String?,
    val totalNet: Long,
    val totalGross: Long,
    val totalVat: Long
)
