package pl.detailing.crm.appointment

import kotlinx.coroutines.runBlocking
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import pl.detailing.crm.appointment.create.*
import pl.detailing.crm.appointment.update.*
import pl.detailing.crm.appointment.domain.AppointmentStatus
import pl.detailing.crm.appointment.get.GetAppointmentHandler
import pl.detailing.crm.appointment.list.AppointmentListItem
import pl.detailing.crm.appointment.list.ListAppointmentsCommand
import pl.detailing.crm.appointment.list.ListAppointmentsHandler
import pl.detailing.crm.auth.SecurityContextHelper
import pl.detailing.crm.shared.*
import java.time.LocalDate

@RestController
@RequestMapping("/api/v1/appointments")
class AppointmentController(
    private val createAppointmentHandler: CreateAppointmentHandler,
    private val updateAppointmentHandler: UpdateAppointmentHandler,
    private val listAppointmentsHandler: ListAppointmentsHandler,
    private val getAppointmentHandler: GetAppointmentHandler
) {

    @GetMapping
    fun getAppointments(
        @RequestParam(defaultValue = "1") page: Int,
        @RequestParam(defaultValue = "20") limit: Int,
        @RequestParam(required = false) status: String?,
        @RequestParam(required = false) search: String?,
        @RequestParam(required = false) scheduledDate: String?
    ): ResponseEntity<AppointmentListResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()

        val appointmentStatus = status?.let {
            try {
                AppointmentStatus.valueOf(it.uppercase())
            } catch (e: IllegalArgumentException) {
                null
            }
        }

        val scheduledDateFilter = scheduledDate?.let {
            try {
                LocalDate.parse(it)
            } catch (e: Exception) {
                null
            }
        }

        val command = ListAppointmentsCommand(
            studioId = principal.studioId,
            page = maxOf(1, page),
            pageSize = maxOf(1, minOf(100, limit)), // Limit page size to 100
            status = appointmentStatus,
            searchTerm = search,
            scheduledDate = scheduledDateFilter
        )

        val result = listAppointmentsHandler.handle(command)

        ResponseEntity.ok(AppointmentListResponse(
            appointments = result.items,
            pagination = PaginationMeta(
                currentPage = result.page,
                totalPages = result.totalPages,
                totalItems = result.total,
                itemsPerPage = result.pageSize
            )
        ))
    }

    @GetMapping("/{id}")
    fun getAppointment(@PathVariable id: String): ResponseEntity<AppointmentListItem> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()

        val appointmentId = AppointmentId.fromString(id)
        val appointment = getAppointmentHandler.handle(appointmentId, principal.studioId)

        ResponseEntity.ok(appointment)
    }

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
                CustomerMode.UPDATE -> {
                    val updateData = request.customer.updateData!!
                    CustomerIdentity.Update(
                        customerId = CustomerId.fromString(request.customer.id!!),
                        firstName = updateData.firstName,
                        lastName = updateData.lastName,
                        phone = updateData.phone,
                        email = updateData.email,
                        companyName = updateData.company?.name,
                        companyNip = updateData.company?.nip,
                        companyRegon = updateData.company?.regon,
                        companyAddress = updateData.company?.address
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
                VehicleMode.UPDATE -> {
                    val updateData = request.vehicle.updateData!!
                    VehicleIdentity.Update(
                        vehicleId = VehicleId.fromString(request.vehicle.id!!),
                        brand = updateData.brand,
                        model = updateData.model,
                        year = updateData.year,
                        licensePlate = updateData.licensePlate
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
                startDateTime = request.schedule.startDateTime,
                endDateTime = request.schedule.endDateTime
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

    @PutMapping("/{id}")
    fun updateAppointment(
        @PathVariable id: String,
        @RequestBody request: CreateAppointmentRequest
    ): ResponseEntity<AppointmentCreateResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()

        if (principal.role != UserRole.OWNER && principal.role != UserRole.MANAGER) {
            throw ForbiddenException("Only OWNER and MANAGER can update appointments")
        }

        val command = UpdateAppointmentCommand(
            appointmentId = AppointmentId.fromString(id),
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
                CustomerMode.UPDATE -> {
                    val updateData = request.customer.updateData!!
                    CustomerIdentity.Update(
                        customerId = CustomerId.fromString(request.customer.id!!),
                        firstName = updateData.firstName,
                        lastName = updateData.lastName,
                        phone = updateData.phone,
                        email = updateData.email,
                        companyName = updateData.company?.name,
                        companyNip = updateData.company?.nip,
                        companyRegon = updateData.company?.regon,
                        companyAddress = updateData.company?.address
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
                VehicleMode.UPDATE -> {
                    val updateData = request.vehicle.updateData!!
                    VehicleIdentity.Update(
                        vehicleId = VehicleId.fromString(request.vehicle.id!!),
                        brand = updateData.brand,
                        model = updateData.model,
                        year = updateData.year,
                        licensePlate = updateData.licensePlate
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
                startDateTime = request.schedule.startDateTime,
                endDateTime = request.schedule.endDateTime
            ),
            appointmentTitle = request.appointmentTitle,
            appointmentColorId = AppointmentColorId.fromString(request.appointmentColorId)
        )

        val result = updateAppointmentHandler.handle(command)

        ResponseEntity.ok(
            AppointmentCreateResponse(
                id = result.appointmentId.toString(),
                customerId = result.customerId.toString(),
                vehicleId = result.vehicleId?.toString(),
                totalNet = result.totalNet.amountInCents,
                totalGross = result.totalGross.amountInCents,
                totalVat = result.totalVat.amountInCents
            )
        )
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

data class AppointmentListResponse(
    val appointments: List<AppointmentListItem>,
    val pagination: PaginationMeta
)

data class PaginationMeta(
    val currentPage: Int,
    val totalPages: Int,
    val totalItems: Int,
    val itemsPerPage: Int
)
