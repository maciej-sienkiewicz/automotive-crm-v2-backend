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
import pl.detailing.crm.audit.domain.AuditAction
import pl.detailing.crm.audit.domain.AuditActor
import pl.detailing.crm.audit.domain.AuditEvent
import pl.detailing.crm.audit.domain.AuditModule
import pl.detailing.crm.audit.domain.AuditService
import pl.detailing.crm.audit.domain.FieldChange
import pl.detailing.crm.shared.ConflictException
import pl.detailing.crm.shared.EmployeeId
import pl.detailing.crm.shared.EntityNotFoundException
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import pl.detailing.crm.shared.ValidationException
import pl.detailing.crm.visit.infrastructure.DocumentStorageService
import java.time.Instant
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.Locale
import java.util.UUID

/**
 * Cykl życia listy obecności: wygeneruj → przechowaj → zatwierdź (opcjonalnie z podpisem)
 * albo usuń.
 *
 * Arkusz jest zapisywany ZAWSZE i nie znika do folderu Pobrane: w zakładce Rozliczenia
 * każdy administrator widzi, kto go wygenerował i czy ktoś go już zatwierdził. Dokument
 * kadrowy, który istnieje wyłącznie jako plik u jednej osoby, nie daje się ani
 * odtworzyć, ani sprawdzić — a to na nim opiera się rozliczenie czasu pracy.
 */
