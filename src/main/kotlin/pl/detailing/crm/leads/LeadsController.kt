package pl.detailing.crm.leads

import kotlinx.coroutines.runBlocking
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import pl.detailing.crm.auth.SecurityContextHelper
import pl.detailing.crm.leads.analytics.GetLeadAnalyticsHandler
import pl.detailing.crm.leads.analytics.LeadAnalyticsDto
import pl.detailing.crm.leads.convert.MarkThreadAsLeadCommand
import pl.detailing.crm.leads.convert.MarkThreadAsLeadHandler
import pl.detailing.crm.leads.create.CreateLeadCommand
import pl.detailing.crm.leads.create.CreateLeadHandler
import pl.detailing.crm.leads.delete.DeleteLeadHandler
import pl.detailing.crm.leads.domain.LeadCategory
import pl.detailing.crm.leads.domain.LeadLostReason
import pl.detailing.crm.leads.query.DictionaryEntryDto
import pl.detailing.crm.leads.query.LeadDictionariesDto
import pl.detailing.crm.leads.query.LeadDto
import pl.detailing.crm.leads.query.LeadPageDto
import pl.detailing.crm.leads.query.LeadQueryHandlers
import pl.detailing.crm.leads.query.LeadStatusHistoryDto
import pl.detailing.crm.leads.query.leadDictionaries
import pl.detailing.crm.leads.tags.LeadTagCatalogService
import pl.detailing.crm.leads.update.LeadServiceItemInput
import pl.detailing.crm.leads.update.UpdateLeadHandlers
import pl.detailing.crm.role.domain.Permission
import pl.detailing.crm.role.permission.RequiresPermission
import pl.detailing.crm.shared.LeadSource
import pl.detailing.crm.shared.LeadStatus
import pl.detailing.crm.shared.ValidationException
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

data class CreateLeadRequest(
    val contactIdentifier: String,
    val customerName: String?,
    val initialMessage: String?,
    val category: String?
)

data class LeadServiceItemRequest(
    val serviceId: String?,
    val name: String?,
    val priceGross: Long?,
    val quantity: Int = 1,
    val priceNet: Long? = null,
    val vatRate: Int? = null,
    val note: String? = null
)

data class MarkThreadAsLeadRequest(
    /** Kody ze słownika tagów studia — wiele na leada, bo jedno zapytanie potrafi dotyczyć kilku usług. */
    val tags: List<String> = emptyList(),
    val services: List<LeadServiceItemRequest> = emptyList()
)

data class ChangeLeadStatusRequest(
    val status: String,
    val lostReasonCode: String?,
    val lostNote: String?
)

data class UpdateLeadServicesRequest(val services: List<LeadServiceItemRequest>)

data class UpdateLeadRequest(
    val category: String?,
    val customerName: String?,
    val assignedUserId: String?
)

data class AssignLeadCustomerRequest(val customerId: String)

/** Podmiana tagów istniejącego leada — cały zestaw, nie różnica. */
data class UpdateLeadTagsRequest(val tags: List<String> = emptyList())

/** Nowy tag w słowniku studia; kod nadaje backend, użytkownik podaje samą nazwę. */
data class CreateLeadTagRequest(val label: String)

/** Ręczna korekta pojazdu; puste pola czyszczą rozpoznanie. */
data class UpdateLeadVehicleRequest(val vehicleBrand: String?, val vehicleModel: String?)

data class MarkThreadAsLeadResponse(val leadId: String, val estimatedValue: Long)

/**
 * Lead pipeline API. Every id from the path is re-checked against the caller's studio
 * inside the handlers.
 */
