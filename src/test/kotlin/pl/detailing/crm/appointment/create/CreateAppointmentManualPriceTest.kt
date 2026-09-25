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
import pl.detailing.crm.audit.domain.AuditService
import pl.detailing.crm.customer.infrastructure.CustomerRepository
import pl.detailing.crm.livemetrics.BusinessEventPublisher
import pl.detailing.crm.service.infrastructure.ServiceEntity
import pl.detailing.crm.service.infrastructure.ServiceRepository
import pl.detailing.crm.shared.*
import pl.detailing.crm.vehicle.infrastructure.VehicleOwnerRepository
import pl.detailing.crm.vehicle.infrastructure.VehicleRepository
import java.time.Instant
import java.util.UUID

/**
 * Regresja z produkcji: w kreatorze wizyty wybrano usługę z ceną ustalaną ręcznie,
 * wpisano 5000,00 zł - i rezerwacja zapisała się z `totalNet = 0`, `totalGross = 0`.
 *
 * Przyczyna: dla pozycji opartej o usługę z cennika handler brał cenę Z CENNIKA,
 * ignorując kwotę z żądania. Przy usłudze `requireManualPrice` cennik trzyma
 * `Money.ZERO` - i to celowo, bo taka usługa nie ma ceny katalogowej
 * (`CreateServiceHandler` wprost odrzuca kwotę przysłaną przy jej tworzeniu).
 * Jedynym miejscem, w którym cena istnieje, jest żądanie tworzące rezerwację.
 *
 * Klienci przysyłają ją w dwóch kształtach i oba muszą działać: kreator wizyty
 * wpisuje kwotę w `basePriceNet`, a przyjęcie pojazdu i edycja rezerwacji zwijają
 * ją do rabatu `SET_NET` na zerowej bazie (`toApiServiceLineItem`).
 */
class CreateAppointmentManualPriceTest {

    private val validatorComposite: CreateAppointmentValidatorComposite = mockk()
    private val appointmentRepository: AppointmentRepository = mockk(relaxed = true)
    private val customerRepository: CustomerRepository = mockk(relaxed = true)
    private val vehicleRepository: VehicleRepository = mockk(relaxed = true)
    private val vehicleOwnerRepository: VehicleOwnerRepository = mockk(relaxed = true)
    private val serviceRepository: ServiceRepository = mockk()
    private val auditService: AuditService = mockk(relaxed = true)
    private val vehicleResolver: AppointmentVehicleResolver = mockk(relaxed = true)
    private val businessEventPublisher: BusinessEventPublisher = mockk(relaxed = true)

    private val handler = CreateAppointmentHandler(
        validatorComposite = validatorComposite,
        appointmentRepository = appointmentRepository,
        customerRepository = customerRepository,
        vehicleRepository = vehicleRepository,
        vehicleOwnerRepository = vehicleOwnerRepository,
        serviceRepository = serviceRepository,
        auditService = auditService,
        vehicleResolver = vehicleResolver,
        businessEventPublisher = businessEventPublisher,
        eventPublisher = mockk(relaxed = true)
    )

    private val studioId = StudioId.random()
    private val userId = UserId.random()
    private val customerId = CustomerId.random()
    private val manualServiceId = UUID.randomUUID()
    private val catalogServiceId = UUID.randomUUID()

    /** Tak zapisuje usługę z ceną ręczną CreateServiceHandler: obie kwoty wyzerowane. */
    private fun manualPriceService() = serviceEntity(
        id = manualServiceId,
        name = "Detailing indywidualny",
        net = 0L,
        gross = 0L,
        requireManualPrice = true
    )

    private fun catalogService() = serviceEntity(
        id = catalogServiceId,
        name = "Mycie zewnętrzne",
        net = 10_000L,
        gross = 12_300L,
        requireManualPrice = false
    )

    private fun serviceEntity(
        id: UUID,
        name: String,
        net: Long,
        gross: Long,
        requireManualPrice: Boolean
    ) = ServiceEntity(
        id = id,
        studioId = studioId.value,
        name = name,
        basePriceNet = net,
        basePriceGross = gross,
        vatRate = 23,
        isActive = true,
        requireManualPrice = requireManualPrice,
        isPackage = false,
        replacesServiceId = null,
        createdBy = userId.value,
        updatedBy = userId.value
    )

    private fun setUp(services: List<ServiceEntity>) {
        coEvery { validatorComposite.validate(any()) } just Runs
        every { serviceRepository.findActiveByStudioId(any()) } returns services
        every { appointmentRepository.save(any<AppointmentEntity>()) } answers { firstArg() }
    }

