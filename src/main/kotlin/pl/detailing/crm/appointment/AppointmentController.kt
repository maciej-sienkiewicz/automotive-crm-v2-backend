package pl.detailing.crm.appointment

import kotlinx.coroutines.runBlocking
import org.apache.coyote.BadRequestException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import pl.detailing.crm.appointment.create.*
import pl.detailing.crm.appointment.update.*
import pl.detailing.crm.appointment.cancel.*
import pl.detailing.crm.appointment.restore.*
import pl.detailing.crm.appointment.delete.*
import pl.detailing.crm.appointment.domain.AppointmentStatus
import pl.detailing.crm.appointment.get.GetAppointmentHandler
import pl.detailing.crm.appointment.list.AppointmentListItem
import pl.detailing.crm.appointment.list.ListAppointmentsCommand
import pl.detailing.crm.appointment.list.ListAppointmentsHandler
import pl.detailing.crm.appointment.recurrence.*
import pl.detailing.crm.appointment.recurrence.create.*
import pl.detailing.crm.appointment.recurrence.delete.*
import pl.detailing.crm.appointment.recurrence.domain.RecurrenceSeriesId
import pl.detailing.crm.appointment.recurrence.get.*
import pl.detailing.crm.appointment.recurrence.update.*
import pl.detailing.crm.appointment.smsprefs.UpdateAppointmentSmsPreferencesCommand
import pl.detailing.crm.appointment.smsprefs.UpdateAppointmentSmsPreferencesHandler
import pl.detailing.crm.appointment.title.UpdateAppointmentTitleHandler
import pl.detailing.crm.appointment.title.UpdateAppointmentTitleCommand
import pl.detailing.crm.auth.SecurityContextHelper
import pl.detailing.crm.shared.*
import pl.detailing.crm.smscampaigns.bookingconfirmation.SendBookingConfirmationSmsCommand
import pl.detailing.crm.smscampaigns.bookingconfirmation.SendBookingConfirmationSmsHandler
import pl.detailing.crm.studio.infrastructure.StudioRepository
import java.time.LocalDate
import java.util.UUID

