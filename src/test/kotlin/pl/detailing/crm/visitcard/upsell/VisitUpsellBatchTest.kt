package pl.detailing.crm.visitcard.upsell

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import pl.detailing.crm.appointment.infrastructure.AppointmentRepository
import pl.detailing.crm.service.infrastructure.ServiceEntity
import pl.detailing.crm.service.infrastructure.ServiceRepository
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import pl.detailing.crm.shared.ValidationException
import pl.detailing.crm.shared.VisitId
import pl.detailing.crm.visit.infrastructure.VisitEntity
import pl.detailing.crm.visit.infrastructure.VisitRepository
import pl.detailing.crm.visitcard.upsell.infrastructure.VisitUpsellSuggestionEntity
import pl.detailing.crm.visitcard.upsell.infrastructure.VisitUpsellSuggestionRepository
import java.util.UUID

/**
 * Kilka propozycji dodanych za jednym razem to JEDNA wiadomość do klienta.
 *
 * Pracownik ogląda auto raz i widzi trzy rzeczy do zrobienia. Zapisywanie ich po jednej
 * wysyłało klientowi trzy SMS-y pod rząd, każdy za osobny kredyt.
 */
class VisitUpsellBatchTest {

    private val visitRepository: VisitRepository = mockk()
    private val appointmentRepository: AppointmentRepository = mockk()
    private val serviceRepository: ServiceRepository = mockk()
    private val suggestionRepository: VisitUpsellSuggestionRepository = mockk {
        every { save(any()) } answers { firstArg() }
    }
    private val notifyHandler: NotifyUpsellSuggestionHandler = mockk()

    private val service = VisitUpsellAdminService(
        visitRepository, appointmentRepository, serviceRepository, suggestionRepository, notifyHandler
    )

    private val studioId = StudioId.random()
    private val userId = UserId.random()
    private val visitId = VisitId.random()

    init {
        every { visitRepository.findByIdAndStudioId(visitId.value, studioId.value) } returns mockk<VisitEntity>(relaxed = true)
        every { notifyHandler.notifyForVisit(any(), any(), any()) } returns
            UpsellNotificationResult(sent = true, queued = false, scheduledFor = null, message = "SMS wysłany")
    }

    private fun catalogService(name: String): String {
        val id = UUID.randomUUID()
        every { serviceRepository.findByIdAndStudioId(id, studioId.value) } returns mockk<ServiceEntity>(relaxed = true).also {
            every { it.id } returns id
            every { it.name } returns name
            every { it.isActive } returns true
            every { it.basePriceNet } returns 100_000
            every { it.basePriceGross } returns 123_000
            every { it.vatRate } returns 23
        }
        return id.toString()
    }

    private fun request(vararg serviceIds: String, notify: Boolean = false) = CreateUpsellSuggestionsRequest(
        suggestions = serviceIds.map { CreateUpsellSuggestionItem(serviceId = it) },
        notifyCustomer = notify
    )

    @Test
    fun `trzy uslugi to trzy propozycje i JEDNA wiadomosc`() {
        val ids = listOf(catalogService("Powłoka ceramiczna"), catalogService("Korekta lakieru"), catalogService("Detailing wnętrza"))

        val response = service.createMany(visitId, studioId, userId, request(*ids.toTypedArray(), notify = true))

        assertEquals(3, response.suggestions.size)
        val notified = slot<List<VisitUpsellSuggestionEntity>>()
        verify(exactly = 1) { notifyHandler.notifyForVisit(visitId, studioId, capture(notified)) }
        assertEquals(
            listOf("Powłoka ceramiczna", "Korekta lakieru", "Detailing wnętrza"),
            notified.captured.map { it.serviceName }
        )
    }

    @Test
    fun `bez zaznaczonego powiadomienia nikt nie dostaje SMS-a`() {
        val ids = listOf(catalogService("Powłoka ceramiczna"), catalogService("Korekta lakieru"))

        val response = service.createMany(visitId, studioId, userId, request(*ids.toTypedArray(), notify = false))

        assertEquals(2, response.suggestions.size)
        assertEquals(null, response.customerNotification)
        verify(exactly = 0) { notifyHandler.notifyForVisit(any(), any(), any()) }
    }

    @Test
    fun `wynik powiadomienia wraca raz, dla calej listy`() {
        val ids = listOf(catalogService("A"), catalogService("B"))

        val response = service.createMany(visitId, studioId, userId, request(*ids.toTypedArray(), notify = true))

        assertEquals("SMS wysłany", response.customerNotification?.message)
    }

    @Test
    fun `pusta lista jest odrzucana, zanim cokolwiek powstanie`() {
        assertThrows(ValidationException::class.java) {
            service.createMany(visitId, studioId, userId, CreateUpsellSuggestionsRequest(emptyList(), notifyCustomer = true))
        }
        verify(exactly = 0) { suggestionRepository.save(any()) }
        verify(exactly = 0) { notifyHandler.notifyForVisit(any(), any(), any()) }
    }

    @Test
    fun `nieaktywna usluga w liscie wywraca calosc - klient nie zobaczy polowy tego, co dostal SMS-em`() {
        val ok = catalogService("Powłoka ceramiczna")
        val inactiveId = UUID.randomUUID()
        every { serviceRepository.findByIdAndStudioId(inactiveId, studioId.value) } returns mockk<ServiceEntity>(relaxed = true).also {
            every { it.name } returns "Wycofana usługa"
            every { it.isActive } returns false
        }

        assertThrows(ValidationException::class.java) {
            service.createMany(visitId, studioId, userId, request(ok, inactiveId.toString(), notify = true))
        }
        verify(exactly = 0) { notifyHandler.notifyForVisit(any(), any(), any()) }
    }

    @Test
    fun `pojedyncze utworzenie nadal dziala i niesie wynik powiadomienia`() {
        val id = catalogService("Powłoka ceramiczna")

        val response = service.create(
            visitId, studioId, userId,
            CreateUpsellSuggestionRequest(serviceId = id, notifyCustomer = true)
        )

        assertEquals("Powłoka ceramiczna", response.serviceName)
        assertEquals("SMS wysłany", response.customerNotification?.message)
        verify(exactly = 1) { notifyHandler.notifyForVisit(any(), any(), any()) }
    }
}