    private fun lineItem(
        serviceId: UUID,
        basePriceNet: Long,
        basePriceGross: Long? = null,
        adjustmentType: AdjustmentType = AdjustmentType.PERCENT,
        adjustmentValue: Double = 0.0
    ) = ServiceLineItemCommand(
        serviceId = ServiceId(serviceId),
        serviceName = null,
        basePriceNet = basePriceNet,
        basePriceGross = basePriceGross,
        vatRate = 23,
        adjustmentType = adjustmentType,
        adjustmentValue = adjustmentValue,
        customNote = null
    )

    private fun command(services: List<ServiceLineItemCommand>) = CreateAppointmentCommand(
        studioId = studioId,
        userId = userId,
        customer = CustomerIdentity.Existing(customerId),
        vehicle = VehicleIdentity.None,
        services = services,
        schedule = ScheduleCommand(
            isAllDay = false,
            startDateTime = Instant.parse("2026-09-16T09:00:00Z"),
            endDateTime = Instant.parse("2026-09-16T10:00:00Z")
        ),
        appointmentTitle = "Wizyta testowa",
        appointmentColorId = AppointmentColorId.random(),
        note = null
    )

    @Test
    fun `cena ustalona recznie i przyslana w basePriceNet nie ginie na rzecz zera z cennika`() = runBlocking {
        setUp(listOf(manualPriceService()))
        // Kreator wizyty: 5000,00 zł netto wpisane w oknie ceny, rabat zerowy.
        val result = handler.handle(command(listOf(
            lineItem(serviceId = manualServiceId, basePriceNet = 500_000L)
        )))

        assertEquals(500_000L, result.totalNet.amountInCents)
        assertEquals(615_000L, result.totalGross.amountInCents)
    }

    @Test
    fun `dokladne brutto ceny recznej przechodzi bez odtwarzania z netta`() = runBlocking {
        setUp(listOf(manualPriceService()))
        // 1900,00 zł brutto: odtworzone z netta dałoby 1900,01 (CLAUDE.md §1).
        val result = handler.handle(command(listOf(
            lineItem(serviceId = manualServiceId, basePriceNet = 154_472L, basePriceGross = 190_000L)
        )))

        assertEquals(190_000L, result.totalGross.amountInCents)
    }

    @Test
    fun `starszy ksztalt - zerowa baza plus SET_NET - dziala tak samo jak dotad`() = runBlocking {
        setUp(listOf(manualPriceService()))
        // Tak wysyła przyjęcie pojazdu i edycja rezerwacji (toApiServiceLineItem).
        val result = handler.handle(command(listOf(
            lineItem(
                serviceId = manualServiceId,
                basePriceNet = 0L,
                adjustmentType = AdjustmentType.SET_NET,
                adjustmentValue = 500_000.0
            )
        )))

        assertEquals(500_000L, result.totalNet.amountInCents)
    }

    @Test
    fun `darmowa usluga - cena 0 jest legalna, nie jest odrzucana ani zamieniana`() = runBlocking {
        setUp(listOf(manualPriceService()))
        // Usługa z ceną ustalaną ręcznie bywa darmowa (gratis, gest wobec klienta).
        // 0 zł to poprawna cena, a nie brak ceny - rezerwacja ma się zapisać z zerem,
        // a nie zostać odrzucona.
        val result = handler.handle(command(listOf(
            lineItem(serviceId = manualServiceId, basePriceNet = 0L, basePriceGross = 0L)
        )))

        assertEquals(0L, result.totalNet.amountInCents)
        assertEquals(0L, result.totalGross.amountInCents)
    }

    @Test
    fun `darmowa usluga w starszym ksztalcie - SET_NET 0 - tez przechodzi`() = runBlocking {
        setUp(listOf(manualPriceService()))
        // Tak wysyła ją przyjęcie pojazdu: zerowa baza plus SET_NET o wartości 0.
        val result = handler.handle(command(listOf(
            lineItem(
                serviceId = manualServiceId,
                basePriceNet = 0L,
                adjustmentType = AdjustmentType.SET_NET,
                adjustmentValue = 0.0
            )
        )))

        assertEquals(0L, result.totalNet.amountInCents)
        assertEquals(0L, result.totalGross.amountInCents)
    }

    @Test
    fun `zwykla usluga nadal bierze cene z cennika, a nie z zadania`() = runBlocking {
        setUp(listOf(catalogService()))
        // Kwota w żądaniu jest celowo inna: cennik ma tu pierwszeństwo, żeby żaden
        // klient HTTP nie mógł po cichu sprzedać usługi taniej, niż ustalono.
        val result = handler.handle(command(listOf(
            lineItem(serviceId = catalogServiceId, basePriceNet = 999_999L, basePriceGross = 1_229_999L)
        )))

        assertEquals(10_000L, result.totalNet.amountInCents)
        assertEquals(12_300L, result.totalGross.amountInCents)
    }
}
