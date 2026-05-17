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
import pl.detailing.crm.leads.summary.GetPipelineSummaryHandler
import pl.detailing.crm.leads.summary.GetPipelineSummaryQuery
import pl.detailing.crm.leads.update.UpdateLeadCommand
import pl.detailing.crm.leads.update.UpdateLeadHandler
import pl.detailing.crm.leads.quotereply.generate.GenerateQuoteReplyHandler
import pl.detailing.crm.leads.quotereply.generate.GenerateQuoteReplyQuery
import pl.detailing.crm.leads.userquote.delete.DeleteUserQuoteCommand
import pl.detailing.crm.leads.userquote.delete.DeleteUserQuoteHandler
import pl.detailing.crm.leads.userquote.save.SaveUserQuoteCommand
import pl.detailing.crm.leads.userquote.save.SaveUserQuoteHandler
import pl.detailing.crm.leads.userquote.save.UserQuoteItemInput
import pl.detailing.crm.shared.AppointmentColorId
import pl.detailing.crm.shared.CustomerId
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
    private val saveUserQuoteHandler: SaveUserQuoteHandler,
    private val deleteUserQuoteHandler: DeleteUserQuoteHandler,
    private val createLeadAppointmentHandler: CreateLeadAppointmentHandler,
    private val generateQuoteReplyHandler: GenerateQuoteReplyHandler
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
        @RequestParam(required = false) dateTo: String?
    ): ResponseEntity<LeadListResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        val zone = ZoneId.of("Europe/Warsaw")

        val query = ListLeadsQuery(
            studioId = principal.studioId,
            search = search,
            statuses = status?.map { LeadStatus.valueOf(it) },
            sources = source?.map { LeadSource.valueOf(it) },
            page = page,
            limit = limit,
            dateFrom = dateFrom?.let { LocalDate.parse(it).atStartOfDay(zone).toInstant() },
            dateTo = dateTo?.let { LocalDate.parse(it).plusDays(1).atStartOfDay(zone).toInstant() }
        )

        val result = listLeadsHandler.handle(query)

        ResponseEntity.ok(
            LeadListResponse(
                leads = result.items.map { it.toDto() },
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
        val result = getLeadHandler.handle(GetLeadQuery(LeadId.fromString(id), principal.studioId))
        ResponseEntity.ok(result.toDetailDto())
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
     * Generate an AI-drafted email reply for a lead's quote.
     * GET /api/v1/leads/{id}/quote-reply
     */
    @GetMapping("/{id}/quote-reply")
    fun generateQuoteReply(@PathVariable id: String): ResponseEntity<GenerateQuoteReplyResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        val result = generateQuoteReplyHandler.handle(
            GenerateQuoteReplyQuery(
                leadId = LeadId.fromString(id),
                studioId = principal.studioId
            )
        )
        ResponseEntity.ok(GenerateQuoteReplyResponse(title = result.title, reply = result.reply))
    }
}
