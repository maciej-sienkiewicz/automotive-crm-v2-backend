package pl.detailing.crm.worktime.attendance

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.apache.pdfbox.pdmodel.PDDocument
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.transaction.support.TransactionCallback
import org.springframework.transaction.support.TransactionTemplate
import pl.detailing.crm.communication.DeliveryPolicy
import pl.detailing.crm.communication.OutboundCommunicationGateway
import pl.detailing.crm.shared.ConflictException
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import pl.detailing.crm.shared.ValidationException
import pl.detailing.crm.signing.SignatureRequestLifecycleService
import pl.detailing.crm.signing.domain.SignatureAuditEventType
import pl.detailing.crm.signing.domain.SignatureChannel
import pl.detailing.crm.signing.domain.SignatureSubject
import pl.detailing.crm.signing.infrastructure.AuditPageSubject
import pl.detailing.crm.signing.infrastructure.AuditTrailPageGenerator
import pl.detailing.crm.signing.infrastructure.DocumentIntegrityService
import pl.detailing.crm.signing.infrastructure.SignatureAuditTrailService
import pl.detailing.crm.signing.infrastructure.SignatureRequestEntity
import pl.detailing.crm.signing.infrastructure.SignatureRequestRepository
import pl.detailing.crm.signing.infrastructure.TabletInfo
import pl.detailing.crm.signing.infrastructure.TabletSessionService
import pl.detailing.crm.signing.signatureRequest
import pl.detailing.crm.smscampaigns.provider.SmsDeliveryResult
import pl.detailing.crm.subscription.entitlement.capability.CapabilityKey
import pl.detailing.crm.subscription.entitlement.capability.CapabilityService
import pl.detailing.crm.user.infrastructure.UserEntity
import pl.detailing.crm.user.infrastructure.UserRepository
import pl.detailing.crm.visit.infrastructure.DocumentStorageService
import pl.detailing.crm.visitcard.VisitCardProperties
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Lista obecności podpisywana na tablecie studia albo na telefonie zatwierdzającego.
 * Podpisuje osoba zalogowana - jej nazwisko i numer biorą się z konta, nigdy z żądania -
 * a po podpisie lista jest zatwierdzona.
 */
class AttendanceSheetRemoteSigningTest {

    private val studioId = StudioId.random()
    private val userId = UserId.random()
    private val sheetId = UUID.randomUUID()
    private val originalKey = "${studioId.value}/attendance-sheets/2026-09/$sheetId.pdf"
    private val pdf = byteArrayOf(1, 2, 3)

    private val sheets = mockk<AttendanceSheetService>()
    private val signer = mockk<AttendanceSheetSigner>()
    private val storage = mockk<DocumentStorageService>()
    private val users = mockk<UserRepository>()
    private val tablets = mockk<TabletSessionService>()
    private val requests = mockk<SignatureRequestRepository>()
    private val integrity = mockk<DocumentIntegrityService>(relaxed = true)
    private val auditTrail = mockk<SignatureAuditTrailService>(relaxed = true)
    private val auditPage = mockk<AuditTrailPageGenerator>(relaxed = true)
    private val lifecycle = mockk<SignatureRequestLifecycleService>()
    private val capabilities = mockk<CapabilityService>(relaxed = true)
    private val gateway = mockk<OutboundCommunicationGateway>()
    private val transactions = mockk<TransactionTemplate>()

    private val signing = AttendanceSheetRemoteSigning(
        sheets, signer, storage, users, tablets, requests, integrity, auditTrail, auditPage, lifecycle,
        capabilities, gateway, VisitCardProperties(frontendBaseUrl = "https://app.detailboost.pl/"), transactions,
        tabletTtlMinutes = 15, smsTtlMinutes = 60
    )

    private val saved = slot<SignatureRequestEntity>()

