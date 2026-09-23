package pl.detailing.crm.worktime.attendance

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import pl.detailing.crm.communication.DeliveryPolicy
import pl.detailing.crm.communication.OutboundCommunicationGateway
import pl.detailing.crm.shared.ConflictException
import pl.detailing.crm.shared.InsufficientSmsCreditsException
import pl.detailing.crm.shared.SignatureRequestId
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import pl.detailing.crm.shared.ValidationException
import pl.detailing.crm.shared.isValidPolishPhone
import pl.detailing.crm.shared.normalizePolishPhone
import pl.detailing.crm.signing.SignatureRequestLifecycleService
import pl.detailing.crm.signing.domain.SignatureAuditEventType
import pl.detailing.crm.signing.domain.SignatureChannel
import pl.detailing.crm.signing.domain.SignatureRequest
import pl.detailing.crm.signing.domain.SignatureRequestStatus
import pl.detailing.crm.signing.domain.SignatureSubject
import pl.detailing.crm.signing.infrastructure.AuditPageSubject
import pl.detailing.crm.signing.infrastructure.AuditTrailPageGenerator
import pl.detailing.crm.signing.infrastructure.DocumentIntegrityService
import pl.detailing.crm.signing.infrastructure.SignatureAuditEventEntity
import pl.detailing.crm.signing.infrastructure.SignatureAuditTrailService
import pl.detailing.crm.signing.infrastructure.SignatureRequestEntity
import pl.detailing.crm.signing.infrastructure.SignatureRequestRepository
import pl.detailing.crm.signing.infrastructure.TabletSessionService
import pl.detailing.crm.signing.newSigningLinkToken
import pl.detailing.crm.signing.signingLinkUrl
import pl.detailing.crm.subscription.entitlement.capability.CapabilityKey
import pl.detailing.crm.subscription.entitlement.capability.CapabilityService
import pl.detailing.crm.user.infrastructure.UserRepository
import pl.detailing.crm.visit.infrastructure.DocumentStorageService
import pl.detailing.crm.visitcard.VisitCardProperties
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Podpis listy obecności na tablecie studia albo na telefonie zatwierdzającego.
 *
 * Ten sam tor co podpis protokołu wizyty: dokument jest przypięty skrótem SHA-256 w chwili
 * prośby, urządzenie dostaje jednorazowy token, każde zdarzenie trafia do łańcucha audytu,
 * a podpis wraca do arkusza razem z kartą podpisu. Aplikacja tabletu i strona podpisu
 * z SMS-a nie odróżniają listy od protokołu - widzą nazwę dokumentu, osobę podpisującą,
 * oświadczenie i bajty PDF.
 *
 * Różnice wobec protokołu:
 * - podpisuje ZATWIERDZAJĄCY, nie klient: imię, nazwisko i numer telefonu biorą się
 *   z konta osoby, która prosi o podpis - nigdy z żądania,
 * - SMS idzie na jej własny numer stałą treścią (szablony SMS studia są pisane do
 *   klientów) i nie trafia do dziennika komunikacji z klientami,
 * - po podpisie lista jest zatwierdzona.
 */
