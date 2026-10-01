package pl.detailing.crm.employee.leaverequest.submit

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import pl.detailing.crm.audit.domain.AuditAction
import pl.detailing.crm.audit.domain.AuditActor
import pl.detailing.crm.audit.domain.AuditEvent
import pl.detailing.crm.audit.domain.AuditModule
import pl.detailing.crm.audit.domain.AuditService
import pl.detailing.crm.audit.domain.FieldChange
import pl.detailing.crm.employee.infrastructure.EmployeeEntity
import pl.detailing.crm.employee.infrastructure.EmployeeRepository
import pl.detailing.crm.employee.leaverequest.domain.LeaveRequestDraft
import pl.detailing.crm.employee.leaverequest.domain.LeaveRequestStatus
import pl.detailing.crm.employee.leaverequest.domain.LeaveRequestSubmittedEvent
import pl.detailing.crm.employee.leaverequest.domain.LeaveRequestValidator
import pl.detailing.crm.employee.leaverequest.domain.LeaveSignatureMethod
import pl.detailing.crm.employee.leaverequest.domain.RequestableLeaveTypes
import pl.detailing.crm.employee.leaverequest.infrastructure.LeaveRequestEntity
import pl.detailing.crm.employee.leaverequest.infrastructure.LeaveRequestRepository
import pl.detailing.crm.employee.leaverequest.pdf.LeaveRequestDocumentService
import pl.detailing.crm.employee.leaverequest.query.LeaveRequestAccess
import pl.detailing.crm.employee.leaverequest.session.LeaveSigningSessions
import pl.detailing.crm.employee.leaverequest.session.SignatureImagePayload
import pl.detailing.crm.shared.ConflictException
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import pl.detailing.crm.shared.ValidationException
import pl.detailing.crm.signing.infrastructure.SignatureImageProcessor
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

data class SubmitLeaveRequestCommand(
    val studioId: StudioId,
    val userId: UserId,
    val userName: String,
    val requestId: UUID,
    val signatureImageBase64: String?,
    val documentSha256: String?,
    val challenge: String?,
    val declarationAccepted: Boolean,
    val ipAddress: String?,
    val userAgent: String?
)

/**
 * Pracownik podpisuje szkic i składa wniosek (DRAFT → PENDING).
 *
 * Kolejność jest zabezpieczeniem, nie kosmetyką:
 *  1. token sesji zużywany ATOMOWO — przechwyconego pakietu nie da się wysłać drugi raz;
 *  2. skrót od klienta = skrót zapisany przy wygenerowaniu (H1) = skrót bajtów pobranych
 *     ponownie z S3 — pracownik podpisuje dokładnie to, co widział (WYSIWYS);
 *  3. obraz podpisu normalizowany i wtapiany wyłącznie w pamięci, potem zerowany;
 *  4. wersja z podpisem (H2) to NOWY plik; H1 zostaje nietknięty;
 *  5. kolizje terminu sprawdzane jeszcze raz pod blokadą pracownika — szkice się nie
 *     blokują, więc dwa szkice na ten sam termin mogły powstać, a złożyć można jeden.
 *
 * Dwa wejścia, jedna ścieżka podpisu ([sign]): [handle] — pracownik na własnym koncie
 * (DEVICE_DRAWN), [handleInPerson] — pracownik podpisuje osobiście na urządzeniu
 * administratora, który wprowadził wniosek w jego imieniu (IN_PERSON). Kontrole WYSIWYS,
 * tokenu i kolizji nie mogą się różnić między nimi: to ten sam podpis pod tym samym
 * oświadczeniem, inna jest tylko sesja.
 */
