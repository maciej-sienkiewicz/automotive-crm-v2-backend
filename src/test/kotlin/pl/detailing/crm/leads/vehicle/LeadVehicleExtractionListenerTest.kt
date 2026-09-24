package pl.detailing.crm.leads.vehicle

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher
import pl.detailing.crm.comms.domain.CommDirection
import pl.detailing.crm.comms.domain.CommFolderKind
import pl.detailing.crm.comms.domain.CommSendStatus
import pl.detailing.crm.comms.domain.EmailTextCleaner
import pl.detailing.crm.comms.infrastructure.CommMessageEntity
import pl.detailing.crm.comms.infrastructure.CommMessageRepository
import pl.detailing.crm.leads.domain.LeadVehicleDetectionStatus
import pl.detailing.crm.leads.infrastructure.LeadEntity
import pl.detailing.crm.leads.infrastructure.LeadRepository
import pl.detailing.crm.rolepreview.RolePreviewOutboundGuard
import pl.detailing.crm.shared.LeadSource
import pl.detailing.crm.shared.LeadStatus
import pl.detailing.crm.vehicle.segment.VehicleSegmentService
import java.time.Instant
import java.util.Optional
import java.util.UUID

/**
 * Co model rozpoznający auto dostaje do przeczytania.
 *
 * Zgłoszenie z produkcji (23.09): lead „oklejenie Ford Transit L3H3" skończył
 * rozpoznanie jako DONE bez marki. Wiadomość miała pustą wersję czystą przy 1627
 * znakach części tekstowej, a listener czytał wyłącznie wersję czystą — model
 * dostał sam napis „Klient:" i zgodnie z promptem nie zgadywał.
 */
class LeadVehicleExtractionListenerTest {

    private val studioId = UUID.randomUUID()
    private val leadId = UUID.randomUUID()
    private val threadId = UUID.randomUUID()

    private val leadRepository = mockk<LeadRepository>()
    private val messageRepository = mockk<CommMessageRepository>()
    private val extractionService = mockk<LeadVehicleExtractionService>()
    private val segmentService = mockk<VehicleSegmentService>(relaxed = true)
    private val eventPublisher = mockk<ApplicationEventPublisher>(relaxed = true)
    private val rolePreviewGuard = mockk<RolePreviewOutboundGuard>()

    private val listener = LeadVehicleExtractionListener(
        leadRepository, messageRepository, EmailTextCleaner(), extractionService,
        segmentService, eventPublisher, rolePreviewGuard
    )

    private val lead = LeadEntity(
        id = leadId,
        studioId = studioId,
        source = LeadSource.EMAIL,
        status = LeadStatus.NEW,
        contactIdentifier = "klientka@example.com",
        customerName = "Anna Nowak",
        initialMessage = null,
        estimatedValue = 0,
        requiresVerification = false,
        vehicleBrand = null,
        vehicleModel = null,
        vehicleDetectionStatus = LeadVehicleDetectionStatus.PENDING,
        customerId = null,
        appointmentId = null,
        visitId = null,
        assignedUserId = null,
        assignedUserName = null,
        lostReason = null,
        stagnantAlertSentAt = null,
        threadId = threadId,
        category = null,
        firstResponseAt = null
    )

    /** Część tekstowa maila z produkcji, dane klientki zmienione. */
    private val productionBody = "Dzień dobry,\r\n\r\n" +
        "proszę przygotowanie wyceny na wykonanie usługi oklejenia samochodu Ford\r\n" +
        "Transit L3H3 (V363). Zależy nam na wykonaniu aplikacji folii na wybranych\r\n" +
        "elementach samochodu.\r\n\r\n" +
        "Pozdrawiam,\r\n\r\n" +
        "--\r\n" +
        "Anna Nowak\r\n\r\n" +
        "600100200\r\n"

    @BeforeEach
    fun setUp() {
        every { leadRepository.findById(leadId) } returns Optional.of(lead)
        every { leadRepository.save(any<LeadEntity>()) } answers { firstArg() }
        every { rolePreviewGuard.isSandbox(any()) } returns false
    }

    private fun inbound(body: String?, clean: String?) = CommMessageEntity(
        id = UUID.randomUUID(),
        studioId = studioId,
        accountId = UUID.randomUUID(),
        threadId = threadId,
        direction = CommDirection.INBOUND,
        folderKind = CommFolderKind.INBOX,
        messageIdHdr = UUID.randomUUID().toString(),
        inReplyTo = null,
        referencesIds = null,
        fromEmail = "klientka@example.com",
        fromName = "Anna Nowak",
        toEmails = null,
        ccEmails = null,
        subject = "Fwd: Wycena oklejenia",
        sentAt = Instant.parse("2026-09-23T12:11:41Z"),
        bodyHtmlSafe = null,
        bodyText = body,
        bodyTextClean = clean,
        imapUid = null,
        imapUidValidity = null,
        readSource = null,
        readAt = null,
        sendStatus = CommSendStatus.RECEIVED
    )

    private fun thread(vararg messages: CommMessageEntity) {
        every { messageRepository.findByThreadIdOrderBySentAtAsc(threadId) } returns messages.toList()
    }

    @Test
    fun `pusta wersja czysta nie oslepia modelu - czyta czesc tekstowa`() {
        thread(inbound(body = productionBody, clean = ""))
        val input = slot<String>()
        coEvery { extractionService.extract(capture(input)) } returns ExtractedVehicle("Ford", "Transit")

        listener.onLeadThreadAttached(LeadThreadAttachedEvent(leadId = leadId, threadId = threadId))

        assertTrue(input.captured.contains("Ford"), "model nie dostał marki: <${input.captured}>")
        assertTrue(input.captured.contains("Transit L3H3"), "model nie dostał modelu: <${input.captured}>")
        assertFalse(input.captured.contains("600100200"), "stopka to nie treść - czyścimy, a nie podajemy surowe ciało")
        assertEquals("Ford", lead.vehicleBrand)
        assertEquals("Transit", lead.vehicleModel)
        assertEquals(LeadVehicleDetectionStatus.DONE, lead.vehicleDetectionStatus)
    }

    @Test
    fun `wiadomosc bez tresci nie trafia do modelu jako samo Klient`() {
        thread(inbound(body = null, clean = ""))

        listener.onLeadThreadAttached(LeadThreadAttachedEvent(leadId = leadId, threadId = threadId))

        coVerify(exactly = 0) { extractionService.extract(any()) }
        assertNull(lead.vehicleBrand)
        assertEquals(LeadVehicleDetectionStatus.DONE, lead.vehicleDetectionStatus, "spinner nie może kręcić się na zawsze")
    }

    @Test
    fun `wersja czysta wygrywa z surowym cialem, gdy cos w niej jest`() {
        thread(inbound(body = "surowe ciało, w którym stoi Audi A4 ze stopki", clean = "Proszę o wycenę BMW M3."))
        val input = slot<String>()
        coEvery { extractionService.extract(capture(input)) } returns ExtractedVehicle("Bmw", "M3")

        listener.onLeadThreadAttached(LeadThreadAttachedEvent(leadId = leadId, threadId = threadId))

        assertEquals("Klient: Proszę o wycenę BMW M3.", input.captured)
    }
}
