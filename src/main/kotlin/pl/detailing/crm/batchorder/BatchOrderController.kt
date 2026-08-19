package pl.detailing.crm.batchorder

import kotlinx.coroutines.runBlocking
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import org.slf4j.LoggerFactory
import org.springframework.web.multipart.MultipartFile
import pl.detailing.crm.auth.SecurityContextHelper
import pl.detailing.crm.role.domain.Permission
import pl.detailing.crm.role.permission.RequiresPermission
import pl.detailing.crm.batchorder.contractor.*
import pl.detailing.crm.batchorder.entry.CreateEntryCommand
import pl.detailing.crm.batchorder.entry.CreateEntryHandler
import pl.detailing.crm.batchorder.entry.DeleteEntryCommand
import pl.detailing.crm.batchorder.entry.DeleteEntryHandler
import pl.detailing.crm.batchorder.entry.ServiceItemInput
import pl.detailing.crm.batchorder.entry.UpdateEntryCommand
import pl.detailing.crm.batchorder.entry.UpdateEntryHandler
import pl.detailing.crm.batchorder.photos.*
import pl.detailing.crm.batchorder.service.BatchServiceItem
import pl.detailing.crm.batchorder.service.CreateBatchServiceHandler
import pl.detailing.crm.batchorder.service.DeleteBatchServiceHandler
import pl.detailing.crm.batchorder.service.ListBatchServicesHandler
import pl.detailing.crm.batchorder.service.RegisterBatchServicesHandler
import pl.detailing.crm.batchorder.service.SaveBatchServiceCommand
import pl.detailing.crm.batchorder.service.UpdateBatchServiceHandler
import pl.detailing.crm.batchorder.infrastructure.BatchOrderEntryRepository
import pl.detailing.crm.batchorder.report.CloseMode
import pl.detailing.crm.batchorder.report.CloseMonthCommand
import pl.detailing.crm.batchorder.report.CloseMonthHandler
import pl.detailing.crm.batchorder.report.GenerateBatchReportCommand
import pl.detailing.crm.batchorder.report.GenerateBatchReportHandler
import pl.detailing.crm.batchorder.vin.VinExtractionService
import pl.detailing.crm.shared.BatchContractorId
import pl.detailing.crm.shared.BatchOrderCloseHistoryId
import pl.detailing.crm.shared.BatchOrderEntryId
import pl.detailing.crm.shared.BatchOrderServiceId
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.vehicle.infrastructure.VehicleRepository
import java.time.LocalDate

