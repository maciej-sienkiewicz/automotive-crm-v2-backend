package pl.detailing.crm.vehicle.documents

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.VehicleId
import pl.detailing.crm.vehicle.infrastructure.VehicleDocumentRepository
import pl.detailing.crm.visit.infrastructure.VisitDocumentRepository
import pl.detailing.crm.visit.infrastructure.VisitRepository
import pl.detailing.crm.visit.infrastructure.DocumentStorageService
import java.time.Instant
import java.util.UUID

@Service
class GetVehicleDocumentsHandler(
    private val vehicleDocumentRepository: VehicleDocumentRepository,
    private val visitDocumentRepository: VisitDocumentRepository,
    private val visitRepository: VisitRepository,
    private val documentStorageService: DocumentStorageService
) {

    @Transactional(readOnly = true)
    fun handle(command: GetVehicleDocumentsCommand): GetVehicleDocumentsResult {
        val vehicleId = command.vehicleId.value
        val studioId = command.studioId.value

        // Get documents directly assigned to the vehicle
        val vehicleDocuments = vehicleDocumentRepository.findByVehicleIdAndStudioId(vehicleId, studioId)
            .map { entity ->
                VehicleDocumentItem(
                    id = entity.id.toString(),
                    name = entity.name,
                    fileName = entity.fileName,
                    fileUrl = documentStorageService.generateDownloadUrl(entity.fileId),
                    uploadedAt = entity.uploadedAt,
                    uploadedByName = entity.uploadedByName,
                    source = "VEHICLE"
                )
            }

        // Get documents from visits related to this vehicle
        val visitsForVehicle = visitRepository.findByVehicleIdAndStudioIdExcludingDraft(vehicleId, studioId)
        val visitDocuments = visitsForVehicle.flatMap { visit ->
            visitDocumentRepository.findByVisit_IdOrderByUploadedAtDesc(visit.id)
                .map { entity ->
                    VehicleDocumentItem(
                        id = entity.id.toString(),
                        name = entity.name,
                        fileName = entity.fileName,
                        fileUrl = documentStorageService.generateDownloadUrl(entity.fileId),
                        uploadedAt = entity.uploadedAt,
                        uploadedByName = entity.uploadedByName,
                        source = "VISIT"
                    )
                }
        }

        // Merge and sort by upload date (newest first)
        val allDocuments = (vehicleDocuments + visitDocuments).sortedByDescending { it.uploadedAt }

        return GetVehicleDocumentsResult(documents = allDocuments)
    }
}

data class GetVehicleDocumentsCommand(
    val vehicleId: VehicleId,
    val studioId: StudioId
)

data class GetVehicleDocumentsResult(
    val documents: List<VehicleDocumentItem>
)

data class VehicleDocumentItem(
    val id: String,
    val name: String,
    val fileName: String,
    val fileUrl: String,
    val uploadedAt: Instant,
    val uploadedByName: String,
    val source: String // "VEHICLE" or "VISIT"
)
