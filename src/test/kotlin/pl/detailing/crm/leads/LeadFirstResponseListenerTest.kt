package pl.detailing.crm.leads

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.springframework.scheduling.annotation.Async
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener
import pl.detailing.crm.comms.domain.CommOutboundSentEvent
import pl.detailing.crm.leads.infrastructure.LeadEntity
import pl.detailing.crm.leads.infrastructure.LeadRepository
import org.springframework.context.ApplicationEventPublisher
import pl.detailing.crm.leads.update.LeadFirstResponseListener
import pl.detailing.crm.leads.update.LeadOwedService
import pl.detailing.crm.leads.update.LeadStatusService
import pl.detailing.crm.shared.LeadSource
import pl.detailing.crm.shared.LeadStatus
import java.time.Instant
import java.util.UUID

/**
 * Odpowiedź wysłana POZA CRM-em (Outlook, telefon, webmail) wraca do nas importem
 * z folderu Wysłane i ma zrobić na leadzie dokładnie to samo, co odpowiedź napisana
 * w CRM-ie: ostemplować czas pierwszej reakcji i zdjąć z leada „Nowy".
 */
class LeadFirstResponseListenerTest {

    private val leadRepository = mockk<LeadRepository> {
        every { save(any()) } answers { firstArg() }
    }
    private val statusService = mockk<LeadStatusService>(relaxed = true)

    /*
     * Prawdziwa usługa długu, nie atrapa: „wysłana wiadomość spłaca obietnicę" jest
     * zachowaniem, a nie wywołaniem, i test ma pilnować skutku na leadzie.
     */
    private val owedService = LeadOwedService(leadRepository, mockk<ApplicationEventPublisher>(relaxed = true))
    private val listener = LeadFirstResponseListener(leadRepository, statusService, owedService)

    private val threadId = UUID.randomUUID()

    private fun lead(
        status: LeadStatus = LeadStatus.NEW,
        firstResponseAt: Instant? = null,
        createdAt: Instant = Instant.now(),
        owedSince: Instant? = null
    ) = LeadEntity(
        id = UUID.randomUUID(),
        studioId = UUID.randomUUID(),
        source = LeadSource.EMAIL,
        status = status,
        contactIdentifier = "klient@example.com",
        customerName = "Jan Klient",
        initialMessage = "Poproszę o wycenę",
        estimatedValue = 0,
        requiresVerification = false,
        vehicleBrand = null,
        vehicleModel = null,
        customerId = null,
        appointmentId = null,
        visitId = null,
        assignedUserId = null,
        assignedUserName = null,
        lostReason = null,
        stagnantAlertSentAt = null,
        threadId = threadId,
        firstResponseAt = firstResponseAt,
        owedSince = owedSince,
        createdAt = createdAt
    )

    private fun event(sentAt: Instant = Instant.now()) =
        CommOutboundSentEvent(studioId = UUID.randomUUID(), threadId = threadId, sentAt = sentAt)

    @Test
    fun `odpowiedź spoza CRM-a stempluje czas reakcji i zdejmuje z leada Nowy`() {
        val lead = lead()
        every { leadRepository.findByThreadIdOrderByCreatedAtAsc(threadId) } returns listOf(lead)
        val sentAt = Instant.now()

        listener.onOutboundSent(event(sentAt))

        assertEquals(sentAt, lead.firstResponseAt)
        verify { statusService.transition(lead, LeadStatus.IN_PROGRESS) }
    }

    @Test
    fun `kolejna odpowiedź nie nadpisuje czasu PIERWSZEJ reakcji`() {
        val first = Instant.now().minusSeconds(3600)
        val lead = lead(status = LeadStatus.IN_PROGRESS, firstResponseAt = first)
        every { leadRepository.findByThreadIdOrderByCreatedAtAsc(threadId) } returns listOf(lead)

        listener.onOutboundSent(event())

        assertEquals(first, lead.firstResponseAt)
        verify(exactly = 0) { statusService.transition(any(), any(), any(), any(), any(), any()) }
    }