@RestController
@RequestMapping("/api/v1/appointments")
class AppointmentController(
    private val createAppointmentHandler: CreateAppointmentHandler,
    private val updateAppointmentHandler: UpdateAppointmentHandler,
    private val cancelAppointmentHandler: CancelAppointmentHandler,
    private val restoreAppointmentHandler: RestoreAppointmentHandler,
    private val deleteAppointmentHandler: DeleteAppointmentHandler,
    private val hardDeleteAppointmentHandler: HardDeleteAppointmentHandler,
    private val listAppointmentsHandler: ListAppointmentsHandler,
    private val getAppointmentHandler: GetAppointmentHandler,
    private val updateAppointmentTitleHandler: UpdateAppointmentTitleHandler,
    private val sendBookingConfirmationSmsHandler: SendBookingConfirmationSmsHandler,
    private val studioRepository: StudioRepository,
    private val updateAppointmentSmsPreferencesHandler: UpdateAppointmentSmsPreferencesHandler,
    private val createRecurringAppointmentHandler: CreateRecurringAppointmentHandler,
    private val updateRecurringAppointmentHandler: UpdateRecurringAppointmentHandler,
    private val deleteRecurringAppointmentHandler: DeleteRecurringAppointmentHandler,
    private val getRecurrenceSeriesHandler: GetRecurrenceSeriesHandler
) {

    @GetMapping
    fun getAppointments(
        @RequestParam(defaultValue = "1") page: Int,
        @RequestParam(defaultValue = "20") limit: Int,
        @RequestParam(required = false) status: String?,
        @RequestParam(required = false) search: String?,
        @RequestParam(required = false) scheduledDate: String?,
        @RequestParam(required = false) customerId: String?
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

        val customerIdFilter = customerId?.let {
            try {
                UUID.fromString(it)
            } catch (e: IllegalArgumentException) {
                null
            }
        }

        val command = ListAppointmentsCommand(
            studioId = principal.studioId,
            page = maxOf(1, page),
            pageSize = maxOf(1, minOf(100, limit)), // Limit page size to 100
            status = appointmentStatus,
            searchTerm = search,
            scheduledDate = scheduledDateFilter,
            customerId = customerIdFilter
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


        // Map request to command
        val command = CreateAppointmentCommand(
            studioId = principal.studioId,
            userId = principal.userId,
            userName = principal.fullName,
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
                    val updateData = request.customer.updateData
                        ?: throw BadRequestException("customer.updateData is required for UPDATE mode")
                    CustomerIdentity.Update(
                        customerId = CustomerId.fromString(request.customer.id
                            ?: throw BadRequestException("customer.id is required for UPDATE mode")),
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
                    serviceId = service.serviceId?.let { ServiceId.fromString(it) },
                    serviceName = service.serviceName,
                    basePriceNet = service.basePriceNet,
                    vatRate = service.vatRate,
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
            appointmentColorId = AppointmentColorId.fromString(request.appointmentColorId),
            note = request.note,
            sendReminderSms = request.sendReminderSms
        )

        val result = createAppointmentHandler.handle(command)

        if (request.sendConfirmationSms) {
            val studio = studioRepository.findByStudioId(principal.studioId.value)
            if (studio != null) {
                sendBookingConfirmationSmsHandler.handle(
                    SendBookingConfirmationSmsCommand(
                        appointmentId = result.appointmentId,
                        studioId = principal.studioId,
                        studioName = studio.name,
                        force = true
                    )
                )
            }
        }

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

    @PostMapping("/recurring")
    fun createRecurringAppointment(
        @RequestBody request: CreateRecurringAppointmentRequest
    ): ResponseEntity<CreateRecurringAppointmentResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()


        val baseRequest = request.toBaseRequest()
        val baseCommand = buildCreateCommand(baseRequest, principal)

        val recurrenceRule = RecurrenceRuleCommand(
            type = request.recurrence.type,
            intervalWeeks = request.recurrence.intervalWeeks,
            daysOfWeek = request.recurrence.daysOfWeek?.toSet(),
            dayOfMonth = request.recurrence.dayOfMonth,
            endType = request.recurrence.endType,
            endDate = request.recurrence.endDate,
            maxOccurrences = request.recurrence.maxOccurrences
        )

        val result = createRecurringAppointmentHandler.handle(
            CreateRecurringAppointmentCommand(base = baseCommand, recurrenceRule = recurrenceRule)
        )

        if (request.sendConfirmationSms) {
            val studio = studioRepository.findByStudioId(principal.studioId.value)
            if (studio != null) {
                sendBookingConfirmationSmsHandler.handle(
                    SendBookingConfirmationSmsCommand(
                        appointmentId = result.firstAppointmentId,
                        studioId = principal.studioId,
                        studioName = studio.name,
                        force = true
                    )
                )
            }
        }

        ResponseEntity.status(HttpStatus.CREATED).body(
            CreateRecurringAppointmentResponse(
                seriesId = result.seriesId.value.toString(),
                occurrenceCount = result.occurrenceCount,
                firstAppointmentId = result.firstAppointmentId.toString(),
                customerId = result.customerId.toString(),
                vehicleId = result.vehicleId?.toString()
            )
        )
    }

    @GetMapping("/series/{seriesId}")
    fun getRecurrenceSeries(@PathVariable seriesId: String): ResponseEntity<RecurrenceSeriesResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        val response = getRecurrenceSeriesHandler.handle(
            GetRecurrenceSeriesQuery(
                seriesId = RecurrenceSeriesId.fromString(seriesId),
                studioId = principal.studioId
            )
        )
        ResponseEntity.ok(response)
    }

    @PutMapping("/{id}")
    fun updateAppointment(
        @PathVariable id: String,
        @RequestBody request: CreateAppointmentRequest,
        @RequestParam(required = false) scope: String?
    ): ResponseEntity<AppointmentCreateResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()


        val command = UpdateAppointmentCommand(
            appointmentId = AppointmentId.fromString(id),
            studioId = principal.studioId,
            userId = principal.userId,
            userName = principal.fullName,
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
                    val updateData = request.customer.updateData
                        ?: throw BadRequestException("customer.updateData is required for UPDATE mode")
                    CustomerIdentity.Update(
                        customerId = CustomerId.fromString(request.customer.id
                            ?: throw BadRequestException("customer.id is required for UPDATE mode")),
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
                    serviceId = service.serviceId?.let { ServiceId.fromString(it) },
                    serviceName = service.serviceName,
                    basePriceNet = service.basePriceNet,
                    vatRate = service.vatRate,
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
            appointmentColorId = AppointmentColorId.fromString(request.appointmentColorId),
            note = request.note
        )

        val result = updateAppointmentHandler.handle(command)

        val editScope = scope?.let {
            try { RecurrenceEditScope.valueOf(it.uppercase()) } catch (e: IllegalArgumentException) { null }
        }

        if (editScope != null && editScope != RecurrenceEditScope.THIS) {
            updateRecurringAppointmentHandler.handle(
                UpdateRecurringAppointmentCommand(
                    appointmentId = AppointmentId.fromString(id),
                    studioId = principal.studioId,
                    userId = principal.userId,
                    userName = principal.fullName,
                    scope = editScope,
                    appointmentTitle = command.appointmentTitle,
                    appointmentColorId = command.appointmentColorId,
                    note = command.note,
                    sendReminderSms = null,
                    copyLineItemsFromAnchor = true
                )
            )
        }

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

    @PatchMapping("/{id}")
    fun updateAppointmentStatus(
        @PathVariable id: String,
        @RequestBody request: UpdateAppointmentStatusRequest
    ): ResponseEntity<Unit> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()


        // Only support CANCELLED status for now
        if (request.status != "CANCELLED") {
            throw BadRequestException("Only CANCELLED status is supported via PATCH")
        }

        val command = CancelAppointmentCommand(
            appointmentId = AppointmentId.fromString(id),
            studioId = principal.studioId,
            userId = principal.userId,
            userName = principal.fullName
        )

        cancelAppointmentHandler.handle(command)

        ResponseEntity.noContent().build()
    }

    @PostMapping("/{id}/restore")
    fun restoreAppointment(@PathVariable id: String): ResponseEntity<Unit> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()


        val command = RestoreAppointmentCommand(
            appointmentId = AppointmentId.fromString(id),
            studioId = principal.studioId,
            userId = principal.userId,
            userName = principal.fullName
        )

        restoreAppointmentHandler.handle(command)

        ResponseEntity.noContent().build()
    }

    @DeleteMapping("/{id}")
    fun deleteAppointment(
        @PathVariable id: String,
        @RequestParam(required = false) scope: String?
    ): ResponseEntity<Unit> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()


        val editScope = scope?.let {
            try { RecurrenceEditScope.valueOf(it.uppercase()) } catch (e: IllegalArgumentException) { null }
        }

        if (editScope != null && editScope != RecurrenceEditScope.THIS) {
            deleteRecurringAppointmentHandler.handle(
                DeleteRecurringAppointmentCommand(
                    appointmentId = AppointmentId.fromString(id),
                    studioId = principal.studioId,
                    userId = principal.userId,
                    userName = principal.fullName,
                    scope = editScope
                )
            )
        } else {
            deleteAppointmentHandler.handle(
                DeleteAppointmentCommand(
                    appointmentId = AppointmentId.fromString(id),
                    studioId = principal.studioId,
                    userId = principal.userId,
                    userName = principal.fullName
                )
            )
        }

        ResponseEntity.noContent().build()
    }

    /**
     * Permanently delete an appointment regardless of its status
     * DELETE /api/v1/appointments/{id}/permanent
     *
     * Deletes the appointment and all associated data:
     * - Scheduled SMS reminders
     * - SMS send logs
     * - Appointment line items (cascade)
     *
     * Blocked for CONVERTED appointments — delete the linked visit first.
     * Only OWNER and MANAGER roles are allowed.
     */
    @DeleteMapping("/{id}/permanent")
    fun hardDeleteAppointment(
        @PathVariable id: String
    ): ResponseEntity<Void> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()


        hardDeleteAppointmentHandler.handle(
            HardDeleteAppointmentCommand(
                appointmentId = AppointmentId.fromString(id),
                studioId = principal.studioId,
                userId = principal.userId,
                userName = principal.fullName
            )
        )

        ResponseEntity.noContent().build()
    }

    @PatchMapping("/{id}/sms-preferences")
    fun updateAppointmentSmsPreferences(
        @PathVariable id: String,
        @RequestBody request: UpdateSmsPreferencesRequest
    ): ResponseEntity<Void> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()


        updateAppointmentSmsPreferencesHandler.handle(
            UpdateAppointmentSmsPreferencesCommand(
                appointmentId = AppointmentId.fromString(id),
                studioId = principal.studioId,
                userId = principal.userId,
                sendReminderSms = request.sendReminderSms
            )
        )

        ResponseEntity.noContent().build<Void>()
    }

    @PatchMapping("/{id}/title")
    fun updateAppointmentTitle(
        @PathVariable id: String,
        @RequestBody request: UpdateAppointmentTitleRequest
    ): ResponseEntity<Void> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()

        val command = UpdateAppointmentTitleCommand(
            appointmentId = AppointmentId.fromString(id),
            studioId = principal.studioId,
            userId = principal.userId,
            userName = principal.fullName,
            title = request.title
        )

        updateAppointmentTitleHandler.handle(command)

        ResponseEntity.noContent().build()
    }

    private fun buildCreateCommand(
        request: CreateAppointmentRequest,
        principal: pl.detailing.crm.auth.UserPrincipal
    ): CreateAppointmentCommand = CreateAppointmentCommand(
        studioId = principal.studioId,
        userId = principal.userId,
        userName = principal.fullName,
        customer = when (request.customer.mode) {
            CustomerMode.EXISTING -> CustomerIdentity.Existing(CustomerId.fromString(request.customer.id!!))
            CustomerMode.NEW -> {
                val d = request.customer.newData!!
                CustomerIdentity.New(d.firstName, d.lastName, d.phone, d.email, d.company?.name, d.company?.nip, d.company?.regon, d.company?.address)
            }
            CustomerMode.UPDATE -> {
                val d = request.customer.updateData ?: throw BadRequestException("customer.updateData is required for UPDATE mode")
                CustomerIdentity.Update(CustomerId.fromString(request.customer.id ?: throw BadRequestException("customer.id is required for UPDATE mode")), d.firstName, d.lastName, d.phone, d.email, d.company?.name, d.company?.nip, d.company?.regon, d.company?.address)
            }
        },
        vehicle = when (request.vehicle.mode) {
            VehicleMode.EXISTING -> VehicleIdentity.Existing(VehicleId.fromString(request.vehicle.id!!))
            VehicleMode.NEW -> { val d = request.vehicle.newData!!; VehicleIdentity.New(d.brand, d.model, d.year, d.licensePlate) }
            VehicleMode.UPDATE -> { val d = request.vehicle.updateData!!; VehicleIdentity.Update(VehicleId.fromString(request.vehicle.id!!), d.brand, d.model, d.year, d.licensePlate) }
            VehicleMode.NONE -> VehicleIdentity.None
        },
        services = request.services.map {
            ServiceLineItemCommand(it.serviceId?.let { sid -> ServiceId.fromString(sid) }, it.serviceName, it.basePriceNet, it.vatRate, it.adjustment.type, it.adjustment.value, it.note)
        },
        schedule = ScheduleCommand(request.schedule.isAllDay, request.schedule.startDateTime, request.schedule.endDateTime),
        appointmentTitle = request.appointmentTitle,
        appointmentColorId = AppointmentColorId.fromString(request.appointmentColorId),
        note = request.note,
        sendReminderSms = request.sendReminderSms
    )
}

data class UpdateAppointmentTitleRequest(
    val title: String?
)

data class UpdateSmsPreferencesRequest(
    val sendReminderSms: Boolean
)

data class UpdateAppointmentStatusRequest(
    val status: String
)

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
