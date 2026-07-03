package pl.detailing.crm.leads

import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import pl.detailing.crm.auth.SecurityContextHelper
import pl.detailing.crm.appointment.create.CustomerIdentity
import pl.detailing.crm.appointment.create.ScheduleCommand
import pl.detailing.crm.appointment.create.ServiceLineItemCommand
import pl.detailing.crm.appointment.create.VehicleIdentity
import pl.detailing.crm.appointment.lead.CreateLeadAppointmentCommand
import pl.detailing.crm.appointment.lead.CreateLeadAppointmentHandler
import pl.detailing.crm.leads.analytics.GetEmployeeStatsHandler
import pl.detailing.crm.leads.analytics.GetEmployeeStatsQuery
import pl.detailing.crm.leads.analytics.GetServiceAnalyticsHandler
import pl.detailing.crm.leads.analytics.GetServiceAnalyticsQuery
import pl.detailing.crm.leads.analytics.GetTimeAnalyticsHandler
import pl.detailing.crm.leads.analytics.GetTimeAnalyticsQuery
import pl.detailing.crm.leads.analytics.InterpretTimeAnalyticsCommand
import pl.detailing.crm.leads.analytics.InterpretTimeAnalyticsService
import pl.detailing.crm.leads.analytics.TimeBucketResult
import pl.detailing.crm.leads.analytics.TimeAnalyticsBucketType
import pl.detailing.crm.leads.analytics.TimeAnalyticsActionType
import pl.detailing.crm.leads.assign.AssignLeadUserCommand
import pl.detailing.crm.leads.assign.AssignLeadUserHandler
import pl.detailing.crm.leads.link.LinkAppointmentCommand
import pl.detailing.crm.leads.link.LinkAppointmentHandler
import pl.detailing.crm.leads.link.LinkVisitCommand
import pl.detailing.crm.leads.link.LinkVisitHandler
import pl.detailing.crm.leads.comments.AddLeadCommentCommand
import pl.detailing.crm.leads.comments.DeleteLeadCommentCommand
import pl.detailing.crm.leads.comments.LeadCommentHandler
import pl.detailing.crm.leads.comments.UpdateLeadCommentCommand
import pl.detailing.crm.leads.history.GetLeadStatusHistoryHandler
import pl.detailing.crm.leads.history.GetLeadStatusHistoryQuery
import pl.detailing.crm.leads.create.CreateLeadCommand
import pl.detailing.crm.leads.create.CreateLeadHandler
import pl.detailing.crm.leads.customer.AssignLeadCustomerCommand
import pl.detailing.crm.leads.customer.AssignLeadCustomerHandler
import pl.detailing.crm.leads.delete.DeleteLeadCommand
import pl.detailing.crm.leads.delete.DeleteLeadHandler
import pl.detailing.crm.leads.estimation.analyze.AnalyzeLeadHandler
import pl.detailing.crm.leads.get.GetLeadHandler
import pl.detailing.crm.leads.get.GetLeadQuery
import pl.detailing.crm.leads.list.ListLeadsHandler
import pl.detailing.crm.leads.list.ListLeadsQuery
import pl.detailing.crm.leads.lostreason.UpdateLostReasonCommand
import pl.detailing.crm.leads.lostreason.UpdateLostReasonHandler
import pl.detailing.crm.leads.split.SplitLeadCommand
import pl.detailing.crm.leads.split.SplitLeadHandler
import pl.detailing.crm.leads.merge.MergeLeadsCommand
import pl.detailing.crm.leads.merge.MergeLeadsHandler

import pl.detailing.crm.leads.summary.GetPipelineSummaryHandler
import pl.detailing.crm.leads.summary.GetPipelineSummaryQuery
import pl.detailing.crm.leads.update.UpdateLeadCommand
import pl.detailing.crm.leads.update.UpdateLeadHandler
import pl.detailing.crm.leads.quotereply.GenerateQuoteReplyCommand
import pl.detailing.crm.leads.quotereply.GenerateQuoteReplyHandler
import pl.detailing.crm.leads.quotereply.QuoteReplyExampleHandler
import pl.detailing.crm.leads.quotereply.SaveQuoteReplyExampleCommand
import pl.detailing.crm.leads.quotereply.UpdateQuoteReplyExampleCommand
import pl.detailing.crm.leads.userquote.delete.DeleteUserQuoteCommand
import pl.detailing.crm.leads.userquote.delete.DeleteUserQuoteHandler
import pl.detailing.crm.leads.userquote.save.SaveUserQuoteCommand
import pl.detailing.crm.leads.userquote.save.SaveUserQuoteHandler
import pl.detailing.crm.leads.userquote.save.UserQuoteItemInput
import pl.detailing.crm.role.domain.Permission
import pl.detailing.crm.role.permission.PermissionCheckService
import pl.detailing.crm.shared.AppointmentColorId
import pl.detailing.crm.shared.CustomerId
import pl.detailing.crm.shared.ForbiddenException
import pl.detailing.crm.shared.LeadId
import pl.detailing.crm.shared.LeadSource
import pl.detailing.crm.shared.LeadStatus
import pl.detailing.crm.shared.ServiceId
import pl.detailing.crm.shared.VehicleId
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