@Service
class SubmitLeaveRequestHandler(
    private val access: LeaveRequestAccess,
    private val employeeRepository: EmployeeRepository,
    private val leaveRequestRepository: LeaveRequestRepository,
    private val validator: LeaveRequestValidator,
    private val sessions: LeaveSigningSessions,
    private val documents: LeaveRequestDocumentService,
    private val signatureImageProcessor: SignatureImageProcessor,
    private val auditService: AuditService,
    private val eventPublisher: ApplicationEventPublisher,
    private val transactionTemplate: TransactionTemplate
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val warsaw = ZoneId.of("Europe/Warsaw")

    /** Samoobsługa: [SubmitLeaveRequestCommand.userId] to konto pracownika. */
    suspend fun handle(command: SubmitLeaveRequestCommand): LeaveRequestEntity = withContext(Dispatchers.IO) {
        val employee = access.employeeOf(command.studioId, command.userId)
        val request = access.ownRequest(command.studioId, employee.id, command.requestId)
        sign(command, employee, request, LeaveSignatureMethod.DEVICE_DRAWN)
    }

    /**
     * Wniosek ON_BEHALF: [SubmitLeaveRequestCommand.userId] to administrator, który go
     * wprowadził, a podpis składa osobiście pracownik na jego urządzeniu. Adres IP
     * i przeglądarka są więc urządzenia administratora — i tak opisuje je karta podpisów.
     */
    suspend fun handleInPerson(command: SubmitLeaveRequestCommand): LeaveRequestEntity = withContext(Dispatchers.IO) {
        val request = access.onBehalfRequest(command.studioId, command.requestId, command.userId)
        // Ponownie „nie dla siebie": konto mogło zostać powiązane z pracownikiem po utworzeniu szkicu.
        val employee = access.employeeForOnBehalf(command.studioId, request.employeeId, command.userId)
        sign(command, employee, request, LeaveSignatureMethod.IN_PERSON)
    }

    private suspend fun sign(
        command: SubmitLeaveRequestCommand,
        employee: EmployeeEntity,
        request: LeaveRequestEntity,
        method: LeaveSignatureMethod
    ): LeaveRequestEntity {
        val inPerson = method == LeaveSignatureMethod.IN_PERSON
        if (request.status != LeaveRequestStatus.DRAFT) throw alreadySubmitted(request)

        if (!command.declarationAccepted) {
            throw ValidationException(
                "Zaznacz oświadczenie „${LeaveRequestDocumentService.EMPLOYEE_DECLARATION}”, żeby podpisać wniosek",
                field = "declarationAccepted"
            )
        }
        val clientSha256 = command.documentSha256?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw ValidationException("Brak skrótu podpisywanego dokumentu", field = "documentSha256")
        val challenge = command.challenge?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw ValidationException("Brak tokenu sesji podpisu", field = "challenge")
        val rawSignature = SignatureImagePayload.decode(
            command.signatureImageBase64, "signatureImageBase64", "Podpisz wniosek, żeby go wysłać"
        )

        // Jedna chwila podpisu: ta sama na dokumencie (metryczka i pole daty) i w bazie.
        val signedAt = Instant.now()
        var normalized: ByteArray? = null
        val stored = try {
            sessions.consumeEmployee(request.id, challenge)
            val draftBytes = documents.download(request.documentS3Key)
            sessions.verifyDocument(clientSha256, request.documentSha256, draftBytes)
            normalized = signatureImageProcessor.normalizeToTransparentPng(rawSignature)
            documents.storeEmployeeSigned(request, draftBytes, normalized, signedAt)
        } finally {
            // Podpis istnieje poza PDF-em tylko przez czas tego żądania — oba bufory zerujemy
            // bez względu na wynik.
            signatureImageProcessor.wipe(rawSignature)
            signatureImageProcessor.wipe(normalized)
        }

        try {
            transactionTemplate.executeWithoutResult {
                employeeRepository.lockForUpdate(employee.id, command.studioId.value)
                val draft = LeaveRequestDraft(request.leaveType, request.onDemand, request.startDate, request.endDate, request.reason)
                validator.checkTerm(draft, LocalDate.now(warsaw))
                validator.checkAgainstExisting(command.studioId.value, employee.id, request.id, draft, onBehalf = inPerson)

                val updated = leaveRequestRepository.markSubmitted(
                    id = request.id,
                    studioId = command.studioId.value,
                    signedAt = signedAt,
                    method = method,
                    pdfKey = stored.s3Key,
                    sha256 = stored.sha256,
                    ip = command.ipAddress?.take(45),
                    userAgent = command.userAgent?.take(500)
                )
                if (updated == 0) {
                    throw alreadySubmitted(leaveRequestRepository.findByIdAndStudioId(request.id, command.studioId.value) ?: request)
                }
                // W transakcji, żeby powiadomienie wyszło dopiero po commicie (AFTER_COMMIT).
                eventPublisher.publishEvent(
                    LeaveRequestSubmittedEvent(
                        studioId = command.studioId,
                        requestId = request.id,
                        number = request.number,
                        employeeUserId = request.employeeUserId?.let(::UserId),
                        createdByUserId = UserId(request.createdBy),
                        employeeName = "${employee.firstName} ${employee.lastName}".trim(),
                        kindLabel = RequestableLeaveTypes.label(request.leaveType, request.onDemand),
                        startDate = request.startDate,
                        endDate = request.endDate,
                        workingDays = request.workingDays
                    )
                )
            }
        } catch (e: Exception) {
            // Przegrany wyścig albo termin zajęty w międzyczasie: kasujemy tylko własny plik.
            documents.deleteQuietly(stored.s3Key)
            throw e
        }

        val submitted = leaveRequestRepository.findByIdAndStudioId(request.id, command.studioId.value)
            ?: throw ConflictException("Nie udało się złożyć wniosku — spróbuj ponownie")
        auditService.recordSync(
            AuditEvent(
                studioId = command.studioId,
                actor = AuditActor.employee(command.userId, command.userName),
                module = AuditModule.EMPLOYEE,
                action = AuditAction.LEAVE_REQUESTED,
                entityId = employee.id.toString(),
                entityDisplayName = "${employee.firstName} ${employee.lastName}".trim(),
                changes = listOf(
                    FieldChange("status", LeaveRequestStatus.DRAFT.name, LeaveRequestStatus.PENDING.name),
                    FieldChange("leave", null, "${submitted.leaveType} ${submitted.startDate} – ${submitted.endDate}")
                ),
                metadata = mapOf(
                    "leaveRequestId" to submitted.id.toString(),
                    "number" to submitted.number,
                    "workingDays" to submitted.workingDays.toString(),
                    "documentSha256" to (submitted.employeeSignedSha256 ?: ""),
                    "origin" to submitted.origin.name,
                    "signatureMethod" to method.name
                )
            )
        )
        logger.info(
            "Leave request submitted: studioId={}, requestId={}, number={}, method={}",
            command.studioId, request.id, request.number, method
        )
        return submitted
    }

    private fun alreadySubmitted(request: LeaveRequestEntity) = ConflictException(
        when (request.status) {
            LeaveRequestStatus.WITHDRAWN -> "Ten wniosek został wycofany"
            LeaveRequestStatus.DRAFT -> "Nie udało się złożyć wniosku — spróbuj ponownie"
            else -> "Ten wniosek został już złożony"
        }
    )
}