@Service
class AttendanceSheetService(
    private val generateHandler: GenerateAttendanceSheetHandler,
    private val repository: AttendanceSheetRepository,
    private val storageService: DocumentStorageService,
    private val signer: AttendanceSheetSigner,
    private val auditService: AuditService
) {
    private val logger = LoggerFactory.getLogger(AttendanceSheetService::class.java)
    private val json: ObjectMapper = jacksonObjectMapper().findAndRegisterModules()

    companion object {
        private const val MAX_HISTORY_LIMIT = 100
        private const val MAX_SIGNATURE_BYTES = 10 * 1024 * 1024
        private val MONTH_FORMAT = DateTimeFormatter.ofPattern("LLLL yyyy", Locale.forLanguageTag("pl-PL"))
    }

    /** Generuje arkusz, zapisuje plik w S3 i zwraca wiersz opisujący dokument. */
    suspend fun generate(
        studioId: StudioId,
        userId: UserId,
        userName: String?,
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

        val sheet = save(
            AttendanceSheetEntity(
                id = id,
                studioId = studioId.value,
                period = period.toString(),
                employeeIdsJson = json.writeValueAsString(employeeIds.map { it.value.toString() }),
                fileS3Key = s3Key,
                createdBy = userId.value,
                createdAt = Instant.now(),
                createdByName = userName?.trim()?.ifBlank { null }
            )
        )
        audit(
            studioId, userId, userName, AuditAction.ATTENDANCE_SHEET_GENERATED, sheet,
            listOf(
                FieldChange("period", null, sheet.period),
                FieldChange("employeeCount", null, employeeIdsOf(sheet).size.toString())
            )
        )
        return sheet
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
     * Zatwierdza arkusz: od tej chwili każdy administrator widzi, że lista jest sprawdzona
     * i gotowa dla księgowości.
     *
     * Podpis jest opcjonalny. Gdy przyjdzie, wtapia się w PDF jako osobny plik obok
     * oryginału — zatwierdzający jest zarazem „osobą potwierdzającą" ze stopki arkusza.
     * Drugi raz zatwierdzić się nie da: przy dwóch administratorach klikających naraz
     * wygrywa pierwszy, a drugi dowiaduje się, kto go uprzedził.
     */
    suspend fun approve(
        studioId: StudioId,
        userId: UserId,
        userName: String,
        sheetId: UUID,
        signatureDataUrl: String?
    ): AttendanceSheetEntity {
        val sheet = require(studioId, sheetId)
        if (sheet.status == AttendanceSheetStatus.APPROVED) throw alreadyApproved(sheet)

        // Podpis dokłada się tylko do arkusza, którego nikt jeszcze nie podpisał.
        val signaturePng = signatureDataUrl
            ?.takeIf { sheet.signedFileS3Key == null }
            ?.let { decodeSignature(it) }
        val approvedAt = Instant.now()

        if (signaturePng == null) {
            if (repository.markApproved(sheetId, studioId.value, approvedAt, userId.value, userName) == 0) {
                throw alreadyApproved(require(studioId, sheetId))
            }
        } else {
            val signedKey = uploadSigned(sheet, signaturePng, userName, approvedAt)
            val updated = repository.markApprovedWithSignature(
                sheetId, studioId.value, approvedAt, userId.value, userName, signedKey
            )
            if (updated == 0) {
                // Przegrany wyścig: po sobie sprzątamy, a zwycięzcy nie ruszamy.
                runCatching { storageService.deleteDocument(signedKey) }
                throw alreadyApproved(require(studioId, sheetId))
            }
            logger.info("Attendance sheet signed: studioId={}, sheetId={}, by={}", studioId, sheetId, userId)
        }

        val approved = require(studioId, sheetId)
        audit(
            studioId, userId, userName, AuditAction.ATTENDANCE_SHEET_APPROVED, approved,
            listOfNotNull(
                FieldChange("status", AttendanceSheetStatus.GENERATED.name, AttendanceSheetStatus.APPROVED.name),
                signaturePng?.let { FieldChange("signed", null, "true") }
            )
        )
        return approved
    }

    /**
     * Podpis bez osobnego kroku zatwierdzania — ścieżka sprzed zakładki Rozliczenia.
     * Podpis jest potwierdzeniem arkusza, więc podpisana lista jest zarazem zatwierdzona.
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
        return approve(studioId, userId, signerName, sheetId, signatureDataUrl)
    }

    /**
     * Usuwa rozliczenie razem z plikami.
     *
     * Najpierw wiersz, potem pliki: wiersz wskazujący na usunięty plik byłby martwym
     * linkiem w tabeli, a osierocony plik w S3 nikomu nie szkodzi — więc błąd przy
     * kasowaniu pliku kończy się wpisem w logu, a nie błędem dla użytkownika.
     */
    suspend fun delete(studioId: StudioId, userId: UserId, userName: String, sheetId: UUID) {
        val sheet = require(studioId, sheetId)
        if (repository.deleteByIdAndStudioId(sheetId, studioId.value) == 0) throw notFound(sheetId)

        listOfNotNull(sheet.fileS3Key, sheet.signedFileS3Key).forEach { key ->
            runCatching { storageService.deleteDocument(key) }
                .onFailure { logger.warn("Could not delete attendance sheet file {} [sheetId={}]", key, sheetId, it) }
        }
        audit(
            studioId, userId, userName, AuditAction.ATTENDANCE_SHEET_DELETED, sheet,
            listOf(
                FieldChange("period", sheet.period, null),
                FieldChange("status", sheet.status.name, null)
            )
        )
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
        repository.findByIdAndStudioId(sheetId, studioId.value) ?: throw notFound(sheetId)

    fun employeeIdsOf(entity: AttendanceSheetEntity): List<String> =
        runCatching { json.readValue<List<String>>(entity.employeeIdsJson) }.getOrDefault(emptyList())

    private fun notFound(sheetId: UUID) = EntityNotFoundException("Nie znaleziono listy obecności o id: $sheetId")

    private fun alreadyApproved(sheet: AttendanceSheetEntity) = ConflictException(
        "Ta lista obecności jest już zatwierdzona" + (sheet.approvedByName?.let { " ($it)" } ?: "") + "."
    )

    /**
     * Wtapia podpis w oryginał i zapisuje wynik pod OSOBNYM kluczem na każdą próbę:
     * dwa równoczesne podpisy nie nadpiszą sobie pliku, a przegrany wyścig kasuje tylko swój.
     */
    private suspend fun uploadSigned(
        sheet: AttendanceSheetEntity,
        signaturePng: ByteArray,
        signerName: String,
        signedAt: Instant
    ): String {
        val signedBytes = withContext(Dispatchers.IO) {
            val original = storageService.downloadBytes(sheet.fileS3Key)
            signer.sign(original, signaturePng, signerName, signedAt)
        }
        val signedKey = sheet.fileS3Key.removeSuffix(".pdf") + "-signed-" + UUID.randomUUID().toString().take(8) + ".pdf"
        storageService.uploadDocument(
            s3Key = signedKey,
            fileBytes = signedBytes,
            contentType = "application/pdf",
            metadata = mapOf("period" to sheet.period, "signed" to "true")
        )
        return signedKey
    }

    private fun audit(
        studioId: StudioId,
        userId: UserId,
        userName: String?,
        action: AuditAction,
        sheet: AttendanceSheetEntity,
        changes: List<FieldChange>
    ) = auditService.recordSync(
        AuditEvent(
            studioId = studioId,
            actor = AuditActor.employee(userId, userName),
            module = AuditModule.WORK_TIME,
            action = action,
            entityId = sheet.id.toString(),
            entityDisplayName = "Lista obecności — ${monthLabel(sheet.period)}",
            changes = changes,
            metadata = mapOf("period" to sheet.period)
        )
    )

    private fun monthLabel(period: String): String =
        runCatching { YearMonth.parse(period).format(MONTH_FORMAT) }.getOrDefault(period)

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
