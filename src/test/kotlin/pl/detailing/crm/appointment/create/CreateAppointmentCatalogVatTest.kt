package pl.detailing.crm.appointment.create

import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import pl.detailing.crm.appointment.domain.AdjustmentType
import pl.detailing.crm.appointment.infrastructure.AppointmentEntity
import pl.detailing.crm.appointment.infrastructure.AppointmentRepository
import pl.detailing.crm.service.infrastructure.ServiceEntity
import pl.detailing.crm.service.infrastructure.ServiceRepository
import pl.detailing.crm.shared.*
import java.time.Instant
import java.util.UUID

/**
 * Rezerwacja z usługą z cennika, gdy stawka VAT pozycji różni się od stawki cennika.
 *
 * Regresja: handler brał z cennika netto I brutto, a stawkę z żądania. Brutto cennika przy
 * innej stawce nie pasowało do netta i kontrola spójności pozycji („Financial integrity
 * violation") wywracała cały zapis. Kalendarz wysyłał przy tym domyślne 23% także dla usług
 * na 8%, więc taka rezerwacja w ogóle się nie zapisywała.
 */
class CreateAppointmentCatalogVatTest {

    private val validatorComposite: CreateAppointmentValidatorComposite = mockk {
        coEvery { validate(any()) } just Runs
    }
    private val appointmentRepository: AppointmentRepository = mockk(relaxed = true) {
        every { save(any<AppointmentEntity>()) } answers { firstArg() }
    }
    private val serviceRepository: ServiceRepository = mockk()

    private val handler = CreateAppointmentHandler(
        validatorComposite = validatorComposite,
        appointmentRepository = appointmentRepository,
        customerRepository = mockk(relaxed = true),
        vehicleRepository = mockk(relaxed = true),
        vehicleOwnerRepository = mockk(relaxed = true),
        serviceRepository = serviceRepository,
        auditService = mockk(relaxed = true),
        vehicleResolver = mockk(relaxed = true),
        businessEventPublisher = mockk(relaxed = true)
    )

    private val studioId = StudioId.random()
    private val userId = UserId.random()
    private val serviceId = UUID.randomUUID()

    private fun catalog(net: Long, gross: Long, vatRate: Int) {
        every { serviceRepository.findActiveByStudioId(any()) } returns listOf(
            ServiceEntity(
                id = serviceId, studioId = studioId.value, name = "Powłoka ceramiczna",
                basePriceNet = net, basePriceGross = gross, vatRate = vatRate,
                isActive = true, requireManualPrice = false, isPackage = false, replacesServiceId = null,
                createdBy = userId.value, updatedBy = userId.value
            )
        )
    }

    private fun book(lineVatRate: Int) = runBlocking {
        handler.handle(
            CreateAppointmentCommand(
                studioId = studioId,
                userId = userId,
                customer = CustomerIdentity.Existing(CustomerId.random()),
                vehicle = VehicleIdentity.None,
                services = listOf(
                    ServiceLineItemCommand(
                        serviceId = ServiceId(serviceId), serviceName = null,
                        basePriceNet = 1, basePriceGross = 1, vatRate = lineVatRate,
                        adjustmentType = AdjustmentType.PERCENT, adjustmentValue = 0.0, customNote = null
                    )
                ),
                schedule = ScheduleCommand(
                    isAllDay = false,
                    startDateTime = Instant.parse("2026-09-16T09:00:00Z"),
                    endDateTime = Instant.parse("2026-09-16T10:00:00Z")
                ),
                appointmentTitle = "Wizyta testowa",
                appointmentColorId = AppointmentColorId.random(),
                note = null
            )
        )
    }

    @Test
    fun `usluga z cennika 1900,00 zl przy tej samej stawce zostaje 1900,00 zl`() {
        catalog(net = 154_472, gross = 190_000, vatRate = 23)

        val result = book(lineVatRate = 23)

        assertEquals(190_000L, result.totalGross.amountInCents)
        assertEquals(154_472L, result.totalNet.amountInCents)
    }

    @Test
    fun `brutto wpisane w cenniku przy innej stawce - zapis sie udaje, brutto zostaje`() {
        catalog(net = 154_472, gross = 190_000, vatRate = 23)

        val result = book(lineVatRate = 8)

        assertEquals(190_000L, result.totalGross.amountInCents)
        assertEquals(175_926L, result.totalNet.amountInCents)
    }

    @Test
    fun `usluga na 8 procent wyslana z domyslnym 23 - zapis sie udaje, netto z cennika`() {
        // 1000,00 netto → 1080,00 brutto przy 8%: para zgodna z przeliczeniem, zostaje netto.
        catalog(net = 100_000, gross = 108_000, vatRate = 8)

        val result = book(lineVatRate = 23)

        assertEquals(100_000L, result.totalNet.amountInCents)
        assertEquals(123_000L, result.totalGross.amountInCents)
    }
}