@RestController
@RequestMapping("/api/batch-orders")
@RequiresPermission(Permission.BATCH_ORDERS)
class BatchOrderController(
    private val listContractorsHandler: ListContractorsHandler,
    private val createContractorHandler: CreateContractorHandler,
    private val updateContractorHandler: UpdateContractorHandler,
    private val deleteContractorHandler: DeleteContractorHandler,
    private val getContractorEntriesHandler: GetContractorEntriesHandler,
    private val createEntryHandler: CreateEntryHandler,
    private val updateEntryHandler: UpdateEntryHandler,
    private val deleteEntryHandler: DeleteEntryHandler,
    private val generateBatchReportHandler: GenerateBatchReportHandler,
    private val closeMonthHandler: CloseMonthHandler,
    private val vehicleRepository: VehicleRepository,
    private val addBatchOrderPhotoHandler: AddBatchOrderPhotoHandler,
    private val listBatchOrderPhotosHandler: ListBatchOrderPhotosHandler,
    private val deleteBatchOrderPhotoHandler: DeleteBatchOrderPhotoHandler,
    private val entryRepository: BatchOrderEntryRepository,
    private val vinExtractionService: VinExtractionService,
    private val listBatchServicesHandler: ListBatchServicesHandler,
    private val createBatchServiceHandler: CreateBatchServiceHandler,
    private val updateBatchServiceHandler: UpdateBatchServiceHandler,
    private val deleteBatchServiceHandler: DeleteBatchServiceHandler,
    private val registerBatchServicesHandler: RegisterBatchServicesHandler
) {
    private val log = LoggerFactory.getLogger(BatchOrderController::class.java)

    /**
     * Adds whatever the operator just typed to the module's service catalog, after the
     * entry itself is safely committed.
     *
     * Deliberately outside the entry's transaction and deliberately swallowed: the
     * catalog is a typing aid, and no failure to remember a name is worth losing the
     * entry the failure would otherwise roll back.
     */
    private fun rememberServices(studioId: StudioId, services: List<ServiceItemRequest>) {
        if (services.isEmpty()) return
        try {
            registerBatchServicesHandler.register(
                studioId,
                services.map {
                    SaveBatchServiceCommand(
                        studioId = studioId,
                        name = it.name,
                        netAmountCents = it.netAmountCents,
                        grossAmountCents = it.grossAmountCents,
                        vatRate = it.vatRate
                    )
                }
            )
        } catch (e: Exception) {
            log.warn("Could not add batch-order services to the catalog for studio={}", studioId, e)
        }
    }

    @GetMapping("/contractors")
    fun listContractors(): ResponseEntity<ContractorsResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        val result = listContractorsHandler.handle(ListContractorsCommand(principal.studioId))
        ResponseEntity.ok(ContractorsResponse(contractors = result.contractors))
    }

    @PostMapping("/contractors")
    fun createContractor(@RequestBody request: ContractorRequest): ResponseEntity<ContractorItemResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        val item = createContractorHandler.handle(
            CreateContractorCommand(
                studioId = principal.studioId,
                name = request.name,
                taxId = request.taxId,
                address = request.address,
                contactPersonName = request.contactPersonName,
                email = request.email,
                phone = request.phone,
                notes = request.notes
            )
        )
        ResponseEntity.status(HttpStatus.CREATED).body(ContractorItemResponse(contractor = item))
    }

    @PutMapping("/contractors/{contractorId}")
    fun updateContractor(
        @PathVariable contractorId: String,
        @RequestBody request: ContractorRequest
    ): ResponseEntity<ContractorItemResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        val item = updateContractorHandler.handle(
            UpdateContractorCommand(
                studioId = principal.studioId,
                contractorId = BatchContractorId.fromString(contractorId),
                name = request.name,
                taxId = request.taxId,
                address = request.address,
                contactPersonName = request.contactPersonName,
                email = request.email,
                phone = request.phone,
                notes = request.notes
            )
        )
        ResponseEntity.ok(ContractorItemResponse(contractor = item))
    }

    @DeleteMapping("/contractors/{contractorId}")
    fun deleteContractor(@PathVariable contractorId: String): ResponseEntity<Void> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        deleteContractorHandler.handle(
            DeleteContractorCommand(
                studioId = principal.studioId,
                contractorId = BatchContractorId.fromString(contractorId)
            )
        )
        ResponseEntity.noContent().build()
    }

    @GetMapping("/contractors/{contractorId}/entries")
    fun getContractorEntries(
        @PathVariable contractorId: String,
        @RequestParam(required = false) from: String?,
        @RequestParam(required = false) to: String?,
        @RequestParam(required = false, defaultValue = "false") includeSettled: Boolean
    ): ResponseEntity<ContractorEntriesResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        val result = getContractorEntriesHandler.handle(
            GetContractorEntriesCommand(
                studioId = principal.studioId,
                contractorId = BatchContractorId.fromString(contractorId),
                from = from?.let { LocalDate.parse(it) },
                to = to?.let { LocalDate.parse(it) },
                includeSettled = includeSettled
            )
        )
        ResponseEntity.ok(
            ContractorEntriesResponse(
                contractor = result.contractor,
                entries = result.entries,
                settledCount = result.settledCount,
                summary = result.summary
            )
        )
    }

    @PostMapping("/contractors/{contractorId}/entries")
    fun createEntry(
        @PathVariable contractorId: String,
        @RequestBody request: EntryRequest
    ): ResponseEntity<EntryItemResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        val item = createEntryHandler.handle(
            CreateEntryCommand(
                studioId = principal.studioId,
                contractorId = BatchContractorId.fromString(contractorId),
                serviceDate = LocalDate.parse(request.serviceDate),
                vehicleMake = request.vehicleMake,
                vehicleModel = request.vehicleModel,
                vehicleLicensePlate = request.vehicleLicensePlate,
                vehicleVin = request.vehicleVin,
                services = request.services.map { ServiceItemInput(it.name, it.netAmountCents, it.grossAmountCents, it.vatRate) },
                notes = request.notes
            )
        )
        rememberServices(principal.studioId, request.services)
        ResponseEntity.status(HttpStatus.CREATED).body(EntryItemResponse(entry = item))
    }

    @PutMapping("/entries/{entryId}")
    fun updateEntry(
        @PathVariable entryId: String,
        @RequestBody request: EntryRequest
    ): ResponseEntity<EntryItemResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        val item = updateEntryHandler.handle(
            UpdateEntryCommand(
                studioId = principal.studioId,
                entryId = BatchOrderEntryId.fromString(entryId),
                serviceDate = LocalDate.parse(request.serviceDate),
                vehicleMake = request.vehicleMake,
                vehicleModel = request.vehicleModel,
                vehicleLicensePlate = request.vehicleLicensePlate,
                vehicleVin = request.vehicleVin,
                services = request.services.map { ServiceItemInput(it.name, it.netAmountCents, it.grossAmountCents, it.vatRate) },
                notes = request.notes
            )
        )
        rememberServices(principal.studioId, request.services)
        ResponseEntity.ok(EntryItemResponse(entry = item))
    }

    @DeleteMapping("/entries/{entryId}")
    fun deleteEntry(@PathVariable entryId: String): ResponseEntity<Void> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        deleteEntryHandler.handle(
            DeleteEntryCommand(
                studioId = principal.studioId,
                entryId = BatchOrderEntryId.fromString(entryId)
            )
        )
        ResponseEntity.noContent().build()
    }

    // ── Service catalog ──────────────────────────────────────────────────────
    // Suggestions and their prices for the entry form. Editing a position here never
    // reaches a recorded entry: entries keep their own snapshot of what was performed.

    @GetMapping("/services")
    fun listBatchServices(@RequestParam(required = false) q: String?): ResponseEntity<BatchServicesResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        val services = listBatchServicesHandler.handle(principal.studioId, q)
        ResponseEntity.ok(BatchServicesResponse(services = services))
    }

    @PostMapping("/services")
    fun createBatchService(@RequestBody request: BatchServiceRequest): ResponseEntity<BatchServiceItemResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        val item = createBatchServiceHandler.handle(
            SaveBatchServiceCommand(
                studioId = principal.studioId,
                name = request.name,
                netAmountCents = request.netAmountCents,
                grossAmountCents = request.grossAmountCents,
                vatRate = request.vatRate
            )
        )
        ResponseEntity.status(HttpStatus.CREATED).body(BatchServiceItemResponse(service = item))
    }

    @PutMapping("/services/{serviceId}")
    fun updateBatchService(
        @PathVariable serviceId: String,
        @RequestBody request: BatchServiceRequest
    ): ResponseEntity<BatchServiceItemResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        val item = updateBatchServiceHandler.handle(
            BatchOrderServiceId.fromString(serviceId),
            SaveBatchServiceCommand(
                studioId = principal.studioId,
                name = request.name,
                netAmountCents = request.netAmountCents,
                grossAmountCents = request.grossAmountCents,
                vatRate = request.vatRate
            )
        )
        ResponseEntity.ok(BatchServiceItemResponse(service = item))
    }

    @DeleteMapping("/services/{serviceId}")
    fun deleteBatchService(@PathVariable serviceId: String): ResponseEntity<Void> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        deleteBatchServiceHandler.handle(BatchOrderServiceId.fromString(serviceId), principal.studioId)
        ResponseEntity.noContent().build()
    }

    @GetMapping("/contractors/{contractorId}/report")
    fun generateReport(
        @PathVariable contractorId: String,
        @RequestParam(required = false) from: String?,
        @RequestParam(required = false) to: String?
    ): ResponseEntity<ByteArray> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        val pdfBytes = generateBatchReportHandler.handle(
            GenerateBatchReportCommand(
                studioId = principal.studioId,
                contractorId = BatchContractorId.fromString(contractorId),
                from = from?.let { LocalDate.parse(it) },
                to = to?.let { LocalDate.parse(it) }
            )
        )
        val headers = HttpHeaders()
        headers.contentType = MediaType.APPLICATION_PDF
        headers.setContentDispositionFormData("attachment", "zestawienie-zbiorcze.pdf")
        ResponseEntity.ok().headers(headers).body(pdfBytes)
    }

    @PostMapping("/contractors/{contractorId}/close-month")
    fun closeMonth(
        @PathVariable contractorId: String,
        @RequestBody request: CloseMonthRequest
    ): ResponseEntity<CloseMonthResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        val result = closeMonthHandler.handle(
            CloseMonthCommand(
                studioId = principal.studioId,
                contractorId = BatchContractorId.fromString(contractorId),
                from = LocalDate.parse(request.from),
                to = LocalDate.parse(request.to),
                mode = CloseMode.valueOf(request.mode),
                sendEmail = request.sendEmail,
                emailOverride = request.emailOverride?.ifBlank { null }
            )
        )
        ResponseEntity.ok(CloseMonthResponse(
            historyId = result.historyId,
            closedEntryCount = result.entryCount,
            financeEntryCreated = false,
            emailSent = result.emailSent
        ))
    }

    @GetMapping("/contractors/{contractorId}/close-history")
    fun getCloseHistory(@PathVariable contractorId: String): ResponseEntity<CloseHistoryResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        val records = closeMonthHandler.listHistory(
            contractorId = BatchContractorId.fromString(contractorId),
            studioId = principal.studioId
        )
        ResponseEntity.ok(CloseHistoryResponse(records = records.map {
            CloseHistoryRecordDto(
                id = it.id,
                closedAt = it.closedAt,
                periodFrom = it.fromDate,
                periodTo = it.toDate,
                entryCount = it.entryCount,
                totalNetCents = it.totalNetCents,
                totalGrossCents = it.totalGrossCents,
                mode = it.mode,
                financeEntryCreated = false,
                emailRequested = it.emailTo != null,
                emailSent = it.emailSent,
                emailRecipient = it.emailTo,
                closedByUserName = null
            )
        }))
    }

    @GetMapping("/close-history/{historyId}/snapshot")
    fun getHistorySnapshot(@PathVariable historyId: String): ResponseEntity<ByteArray> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        val pdfBytes = generateBatchReportHandler.handleForSnapshot(
            historyId = BatchOrderCloseHistoryId.fromString(historyId),
            studioId = principal.studioId
        )
        val headers = HttpHeaders()
        headers.contentType = MediaType.APPLICATION_PDF
        headers.setContentDispositionFormData("attachment", "zestawienie-zbiorcze.pdf")
        ResponseEntity.ok().headers(headers).body(pdfBytes)
    }

    @PostMapping("/entries/{entryId}/photos/upload-url")
    fun requestPhotoUploadUrl(
        @PathVariable entryId: String,
        @RequestBody request: PhotoUploadRequest
    ): ResponseEntity<PhotoUploadResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        val result = addBatchOrderPhotoHandler.handle(
            AddBatchOrderPhotoCommand(
                entryId = BatchOrderEntryId.fromString(entryId),
                studioId = principal.studioId,
                fileName = request.fileName,
                description = request.description,
                userId = principal.userId,
                userName = principal.fullName
            )
        )
        ResponseEntity.ok(PhotoUploadResponse(
            photoId = result.photoId,
            uploadUrl = result.uploadUrl,
            fileId = result.fileId
        ))
    }

    @GetMapping("/entries/{entryId}/photos")
    fun listEntryPhotos(@PathVariable entryId: String): ResponseEntity<EntryPhotosResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        val photos = listBatchOrderPhotosHandler.handle(
            ListBatchOrderPhotosCommand(
                entryId = BatchOrderEntryId.fromString(entryId),
                studioId = principal.studioId
            )
        )
        ResponseEntity.ok(EntryPhotosResponse(photos = photos))
    }

    @DeleteMapping("/entries/{entryId}/photos/{photoId}")
    fun deleteEntryPhoto(
        @PathVariable entryId: String,
        @PathVariable photoId: String
    ): ResponseEntity<Void> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        deleteBatchOrderPhotoHandler.handle(
            DeleteBatchOrderPhotoCommand(
                photoId = photoId,
                studioId = principal.studioId
            )
        )
        ResponseEntity.noContent().build()
    }

    @GetMapping("/vehicles/search")
    fun searchVehicles(@RequestParam q: String): ResponseEntity<List<VehicleSuggestionDto>> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        val normalized = q.trim().replace("\\s".toRegex(), "").uppercase()
        if (normalized.length < 2) return@runBlocking ResponseEntity.ok(emptyList())

        val suggestions = vehicleRepository.findByStudioId(principal.studioId.value)
            .filter { v ->
                v.licensePlate?.replace("\\s".toRegex(), "")?.uppercase()?.contains(normalized) == true
            }
            .take(10)
            .map { VehicleSuggestionDto(licensePlate = it.licensePlate ?: "", brand = it.brand, model = it.model, vin = null) }

        ResponseEntity.ok(suggestions)
    }

    @GetMapping("/vehicles/search-entry")
    fun searchVehiclesFromEntries(@RequestParam q: String): ResponseEntity<List<VehicleSuggestionDto>> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        val normalized = q.trim().replace("\\s".toRegex(), "").uppercase()
        if (normalized.length < 2) return@runBlocking ResponseEntity.ok(emptyList())

        val seen = mutableSetOf<String>()
        val suggestions = entryRepository.searchByVinOrPlate(principal.studioId.value, normalized)
            .mapNotNull { e ->
                val key = "${e.vehicleVin.orEmpty()}|${e.vehicleLicensePlate.orEmpty()}"
                if (seen.add(key)) {
                    VehicleSuggestionDto(
                        licensePlate = e.vehicleLicensePlate ?: "",
                        brand = e.vehicleMake ?: "",
                        model = e.vehicleModel ?: "",
                        vin = e.vehicleVin
                    )
                } else null
            }
            .take(10)

        ResponseEntity.ok(suggestions)
    }

    @PostMapping("/vin/extract", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun extractVin(@RequestParam("image") image: MultipartFile): ResponseEntity<VinExtractResponse> = runBlocking {
        SecurityContextHelper.getCurrentUser()

        if (image.isEmpty) return@runBlocking ResponseEntity.badRequest().body(VinExtractResponse(null))

        val contentType = image.contentType?.takeIf { it.startsWith("image/") }
            ?: return@runBlocking ResponseEntity.badRequest().body(VinExtractResponse(null))

        val vin = vinExtractionService.extractVin(image.bytes, contentType)
        ResponseEntity.ok(VinExtractResponse(vin))
    }
}

