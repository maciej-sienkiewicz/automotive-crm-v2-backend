package pl.detailing.crm.visitcard

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import pl.detailing.crm.appointment.domain.AppointmentStatus
import pl.detailing.crm.appointment.infrastructure.AppointmentEntity
import pl.detailing.crm.appointment.infrastructure.AppointmentRepository
import pl.detailing.crm.communication.CommunicationLogService
import pl.detailing.crm.communication.OutboundCommunicationGateway
import pl.detailing.crm.communication.template.MessageTemplateRenderer
import pl.detailing.crm.customer.infrastructure.CustomerEntity
import pl.detailing.crm.customer.infrastructure.CustomerRepository
import pl.detailing.crm.email.domain.EmailAutomationConfig
import pl.detailing.crm.email.domain.EmailAutomationConfigRepository
import pl.detailing.crm.email.domain.EmailNotificationRule
import pl.detailing.crm.email.provider.EmailDeliveryResult
import pl.detailing.crm.livemetrics.BusinessEventPublisher
import pl.detailing.crm.shared.AppointmentId
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.smscampaigns.domain.SmsAutomationConfig
import pl.detailing.crm.smscampaigns.domain.SmsAutomationConfigRepository
import pl.detailing.crm.smscampaigns.domain.SmsNotificationRule
import pl.detailing.crm.studio.settings.StudioSettingsRepository
import pl.detailing.crm.shared.VehicleStatus
import pl.detailing.crm.vehicle.infrastructure.VehicleEntity
import pl.detailing.crm.vehicle.infrastructure.VehicleRepository
import java.time.Instant
import java.util.Optional
import java.util.UUID

/**
 * Regresja pod widełki: "Link do Karty Rezerwacji" dostał te same zmienne pojazdu
 * co "Link do Karty Wizyty" ({{pojazd}}, {{rejestracja}}) - w przeciwieństwie do
 * {{numer_wizyty}}, którego rezerwacja fizycznie nie ma (wizyta powstaje dopiero przy
 * przyjęciu pojazdu). Te testy pilnują, że wartości faktycznie trafiają do renderowanej
 * treści, oraz że brak pojazdu na rezerwacji nie wywraca wysyłki.
 */
class SendReservationCardLinkHandlerTest {

    private val appointmentRepository: AppointmentRepository = mockk()
    private val customerRepository: CustomerRepository = mockk()
    private val studioSettingsRepository: StudioSettingsRepository = mockk()
    private val tokenService: VisitCardTokenService = mockk()
    private val communicationGateway: OutboundCommunicationGateway = mockk(relaxed = true)
    private val communicationLogService: CommunicationLogService = mockk(relaxed = true)
    private val smsAutomationConfigRepository: SmsAutomationConfigRepository = mockk()
    private val emailAutomationConfigRepository: EmailAutomationConfigRepository = mockk()
    private val renderer = MessageTemplateRenderer()
    private val businessEventPublisher: BusinessEventPublisher = mockk(relaxed = true)
    private val vehicleRepository: VehicleRepository = mockk()

    private val handler = SendReservationCardLinkHandler(
        appointmentRepository,
        customerRepository,
        studioSettingsRepository,
        tokenService,
        communicationGateway,
        communicationLogService,
        VisitCardProperties(),
        smsAutomationConfigRepository,
        emailAutomationConfigRepository,
        renderer,
        businessEventPublisher,
        vehicleRepository
    )

    private val studioId = StudioId(UUID.randomUUID())
    private val appointmentId = AppointmentId(UUID.randomUUID())
    private val customerId = UUID.randomUUID()
    private val vehicleId = UUID.randomUUID()

    private fun setUpCommon() {
        every { customerRepository.findByIdAndStudioId(customerId, studioId.value) } returns customerEntity()
        every { studioSettingsRepository.findById(studioId.value) } returns Optional.empty()
        every { tokenService.getOrCreateTokenForAppointment(studioId, appointmentId) } returns "tok123"
        every { emailAutomationConfigRepository.findByStudioId(studioId) } returns EmailAutomationConfig.defaultFor(studioId).copy(
            reservationCardLink = EmailNotificationRule(
                enabled = true,
                subjectTemplate = "Twoja rezerwacja",
                bodyTemplate = "Pojazd: {{pojazd}} ({{rejestracja}}). Link: {{link}}"
            )
        )
        every { smsAutomationConfigRepository.findByStudioId(studioId) } returns SmsAutomationConfig.defaultFor(studioId).copy(
            reservationCardLink = SmsNotificationRule(
                enabled = true,
                messageTemplate = "Pojazd {{pojazd}} {{rejestracja}}: {{link}}"
            )
        )
    }