@Service
class AttendanceSheetRemoteSigning(
    private val attendanceSheetService: AttendanceSheetService,
    private val signer: AttendanceSheetSigner,
    private val storageService: DocumentStorageService,
    private val userRepository: UserRepository,
    private val tabletSessionService: TabletSessionService,
    private val signatureRequestRepository: SignatureRequestRepository,
    private val documentIntegrityService: DocumentIntegrityService,
    private val auditTrailService: SignatureAuditTrailService,
    private val auditTrailPageGenerator: AuditTrailPageGenerator,
    private val lifecycleService: SignatureRequestLifecycleService,
    private val capabilityService: CapabilityService,
    private val communicationGateway: OutboundCommunicationGateway,
    private val visitCardProperties: VisitCardProperties,
    private val transactionTemplate: TransactionTemplate,
    @Value("\${signing.request.ttl-minutes:15}") private val tabletTtlMinutes: Long,
    @Value("\${signing.request.sms-ttl-minutes:60}") private val smsTtlMinutes: Long
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    /** Czym można poprosić o podpis: sparowane tablety studia i numer z konta (zamaskowany). */
    fun options(studioId: StudioId, userId: UserId): AttendanceSigningOptions =
        AttendanceSigningOptions(
            tablets = tabletSessionService.listTablets(studioId.value.toString())
                .map { AttendanceSigningTablet(tabletId = it.tabletId, deviceName = it.deviceName) },
            phone = ownPhone(studioId, userId)?.let(::maskPhone)
        )

    /**
     * „Wyświetl podpis na tablecie" / „Wyślij podpis na mój numer telefonu".
     *
     * @param tabletId wybrany tablet; null = dowolny sparowany tablet studia.
     */
    suspend fun request(
        studioId: StudioId,
        userId: UserId,
        userName: String,
        sheetId: UUID,
        channel: SignatureChannel,
        tabletId: String?,
        ipAddress: String?
    ): SignatureRequest = withContext(Dispatchers.IO) {
        // Moduły jak przy protokołach: tablet to podpisy elektroniczne, link SMS-em -
        // podpisy i komunikacja naraz.
        capabilityService.requireCapability(
            studioId,
            when (channel) {
                SignatureChannel.TABLET -> CapabilityKey.SIGNATURE_LOCAL
                SignatureChannel.SMS_LINK -> CapabilityKey.SIGNATURE_REMOTE_REQUEST
            }
        )

        val sheet = attendanceSheetService.require(studioId, sheetId)
        if (sheet.status == AttendanceSheetStatus.APPROVED) {
            throw ConflictException("Ta lista obecności jest już zatwierdzona" + (sheet.approvedByName?.let { " ($it)" } ?: "") + ".")
        }
        if (sheet.signedFileS3Key != null) throw ValidationException("Ten arkusz jest już podpisany.")
        if (activeRequests(studioId, sheetId).isNotEmpty()) {
            throw ConflictException("Ta lista obecności czeka już na podpis. Anuluj poprzednią prośbę, zanim wyślesz nową.")
        }

        val routedTablet = if (channel == SignatureChannel.TABLET) resolveTablet(studioId, tabletId) else null
        val phone = if (channel == SignatureChannel.SMS_LINK) {
            ownPhone(studioId, userId)
                ?: throw ValidationException("Na Twoim koncie nie ma numeru telefonu - uzupełnij go w swoim profilu.")
        } else null

        // WYSIWYS: skrót dokładnie tych bajtów, które zobaczy podpisujący.
        val documentBytes = storageService.downloadBytes(sheet.fileS3Key)
        val documentSha256 = documentIntegrityService.sha256Hex(documentBytes)
        val documentName = attendanceSheetService.documentNameOf(sheet)
        val ttl = Duration.ofMinutes(if (channel == SignatureChannel.SMS_LINK) smsTtlMinutes else tabletTtlMinutes)

        val now = Instant.now()
        val request = SignatureRequest(
            id = SignatureRequestId.random(),
            studioId = studioId,
            subject = SignatureSubject.AttendanceSheet(sheetId),
            tabletId = routedTablet,
            channel = channel,
            signerPhone = phone,
            linkToken = if (channel == SignatureChannel.SMS_LINK) newSigningLinkToken() else null,
            status = SignatureRequestStatus.PENDING_DISPLAY,
            documentS3Key = sheet.fileS3Key,
            documentSha256 = documentSha256,
            documentName = documentName,
            signerName = userName,
            declarationText = "Zatwierdzam listę obecności za ${attendanceSheetService.monthLabelOf(sheet)} " +
                "i potwierdzam jej zgodność z ewidencją czasu pracy.",
            requestedBy = userId,
            requestedByName = userName,
            createdAt = now,
            expiresAt = now.plus(ttl),
            displayedAt = null,
            declarationAcceptedAt = null,
            signedAt = null,
            completedAt = null,
            signerIpAddress = null,
            signerDevice = null,
            signedPdfS3Key = null,
            failureReason = null,
            updatedAt = now
        )

        // Jedna transakcja jak w RequestSignatureHandler: SMS, którego nie da się wysłać,
        // cofa też żądanie - inaczej lista wisiałaby „czeka na podpis" do końca ważności.
        transactionTemplate.execute {
            signatureRequestRepository.save(SignatureRequestEntity.fromDomain(request))
            documentIntegrityService.issueChallenge(request.id.value, ttl)
            documentIntegrityService.cacheDocument(request.id.value, documentBytes, ttl)
            auditTrailService.append(
                requestId = request.id.value,
                studioId = studioId.value,
                eventType = SignatureAuditEventType.REQUEST_CREATED,
                actor = "$userName [${userId.value}]",
                ipAddress = ipAddress,
                details = "dokument=$documentName, sha256=$documentSha256, lista obecności=${sheet.period}, kanał=$channel"
            )
            if (channel == SignatureChannel.SMS_LINK) sendLinkSms(request, sheet)
        }

        logger.info(
            "Attendance sheet signature request {} created: sheetId={}, channel={}, sha256={}",
            request.id, sheetId, channel, documentSha256
        )
        request
    }

    /**
     * Najnowsza prośba o podpis tej listy - także zakończona: okno zatwierdzania po
     * ponownym otwarciu wie z niej, czy czekać dalej, czy pokazać wynik.
     */
    fun latest(studioId: StudioId, sheetId: UUID): SignatureRequest? {
        attendanceSheetService.require(studioId, sheetId)
        val request = signatureRequestRepository
            .findFirstByStudioIdAndAttendanceSheetIdOrderByCreatedAtDesc(studioId.value, sheetId)
            ?.toDomain()
            ?: return null
        return if (lifecycleService.isEffectivelyExpired(request)) lifecycleService.markExpired(request) else request
    }

    /** Anuluje czekającą prośbę (np. podpis jednak na tym urządzeniu). */
    fun cancel(studioId: StudioId, sheetId: UUID, userId: UserId, userName: String, ipAddress: String?): SignatureRequest? {
        attendanceSheetService.require(studioId, sheetId)
        return activeRequests(studioId, sheetId).firstOrNull()?.let { pending ->
            lifecycleService.cancel(
                studioId = studioId,
                requestId = SignatureRequestId(pending.id),
                cancelledBy = "$userName [${userId.value}]",
                ipAddress = ipAddress
            )
        }
    }

    /**
     * Podpis przeszedł weryfikację w SubmitSignatureHandler: wtapia go w pole podpisu
     * arkusza, dokłada kartę podpisu i zatwierdza listę. Zwraca klucz podpisanego pliku.
     *
     * @param documentBytes dokładnie te bajty, które podpisujący widział (skrót sprawdzony).
     * @param request żądanie z adresem IP, urządzeniem i chwilą podpisu - na kartę podpisu.
     */
    suspend fun completeSigning(
        request: SignatureRequest,
        sheetId: UUID,
        documentBytes: ByteArray,
        normalizedSignature: ByteArray,
        signedAt: Instant,
        auditEvents: List<SignatureAuditEventEntity>
    ): String {
        val sheet = attendanceSheetService.require(request.studioId, sheetId)
        val signedPdf = signer.signNormalized(documentBytes, normalizedSignature, request.signerName, signedAt) { document ->
            auditTrailPageGenerator.appendAuditPage(
                document, request, auditEvents,
                AuditPageSubject(
                    documentIdLabel = "Identyfikator dokumentu (listy obecności)",
                    documentId = sheet.id.toString(),
                    contextLabel = "Okres rozliczenia",
                    contextValue = attendanceSheetService.monthLabelOf(sheet)
                )
            )
        }
        val approved = attendanceSheetService.approveWithSignedDocument(
            studioId = request.studioId,
            sheetId = sheetId,
            approverId = request.requestedBy,
            approverName = request.requestedByName,
            signedPdf = signedPdf,
            signedAt = signedAt,
            channel = request.channel.name
        )
        return checkNotNull(approved.signedFileS3Key) { "Zatwierdzona lista $sheetId nie ma podpisanego pliku" }
    }

    private fun activeRequests(studioId: StudioId, sheetId: UUID): List<SignatureRequestEntity> =
        signatureRequestRepository.findActiveForAttendanceSheets(studioId.value, listOf(sheetId), Instant.now())

    /** Wybrany tablet musi być sparowany z tym studiem - inaczej prośba nigdy by się nie wyświetliła. */
    private fun resolveTablet(studioId: StudioId, tabletId: String?): String? {
        val tablets = tabletSessionService.listTablets(studioId.value.toString())
        if (tablets.isEmpty()) throw ValidationException("Brak sparowanego tabletu - sparuj tablet w Ustawieniach.")
        val wanted = tabletId?.trim()?.ifBlank { null } ?: return null
        if (tablets.none { it.tabletId == wanted }) throw ValidationException("Wybrany tablet nie jest już sparowany.")
        return wanted
    }

    /** Numer z konta osoby zalogowanej - jedyny, na który ta prośba może pójść. */
    private fun ownPhone(studioId: StudioId, userId: UserId): String? =
        userRepository.findByIdAndStudioId(userId.value, studioId.value)
            ?.phoneNumber
            ?.takeIf { it.isNotBlank() && isValidPolishPhone(it) }
            ?.let(::normalizePolishPhone)

    private fun sendLinkSms(request: SignatureRequest, sheet: AttendanceSheetEntity) {
        val phone = requireNotNull(request.signerPhone)
        val link = signingLinkUrl(visitCardProperties.frontendBaseUrl, requireNotNull(request.linkToken))
        val message = "Lista obecności za ${attendanceSheetService.monthLabelOf(sheet)} czeka na Twój podpis: $link"

        val result = try {
            // IMMEDIATE: zatwierdzający czeka na link teraz, a token ma krótką ważność.
            communicationGateway.sendTransactionalSms(
                request.studioId.value, phone, message, delivery = DeliveryPolicy.IMMEDIATE
            )
        } catch (e: InsufficientSmsCreditsException) {
            abandon(request)
            throw e
        }
        if (!result.success) {
            abandon(request)
            throw ValidationException("Nie udało się wysłać SMS-a z linkiem do podpisu: ${result.errorMessage ?: "błąd dostawcy"}")
        }
        logger.info("Attendance signing link SMS sent for request {} (phone ends with …{})", request.id, phone.takeLast(3))
    }

    /** Żądanie cofa transakcja - token i kopia dokumentu w Redisie nie mogą go przeżyć. */
    private fun abandon(request: SignatureRequest) {
        documentIntegrityService.invalidateChallenge(request.id.value)
        documentIntegrityService.evictCachedDocument(request.id.value)
    }

    private fun maskPhone(phone: String): String = "+48 ••• ••• ${phone.takeLast(3)}"
}

data class AttendanceSigningOptions(
    val tablets: List<AttendanceSigningTablet>,
    /** Numer z konta, zamaskowany; null, gdy konto nie ma poprawnego numeru. */
    val phone: String?
)

data class AttendanceSigningTablet(
    val tabletId: String,
    val deviceName: String
)
