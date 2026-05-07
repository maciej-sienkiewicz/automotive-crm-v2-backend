package pl.detailing.crm.leads

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import pl.detailing.crm.auth.SecurityContextHelper
import pl.detailing.crm.leads.create.CreateLeadCommand
import pl.detailing.crm.leads.create.CreateLeadHandler
import pl.detailing.crm.leads.delete.DeleteLeadCommand
import pl.detailing.crm.leads.delete.DeleteLeadHandler
import pl.detailing.crm.leads.estimation.analyze.AnalyzeLeadCommand
import pl.detailing.crm.leads.estimation.analyze.AnalyzeLeadHandler
import pl.detailing.crm.leads.get.GetLeadHandler
import pl.detailing.crm.leads.get.GetLeadQuery
import pl.detailing.crm.leads.list.ListLeadsHandler
import pl.detailing.crm.leads.list.ListLeadsQuery
import pl.detailing.crm.leads.summary.GetPipelineSummaryHandler
import pl.detailing.crm.leads.summary.GetPipelineSummaryQuery
import pl.detailing.crm.leads.update.UpdateLeadCommand
import pl.detailing.crm.leads.update.UpdateLeadHandler
import pl.detailing.crm.shared.LeadId
import pl.detailing.crm.shared.LeadSource
import pl.detailing.crm.shared.LeadStatus

@RestController
@RequestMapping("/api/v1/leads")
class LeadsController(
    private val createLeadHandler: CreateLeadHandler,
    private val updateLeadHandler: UpdateLeadHandler,
    private val deleteLeadHandler: DeleteLeadHandler,
    private val listLeadsHandler: ListLeadsHandler,
    private val getPipelineSummaryHandler: GetPipelineSummaryHandler,
    private val getLeadHandler: GetLeadHandler,
    private val analyzeLeadHandler: AnalyzeLeadHandler
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
        @RequestParam(defaultValue = "20") limit: Int
    ): ResponseEntity<LeadListResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()

        val query = ListLeadsQuery(
            studioId = principal.studioId,
            search = search,
            statuses = status?.map { LeadStatus.valueOf(it) },
            sources = source?.map { LeadSource.valueOf(it) },
            page = page,
            limit = limit
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
     * Get a single lead by ID — includes AI estimation breakdown if available
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
                relatedVisits = emptyList(), aiReasoning = null
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
                source = null, // Not returned in update
                status = result.status.name,
                contactIdentifier = null, // Not returned in update
                customerName = result.customerName,
                initialMessage = result.initialMessage,
                createdAt = null, // Not returned in update
                updatedAt = result.updatedAt,
                estimatedValue = result.estimatedValue,
                requiresVerification = result.requiresVerification,
                vehicleBrand = null,
                vehicleModel = null,
                relatedVisits = emptyList(), aiReasoning = null
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
        @RequestParam(required = false) source: List<String>?
    ): ResponseEntity<PipelineSummaryDto> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()

        val query = GetPipelineSummaryQuery(
            studioId = principal.studioId,
            sourceFilter = source?.map { LeadSource.valueOf(it) }
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
                atRiskCount = result.atRiskCount
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
                relatedVisits = emptyList(), aiReasoning = null
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
                relatedVisits = emptyList(), aiReasoning = null
            )
        )
    }
}