@RestController
@RequestMapping("/api/v1/leads")
@RequiresPermission(Permission.LEADS_MANAGE)
class LeadsController(
    private val queryHandlers: LeadQueryHandlers,
    private val createLeadHandler: CreateLeadHandler,
    private val markThreadAsLeadHandler: MarkThreadAsLeadHandler,
    private val updateHandlers: UpdateLeadHandlers,
    private val deleteLeadHandler: DeleteLeadHandler,
    private val tagCatalog: LeadTagCatalogService,
    private val analyticsHandler: GetLeadAnalyticsHandler
) {

    @GetMapping
    fun list(
        @RequestParam(required = false) status: String?,
        @RequestParam(required = false) query: String?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "25") pageSize: Int
    ): ResponseEntity<LeadPageDto> {
        val principal = SecurityContextHelper.getCurrentUser()
        return ResponseEntity.ok(
            queryHandlers.list(principal.studioId, status?.let(::parseStatus), query, page, pageSize)
        )
    }

    @GetMapping("/dictionaries")
    fun dictionaries(): ResponseEntity<LeadDictionariesDto> {
        val principal = SecurityContextHelper.getCurrentUser()
        return ResponseEntity.ok(
            leadDictionaries(
                tagCatalog.listActive(principal.studioId).map { DictionaryEntryDto(it.code, it.label) }
            )
        )
    }

    /** Nowy tag w słowniku — od razu widoczny w oknie „Oznacz jako lead". */
    @PostMapping("/tags")
    fun createTag(@RequestBody request: CreateLeadTagRequest): ResponseEntity<DictionaryEntryDto> {
        val principal = SecurityContextHelper.getCurrentUser()
        val created = tagCatalog.create(principal.studioId, request.label)
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(DictionaryEntryDto(created.code, created.label))
    }

    /**
     * Usunięcie tagu ze słownika. Leady, które go mają, zachowują go w historii —
     * znika tylko z listy wyboru, żeby statystyki sprzed usunięcia dały się czytać.
     */
    @DeleteMapping("/tags/{code}")
    fun deleteTag(@PathVariable code: String): ResponseEntity<Void> {
        val principal = SecurityContextHelper.getCurrentUser()
        tagCatalog.archive(principal.studioId, code)
        return ResponseEntity.noContent().build()
    }

    @GetMapping("/analytics")
    fun analytics(
        @RequestParam(required = false) from: Instant?,
        @RequestParam(required = false) to: Instant?
    ): ResponseEntity<LeadAnalyticsDto> {
        val principal = SecurityContextHelper.getCurrentUser()
        val rangeTo = to ?: Instant.now()
        val rangeFrom = from ?: rangeTo.minus(30, ChronoUnit.DAYS)
        return ResponseEntity.ok(analyticsHandler.handle(principal.studioId, rangeFrom, rangeTo))
    }

    @GetMapping("/{id}")
    fun get(@PathVariable id: String): ResponseEntity<LeadDto> {
        val principal = SecurityContextHelper.getCurrentUser()
        return ResponseEntity.ok(queryHandlers.get(principal.studioId, UUID.fromString(id)))
    }

    @GetMapping("/{id}/history")
    fun history(@PathVariable id: String): ResponseEntity<List<LeadStatusHistoryDto>> {
        val principal = SecurityContextHelper.getCurrentUser()
        return ResponseEntity.ok(queryHandlers.statusHistory(principal.studioId, UUID.fromString(id)))
    }

    @PostMapping
    fun create(@RequestBody request: CreateLeadRequest): ResponseEntity<LeadDto> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        if (request.contactIdentifier.isBlank()) {
            throw ValidationException("Podaj adres e-mail lub numer telefonu")
        }
        val result = createLeadHandler.handle(
            CreateLeadCommand(
                studioId = principal.studioId,
                userId = principal.userId,
                source = LeadSource.MANUAL,
                contactIdentifier = request.contactIdentifier,
                customerName = request.customerName,
                initialMessage = request.initialMessage,
                estimatedValue = 0,
                userName = principal.fullName,
                category = request.category?.let(::parseCategory)
            )
        )
        ResponseEntity.status(HttpStatus.CREATED)
            .body(queryHandlers.get(principal.studioId, result.leadId.value))
    }

    /** One-click promotion of a conversation to a lead, with instant service pricing. */
    @PostMapping("/from-thread/{threadId}")
    fun markThreadAsLead(
        @PathVariable threadId: String,
        @RequestBody request: MarkThreadAsLeadRequest
    ): ResponseEntity<MarkThreadAsLeadResponse> {
        val principal = SecurityContextHelper.getCurrentUser()
        val result = markThreadAsLeadHandler.handle(
            MarkThreadAsLeadCommand(
                studioId = principal.studioId,
                threadId = UUID.fromString(threadId),
                userId = principal.userId.value,
                userName = principal.fullName,
                tags = tagCatalog.validate(principal.studioId, request.tags),
                services = request.services.map { it.toInput() }
            )
        )
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(MarkThreadAsLeadResponse(result.leadId.toString(), result.estimatedValue))
    }

    /** Poprawienie lub uzupełnienie pojazdu — ostatnie słowo ma człowiek, nie model. */
    @PutMapping("/{id}/vehicle")
    fun updateVehicle(
        @PathVariable id: String,
        @RequestBody request: UpdateLeadVehicleRequest
    ): ResponseEntity<LeadDto> {
        val principal = SecurityContextHelper.getCurrentUser()
        val leadId = UUID.fromString(id)
        updateHandlers.updateVehicle(principal.studioId, leadId, request.vehicleBrand, request.vehicleModel)
        return ResponseEntity.ok(queryHandlers.get(principal.studioId, leadId))
    }

    @PutMapping("/{id}/tags")
    fun updateTags(
        @PathVariable id: String,
        @RequestBody request: UpdateLeadTagsRequest
    ): ResponseEntity<LeadDto> {
        val principal = SecurityContextHelper.getCurrentUser()
        val leadId = UUID.fromString(id)
        updateHandlers.updateTags(principal.studioId, leadId, request.tags)
        return ResponseEntity.ok(queryHandlers.get(principal.studioId, leadId))
    }

    @PutMapping("/{id}/status")
    fun changeStatus(
        @PathVariable id: String,
        @RequestBody request: ChangeLeadStatusRequest
    ): ResponseEntity<LeadDto> {
        val principal = SecurityContextHelper.getCurrentUser()
        updateHandlers.changeStatus(
            studioId = principal.studioId,
            leadId = UUID.fromString(id),
            targetStatus = parseStatus(request.status),
            lostReasonCode = request.lostReasonCode?.let(::parseLostReason),
            lostNote = request.lostNote,
            userId = principal.userId.value,
            userName = principal.fullName
        )
        return ResponseEntity.ok(queryHandlers.get(principal.studioId, UUID.fromString(id)))
    }

    @PutMapping("/{id}/services")
    fun updateServices(
        @PathVariable id: String,
        @RequestBody request: UpdateLeadServicesRequest
    ): ResponseEntity<LeadDto> {
        val principal = SecurityContextHelper.getCurrentUser()
        updateHandlers.updateServices(
            principal.studioId, UUID.fromString(id), request.services.map { it.toInput() }
        )
        return ResponseEntity.ok(queryHandlers.get(principal.studioId, UUID.fromString(id)))
    }

    @PutMapping("/{id}")
    fun update(
        @PathVariable id: String,
        @RequestBody request: UpdateLeadRequest
    ): ResponseEntity<LeadDto> {
        val principal = SecurityContextHelper.getCurrentUser()
        updateHandlers.updateDetails(
            studioId = principal.studioId,
            leadId = UUID.fromString(id),
            category = request.category?.let(::parseCategory),
            customerName = request.customerName,
            assignedUserId = request.assignedUserId?.let(UUID::fromString)
        )
        return ResponseEntity.ok(queryHandlers.get(principal.studioId, UUID.fromString(id)))
    }

    /** Usunięcie leada — pomyłka, duplikat, test. Korespondencja zostaje w skrzynce. */
    @DeleteMapping("/{id}")
    fun delete(@PathVariable id: String): ResponseEntity<Void> {
        val principal = SecurityContextHelper.getCurrentUser()
        deleteLeadHandler.handle(principal.studioId, UUID.fromString(id))
        return ResponseEntity.noContent().build()
    }

    @PutMapping("/{id}/customer")
    fun assignCustomer(
        @PathVariable id: String,
        @RequestBody request: AssignLeadCustomerRequest
    ): ResponseEntity<LeadDto> {
        val principal = SecurityContextHelper.getCurrentUser()
        updateHandlers.assignCustomer(
            principal.studioId, UUID.fromString(id), UUID.fromString(request.customerId)
        )
        return ResponseEntity.ok(queryHandlers.get(principal.studioId, UUID.fromString(id)))
    }

    private fun LeadServiceItemRequest.toInput() = LeadServiceItemInput(
        serviceId = serviceId?.let(UUID::fromString),
        name = name,
        priceGross = priceGross,
        quantity = quantity,
        priceNet = priceNet,
        vatRate = vatRate,
        note = note
    )

    private fun parseStatus(value: String): LeadStatus =
        runCatching { LeadStatus.valueOf(value) }.getOrElse {
            throw ValidationException("Nieznany status leada: $value")
        }

    private fun parseCategory(value: String): LeadCategory =
        runCatching { LeadCategory.valueOf(value) }.getOrElse {
            throw ValidationException("Nieznana kategoria zapytania: $value")
        }

    private fun parseLostReason(value: String): LeadLostReason =
        runCatching { LeadLostReason.valueOf(value) }.getOrElse {
            throw ValidationException("Nieznany powód przegranej: $value")
        }
}