    /** Lead zamknięty, zarezerwowany czy przegrany nie wraca odpowiedzią na wcześniejszy etap. */
    @Test
    fun `odpowiedź nie cofa statusu leada spoza NEW`() {
        val lead = lead(status = LeadStatus.COMPLETED)
        every { leadRepository.findByThreadIdOrderByCreatedAtAsc(threadId) } returns listOf(lead)

        listener.onOutboundSent(event())

        assertNotNull(lead.firstResponseAt)
        verify(exactly = 0) { statusService.transition(any(), any(), any(), any(), any(), any()) }
    }

    /**
     * Klient poprosił przez telefon o ofertę mailem, więc ktoś zgłosił dług studia.
     * Wysłanie tej oferty jest jedynym dowodem spłaty — i musi zdjąć sprawę z sekcji
     * „Czeka na Ciebie" bez proszenia użytkownika o drugie kliknięcie.
     */
    @Test
    fun `wysłana wiadomość spłaca ręcznie zgłoszony dług studia`() {
        val lead = lead(
            status = LeadStatus.IN_PROGRESS,
            firstResponseAt = Instant.now().minusSeconds(7200),
            owedSince = Instant.now().minusSeconds(3600)
        )
        lead.owedNote = "Wysłać wycenę ceramiki"
        every { leadRepository.findByThreadIdOrderByCreatedAtAsc(threadId) } returns listOf(lead)

        listener.onOutboundSent(event())

        assertNull(lead.owedSince)
        assertNull(lead.owedNote)
    }

    @Test
    fun `wątek bez leada przechodzi bez śladu`() {
        every { leadRepository.findByThreadIdOrderByCreatedAtAsc(threadId) } returns emptyList()

        listener.onOutboundSent(event())

        verify(exactly = 0) { leadRepository.save(any()) }
    }

    /**
     * Wątek formularzowego robota skleja zgłoszenia wielu osób i potrafi wisieć pod
     * kilkoma leadami. Zapytanie o JEDEN wynik wywracało się tutaj na policzalności,
     * a wyjątek w tym miejscu kosztował całą importowaną wiadomość.
     */
    @Test
    fun `kilka leadów na jednym wątku nie wywraca odnotowania`() {
        val older = lead(createdAt = Instant.now().minusSeconds(7200))
        val newer = lead(createdAt = Instant.now())
        every { leadRepository.findByThreadIdOrderByCreatedAtAsc(threadId) } returns listOf(older, newer)

        listener.onOutboundSent(event())

        assertNotNull(older.firstResponseAt)
        assertNull(newer.firstResponseAt)
    }

    /**
     * REGRESJA PRZYCZYNY BŁĘDU. Nasłuch MUSI biec po zatwierdzeniu transakcji importu.
     * Wpięty w tę samą transakcję (zwykły `@EventListener` + `@Transactional`) oznaczał
     * ją przy każdym potknięciu jako rollback-only — wtedy zapis wiadomości przepadał,
     * a synchronizacja i tak przesuwała znacznik UID, więc odpowiedź wysłana z Outlooka
     * nie pojawiała się w CRM-ie już nigdy: ani w skrzynce, ani na leadzie.
     */
    @Test
    fun `nasłuch biegnie po zatwierdzeniu transakcji i asynchronicznie`() {
        val method = LeadFirstResponseListener::class.java
            .methods.single { it.name == "onOutboundSent" }

        val transactional = method.getAnnotation(TransactionalEventListener::class.java)
        assertNotNull(transactional, "nasłuch musi być @TransactionalEventListener")
        assertEquals(TransactionPhase.AFTER_COMMIT, transactional.phase)
        // Wysyłka z CRM-a publikuje zdarzenie bez otwartej transakcji — bez tej flagi
        // nasłuch w ogóle by wtedy nie wystartował.
        assertEquals(true, transactional.fallbackExecution)
        assertNotNull(method.getAnnotation(Async::class.java), "nasłuch nie może blokować pętli IMAP")
    }
}