    init {
        every { transactions.execute(any<TransactionCallback<Any?>>()) } answers {
            firstArg<TransactionCallback<Any?>>().doInTransaction(mockk(relaxed = true))
        }
        every { sheets.require(studioId, sheetId) } returns sheet()
        every { sheets.documentNameOf(any()) } returns "Lista obecności — wrzesień 2026"
        every { sheets.monthLabelOf(any()) } returns "wrzesień 2026"
        every { storage.downloadBytes(originalKey) } returns pdf
        every { integrity.sha256Hex(pdf) } returns "f".repeat(64)
        every { requests.findActiveForAttendanceSheets(studioId.value, listOf(sheetId), any()) } returns emptyList()
        every { requests.save(capture(saved)) } answers { firstArg() }
        every { tablets.listTablets(studioId.value.toString()) } returns listOf(tablet("t-1", "Recepcja"))
        every { users.findByIdAndStudioId(userId.value, studioId.value) } returns user(phone = "512 345 678")
    }

    private fun sheet(status: AttendanceSheetStatus = AttendanceSheetStatus.GENERATED, signedKey: String? = null) =
        AttendanceSheetEntity(
            id = sheetId,
            studioId = studioId.value,
            period = "2026-09",
            employeeIdsJson = "[]",
            fileS3Key = originalKey,
            signedFileS3Key = signedKey,
            createdBy = UUID.randomUUID(),
            createdAt = Instant.parse("2026-09-23T10:00:00Z"),
            status = status,
            approvedByName = if (status == AttendanceSheetStatus.APPROVED) "Anna Nowak" else null
        )

    private fun tablet(id: String, name: String) = TabletInfo(id, name, Instant.now(), null)

    private fun user(phone: String): UserEntity = mockk { every { phoneNumber } returns phone }

    private fun request(channel: SignatureChannel, tabletId: String? = null) = runBlocking {
        signing.request(studioId, userId, "Jan Kowalski", sheetId, channel, tabletId, "10.0.0.1")
    }

