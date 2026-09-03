package pl.detailing.crm.worktime.attendance

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.shared.EmployeeId
import pl.detailing.crm.shared.EntityNotFoundException
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import pl.detailing.crm.shared.ValidationException
import pl.detailing.crm.visit.infrastructure.DocumentStorageService
import java.time.Instant
import java.time.YearMonth
import java.util.Base64
import java.util.UUID

/**
 * Cykl życia listy obecności: wygeneruj → przechowaj → (opcjonalnie) podpisz → pobierz.
 *
 * Arkusz jest zapisywany ZAWSZE, także gdy nikt go nie podpisze. Dokument kadrowy,
 * który istnieje wyłącznie jako plik w folderze Pobrane, nie daje się ani odtworzyć,
 * ani sprawdzić — a to jest wydruk, na którym opiera się rozliczenie czasu pracy.
 */
@Service
class AttendanceSheetService(
    private val generateHandler: GenerateAttendanceSheetHandler,
    private val repository: AttendanceSheetRepository,
    private val storageService: DocumentStorageService,
    private val signer: AttendanceSheetSigner
) {
    private val logger = LoggerFactory.getLogger(AttendanceSheetService::class.java)
    private val json: ObjectMapper = jacksonObjectMapper().findAndRegisterModules()

    companion object {
        private const val MAX_HISTORY_LIMIT = 100
        private const val MAX_SIGNATURE_BYTES = 10 * 1024 * 1024
    }

    /** Generuje arkusz, zapisuje plik w S3 i zwraca wiersz opisujący dokument. */
    suspend fun generate(
        studioId: StudioId,
        userId: UserId,
        period: YearMonth,
        employeeIds: List<EmployeeId>
    ): AttendanceSheetEntity {
        val pdfBytes = generateHandler.handle(
            GenerateAttendanceSheetCommand(studioId = studioId, period = period, employeeIds = employeeIds)
        )

        val id = UUID.randomUUID()
        val s3Key = "${studioId.value}/attendance-sheets/$period/$id.pdf"
        storageService.uploadDocument(
            s3Key = s3Key,
            fileBytes = pdfBytes,
            contentType = "application/pdf",
            metadata = mapOf("period" to period.toString(), "studioId" to studioId.value.toString())
        )

        return save(
            AttendanceSheetEntity(
                id = id,
                studioId = studioId.value,
                period = period.toString(),
                employeeIdsJson = json.writeValueAsString(employeeIds.map { it.value.toString() }),
                fileS3Key = s3Key,
                createdBy = userId.value,
                createdAt = Instant.now()
            )
        )
    }

    /**
     * Bajty dokumentu: podpisana wersja, gdy istnieje.
     *
     * Po podpisaniu nikt nie chce już oryginału — a gdyby chciał, oryginał nadal leży
     * w S3 pod [AttendanceSheetEntity.fileS3Key].
     */
    suspend fun download(studioId: StudioId, sheetId: UUID): Pair<AttendanceSheetEntity, ByteArray> =
        withContext(Dispatchers.IO) {
            val sheet = require(studioId, sheetId)
            val key = sheet.signedFileS3Key ?: sheet.fileS3Key
            sheet to storageService.downloadBytes(key)
        }

    /**
     * Wtapia podpis w arkusz i zapisuje podpisaną wersję jako osobny plik.
     *
     * Ponowny podpis jest odrzucany: podpisany dokument, który da się podpisać jeszcze
     * raz „na wierzch", przestaje być dowodem czegokolwiek.
     */
    suspend fun sign(
        studioId: StudioId,
        userId: UserId,
        signerName: String,
        sheetId: UUID,
        signatureDataUrl: String
    ): AttendanceSheetEntity {
        val sheet = require(studioId, sheetId)
        if (sheet.signedFileS3Key != null) {
            throw ValidationException("Ten arkusz jest już podpisany.")
        }

        val signaturePng = decodeSignature(signatureDataUrl)
        val signedAt = Instant.now()

        val signedBytes = withContext(Dispatchers.IO) {
            val original = storageService.downloadBytes(sheet.fileS3Key)
            signer.sign(original, signaturePng, signerName, signedAt)
        }

        val signedKey = sheet.fileS3Key.removeSuffix(".pdf") + "-signed.pdf"
        storageService.uploadDocument(
            s3Key = signedKey,
            fileBytes = signedBytes,
            contentType = "application/pdf",
            metadata = mapOf("period" to sheet.period, "signed" to "true")
        )

        sheet.signedFileS3Key = signedKey
        sheet.signerName = signerName
        sheet.signedAt = signedAt
        sheet.signedBy = userId.value
        logger.info("Attendance sheet signed: studioId={}, sheetId={}, by={}", studioId, sheetId, userId)
        return save(sheet)
    }

    @Transactional(readOnly = true)
    fun history(studioId: StudioId, limit: Int): List<AttendanceSheetEntity> =
        repository.findByStudioIdOrderByCreatedAtDesc(
            studioId.value,
            PageRequest.of(0, limit.coerceIn(1, MAX_HISTORY_LIMIT))
        )

    // ── Pomocnicze ────────────────────────────────────────────────────────────

    @Transactional
    fun save(entity: AttendanceSheetEntity): AttendanceSheetEntity = repository.save(entity)

    @Transactional(readOnly = true)
    fun require(studioId: StudioId, sheetId: UUID): AttendanceSheetEntity =
        repository.findByIdAndStudioId(sheetId, studioId.value)
            ?: throw EntityNotFoundException("Nie znaleziono listy obecności o id: $sheetId")

    fun employeeIdsOf(entity: AttendanceSheetEntity): List<String> =
        runCatching { json.readValue<List<String>>(entity.employeeIdsJson) }.getOrDefault(emptyList())

    /**
     * Kanwa z przeglądarki oddaje podpis jako `data:image/png;base64,...`.
     * Przyjmujemy wyłącznie PNG — [SignatureImageProcessor] i tak sprawdzi zawartość,
     * ale odrzucenie innego typu od razu daje czytelny komunikat zamiast „nieprawidłowy obraz".
     */
    private fun decodeSignature(dataUrl: String): ByteArray {
        val payload = dataUrl.substringAfter("base64,", missingDelimiterValue = "")
        if (payload.isBlank() || !dataUrl.startsWith("data:image/png")) {
            throw ValidationException("Podpis musi być obrazem PNG z kanwy.")
        }
        val bytes = runCatching { Base64.getDecoder().decode(payload) }
            .getOrElse { throw ValidationException("Nie udało się odczytać obrazu podpisu.") }
        if (bytes.size > MAX_SIGNATURE_BYTES) {
            throw ValidationException("Obraz podpisu jest za duży.")
        }
        return bytes
    }
}
