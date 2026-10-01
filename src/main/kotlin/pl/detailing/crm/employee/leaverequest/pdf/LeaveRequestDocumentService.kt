package pl.detailing.crm.employee.leaverequest.pdf

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import pl.detailing.crm.employee.leave.domain.LeaveType
import pl.detailing.crm.employee.leaverequest.domain.LeaveRequestOrigin
import pl.detailing.crm.employee.leaverequest.domain.LeaveSignatureMethod
import pl.detailing.crm.employee.leaverequest.domain.RequestableLeaveTypes
import pl.detailing.crm.employee.leaverequest.infrastructure.LeaveRequestEntity
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.signing.infrastructure.AuditTrailPageGenerator
import pl.detailing.crm.signing.infrastructure.DocumentIntegrityService
import pl.detailing.crm.signing.infrastructure.SignatureCardEntry
import pl.detailing.crm.studio.logo.CompanyLogoService
import pl.detailing.crm.studio.settings.StudioSettingsRepository
import pl.detailing.crm.visit.infrastructure.DocumentStorageService
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * Pliki wniosku urlopowego: wygenerowanie szkicu (H1), wersja z podpisem pracownika (H2)
 * i dokument końcowy z decyzją i kartą podpisów (H3).
 *
 * Każda wersja to osobny obiekt w S3 pod WŁASNYM kluczem, z losowym przyrostkiem przy
 * wersjach podpisanych: dwie równoległe próby podpisu nie nadpiszą sobie pliku, a próba,
 * która przegra wyścig o zmianę statusu, kasuje tylko swój plik ([deleteQuietly]).
 * Nic nie jest nigdy nadpisywane.
 */