    @Test
    fun `tablet - dokument przypiety skrotem, podpisuje zatwierdzajacy, bez SMS-a`() {
        val request = request(SignatureChannel.TABLET, tabletId = "t-1")

        verify { capabilities.requireCapability(studioId, CapabilityKey.SIGNATURE_LOCAL) }
        assertEquals(SignatureSubject.AttendanceSheet(sheetId), request.subject)
        assertEquals("t-1", request.tabletId)
        assertEquals("Jan Kowalski", request.signerName)
        assertEquals("Jan Kowalski", request.requestedByName)
        assertEquals("f".repeat(64), request.documentSha256)
        assertEquals(originalKey, request.documentS3Key)
        assertTrue("wrzesień 2026" in request.declarationText, request.declarationText)
        assertNull(request.linkToken)
        assertEquals(Duration.ofMinutes(15), Duration.between(request.createdAt, request.expiresAt))

        assertEquals(sheetId, saved.captured.attendanceSheetId)
        assertNull(saved.captured.visitId)
        verify { integrity.issueChallenge(request.id.value, Duration.ofMinutes(15)) }
        verify { integrity.cacheDocument(request.id.value, pdf, Duration.ofMinutes(15)) }
        verify {
            auditTrail.append(request.id.value, studioId.value, SignatureAuditEventType.REQUEST_CREATED, any(), "10.0.0.1", any(), any(), any())
        }
        verify(exactly = 0) { gateway.sendTransactionalSms(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `tablet - bez wskazania trafia do dowolnego tabletu studia`() {
        assertNull(request(SignatureChannel.TABLET).tabletId)
    }

    @Test
    fun `tablet - bez sparowanego tabletu prosba nie powstaje`() {
        every { tablets.listTablets(any()) } returns emptyList()

        assertThrows<ValidationException> { request(SignatureChannel.TABLET) }
        verify(exactly = 0) { requests.save(any()) }
    }

    @Test
    fun `tablet - tablet spoza studia jest odrzucany`() {
        assertThrows<ValidationException> { request(SignatureChannel.TABLET, tabletId = "cudzy") }
        verify(exactly = 0) { requests.save(any()) }
    }

    @Test
    fun `SMS - link idzie na numer z konta zalogowanego, od razu`() {
        val sms = slot<String>()
        every {
            gateway.sendTransactionalSms(studioId.value, "+48512345678", capture(sms), any(), DeliveryPolicy.IMMEDIATE, any())
        } returns SmsDeliveryResult(success = true, externalMessageId = "x", errorMessage = null)

        val request = request(SignatureChannel.SMS_LINK)

        verify { capabilities.requireCapability(studioId, CapabilityKey.SIGNATURE_REMOTE_REQUEST) }
        assertEquals("+48512345678", request.signerPhone)
        val token = requireNotNull(request.linkToken)
        assertTrue(sms.captured.contains("https://app.detailboost.pl/sign/$token"), sms.captured)
        assertTrue(sms.captured.contains("wrzesień 2026"), sms.captured)
        assertEquals(Duration.ofMinutes(60), Duration.between(request.createdAt, request.expiresAt))
    }

    @Test
    fun `SMS - konto bez numeru telefonu nie wysyla niczego`() {
        every { users.findByIdAndStudioId(userId.value, studioId.value) } returns user(phone = "")

        val error = assertThrows<ValidationException> { request(SignatureChannel.SMS_LINK) }

        assertTrue("numeru telefonu" in error.message!!, error.message)
        verify(exactly = 0) { requests.save(any()) }
        verify(exactly = 0) { gateway.sendTransactionalSms(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `SMS - niewyslany SMS cofa prosbe i uniewaznia token`() {
        every { gateway.sendTransactionalSms(any(), any(), any(), any(), any(), any()) } returns
            SmsDeliveryResult(success = false, externalMessageId = null, errorMessage = "dostawca niedostępny")

        assertThrows<ValidationException> { request(SignatureChannel.SMS_LINK) }

        val id = saved.captured.id
        verify { integrity.invalidateChallenge(id) }
        verify { integrity.evictCachedDocument(id) }
    }

    @Test
    fun `zatwierdzonej listy nie da sie wyslac do podpisu`() {
        every { sheets.require(studioId, sheetId) } returns sheet(AttendanceSheetStatus.APPROVED)

        assertThrows<ConflictException> { request(SignatureChannel.TABLET) }
        verify(exactly = 0) { requests.save(any()) }
    }

    @Test
    fun `druga prosba o podpis tej samej listy czeka na anulowanie pierwszej`() {
        every { requests.findActiveForAttendanceSheets(studioId.value, listOf(sheetId), any()) } returns
            listOf(SignatureRequestEntity.fromDomain(signatureRequest(SignatureSubject.AttendanceSheet(sheetId), studioId)))

        assertThrows<ConflictException> { request(SignatureChannel.TABLET) }
        verify(exactly = 0) { requests.save(any()) }
    }

    @Test
    fun `opcje pokazuja tablety studia i zamaskowany numer z konta`() {
        val options = signing.options(studioId, userId)

        assertEquals(listOf(AttendanceSigningTablet("t-1", "Recepcja")), options.tablets)
        assertEquals("+48 ••• ••• 678", options.phone)
    }

    @Test
    fun `po weryfikacji podpis trafia w pole arkusza, dostaje karte podpisu i zatwierdza liste`() {
        val request = signatureRequest(
            SignatureSubject.AttendanceSheet(sheetId), studioId,
            requestedBy = userId, requestedByName = "Jan Kowalski"
        )
        val appendPages = slot<(PDDocument) -> Unit>()
        every { signer.signNormalized(pdf, any(), "Jan Kowalski", any(), capture(appendPages)) } answers {
            appendPages.captured(mockk(relaxed = true))
            byteArrayOf(9)
        }
        coEvery {
            sheets.approveWithSignedDocument(studioId, sheetId, userId, "Jan Kowalski", byteArrayOf(9), any(), "TABLET")
        } returns sheet(AttendanceSheetStatus.APPROVED, signedKey = "signed.pdf")

        val key = runBlocking {
            signing.completeSigning(request, sheetId, pdf, byteArrayOf(5), Instant.now(), emptyList())
        }

        assertEquals("signed.pdf", key)
        val subject = slot<AuditPageSubject>()
        verify { auditPage.appendAuditPage(any(), request, emptyList(), capture(subject)) }
        assertEquals(sheetId.toString(), subject.captured.documentId)
        assertEquals("wrzesień 2026", subject.captured.contextValue)
        coVerify(exactly = 1) { sheets.approveWithSignedDocument(any(), any(), any(), any(), any(), any(), any()) }
    }
}