@RestController
@RequestMapping("/api/v1/leads")
class LeadsController(
    private val createLeadHandler: CreateLeadHandler,
    private val updateLeadHandler: UpdateLeadHandler,
    private val deleteLeadHandler: DeleteLeadHandler,
    private val listLeadsHandler: ListLeadsHandler,
    private val getPipelineSummaryHandler: GetPipelineSummaryHandler,
    private val getLeadHandler: GetLeadHandler,
    private val analyzeLeadHandler: AnalyzeLeadHandler,
    private val assignLeadCustomerHandler: AssignLeadCustomerHandler,
    private val assignLeadUserHandler: AssignLeadUserHandler,
    private val updateLostReasonHandler: UpdateLostReasonHandler,
    private val linkAppointmentHandler: LinkAppointmentHandler,
    private val linkVisitHandler: LinkVisitHandler,

    private val getServiceAnalyticsHandler: GetServiceAnalyticsHandler,
    private val getEmployeeStatsHandler: GetEmployeeStatsHandler,
    private val getTimeAnalyticsHandler: GetTimeAnalyticsHandler,
    private val interpretTimeAnalyticsService: InterpretTimeAnalyticsService,
    private val leadCommentHandler: LeadCommentHandler,
    private val splitLeadHandler: SplitLeadHandler,
    private val mergeLeadsHandler: MergeLeadsHandler,
    private val getLeadStatusHistoryHandler: GetLeadStatusHistoryHandler,
    private val saveUserQuoteHandler: SaveUserQuoteHandler,
    private val deleteUserQuoteHandler: DeleteUserQuoteHandler,
    private val createLeadAppointmentHandler: CreateLeadAppointmentHandler,
    private val generateQuoteReplyHandler: GenerateQuoteReplyHandler,
    private val quoteReplyExampleHandler: QuoteReplyExampleHandler,
    private val permissionCheckService: PermissionCheckService,
    private val acknowledgeLeadActivityHandler: pl.detailing.crm.leads.acknowledge.AcknowledgeLeadActivityHandler
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Get paginated list of leads with optional filters
     * GET /api/v1/leads
     */
    @GetMapping
    fun getLeads(
        @RequestParam(required = false) search: String?,
        @RequestParam(required = false) status: List<String>?,
        @RequestParam(required = false) source: List<String>?,
        @RequestParam(defaultValue = "1") page: Int,
        @RequestParam(defaultValue = "20") limit: Int,
        @RequestParam(required = false) sortBy: String?,
        @RequestParam(required = false) sortDirection: String?,
        @RequestParam(required = false) dateFrom: String?,
        @RequestParam(required = false) dateTo: String?,
        @RequestParam(required = false) valueMin: Long?,
        @RequestParam(required = false) valueMax: Long?,
        @RequestParam(required = false) assignedUserId: String?,
    ): ResponseEntity<LeadListResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        val mask = !permissionCheckService.hasPermission(principal.userId, principal.studioId, Permission.CUSTOMERS_VIEW_PERSONAL_DATA)
        val zone = ZoneId.of("Europe/Warsaw")

        val query = ListLeadsQuery(
            studioId = principal.studioId,
            search = search,
            statuses = status?.map { LeadStatus.valueOf(it) },
            sources = source?.map { LeadSource.valueOf(it) },
            page = page,
            limit = limit,
            dateFrom = dateFrom?.let { LocalDate.parse(it).atStartOfDay(zone).toInstant() },
            dateTo = dateTo?.let { LocalDate.parse(it).plusDays(1).atStartOfDay(zone).toInstant() },
            valueMin = valueMin,
            valueMax = valueMax,
            assignedUserId = assignedUserId?.let { UUID.fromString(it) },

        )

        val result = listLeadsHandler.handle(query)

        ResponseEntity.ok(
            LeadListResponse(
                leads = result.items.map { dto ->
                    dto.toDto().let { if (mask) it.copy(assignedCustomer = it.assignedCustomer?.maskPii()) else it }
                },
                pagination = PaginationInfo(
                    currentPage = result.currentPage,
                    totalPages = result.totalPages,
                    totalItems = result.totalItems.toInt(),
                    itemsPerPage = result.itemsPerPage
                )
            )
        )
    }

    /**
     * Get a single lead by ID — includes AI estimation breakdown and user quote if available
     * GET /api/v1/leads/{id}
     */
    @GetMapping("/{id}")
    fun getLead(@PathVariable id: String): ResponseEntity<LeadDetailDto> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        val mask = !permissionCheckService.hasPermission(principal.userId, principal.studioId, Permission.CUSTOMERS_VIEW_PERSONAL_DATA)
        val result = getLeadHandler.handle(GetLeadQuery(LeadId.fromString(id), principal.studioId))
        val dto = result.toDetailDto()
        ResponseEntity.ok(if (mask) dto.copy(assignedCustomer = dto.assignedCustomer?.maskPii()) else dto)
    }

    /**
     * Create a new lead (manual entry)
     * POST /api/v1/leads
     */
    @PostMapping
    fun createLead(@RequestBody request: CreateLeadRequest): ResponseEntity<LeadDto> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()

        val command = CreateLeadCommand(
            studioId = principal.studioId,
            userId = principal.userId,
            source = request.source,
            contactIdentifier = request.contactIdentifier,
            customerName = request.customerName,
            initialMessage = request.initialMessage,
            estimatedValue = request.estimatedValue,
            userName = principal.fullName
        )

        val result = createLeadHandler.handle(command)

        ResponseEntity.status(HttpStatus.CREATED).body(
            LeadDto(
                id = result.leadId.toString(),
                source = result.source.name,
                status = result.status.name,
                contactIdentifier = result.contactIdentifier,
                customerName = result.customerName,
                initialMessage = result.initialMessage,
                createdAt = result.createdAt,
                updatedAt = result.updatedAt,
                estimatedValue = result.estimatedValue,
                estimationStatus = null,
                requiresVerification = result.requiresVerification,
                vehicleBrand = null,
                vehicleModel = null,
                relatedVisits = emptyList(),
                summary = null,
                assignedCustomer = null,
                appointmentId = null,
                visitId = null
            )
        )
    }

    /**
     * Update an existing lead
     * PATCH /api/v1/leads/{id}
     */
    @PatchMapping("/{id}")
    fun updateLead(
        @PathVariable id: String,
        @RequestBody request: UpdateLeadRequest
    ): ResponseEntity<LeadDto> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()

        val command = UpdateLeadCommand(
            leadId = LeadId.fromString(id),
            studioId = principal.studioId,
            userId = principal.userId,
            status = request.status?.let { LeadStatus.valueOf(it) },
            customerName = request.customerName,
            initialMessage = request.initialMessage,
            estimatedValue = request.estimatedValue,
            userName = principal.fullName
        )

        val result = updateLeadHandler.handle(command)

        ResponseEntity.ok(
            LeadDto(
                id = result.leadId.toString(),
                source = null,
                status = result.status.name,
                contactIdentifier = null,
                customerName = result.customerName,
                initialMessage = result.initialMessage,
                createdAt = null,
                updatedAt = result.updatedAt,
                estimatedValue = result.estimatedValue,
                estimationStatus = null,
                requiresVerification = result.requiresVerification,
                vehicleBrand = null,
                vehicleModel = null,
                relatedVisits = emptyList(),
                summary = null,
                assignedCustomer = null,
                appointmentId = null,
                visitId = null
            )
        )
    }

    /**
     * Delete a lead
     * DELETE /api/v1/leads/{id}
     */
    @DeleteMapping("/{id}")
    fun deleteLead(@PathVariable id: String): ResponseEntity<Void> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()

        if (!principal.isOwner) {
            throw ForbiddenException("Tylko właściciel może usuwać leady.")
        }

        val command = DeleteLeadCommand(
            leadId = LeadId.fromString(id),
            studioId = principal.studioId
        )

        deleteLeadHandler.handle(command)

        ResponseEntity.noContent().build()
    }

    /**
     * Get pipeline summary for dashboard widget
     * GET /api/v1/leads/pipeline-summary
     */
    @GetMapping("/pipeline-summary")
    fun getPipelineSummary(
        @RequestParam(required = false) source: List<String>?,
        @RequestParam(required = false) dateFrom: String?,
        @RequestParam(required = false) dateTo: String?
    ): ResponseEntity<PipelineSummaryDto> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        val zone = ZoneId.of("Europe/Warsaw")

        val query = GetPipelineSummaryQuery(
            studioId = principal.studioId,
            sourceFilter = source?.map { LeadSource.valueOf(it) },
            dateFrom = dateFrom?.let { LocalDate.parse(it).atStartOfDay(zone).toInstant() },
            dateTo = dateTo?.let { LocalDate.parse(it).plusDays(1).atStartOfDay(zone).toInstant() }
        )

        val result = getPipelineSummaryHandler.handle(query)

        ResponseEntity.ok(
            PipelineSummaryDto(
                awaitingFirstContactCount = result.awaitingFirstContactCount,
                avgWaitingTimeMinutes = result.avgWaitingTimeMinutes,
                conversionRateThisMonth = result.conversionRateThisMonth,
                conversionRateTrendPp = result.conversionRateTrendPp,
                convertedValueThisMonth = result.convertedValueThisMonth,
                convertedCountThisMonth = result.convertedCountThisMonth,
                atRiskValue = result.atRiskValue,
                atRiskCount = result.atRiskCount,
                newLeadsCount = result.newLeadsCount
            )
        )
    }

    /**
     * Quick status update for a lead
     * PATCH /api/v1/leads/{id}/status
     */
    @PatchMapping("/{id}/status")
    fun updateLeadStatus(
        @PathVariable id: String,
        @RequestBody request: UpdateStatusRequest
    ): ResponseEntity<LeadDto> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()

        val command = UpdateLeadCommand(
            leadId = LeadId.fromString(id),
            studioId = principal.studioId,
            userId = principal.userId,
            status = LeadStatus.valueOf(request.status),
            customerName = null,
            initialMessage = null,
            estimatedValue = null,
            userName = principal.fullName
        )

        val result = updateLeadHandler.handle(command)

        ResponseEntity.ok(
            LeadDto(
                id = result.leadId.toString(),
                source = null,
                status = result.status.name,
                contactIdentifier = null,
                customerName = result.customerName,
                initialMessage = result.initialMessage,
                createdAt = null,
                updatedAt = result.updatedAt,
                estimatedValue = result.estimatedValue,
                estimationStatus = null,
                requiresVerification = result.requiresVerification,
                vehicleBrand = null,
                vehicleModel = null,
                relatedVisits = emptyList(),
                summary = null,
                assignedCustomer = null,
                appointmentId = null,
                visitId = null
            )
        )
    }

    /**
     * Quick estimated value update for a lead
     * PATCH /api/v1/leads/{id}/value
     */
    @PatchMapping("/{id}/value")
    fun updateLeadValue(
        @PathVariable id: String,
        @RequestBody request: UpdateValueRequest
    ): ResponseEntity<LeadDto> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()

        val command = UpdateLeadCommand(
            leadId = LeadId.fromString(id),
            studioId = principal.studioId,
            userId = principal.userId,
            status = null,
            customerName = null,
            initialMessage = null,
            estimatedValue = request.estimatedValue,
            userName = principal.fullName
        )

        val result = updateLeadHandler.handle(command)

        ResponseEntity.ok(
            LeadDto(
                id = result.leadId.toString(),
                source = null,
                status = result.status.name,
                contactIdentifier = null,
                customerName = result.customerName,
                initialMessage = result.initialMessage,
                createdAt = null,
                updatedAt = result.updatedAt,
                estimatedValue = result.estimatedValue,
                estimationStatus = null,
                requiresVerification = result.requiresVerification,
                vehicleBrand = null,
                vehicleModel = null,
                relatedVisits = emptyList(),
                summary = null,
                assignedCustomer = null,
                appointmentId = null,
                visitId = null
            )
        )
    }

    /**
     * Assign, change, or unassign a customer to/from a lead.
     * Pass customerId to assign/change; pass null or omit to unassign.
     * PATCH /api/v1/leads/{id}/customer
     */
    @PatchMapping("/{id}/customer")
    fun assignCustomer(
        @PathVariable id: String,
        @RequestBody request: AssignCustomerRequest
    ): ResponseEntity<CustomerSnapshotDto?> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()

        val command = AssignLeadCustomerCommand(
            leadId = LeadId.fromString(id),
            studioId = principal.studioId,
            userId = principal.userId,
            userName = principal.fullName,
            customerId = request.customerId?.let { CustomerId.fromString(it) }
        )

        val result = assignLeadCustomerHandler.handle(command)

        if (result.customerSnapshot != null) {
            ResponseEntity.ok(result.customerSnapshot.toDto())
        } else {
            ResponseEntity.ok(null)
        }
    }

    /**
     * Create or replace the user-defined quote for a lead.
     * Replaces all existing items with the new list.
     * PUT /api/v1/leads/{id}/user-quote
     */
    @PutMapping("/{id}/user-quote")
    fun saveUserQuote(
        @PathVariable id: String,
        @RequestBody request: SaveUserQuoteRequest
    ): ResponseEntity<LeadUserQuoteDto> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()

        val command = SaveUserQuoteCommand(
            leadId = LeadId.fromString(id),
            studioId = principal.studioId,
            userId = principal.userId,
            userName = principal.fullName ?: "",
            items = request.items.map {
                UserQuoteItemInput(
                    serviceId = it.serviceId?.let { id -> UUID.fromString(id) },
                    serviceName = it.serviceName,
                    priceNet = it.priceNet,
                    vatRate = it.vatRate,
                    priceGross = it.priceGross
                )
            }
        )

        val result = saveUserQuoteHandler.handle(command)
        ResponseEntity.ok(result.toDto())
    }

    /**
     * Delete the user-defined quote for a lead.
     * DELETE /api/v1/leads/{id}/user-quote
     */
    @DeleteMapping("/{id}/user-quote")
    fun deleteUserQuote(@PathVariable id: String): ResponseEntity<Void> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()

        val command = DeleteUserQuoteCommand(
            leadId = LeadId.fromString(id),
            studioId = principal.studioId
        )

        deleteUserQuoteHandler.handle(command)
        ResponseEntity.noContent().build()
    }

    /**
     * Create an appointment directly from a lead.
     * Sets lead status to CONFIRMED and links the appointment.
     * POST /api/v1/leads/{id}/appointment
     */
    @PostMapping("/{id}/appointment")
    fun createAppointmentFromLead(
        @PathVariable id: String,
        @RequestBody request: CreateLeadAppointmentRequest
    ): ResponseEntity<CreateLeadAppointmentResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()

        val customer: CustomerIdentity = when (request.customer.mode) {
            LeadAppointmentCustomerMode.EXISTING -> CustomerIdentity.Existing(
                customerId = pl.detailing.crm.shared.CustomerId.fromString(
                    request.customer.id ?: throw IllegalArgumentException("customer.id required for EXISTING mode")
                )
            )
            LeadAppointmentCustomerMode.NEW -> {
                val d = request.customer.newData ?: throw IllegalArgumentException("customer.newData required for NEW mode")
                CustomerIdentity.New(
                    firstName = d.firstName,
                    lastName = d.lastName,
                    phone = d.phone,
                    email = d.email,
                    companyName = d.company?.name,
                    companyNip = d.company?.nip,
                    companyRegon = d.company?.regon,
                    companyAddress = d.company?.address
                )
            }
            LeadAppointmentCustomerMode.UPDATE -> {
                val d = request.customer.updateData ?: throw IllegalArgumentException("customer.updateData required for UPDATE mode")
                CustomerIdentity.Update(
                    customerId = pl.detailing.crm.shared.CustomerId.fromString(
                        request.customer.id ?: throw IllegalArgumentException("customer.id required for UPDATE mode")
                    ),
                    firstName = d.firstName,
                    lastName = d.lastName,
                    phone = d.phone,
                    email = d.email,
                    companyName = d.company?.name,
                    companyNip = d.company?.nip,
                    companyRegon = d.company?.regon,
                    companyAddress = d.company?.address
                )
            }
        }

        val vehicle: VehicleIdentity = when (request.vehicle.mode) {
            LeadAppointmentVehicleMode.EXISTING -> VehicleIdentity.Existing(
                vehicleId = VehicleId.fromString(
                    request.vehicle.id ?: throw IllegalArgumentException("vehicle.id required for EXISTING mode")
                )
            )
            LeadAppointmentVehicleMode.NEW -> {
                val d = request.vehicle.newData ?: throw IllegalArgumentException("vehicle.newData required for NEW mode")
                VehicleIdentity.New(brand = d.brand, model = d.model, year = d.year, licensePlate = d.licensePlate)
            }
            LeadAppointmentVehicleMode.UPDATE -> {
                val d = request.vehicle.updateData ?: throw IllegalArgumentException("vehicle.updateData required for UPDATE mode")
                VehicleIdentity.Update(
                    vehicleId = VehicleId.fromString(
                        request.vehicle.id ?: throw IllegalArgumentException("vehicle.id required for UPDATE mode")
                    ),
                    brand = d.brand, model = d.model, year = d.year, licensePlate = d.licensePlate
                )
            }
            LeadAppointmentVehicleMode.NONE -> VehicleIdentity.None
        }

        val command = CreateLeadAppointmentCommand(
            leadId = LeadId.fromString(id),
            studioId = principal.studioId,
            userId = principal.userId,
            userName = principal.fullName,
            customer = customer,
            vehicle = vehicle,
            services = request.services.map { s ->
                ServiceLineItemCommand(
                    serviceId = s.serviceId?.let { ServiceId.fromString(it) },
                    serviceName = s.serviceName,
                    basePriceNet = s.basePriceNet,
                    vatRate = s.vatRate,
                    adjustmentType = s.adjustment.type,
                    adjustmentValue = s.adjustment.value,
                    customNote = s.note
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

        val result = createLeadAppointmentHandler.handle(command)

        ResponseEntity.status(HttpStatus.CREATED).body(
            CreateLeadAppointmentResponse(
                appointmentId = result.appointmentId.toString(),
                customerId = result.customerId.toString(),
                vehicleId = result.vehicleId?.toString(),
                leadStatus = LeadStatus.CONFIRMED.name,
                totalNet = result.totalNet.amountInCents,
                totalGross = result.totalGross.amountInCents,
                totalVat = result.totalVat.amountInCents
            )
        )
    }

    /**
     * Generate a professional quote-reply email for a lead.
     * Uses the user quote if present, otherwise falls back to the AI estimation.
     * POST /api/v1/leads/{id}/quote-reply
     */
    @PostMapping("/{id}/quote-reply")
    fun generateQuoteReply(@PathVariable id: String): ResponseEntity<GenerateQuoteReplyResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()

        val command = GenerateQuoteReplyCommand(
            leadId = LeadId.fromString(id),
            studioId = principal.studioId,
            userName = principal.fullName
        )

        val result = generateQuoteReplyHandler.handle(command)
        ResponseEntity.ok(GenerateQuoteReplyResponse(title = result.title, reply = result.reply))
    }

    /**
     * Assign or unassign a user (employee) to a lead.
     * Pass userId to assign; pass null to unassign.
     * PATCH /api/v1/leads/{id}/assign
     */
    @PatchMapping("/{id}/assign")
    fun assignUser(
        @PathVariable id: String,
        @RequestBody request: AssignLeadUserRequest
    ): ResponseEntity<Void> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()

        val command = AssignLeadUserCommand(
            leadId = LeadId.fromString(id),
            studioId = principal.studioId,
            requestingUserId = principal.userId,
            requestingUserName = principal.fullName,
            assignedUserId = request.userId?.let { UUID.fromString(it) },
            assignedUserName = request.userName
        )

        assignLeadUserHandler.handle(command)
        ResponseEntity.noContent().build()
    }

    /**
     * Update or clear the lost reason for a lead.
     * PATCH /api/v1/leads/{id}/lost-reason
     */
    @PatchMapping("/{id}/lost-reason")
    fun updateLostReason(
        @PathVariable id: String,
        @RequestBody request: UpdateLostReasonRequest
    ): ResponseEntity<Void> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()

        val command = UpdateLostReasonCommand(
            leadId = LeadId.fromString(id),
            studioId = principal.studioId,
            requestingUserId = principal.userId,
            requestingUserName = principal.fullName,
            lostReason = request.lostReason
        )

        updateLostReasonHandler.handle(command)
        ResponseEntity.noContent().build()
    }

    /**
     * PATCH /api/v1/leads/{id}/link-appointment
     */
    @PatchMapping("/{id}/link-appointment")
    fun linkAppointment(
        @PathVariable id: String,
        @RequestBody request: LinkAppointmentRequest
    ): ResponseEntity<Void> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        linkAppointmentHandler.handle(
            LinkAppointmentCommand(
                leadId = LeadId.fromString(id),
                studioId = principal.studioId,
                appointmentId = request.appointmentId?.let { UUID.fromString(it) }
            )
        )
        ResponseEntity.noContent().build()
    }

    /**
     * PATCH /api/v1/leads/{id}/link-visit
     */
    @PatchMapping("/{id}/link-visit")
    fun linkVisit(
        @PathVariable id: String,
        @RequestBody request: LinkVisitRequest
    ): ResponseEntity<Void> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        linkVisitHandler.handle(
            LinkVisitCommand(
                leadId = LeadId.fromString(id),
                studioId = principal.studioId,
                visitId = request.visitId?.let { UUID.fromString(it) }
            )
        )
        ResponseEntity.noContent().build()
    }

    /**
     * Get win/loss analytics per service.
     * GET /api/v1/leads/service-analytics
     */
    @GetMapping("/service-analytics")
    fun getServiceAnalytics(
        @RequestParam(required = false) source: List<String>?,
        @RequestParam(required = false) dateFrom: String?,
        @RequestParam(required = false) dateTo: String?
    ): ResponseEntity<List<ServiceAnalyticsItemDto>> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        val zone = ZoneId.of("Europe/Warsaw")

        val query = GetServiceAnalyticsQuery(
            studioId = principal.studioId,
            sources = source?.map { LeadSource.valueOf(it) },
            dateFrom = dateFrom?.let { LocalDate.parse(it).atStartOfDay(zone).toInstant() },
            dateTo = dateTo?.let { LocalDate.parse(it).plusDays(1).atStartOfDay(zone).toInstant() }
        )

        val result = getServiceAnalyticsHandler.handle(query)
        ResponseEntity.ok(result.map {
            ServiceAnalyticsItemDto(
                serviceId = it.serviceId,
                serviceName = it.serviceName,
                wonCount = it.wonCount,
                lostCount = it.lostCount,
                totalCount = it.totalCount,
                winRate = it.winRate
            )
        })
    }

    /**
     * Get lead handling statistics per employee.
     * GET /api/v1/leads/employee-stats
     */
    @GetMapping("/employee-stats")
    fun getEmployeeStats(
        @RequestParam(required = false) dateFrom: String?,
        @RequestParam(required = false) dateTo: String?
    ): ResponseEntity<List<EmployeeStatsItemDto>> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        val zone = ZoneId.of("Europe/Warsaw")

        val query = GetEmployeeStatsQuery(
            studioId = principal.studioId,
            dateFrom = dateFrom?.let { LocalDate.parse(it).atStartOfDay(zone).toInstant() },
            dateTo = dateTo?.let { LocalDate.parse(it).plusDays(1).atStartOfDay(zone).toInstant() }
        )

        val result = getEmployeeStatsHandler.handle(query)
        ResponseEntity.ok(result.map {
            EmployeeStatsItemDto(
                userId = it.userId,
                userName = it.userName,
                totalLeads = it.totalLeads,
                converted = it.converted,
                lost = it.lost,
                conversionRate = it.conversionRate,
                avgLeadValueCents = it.avgLeadValueCents
            )
        })
    }

    /**
     * GET /api/v1/leads/time-analytics
     */
    @GetMapping("/time-analytics")
    fun getTimeAnalytics(
        @RequestParam(required = false) timezone: String?,
        @RequestParam(required = false) valueMin: Long?,
        @RequestParam(required = false) valueMax: Long?,
        @RequestParam(required = false) dateFrom: String?,
        @RequestParam(required = false) dateTo: String?
    ): ResponseEntity<TimeAnalyticsDto> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        val result = getTimeAnalyticsHandler.handle(
            GetTimeAnalyticsQuery(
                studioId = principal.studioId,
                timezone = timezone ?: "UTC",
                valueMin = valueMin,
                valueMax = valueMax,
                dateFrom = dateFrom,
                dateTo = dateTo
            )
        )
        ResponseEntity.ok(
            TimeAnalyticsDto(
                byHour = result.byHour.map { TimeBucketDto(it.bucket, it.incomingCount, it.acceptedCount, it.rejectedCount) },
                byDayOfMonth = result.byDayOfMonth.map { TimeBucketDto(it.bucket, it.incomingCount, it.acceptedCount, it.rejectedCount) }
            )
        )
    }

    /**
     * POST /api/v1/leads/time-analytics/interpret
     */
    @PostMapping("/time-analytics/interpret")
    fun interpretTimeAnalytics(
        @RequestBody request: InterpretTimeAnalyticsRequest
    ): ResponseEntity<TimeAnalyticsInterpretationDto> = runBlocking {
        val bucketType = when (request.bucketType.uppercase()) {
            "BY_HOUR" -> TimeAnalyticsBucketType.BY_HOUR
            "BY_DAY_OF_MONTH" -> TimeAnalyticsBucketType.BY_DAY_OF_MONTH
            else -> throw IllegalArgumentException("bucketType must be BY_HOUR or BY_DAY_OF_MONTH")
        }
        val actionTypes = request.actionTypes.map {
            when (it.uppercase()) {
                "INCOMING" -> TimeAnalyticsActionType.INCOMING
                "ACCEPTED" -> TimeAnalyticsActionType.ACCEPTED
                "REJECTED" -> TimeAnalyticsActionType.REJECTED
                else -> throw IllegalArgumentException("actionType '$it' unknown")
            }
        }.toSet()
        require(actionTypes.isNotEmpty()) { "actionTypes must not be empty" }

        val result = interpretTimeAnalyticsService.interpret(
            InterpretTimeAnalyticsCommand(
                bucketType = bucketType,
                actionTypes = actionTypes,
                buckets = request.buckets.map {
                    TimeBucketResult(it.bucket, it.incomingCount, it.acceptedCount, it.rejectedCount)
                }
            )
        )
        ResponseEntity.ok(
            TimeAnalyticsInterpretationDto(
                summary = result.summary,
                insights = result.insights.map { TimeAnalysisInsightDto(it.bucketLabel, it.observation, it.causalExplanation) },
                recommendations = TimeAnalyticsRecommendationsDto(
                    bestTimeToCall = result.recommendations.bestTimeToCall,
                    bestTimeToRemind = result.recommendations.bestTimeToRemind,
                    adCampaignTiming = result.recommendations.adCampaignTiming,
                    socialMediaTiming = result.recommendations.socialMediaTiming
                )
            )
        )
    }

    /**
     * List all comments on a lead (sorted oldest first).
     * GET /api/v1/leads/{id}/comments
     */
    @GetMapping("/{id}/comments")
    fun listComments(@PathVariable id: String): ResponseEntity<List<LeadCommentDto>> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        val comments = leadCommentHandler.listComments(LeadId.fromString(id), principal.studioId)
        ResponseEntity.ok(comments.map { it.toDto() })
    }

    /**
     * Add a comment to a lead.
     * POST /api/v1/leads/{id}/comments
     */
    @PostMapping("/{id}/comments")
    fun addComment(
        @PathVariable id: String,
        @RequestBody request: AddLeadCommentRequest
    ): ResponseEntity<LeadCommentDto> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        val saved = leadCommentHandler.addComment(
            AddLeadCommentCommand(
                leadId = LeadId.fromString(id),
                studioId = principal.studioId,
                userId = principal.userId,
                userName = principal.fullName,
                content = request.content
            )
        )
        ResponseEntity.status(HttpStatus.CREATED).body(saved.toDto())
    }

    /**
     * Update a comment on a lead.
     * PATCH /api/v1/leads/{id}/comments/{commentId}
     */
    @PatchMapping("/{id}/comments/{commentId}")
    fun updateComment(
        @PathVariable id: String,
        @PathVariable commentId: String,
        @RequestBody request: UpdateLeadCommentRequest
    ): ResponseEntity<LeadCommentDto> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        val saved = leadCommentHandler.updateComment(
            UpdateLeadCommentCommand(
                commentId = UUID.fromString(commentId),
                leadId = LeadId.fromString(id),
                studioId = principal.studioId,
                userId = principal.userId,
                userName = principal.fullName,
                content = request.content
            )
        )
        ResponseEntity.ok(saved.toDto())
    }

    /**
     * Soft-delete a comment on a lead.
     * DELETE /api/v1/leads/{id}/comments/{commentId}
     */
    @DeleteMapping("/{id}/comments/{commentId}")
    fun deleteComment(
        @PathVariable id: String,
        @PathVariable commentId: String
    ): ResponseEntity<Void> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        leadCommentHandler.deleteComment(
            DeleteLeadCommentCommand(
                commentId = UUID.fromString(commentId),
                leadId = LeadId.fromString(id),
                studioId = principal.studioId,
                userId = principal.userId,
                userName = principal.fullName
            )
        )
        ResponseEntity.noContent().build()
    }

    /**
     * Split a comment out of a lead into its own, independent lead.
     * POST /api/v1/leads/{id}/comments/{commentId}/split
     */
    @PostMapping("/{id}/comments/{commentId}/split")
    fun splitComment(
        @PathVariable id: String,
        @PathVariable commentId: String
    ): ResponseEntity<SplitLeadResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        val result = splitLeadHandler.handle(
            SplitLeadCommand(
                sourceLeadId = LeadId.fromString(id),
                commentId = UUID.fromString(commentId),
                studioId = principal.studioId,
                userId = principal.userId,
                userName = principal.fullName
            )
        )
        ResponseEntity.status(HttpStatus.CREATED).body(
            SplitLeadResponse(
                newLeadId = result.newLeadId.value.toString(),
                sourceLeadId = result.sourceLeadId.value.toString()
            )
        )
    }

    /**
     * Merge this lead (source) into another lead (target) of the same client.
     * POST /api/v1/leads/{id}/merge
     */
    @PostMapping("/{id}/merge")
    fun mergeLead(
        @PathVariable id: String,
        @RequestBody request: MergeLeadsRequest
    ): ResponseEntity<MergeLeadsResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        val result = mergeLeadsHandler.handle(
            MergeLeadsCommand(
                sourceLeadId = LeadId.fromString(id),
                targetLeadId = LeadId.fromString(request.targetLeadId),
                studioId = principal.studioId,
                userId = principal.userId,
                userName = principal.fullName
            )
        )
        ResponseEntity.ok(
            MergeLeadsResponse(targetLeadId = result.targetLeadId.value.toString())
        )
    }

    /**
     * Get status change history for a lead (chronological).
     * GET /api/v1/leads/{id}/status-history
     */
    @GetMapping("/{id}/status-history")
    fun getStatusHistory(@PathVariable id: String): ResponseEntity<List<LeadStatusHistoryEntryDto>> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        val result = getLeadStatusHistoryHandler.handle(
            GetLeadStatusHistoryQuery(
                leadId = LeadId.fromString(id),
                studioId = principal.studioId
            )
        )
        ResponseEntity.ok(result.map {
            LeadStatusHistoryEntryDto(
                changedAt = it.changedAt,
                action = it.action,
                changedByUserId = it.changedByUserId,
                changedByName = it.changedByName,
                changes = it.changes.map { c -> HistoryFieldChangeDto(c.field, c.oldValue, c.newValue) }
            )
        })
    }

    /**
     * GET /api/v1/leads/quote-reply-examples
     */
    @GetMapping("/quote-reply-examples")
    fun listQuoteReplyExamples(): ResponseEntity<List<QuoteReplyExampleDto>> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        ResponseEntity.ok(quoteReplyExampleHandler.list(principal.studioId).map { it.toDto() })
    }

    /**
     * POST /api/v1/leads/quote-reply-examples
     */
    @PostMapping("/quote-reply-examples")
    fun saveQuoteReplyExample(@RequestBody request: SaveQuoteReplyExampleRequest): ResponseEntity<QuoteReplyExampleDto> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        val result = quoteReplyExampleHandler.save(
            SaveQuoteReplyExampleCommand(studioId = principal.studioId, userId = principal.userId, userName = principal.fullName, title = request.title, content = request.content)
        )
        ResponseEntity.status(HttpStatus.CREATED).body(result.toDto())
    }

    /**
     * PATCH /api/v1/leads/quote-reply-examples/{id}
     */
    @PatchMapping("/quote-reply-examples/{id}")
    fun updateQuoteReplyExample(
        @PathVariable id: String,
        @RequestBody request: SaveQuoteReplyExampleRequest
    ): ResponseEntity<QuoteReplyExampleDto> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        val result = quoteReplyExampleHandler.update(
            UpdateQuoteReplyExampleCommand(
                id = UUID.fromString(id),
                studioId = principal.studioId,
                userId = principal.userId,
                userName = principal.fullName,
                title = request.title,
                content = request.content
            )
        )
        ResponseEntity.ok(result.toDto())
    }

    /**
     * DELETE /api/v1/leads/quote-reply-examples/{id}
     */
    @DeleteMapping("/quote-reply-examples/{id}")
    fun deleteQuoteReplyExample(@PathVariable id: String): ResponseEntity<Void> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        quoteReplyExampleHandler.delete(UUID.fromString(id), principal.studioId)
        ResponseEntity.noContent().build()
    }

    /**
     * Clears the newActivityAt flag — marks the new-activity alert as seen.
     * POST /api/v1/leads/{leadId}/acknowledge
     */
    @PostMapping("/{leadId}/acknowledge")
    fun acknowledgeActivity(@PathVariable leadId: String): ResponseEntity<Void> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        acknowledgeLeadActivityHandler.handle(LeadId.fromString(leadId), principal.studioId, principal)
        ResponseEntity.noContent().build()
    }
}

private fun pl.detailing.crm.leads.quotereply.QuoteReplyExampleDto.toDto() = pl.detailing.crm.leads.QuoteReplyExampleDto(
    id = id,
    title = title,
    content = content,
    createdBy = createdBy,
    createdByName = createdByName,
    updatedBy = updatedBy,
    updatedByName = updatedByName,
    createdAt = createdAt,
    updatedAt = updatedAt
)

private fun pl.detailing.crm.leads.comments.LeadCommentEntity.toDto() = LeadCommentDto(
    id = id.toString(),
    content = content,
    createdBy = createdBy.toString(),
    createdByName = createdByName,
    createdAt = createdAt,
    updatedBy = updatedBy?.toString(),
    updatedByName = updatedByName,
    updatedAt = updatedAt
)