@Service
class LeaveRequestDocumentService(
    private val renderer: LeaveRequestPdfRenderer,
    private val stamper: LeaveRequestPdfStamper,
    private val storageService: DocumentStorageService,
    private val integrity: DocumentIntegrityService,
    private val auditTrailPageGenerator: AuditTrailPageGenerator,
    private val studioSettingsRepository: StudioSettingsRepository,
    private val companyLogoService: CompanyLogoService
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    companion object {
        const val SUBMISSION_MODE = "Samodzielnie w aplikacji, podpis na ekranie"

        /**
         * „Sposób złożenia" drukowany na wniosku. Przy ON_BEHALF mówi, kto wprowadził wniosek
         * i że pracownik podpisał go osobiście — zanim jeszcze podpisze: to opis procedury,
         * którą pracownik widzi na dokumencie, a nie relacja z jej przebiegu (ta jest na
         * karcie podpisów).
         */
        fun submissionMode(origin: LeaveRequestOrigin, createdByName: String?): String = when (origin) {
            LeaveRequestOrigin.SELF_SERVICE -> SUBMISSION_MODE
            LeaveRequestOrigin.ON_BEHALF -> "Wprowadzony przez: ${createdByName ?: "administrator"}, podpisany osobiście"
        }

        /** Treść oświadczenia, które pracownik zaznacza przed podpisem — trafia na kartę podpisów. */
        const val EMPLOYEE_DECLARATION = "Znam treść wniosku i podpisuję go."

        private const val MAX_DEVICE_LENGTH = 90
        private val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern(LeaveRequestPdfRenderer.DATE_PATTERN)
    }

    /** Wynik zapisu jednej wersji dokumentu. */
    data class StoredPdf(val s3Key: String, val sha256: String, val bytes: ByteArray)

    /** Dane pracownika i wniosku potrzebne do wydruku. */
    data class DraftContent(
        val number: String,
        val submissionMode: String,
        val employeeName: String,
        val employeeEmail: String?,
        val employeePhone: String?,
        val startDate: LocalDate,
        val endDate: LocalDate,
        val workingDays: Int,
        val leaveType: LeaveType,
        val onDemand: Boolean,
        val reason: String?
    )

    /** Generuje szkic wniosku (H1) i zapisuje go w S3. */
    suspend fun createDraft(studioId: StudioId, requestId: UUID, content: DraftContent): StoredPdf {
        val settings = studioSettingsRepository.findById(studioId.value).orElse(null)
        val bytes = renderer.render(
            LeaveRequestPdfData(
                number = content.number,
                submissionMode = content.submissionMode,
                employerName = settings?.name?.trim()?.takeIf { it.isNotBlank() } ?: "Pracodawca",
                employerAddress = listOfNotNull(
                    settings?.street?.trim()?.takeIf { it.isNotBlank() },
                    listOfNotNull(settings?.postalCode?.trim(), settings?.city?.trim())
                        .filter { it.isNotBlank() }.joinToString(" ").takeIf { it.isNotBlank() }
                ).joinToString(", ").takeIf { it.isNotBlank() },
                employerTaxId = settings?.taxId?.trim()?.takeIf { it.isNotBlank() },
                logoPng = loadLogo(studioId),
                employeeName = content.employeeName,
                employeeEmail = content.employeeEmail,
                employeePhone = content.employeePhone,
                startDate = content.startDate,
                endDate = content.endDate,
                workingDays = content.workingDays,
                leaveType = content.leaveType,
                onDemand = content.onDemand,
                reason = content.reason
            )
        )
        return store(studioId, requestId, "draft.pdf", bytes)
    }

    /** H1 → H2: wtapia podpis pracownika i zapisuje NOWY plik. */
    suspend fun storeEmployeeSigned(
        request: LeaveRequestEntity,
        draftBytes: ByteArray,
        normalizedSignature: ByteArray,
        signedAt: Instant
    ): StoredPdf {
        val signed = stamper.stampEmployeeSignature(draftBytes, normalizedSignature, signedAt)
        return store(StudioId(request.studioId), request.id, "employee-signed-${suffix()}.pdf", signed)
    }

    /**
     * Wszystko, co trafia na dokument i kartę podpisów przy decyzji. Bez podstawy
     * uprawnienia (kontrakt v2) — tę handler zapisuje w bazie i w dzienniku zdarzeń.
     */
    data class DecisionStamp(
        val approved: Boolean,
        val decidedByName: String,
        val note: String?,
        val method: LeaveSignatureMethod,
        val decidedAt: Instant,
        val ipAddress: String?,
        val userAgent: String?
    )

    /** H2 → H3: decyzja, podpis rozpatrującego i karta podpisów z oboma sesjami. */
    suspend fun storeFinal(
        request: LeaveRequestEntity,
        employeeName: String,
        employeeSignedBytes: ByteArray,
        normalizedSignature: ByteArray,
        stamp: DecisionStamp
    ): StoredPdf {
        // Podpis osobisty na cudzym urządzeniu: karta mówi wprost, czyje to urządzenie
        // i sesja — inaczej adres IP i przeglądarka przy podpisie pracownika wskazywałyby
        // na konto, które do niego nie należy, bez słowa wyjaśnienia.
        val inPersonCreator = (request.createdByName ?: "administrator")
            .takeIf { request.employeeSignatureMethod == LeaveSignatureMethod.IN_PERSON }
        val final = stamper.stampDecision(
            pdf = employeeSignedBytes,
            approved = stamp.approved,
            decidedByName = stamp.decidedByName,
            note = stamp.note,
            normalizedSignaturePng = normalizedSignature,
            decidedAt = stamp.decidedAt
        ) { document ->
            auditTrailPageGenerator.appendSignatureCard(
                document = document,
                documentName = documentName(request),
                identification = listOf(
                    "Numer wniosku" to request.number,
                    "Identyfikator wniosku" to request.id.toString(),
                    "Pracownik" to employeeName,
                    "Termin" to "${request.startDate.format(DATE)} – ${request.endDate.format(DATE)}, " +
                        "dni robocze: ${request.workingDays}",
                    "Decyzja" to if (stamp.approved) "zgoda na urlop" else "brak zgody",
                    "Skrót SHA-256 wniosku bez podpisów" to request.documentSha256
                ) + listOfNotNull(
                    inPersonCreator?.let { "Osoba wprowadzająca wniosek" to "$it, w imieniu pracownika" }
                ),
                signatures = listOf(
                    SignatureCardEntry(
                        role = if (inPersonCreator != null) "pracownik, złożenie wniosku osobiście" else "pracownik, złożenie wniosku",
                        signerName = employeeName,
                        method = inPersonCreator?.let { inPersonMethod(it) }
                            ?: methodLabel(request.employeeSignatureMethod ?: LeaveSignatureMethod.DEVICE_DRAWN),
                        signedAt = request.employeeSignedAt ?: stamp.decidedAt,
                        ipAddress = request.employeeSignerIp,
                        device = request.employeeSignerUserAgent?.take(MAX_DEVICE_LENGTH),
                        documentSha256 = request.documentSha256,
                        documentLabel = "wniosek bez podpisów",
                        declaration = EMPLOYEE_DECLARATION
                    ),
                    SignatureCardEntry(
                        role = "osoba rozpatrująca, decyzja pracodawcy",
                        signerName = stamp.decidedByName,
                        method = methodLabel(stamp.method),
                        signedAt = stamp.decidedAt,
                        ipAddress = stamp.ipAddress,
                        device = stamp.userAgent?.take(MAX_DEVICE_LENGTH),
                        documentSha256 = request.employeeSignedSha256 ?: "—",
                        documentLabel = "wniosek z podpisem pracownika"
                    )
                ),
                integrityNote = "Każdy podpis został powiązany ze skrótem SHA-256 dokumentu wyświetlonego osobie " +
                    "podpisującej (zasada WYSIWYS), a zgodność skrótu przesłanego z urządzenia ze skrótem " +
                    "dokumentu w magazynie została zweryfikowana na serwerze przed złożeniem podpisu. " +
                    "Każda sesja podpisu miała jednorazowy token, zużyty przy podpisie. Podpis odręczny " +
                    "przetworzono wyłącznie w pamięci serwera i scalono z dokumentem; podpis zapisany " +
                    "w profilu użytkownika jest oznaczony powyżej jako „zapisany podpis”. Wcześniejsze " +
                    "wersje dokumentu są przechowywane bez zmian." +
                    (inPersonCreator?.let {
                        " Pracownik podpisał wniosek osobiście na urządzeniu osoby, która go wprowadziła ($it); " +
                            "adres IP i urządzenie przy jego podpisie dotyczą tego urządzenia i tej sesji."
                    } ?: "")
            )
        }
        return store(StudioId(request.studioId), request.id, "final-${suffix()}.pdf", final)
    }

    fun download(s3Key: String): ByteArray = storageService.downloadBytes(s3Key)

    fun sha256(bytes: ByteArray): String = integrity.sha256Hex(bytes)

    /** Kasuje plik bez rzucania — osierocony plik w S3 nikomu nie szkodzi, błąd tutaj nie może maskować właściwego. */
    suspend fun deleteQuietly(s3Key: String) {
        runCatching { storageService.deleteDocument(s3Key) }
            .onFailure { logger.warn("Could not delete leave request file {}", s3Key, it) }
    }

    fun documentName(request: LeaveRequestEntity): String =
        "Wniosek urlopowy ${request.number} (${RequestableLeaveTypes.label(request.leaveType, request.onDemand)})"

    private fun methodLabel(method: LeaveSignatureMethod): String = when (method) {
        LeaveSignatureMethod.DEVICE_DRAWN -> "podpis odręczny złożony na ekranie urządzenia"
        LeaveSignatureMethod.SAVED_SIGNATURE -> "zapisany podpis z profilu użytkownika, użyty świadomie przy decyzji"
        LeaveSignatureMethod.IN_PERSON -> inPersonMethod(null)
    }

    private fun inPersonMethod(creator: String?): String =
        "podpis odręczny złożony osobiście na ekranie urządzenia osoby wprowadzającej wniosek" +
            (creator?.let { " ($it)" } ?: "")

    private suspend fun store(studioId: StudioId, requestId: UUID, fileName: String, bytes: ByteArray): StoredPdf {
        val key = "${studioId.value}/leave-requests/$requestId/$fileName"
        storageService.uploadDocument(
            s3Key = key,
            fileBytes = bytes,
            contentType = "application/pdf",
            metadata = mapOf("studioId" to studioId.value.toString(), "leaveRequestId" to requestId.toString())
        )
        return StoredPdf(key, integrity.sha256Hex(bytes), bytes)
    }

    private fun suffix(): String = UUID.randomUUID().toString().take(8)

    private fun loadLogo(studioId: StudioId): ByteArray? = runCatching {
        companyLogoService.loadDocumentLogo(studioId.value)?.printPng
    }.onFailure {
        logger.warn("Nie udało się wczytać logo studia na wniosek urlopowy: ${it.message}")
    }.getOrNull()
}
