package pl.detailing.crm.batchorder

import kotlinx.coroutines.runBlocking
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import org.springframework.web.multipart.MultipartFile
import pl.detailing.crm.auth.SecurityContextHelper
import pl.detailing.crm.batchorder.contractor.*
import pl.detailing.crm.batchorder.entry.CreateEntryCommand
import pl.detailing.crm.batchorder.entry.CreateEntryHandler
import pl.detailing.crm.batchorder.entry.DeleteEntryCommand
import pl.detailing.crm.batchorder.entry.DeleteEntryHandler
import pl.detailing.crm.batchorder.entry.ServiceItemInput
import pl.detailing.crm.batchorder.entry.UpdateEntryCommand
import pl.detailing.crm.batchorder.entry.UpdateEntryHandler
import pl.detailing.crm.batchorder.photos.*
import pl.detailing.crm.batchorder.infrastructure.BatchOrderEntryRepository
import pl.detailing.crm.batchorder.report.GenerateBatchReportCommand
import pl.detailing.crm.batchorder.report.GenerateBatchReportHandler
import pl.detailing.crm.batchorder.vin.VinExtractionService
import pl.detailing.crm.shared.BatchContractorId
import pl.detailing.crm.shared.BatchOrderEntryId
import pl.detailing.crm.vehicle.infrastructure.VehicleRepository
import java.time.LocalDate

@RestController
@RequestMapping("/api/batch-orders")
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
    private val vehicleRepository: VehicleRepository,
    private val addBatchOrderPhotoHandler: AddBatchOrderPhotoHandler,
    private val listBatchOrderPhotosHandler: ListBatchOrderPhotosHandler,
    private val deleteBatchOrderPhotoHandler: DeleteBatchOrderPhotoHandler,
    private val entryRepository: BatchOrderEntryRepository,
    private val vinExtractionService: VinExtractionService
) {

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
        @RequestParam(required = false) to: String?
    ): ResponseEntity<ContractorEntriesResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        val result = getContractorEntriesHandler.handle(
            GetContractorEntriesCommand(
                studioId = principal.studioId,
                contractorId = BatchContractorId.fromString(contractorId),
                from = from?.let { LocalDate.parse(it) },
                to = to?.let { LocalDate.parse(it) }
            )
        )
        ResponseEntity.ok(
            ContractorEntriesResponse(
                contractor = result.contractor,
                entries = result.entries,
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
    val summary: EntrySummary
)
data class EntryItemResponse(val entry: EntryItem)