data class ContractorRequest(
    val name: String,
    val taxId: String?,
    val address: String?,
    val contactPersonName: String?,
    val email: String?,
    val phone: String?,
    val notes: String?
)

data class ServiceItemRequest(
    val name: String,
    val netAmountCents: Long,
    val grossAmountCents: Long,
    val vatRate: Int
)

data class EntryRequest(
    val serviceDate: String,
    val vehicleMake: String?,
    val vehicleModel: String?,
    val vehicleLicensePlate: String?,
    val vehicleVin: String?,
    val services: List<ServiceItemRequest>,
    val notes: String?
)

data class VehicleSuggestionDto(
    val licensePlate: String,
    val brand: String,
    val model: String,
    val vin: String?
)

data class PhotoUploadRequest(
    val fileName: String,
    val description: String?
)

data class PhotoUploadResponse(
    val photoId: String,
    val uploadUrl: String,
    val fileId: String
)

data class EntryPhotosResponse(
    val photos: List<BatchOrderPhotoItem>
)

data class VinExtractResponse(val vin: String?)

data class ContractorsResponse(val contractors: List<ContractorListItem>)
data class ContractorItemResponse(val contractor: ContractorListItem)
data class ContractorEntriesResponse(
    val contractor: ContractorListItem,
    val entries: List<EntryItem>,
    /** Settled entries in the period, counted even when the list hides them. */
    val settledCount: Int,
    val summary: EntrySummary
)
data class EntryItemResponse(val entry: EntryItem)

data class BatchServiceRequest(
    val name: String,
    val netAmountCents: Long,
    val grossAmountCents: Long,
    val vatRate: Int
)

data class BatchServicesResponse(val services: List<BatchServiceItem>)
data class BatchServiceItemResponse(val service: BatchServiceItem)

data class CloseMonthRequest(
    val from: String,
    val to: String,
    val mode: String = "NEW_ONLY",
    val addToFinances: Boolean = false,
    val sendEmail: Boolean = false,
    val emailOverride: String? = null
)

data class CloseMonthResponse(
    val historyId: String,
    val closedEntryCount: Int,
    val financeEntryCreated: Boolean,
    val emailSent: Boolean
)

data class CloseHistoryRecordDto(
    val id: String,
    val closedAt: String,
    val periodFrom: String?,
    val periodTo: String?,
    val entryCount: Int,
    val totalNetCents: Long,
    val totalGrossCents: Long,
    val mode: String,
    val financeEntryCreated: Boolean,
    val emailRequested: Boolean,
    val emailSent: Boolean,
    val emailRecipient: String?,
    val closedByUserName: String?
)

data class CloseHistoryResponse(val records: List<CloseHistoryRecordDto>)
