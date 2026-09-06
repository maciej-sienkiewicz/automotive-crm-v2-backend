package pl.detailing.crm.appointment.create

import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import pl.detailing.crm.appointment.infrastructure.AppointmentEntity
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import pl.detailing.crm.appointment.domain.AdjustmentType
import pl.detailing.crm.appointment.infrastructure.AppointmentRepository
import pl.detailing.crm.audit.domain.AuditService
import pl.detailing.crm.customer.infrastructure.CustomerRepository
import pl.detailing.crm.livemetrics.BusinessEventPublisher
import pl.detailing.crm.service.infrastructure.ServiceRepository
import pl.detailing.crm.shared.*
import pl.detailing.crm.vehicle.infrastructure.VehicleOwnerRepository
import pl.detailing.crm.vehicle.infrastructure.VehicleRepository
import java.time.Instant

/**
 * Regresja z produkcji: dodanie w QuickEventModal nowej usługi (bez zapisu do katalogu)
 * z ceną brutto 1900,00 zł przy 23% VAT zapisywało się jako 1900,01 zł. Ten sam scenariusz
 * w Ustawienia → Usługi zapisywał się poprawnie jako 1900,00 zł, bo tamten formularz
 * zawsze wysyła basePriceGross razem z basePriceNet.
 *
 * Przyczyna: [ServiceLineItemCommand] (i request, z którego powstaje) NIE MIAŁO pola
 * basePriceGross, więc dla usługi bez serviceId (custom service) handler musiał odtworzyć
 * brutto z netto - a to odtworzenie zaokrągla inaczej niż oryginalne przeliczenie brutto→netto
 * (1900,00 zł → netto 1544,72 zł → odtworzone brutto 1900,01 zł, bo 154472 * 1,23 zaokrągla
 * w górę). Ten test dowodzi, że skoro basePriceGross jest przekazane w komendzie, końcowe
 * brutto pozycji zostaje dokładnie takie, jak wpisał użytkownik.
 */
class CreateAppointmentHandlerTest {

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
        businessEventPublisher = businessEventPublisher
    )

    private val studioId = StudioId.random()
    private val userId = UserId.random()
    private val customerId = CustomerId.random()

    private fun setUp() {
        coEvery { validatorComposite.validate(any()) } just Runs
        every { serviceRepository.findActiveByStudioId(any()) } returns emptyList()
        // Zwrot save() nie jest tym, co handler dalej czyta (wynik budowany jest z
        // lokalnego obiektu domenowego) - relaxed mock samodzielnie nie potrafi jednak
        // odtworzyć wartości dla generycznej sygnatury JpaRepository.save(), więc trzeba
        // ją jawnie ustalić.
        every { appointmentRepository.save(any<AppointmentEntity>()) } answers { firstArg() }
    }

    private fun customServiceLineItem(
        basePriceNet: Long,
        basePriceGross: Long?,
        vatRate: Int = 23
    ) = ServiceLineItemCommand(
        serviceId = null,
        serviceName = "Nowa usługa",
        basePriceNet = basePriceNet,
        basePriceGross = basePriceGross,
        vatRate = vatRate,
        adjustmentType = AdjustmentType.PERCENT,
        adjustmentValue = 0.0,
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
            startDateTime = Instant.parse("2026-09-15T09:00:00Z"),
            endDateTime = Instant.parse("2026-09-15T10:00:00Z")
        ),
        appointmentTitle = "Wizyta testowa",
        appointmentColorId = AppointmentColorId.random(),
        note = null
    )

    @Test
    fun `brutto 1900,00 wpisane przy tworzeniu wpisuje sie dokladnie 1900,00 zl, nie 1900,01`() = runBlocking {
        setUp()
        // 1900,00 zł brutto przy 23% VAT -> netto = round(190000 * 100 / 123) = 154472 gr.
        // Odtworzenie brutto z tego netta (round(154472 * 1,23)) daje 190001 gr, nie 190000 -
        // stąd zgłoszony błąd, gdy basePriceGross nie dojeżdżał do handlera.
        val result = handler.handle(command(listOf(
            customServiceLineItem(basePriceNet = 154_472L, basePriceGross = 190_000L)
        )))

        assertEquals(190_000L, result.totalGross.amountInCents)
    }

    @Test
    fun `bez basePriceGross (starszy klient) brutto jest odtwarzane z netta - moze zajsc 1-groszowy dryf`() = runBlocking {
        setUp()
        // Dokumentuje zachowanie SPRZED naprawy jako wciąż dostępną (tolerowaną) ścieżkę:
        // brak basePriceGross w żądaniu nie jest błędem, tylko brakiem informacji - backend
        // wtedy liczy brutto ze wzoru VAT "od sta", co dla tej konkretnej kwoty daje 1 grosz
        // więcej niż to, co użytkownik faktycznie wpisał.
        val result = handler.handle(command(listOf(
            customServiceLineItem(basePriceNet = 154_472L, basePriceGross = null)
        )))

        assertEquals(190_001L, result.totalGross.amountInCents)
    }

    @Test
    fun `basePriceGross przy VAT zwolnionym rowniez jest zachowywany dokladnie`() = runBlocking {
        setUp()
        val result = handler.handle(command(listOf(
            customServiceLineItem(basePriceNet = 190_000L, basePriceGross = 190_000L, vatRate = -1)
        )))

        assertEquals(190_000L, result.totalGross.amountInCents)
    }

    @Test
    fun `kilka pozycji niestandardowych - kazda zachowuje swoje wlasne dokladne brutto`() = runBlocking {
        setUp()
        val result = handler.handle(command(listOf(
            customServiceLineItem(basePriceNet = 154_472L, basePriceGross = 190_000L),
            customServiceLineItem(basePriceNet = 8_102L, basePriceGross = 8_750L, vatRate = 8)
        )))

        assertEquals(190_000L + 8_750L, result.totalGross.amountInCents)
    }
}
