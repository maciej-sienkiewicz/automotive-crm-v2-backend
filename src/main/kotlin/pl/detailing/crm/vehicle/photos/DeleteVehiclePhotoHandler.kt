package pl.detailing.crm.vehicle.photos

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import pl.detailing.crm.audit.domain.*
import pl.detailing.crm.shared.*
import pl.detailing.crm.vehicle.infrastructure.VehicleRepository
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest

/**
 * Usunięcie zdjęcia pojazdu: z kolekcji i z S3.
 *
 * Dwie rzeczy, które trzeba tu zrobić inaczej niż „normalnie", i obie z tego samego
 * powodu — `@Transactional` na funkcji `suspend` NIE DZIAŁA (Spring prowadzi
 * zawieszalne metody transakcyjnie tylko przez ReactiveTransactionManager, którego
 * aplikacja na JPA nie ma; ten sam wywód stoi w AuditLogWriter):
 *
 *  1. Zdjęcia czytamy zapytaniem z JOIN FETCH, a nie leniwie. Wcześniej handler brał
 *     pojazd bez zdjęć i dotykał `photos.size`, licząc na sesję z open-in-view — ale
 *     ta jest przypięta do wątku ŻĄDANIA, a `withContext(Dispatchers.IO)` z niego
 *     wyskakuje. Stąd LazyInitializationException przy każdym usunięciu; dodawanie
 *     zdjęcia działało tylko dlatego, że zostaje na wątku żądania.
 *  2. Samą zmianę opakowujemy w TransactionTemplate, żeby odczyt–modyfikacja–zapis
 *     kolekcji poszły jedną transakcją, a nie trzema niezależnymi.
 *
 * S3 sprzątamy PO zatwierdzeniu: nieudane kasowanie pliku nie może cofnąć usunięcia
 * z bazy — plik-sierota jest tańszy niż zdjęcie, którego nie da się usunąć.
 */
@Service
class DeleteVehiclePhotoHandler(
    private val vehicleRepository: VehicleRepository,
    private val s3Client: S3Client,
    @Value("\${aws.s3.bucket-name}") private val bucketName: String,
    private val auditService: AuditService,
    private val transactionTemplate: TransactionTemplate
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** To, co po usunięciu jest jeszcze potrzebne — poza transakcją encja już nie żyje. */
    private data class RemovedPhoto(
        val fileName: String,
        val fileId: String,
        val vehicleLabel: String
    )

    suspend fun handle(command: DeleteVehiclePhotoCommand) {
        val removed = withContext(Dispatchers.IO) {
            transactionTemplate.execute {
                val vehicleEntity = vehicleRepository.findByIdAndStudioIdWithPhotos(
                    id = command.vehicleId.value,
                    studioId = command.studioId.value
                ) ?: throw EntityNotFoundException("Pojazd nie został znaleziony: ${command.vehicleId}")

                val photoToDelete = vehicleEntity.photos.find { it.id == command.photoId.value }
                    ?: throw EntityNotFoundException("Zdjęcie nie zostało znalezione: ${command.photoId}")

                vehicleEntity.photos.remove(photoToDelete)
                vehicleRepository.save(vehicleEntity)

                RemovedPhoto(
                    fileName = photoToDelete.fileName,
                    fileId = photoToDelete.fileId,
                    vehicleLabel = listOfNotNull(
                        vehicleEntity.brand, vehicleEntity.model, vehicleEntity.licensePlate
                    ).joinToString(" ")
                )
            }!!
        }

        if (command.userId != null) {
            auditService.log(
                LogAuditCommand(
                    studioId = command.studioId,
                    userId = command.userId,
                    userDisplayName = command.userName ?: "",
                    module = AuditModule.VEHICLE,
                    entityId = command.vehicleId.value.toString(),
                    entityDisplayName = removed.vehicleLabel,
                    action = AuditAction.PHOTO_DELETED,
                    changes = listOf(FieldChange("fileName", removed.fileName, null)),
                    metadata = mapOf("photoId" to command.photoId.value.toString())
                )
            )
        }

        try {
            s3Client.deleteObject(
                DeleteObjectRequest.builder()
                    .bucket(bucketName)
                    .key(removed.fileId)
                    .build()
            )
        } catch (e: Exception) {
            // Zdjęcia już nie ma w bazie — osierocony plik w S3 posprząta późniejsze
            // czyszczenie. Zgłoszenie błędu użytkownikowi sugerowałoby, że usunięcie
            // się nie udało, a udało się w tej części, która jest widoczna.
            log.warn("[VEHICLE_PHOTO] Nie udało się usunąć pliku {} z S3", removed.fileId, e)
        }
    }
}

/**
 * Command to delete a photo from a vehicle
 */
data class DeleteVehiclePhotoCommand(
    val vehicleId: VehicleId,
    val photoId: VehiclePhotoId,
    val studioId: StudioId,
    val userId: UserId? = null,
    val userName: String? = null
)