    @Test
    fun `pojazd i rejestracja z kartoteki trafiaja do wyslanej wiadomosci`() = runBlocking {
        setUpCommon()
        every { appointmentRepository.findByIdAndStudioId(appointmentId.value, studioId.value) } returns
            appointmentEntity(vehicleId)
        every { vehicleRepository.findByIdAndStudioId(vehicleId, studioId.value) } returns vehicleEntity()

        val bodySlot = slot<String>()
        every {
            communicationGateway.sendEmail(any(), any(), any(), any(), capture(bodySlot), any(), any(), any())
        } returns EmailDeliveryResult.success("msg-1")

        handler.handle(SendReservationCardLinkCommand(appointmentId, studioId))

        assertEquals("Pojazd: Audi RS6 (WE 4RS6X). Link: https://detailboost.pl/vc/tok123", bodySlot.captured)
    }

    @Test
    fun `rezerwacja bez wybranego pojazdu nie wywraca wysylki - zmienne wychodza puste`() = runBlocking {
        setUpCommon()
        every { appointmentRepository.findByIdAndStudioId(appointmentId.value, studioId.value) } returns
            appointmentEntity(vehicleId = null)

        val bodySlot = slot<String>()
        every {
            communicationGateway.sendEmail(any(), any(), any(), any(), capture(bodySlot), any(), any(), any())
        } returns EmailDeliveryResult.success("msg-1")

        val result = handler.handle(SendReservationCardLinkCommand(appointmentId, studioId))

        // MessageTemplateRenderer scala biegi spacji do jednej (patrz HORIZONTAL_RUN) - puste {{pojazd}}/{{rejestracja}} nie zostawiają podwójnych spacji w treści.
        assertEquals("Pojazd: (). Link: https://detailboost.pl/vc/tok123", bodySlot.captured)
        assertEquals(true, result.emailSent)
    }

    @Test
    fun `numer wizyty nie jest zmienna Karty Rezerwacji - wizyty jeszcze nie ma`() {
        // Widełki backendu: reservationCardLink nie ma numer_wizyty w allowedPlaceholders,
        // więc szablon, który by go użył, odrzuca się przy zapisie (walidacja poza tym
        // testem) - a nie dopiero przy wysyłce, kiedy klient już czeka na SMS.
        val allowed = pl.detailing.crm.communication.template.MessageTemplateKind.SMS_RESERVATION_CARD_LINK
            .allowedPlaceholders
        assertEquals(setOf("imie", "nazwisko", "pojazd", "rejestracja", "data", "godzina", "link"), allowed)
    }

    private fun appointmentEntity(vehicleId: UUID?): AppointmentEntity {
        val entity = AppointmentEntity(
            id = appointmentId.value,
            studioId = studioId.value,
            customerId = customerId,
            vehicleId = vehicleId,
            appointmentTitle = null,
            appointmentColorId = UUID.randomUUID(),
            isAllDay = false,
            startDateTime = Instant.parse("2026-09-15T09:00:00Z"),
            endDateTime = Instant.parse("2026-09-15T10:00:00Z"),
            status = AppointmentStatus.CREATED,
            note = null,
            createdBy = UUID.randomUUID(),
            updatedBy = UUID.randomUUID()
        )
        return entity
    }

    private fun vehicleEntity() = VehicleEntity(
        id = vehicleId,
        studioId = studioId.value,
        licensePlate = "WE 4RS6X",
        brand = "Audi",
        model = "RS6",
        yearOfProduction = 2023,
        color = null,
        paintType = null,
        currentMileage = 0,
        status = VehicleStatus.ACTIVE,
        createdBy = UUID.randomUUID(),
        updatedBy = UUID.randomUUID()
    )

    private fun customerEntity() = mockk<CustomerEntity>(relaxed = true) {
        every { id } returns customerId
        every { firstName } returns "Jan"
        every { lastName } returns "Kowalski"
        every { phone } returns null
        every { email } returns "jan@test.pl"
    }
}
