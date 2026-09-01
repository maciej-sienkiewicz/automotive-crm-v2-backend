package pl.detailing.crm.appointment.update

import io.mockk.coEvery
import io.mockk.just
import io.mockk.mockk
import io.mockk.Runs
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import pl.detailing.crm.appointment.create.CustomerIdentity
import pl.detailing.crm.appointment.create.DoorToDoorAppointmentCommand
import pl.detailing.crm.appointment.create.ScheduleCommand
import pl.detailing.crm.appointment.create.ServiceLineItemCommand
import pl.detailing.crm.appointment.create.VehicleIdentity
import pl.detailing.crm.appointment.domain.AdjustmentType
import pl.detailing.crm.appointment.domain.AppointmentStatus
import pl.detailing.crm.appointment.infrastructure.AppointmentColorEntity
import pl.detailing.crm.appointment.infrastructure.AppointmentColorRepository
import pl.detailing.crm.appointment.infrastructure.AppointmentEntity
import pl.detailing.crm.appointment.infrastructure.AppointmentRepository
import pl.detailing.crm.audit.domain.AuditService
import pl.detailing.crm.customer.infrastructure.CustomerEntity
import pl.detailing.crm.customer.infrastructure.CustomerRepository
import pl.detailing.crm.leads.appointment.LeadQuoteSyncService
import pl.detailing.crm.service.infrastructure.ServiceEntity
import pl.detailing.crm.service.infrastructure.ServiceRepository
import pl.detailing.crm.shared.*
import pl.detailing.crm.vehicle.infrastructure.VehicleEntity
import pl.detailing.crm.vehicle.infrastructure.VehicleOwnerRepository
import pl.detailing.crm.vehicle.infrastructure.VehicleRepository
import java.time.Instant
import java.util.UUID

/**
 * Dowód, że edycja rezerwacji NAPRAWDĘ zapisuje każde pole do bazy — nie tylko że handler
 * poprawnie mutuje obiekt encji w pamięci (co udowadnia mockowane repozytorium), ale że
 * wartość przetrwa realny round-trip przez Hibernate i Postgresa: zapis, `flush()`,
 * `clear()` kontekstu persystencji (żeby kolejny odczyt NIE trafił w cache pierwszego
 * poziomu i wymusił prawdziwy SELECT), i dopiero wtedy odczyt.
 *
 * WYMAGA DEMONA DOCKERA na maszynie/agencie CI, który uruchamia testy — Testcontainers sam
 * podnosi kontener z Postgresem. To PIERWSZY tego typu test w repozytorium: jeśli agent
 * Jenkinsa z etykietą `docker` buduje ten projekt WEWNĄTRZ kontenera `gradle:8.14-jdk17`
 * (patrz Jenkinsfile), Testcontainers wewnątrz tego kontenera potrzebuje dostępu do soketa
 * Dockera hosta (montaż `/var/run/docker.sock` albo Docker-in-Docker) — bez tego test
 * nie wystartuje.
 *
 * Dlatego jest oznaczony `@Tag("testcontainers")` i WYŁĄCZONY z domyślnego `./gradlew test`
 * (patrz `tasks.withType<Test>` w build.gradle.kts) — dokładnie tym samym wzorcem opt-in co
 * `-PksefStub` w tym samym pliku. Zanim ten test wejdzie do domyślnego uruchomienia CI,
 * potwierdź dostęp do Dockera na agencie i uruchom `./gradlew test -PrunTestcontainers`.
 * W tym środowisku deweloperskim/agentowym Docker był niedostępny (`docker info` failed),
 * więc ten plik jest sprawdzony pod kątem kompilacji, ale NIE uruchomiony.
 *
 * Zakres: każde pole [UpdateAppointmentCommand] dostaje własny scenariusz. Zależności
 * poboczne (walidacja, audyt, synchronizacja wyceny leada, rozwiązywanie duplikatów
 * pojazdu) są mockowane — nie są tym, co ten test dowodzi; repozytoria JPA są PRAWDZIWE,
 * podłączone do kontenera Postgresa przez `@ServiceConnection`.
 */
@Tag("testcontainers")
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class UpdateAppointmentHandlerPersistenceTest {

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:16-alpine")
    }

    @Autowired lateinit var entityManager: TestEntityManager
    @Autowired lateinit var appointmentRepository: AppointmentRepository
    @Autowired lateinit var customerRepository: CustomerRepository
    @Autowired lateinit var vehicleRepository: VehicleRepository
    @Autowired lateinit var vehicleOwnerRepository: VehicleOwnerRepository
    @Autowired lateinit var serviceRepository: ServiceRepository
    @Autowired lateinit var appointmentColorRepository: AppointmentColorRepository

    private lateinit var handler: UpdateAppointmentHandler

    private val studioId = StudioId.random()
    private val userId = UserId.random()

    private lateinit var customer: CustomerEntity
    private lateinit var vehicle: VehicleEntity
    private lateinit var color: AppointmentColorEntity
    private lateinit var appointment: AppointmentEntity

    @BeforeEach
    fun setUp() {
        // Zależności poboczne — mockowane, bo nie są tym, co ten test dowodzi (patrz KDoc klasy).
        val auditService = mockk<AuditService>(relaxed = true)
        val leadQuoteSync = mockk<LeadQuoteSyncService>(relaxed = true)
        val vehicleResolver = mockk<pl.detailing.crm.appointment.create.AppointmentVehicleResolver>(relaxed = true)
        val validatorComposite = mockk<pl.detailing.crm.appointment.create.CreateAppointmentValidatorComposite>()
        coEvery { validatorComposite.validate(any()) } just Runs

        handler = UpdateAppointmentHandler(
            validatorComposite = validatorComposite,
            appointmentRepository = appointmentRepository,
            customerRepository = customerRepository,
            vehicleRepository = vehicleRepository,
            vehicleOwnerRepository = vehicleOwnerRepository,
            serviceRepository = serviceRepository,
            auditService = auditService,
            vehicleResolver = vehicleResolver,
            leadQuoteSync = leadQuoteSync
        )

        color = persist(appointmentColorEntity())
        customer = persist(customerEntity())
        vehicle = persist(vehicleEntity())
        appointment = persist(baselineAppointment())
        entityManager.flush()
    }

    // ─── Seeding helpers ────────────────────────────────────────────────────────

    private fun <T> persist(entity: T): T {
        entityManager.persist(entity)
        return entity
    }

    private fun customerEntity(suffix: String = "1") = CustomerEntity(
        id = UUID.randomUUID(),
        studioId = studioId.value,
        firstName = "Jan$suffix",
        lastName = "Kowalski$suffix",
        email = "jan$suffix@example.pl",
        phone = "+4860010000$suffix".take(12),
        homeAddressStreet = null,
        homeAddressCity = null,
        homeAddressPostalCode = null,
        homeAddressCountry = null,
        companyName = null,
        companyNip = null,
        companyRegon = null,
        companyAddressStreet = null,
        companyAddressCity = null,
        companyAddressPostalCode = null,
        companyAddressCountry = null,
        createdBy = userId.value,
        updatedBy = userId.value
    )

    private fun vehicleEntity(suffix: String = "1") = VehicleEntity(
        id = UUID.randomUUID(),
        studioId = studioId.value,
        licensePlate = "WA$suffix 12345",
        brand = "Toyota",
        model = "Corolla",
        yearOfProduction = 2018,
        color = null,
        paintType = null,
        currentMileage = 0,
        status = VehicleStatus.ACTIVE,
        createdBy = userId.value,
        updatedBy = userId.value
    )

    private fun appointmentColorEntity() = AppointmentColorEntity(
        id = UUID.randomUUID(),
        studioId = studioId.value,
        name = "Detailing",
        hexColor = "#3B82F6",
        createdBy = userId.value,
        updatedBy = userId.value
    )

    private fun serviceEntity(name: String, priceNet: Long, priceGross: Long, vat: Int) = ServiceEntity(
        id = UUID.randomUUID(),
        studioId = studioId.value,
        name = name,
        basePriceNet = priceNet,
        basePriceGross = priceGross,
        vatRate = vat,
        replacesServiceId = null,
        createdBy = userId.value,
        updatedBy = userId.value
    )

    private fun baselineAppointment() = AppointmentEntity(
        id = UUID.randomUUID(),
        studioId = studioId.value,
        customerId = customer.id,
        vehicleId = vehicle.id,
        appointmentTitle = "Wizyta wstępna",
        appointmentColorId = color.id,
        isAllDay = false,
        startDateTime = Instant.parse("2026-01-10T09:00:00Z"),
        endDateTime = Instant.parse("2026-01-10T11:00:00Z"),
        status = AppointmentStatus.CREATED,
        note = "Notatka początkowa",
        internalNote = "Notatka wewnętrzna początkowa",
        protocolNote = "Notatka protokołu początkowa",
        createdBy = userId.value,
        updatedBy = userId.value
    )

    private fun baseCommand(
        customer: CustomerIdentity = CustomerIdentity.Existing(CustomerId(this.customer.id)),
        vehicle: VehicleIdentity = VehicleIdentity.Existing(VehicleId(this.vehicle.id)),
        services: List<ServiceLineItemCommand> = listOf(customServiceItem()),
        schedule: ScheduleCommand = ScheduleCommand(
            isAllDay = false,
            startDateTime = Instant.parse("2026-01-10T09:00:00Z"),
            endDateTime = Instant.parse("2026-01-10T11:00:00Z")
        ),
        appointmentTitle: String? = "Wizyta wstępna",
        appointmentColorId: AppointmentColorId = AppointmentColorId(color.id),
        note: String? = "Notatka początkowa",
        internalNote: String? = "Notatka wewnętrzna początkowa",
        protocolNote: String? = "Notatka protokołu początkowa",
        doorToDoor: DoorToDoorAppointmentCommand? = null
    ) = UpdateAppointmentCommand(
        appointmentId = AppointmentId(appointment.id),
        studioId = studioId,
        userId = userId,
        userName = "Testowy Właściciel",
        customer = customer,
        vehicle = vehicle,
        services = services,
        schedule = schedule,
        appointmentTitle = appointmentTitle,
        appointmentColorId = appointmentColorId,
        note = note,
        internalNote = internalNote,
        protocolNote = protocolNote,
        doorToDoor = doorToDoor
    )

    private fun customServiceItem(
        name: String = "Mycie ręczne",
        basePriceNet: Long = 10_000L,
        vatRate: Int = 23,
        adjustmentType: AdjustmentType = AdjustmentType.PERCENT,
        adjustmentValue: Double = 0.0,
        note: String? = null
    ) = ServiceLineItemCommand(
        serviceId = null,
        serviceName = name,
        basePriceNet = basePriceNet,
        vatRate = vatRate,
        adjustmentType = adjustmentType,
        adjustmentValue = adjustmentValue,
        customNote = note
    )

    /** Wymusza prawdziwy SELECT z Postgresa: bez tego drugi odczyt trafiłby w cache L1. */
    private fun reloadAppointment(): AppointmentEntity {
        entityManager.flush()
        entityManager.clear()
        return appointmentRepository.findByIdAndStudioId(appointment.id, studioId.value)
            ?: error("Rezerwacja zniknęła po aktualizacji")
    }

    private fun reloadCustomer(id: UUID) =
        customerRepository.findByIdAndStudioId(id, studioId.value) ?: error("Klient zniknął po aktualizacji")

    private fun reloadVehicle(id: UUID) =
        vehicleRepository.findByIdAndStudioId(id, studioId.value) ?: error("Pojazd zniknął po aktualizacji")

    // ─── appointmentTitle ───────────────────────────────────────────────────────

    @Test
    fun `appointmentTitle zapisuje sie i przetrwa realny odczyt z bazy`() = runBlocking {
        handler.handle(baseCommand(appointmentTitle = "Wymiana opon zimowych"))

        assertEquals("Wymiana opon zimowych", reloadAppointment().appointmentTitle)
    }

    @Test
    fun `appointmentTitle mozna wyzerowac do null`() = runBlocking {
        handler.handle(baseCommand(appointmentTitle = null))

        assertNull(reloadAppointment().appointmentTitle)
    }

    // ─── appointmentColorId ─────────────────────────────────────────────────────

    @Test
    fun `appointmentColorId zmienia sie na inny kolor studia`() = runBlocking {
        val otherColor = persist(appointmentColorEntity().also { it.name = "Pilne" })
        entityManager.flush()

        handler.handle(baseCommand(appointmentColorId = AppointmentColorId(otherColor.id)))

        assertEquals(otherColor.id, reloadAppointment().appointmentColorId)
    }

    // ─── note / internalNote / protocolNote ────────────────────────────────────

    @Test
    fun `note internalNote i protocolNote zapisuja sie niezaleznie`() = runBlocking {
        handler.handle(baseCommand(
            note = "Nowa notatka klienta",
            internalNote = "Nowa notatka wewnętrzna",
            protocolNote = "Nowa notatka na protokół"
        ))

        val reloaded = reloadAppointment()
        assertEquals("Nowa notatka klienta", reloaded.note)
        assertEquals("Nowa notatka wewnętrzna", reloaded.internalNote)
        assertEquals("Nowa notatka na protokół", reloaded.protocolNote)
    }

    @Test
    fun `note internalNote i protocolNote mozna wyzerowac do null niezaleznie`() = runBlocking {
        handler.handle(baseCommand(note = null, internalNote = null, protocolNote = "zostaje"))

        val reloaded = reloadAppointment()
        assertNull(reloaded.note)
        assertNull(reloaded.internalNote)
        assertEquals("zostaje", reloaded.protocolNote)
    }

    // ─── schedule (isAllDay / startDateTime / endDateTime) ─────────────────────

    @Test
    fun `schedule zmienia daty rozpoczecia i zakonczenia`() = runBlocking {
        val newStart = Instant.parse("2026-03-05T14:00:00Z")
        val newEnd = Instant.parse("2026-03-05T16:30:00Z")

        handler.handle(baseCommand(schedule = ScheduleCommand(isAllDay = false, startDateTime = newStart, endDateTime = newEnd)))

        val reloaded = reloadAppointment()
        assertEquals(newStart, reloaded.startDateTime)
        assertEquals(newEnd, reloaded.endDateTime)
        assertEquals(false, reloaded.isAllDay)
    }

    @Test
    fun `schedule isAllDay przelacza sie na true`() = runBlocking {
        val day = Instant.parse("2026-03-05T00:00:00Z")
        val dayEnd = Instant.parse("2026-03-05T23:59:59Z")

        handler.handle(baseCommand(schedule = ScheduleCommand(isAllDay = true, startDateTime = day, endDateTime = dayEnd)))

        assertTrue(reloadAppointment().isAllDay)
    }

    // ─── customer: Update (imię/nazwisko/telefon/e-mail zapisują się na CustomerEntity) ──

    @Test
    fun `customer Update zapisuje zmienione dane klienta w tabeli klientow`() = runBlocking {
        handler.handle(baseCommand(
            customer = CustomerIdentity.Update(
                customerId = CustomerId(customer.id),
                firstName = "Adam",
                lastName = "Nowak",
                phone = "+48600999888",
                email = "adam.nowak@example.pl",
                companyName = null,
                companyNip = null,
                companyRegon = null,
                companyAddress = null
            )
        ))

        val reloaded = reloadCustomer(customer.id)
        assertEquals("Adam", reloaded.firstName)
        assertEquals("Nowak", reloaded.lastName)
        assertEquals("+48600999888", reloaded.phone)
        assertEquals("adam.nowak@example.pl", reloaded.email)
        // Rezerwacja dalej wskazuje na TEGO SAMEGO klienta — Update edytuje, nie podmienia.
        assertEquals(customer.id, reloadAppointment().customerId)
    }

    @Test
    fun `customer Update zapisuje dane firmy`() = runBlocking {
        handler.handle(baseCommand(
            customer = CustomerIdentity.Update(
                customerId = CustomerId(customer.id),
                firstName = customer.firstName,
                lastName = customer.lastName,
                phone = customer.phone,
                email = customer.email,
                companyName = "Firma Testowa Sp. z o.o.",
                companyNip = "1234567890",
                companyRegon = "123456789",
                companyAddress = "ul. Testowa 1, 00-001 Warszawa"
            )
        ))

        val reloaded = reloadCustomer(customer.id)
        assertEquals("Firma Testowa Sp. z o.o.", reloaded.companyName)
        assertEquals("1234567890", reloaded.companyNip)
        assertEquals("123456789", reloaded.companyRegon)
        assertEquals("ul. Testowa 1, 00-001 Warszawa", reloaded.companyAddressStreet)
    }

    @Test
    fun `customer Existing przelacza rezerwacje na innego juz istniejacego klienta`() = runBlocking {
        val otherCustomer = persist(customerEntity(suffix = "2"))
        entityManager.flush()

        handler.handle(baseCommand(customer = CustomerIdentity.Existing(CustomerId(otherCustomer.id))))

        assertEquals(otherCustomer.id, reloadAppointment().customerId)
    }

    // ─── vehicle: Update (marka/model/rok/tablica zapisują się na VehicleEntity) ─────────

    @Test
    fun `vehicle Update zapisuje marke model rok i tablice rejestracyjna`() = runBlocking {
        // To pole jest tu celowo najważniejsze: frontend ma potwierdzony bug wysyłania
        // klucza JSON "yearOfProduction" zamiast oczekiwanego przez backend "year" —
        // ten test dowodzi, że BACKEND poprawnie persystuje rok, gdy dostanie go pod
        // właściwym kluczem, więc naprawa należy wyłącznie po stronie frontendu.
        handler.handle(baseCommand(
            vehicle = VehicleIdentity.Update(
                vehicleId = VehicleId(vehicle.id),
                brand = "Volkswagen",
                model = "Passat",
                year = 2021,
                licensePlate = "KR9 88877"
            )
        ))

        val reloaded = reloadVehicle(vehicle.id)
        assertEquals("Volkswagen", reloaded.brand)
        assertEquals("Passat", reloaded.model)
        assertEquals(2021, reloaded.yearOfProduction)
        assertEquals("KR9 88877", reloaded.licensePlate)
    }

    @Test
    fun `vehicle None usuwa powiazanie pojazdu z rezerwacji`() = runBlocking {
        handler.handle(baseCommand(vehicle = VehicleIdentity.None))

        assertNull(reloadAppointment().vehicleId)
    }

    @Test
    fun `vehicle Existing przelacza rezerwacje na inny juz istniejacy pojazd`() = runBlocking {
        val otherVehicle = persist(vehicleEntity(suffix = "2"))
        entityManager.flush()

        handler.handle(baseCommand(vehicle = VehicleIdentity.Existing(VehicleId(otherVehicle.id))))

        assertEquals(otherVehicle.id, reloadAppointment().vehicleId)
    }

    // ─── services: pozycja niestandardowa (bez katalogowego serviceId) ─────────────────

    @Test
    fun `pozycja niestandardowa zapisuje nazwe cene vat i notatke wprost z zadania`() = runBlocking {
        handler.handle(baseCommand(services = listOf(
            customServiceItem(name = "Renowacja reflektorów", basePriceNet = 25_000L, vatRate = 8, note = "Uwaga: matowa osłona")
        )))

        val items = reloadAppointment().lineItems
        assertEquals(1, items.size)
        assertEquals("Renowacja reflektorów", items[0].serviceName)
        assertEquals(25_000L, items[0].basePriceNet)
        assertEquals(8, items[0].vatRate)
        assertEquals("Uwaga: matowa osłona", items[0].customNote)
        assertNull(items[0].serviceId)
    }

    @Test
    fun `pozycja z katalogu bierze nazwe i cene z uslugi, nie z zadania`() = runBlocking {
        val catalogService = persist(serviceEntity("Powłoka ceramiczna", priceNet = 80_000L, priceGross = 98_400L, vat = 23))
        entityManager.flush()

        handler.handle(baseCommand(services = listOf(
            ServiceLineItemCommand(
                serviceId = ServiceId(catalogService.id),
                // Backend świadomie ignoruje te dwie wartości z żądania dla usługi
                // katalogowej — bierze aktualną cenę i nazwę z bazy usług.
                serviceName = "stara nazwa z formularza",
                basePriceNet = 1L,
                vatRate = 23,
                adjustmentType = AdjustmentType.PERCENT,
                adjustmentValue = 0.0,
                customNote = null
            )
        )))

        val items = reloadAppointment().lineItems
        assertEquals(1, items.size)
        assertEquals(catalogService.id, items[0].serviceId)
        assertEquals("Powłoka ceramiczna", items[0].serviceName)
        assertEquals(80_000L, items[0].basePriceNet)
    }

    @Test
    fun `rabat procentowy przelicza sie na punkty bazowe`() = runBlocking {
        // 20% rabatu -> convertPercentValueToBasisPoints (patrz AdjustmentType) koduje
        // rabat/narzut w jednej wartości całkowitej zamiast Double na kolumnie bazy.
        handler.handle(baseCommand(services = listOf(
            customServiceItem(adjustmentType = AdjustmentType.PERCENT, adjustmentValue = 20.0)
        )))

        val items = reloadAppointment().lineItems
        assertEquals(AdjustmentType.PERCENT, items[0].adjustmentType)
        assertEquals(
            AdjustmentType.convertPercentValueToBasisPoints(20.0),
            items[0].adjustmentValue
        )
    }

    @Test
    fun `zamiana listy uslug podmienia wszystkie pozycje, nie dokleja ich`() = runBlocking {
        handler.handle(baseCommand(services = listOf(
            customServiceItem(name = "Usługa A"), customServiceItem(name = "Usługa B")
        )))
        handler.handle(baseCommand(services = listOf(customServiceItem(name = "Usługa C"))))

        val items = reloadAppointment().lineItems
        assertEquals(1, items.size)
        assertEquals("Usługa C", items[0].serviceName)
    }

    // ─── doorToDoor: wszystkie 5 pól + wyłączenie zeruje wszystko ──────────────────────

    @Test
    fun `doorToDoor zapisuje wszystkie pola adresu odbioru i dostawy`() = runBlocking {
        handler.handle(baseCommand(doorToDoor = DoorToDoorAppointmentCommand(
            pickupCity = "Warszawa",
            pickupStreet = "ul. Odbiorcza 1",
            deliveryCity = "Kraków",
            deliveryStreet = "ul. Dostawcza 2",
            notes = "Domofon 42"
        )))

        val reloaded = reloadAppointment()
        assertEquals("Warszawa", reloaded.d2dPickupCity)
        assertEquals("ul. Odbiorcza 1", reloaded.d2dPickupStreet)
        assertEquals("Kraków", reloaded.d2dDeliveryCity)
        assertEquals("ul. Dostawcza 2", reloaded.d2dDeliveryStreet)
        assertEquals("Domofon 42", reloaded.d2dNotes)
    }

    @Test
    fun `wylaczenie doorToDoor zeruje wszystkie jego pola`() = runBlocking {
        handler.handle(baseCommand(doorToDoor = DoorToDoorAppointmentCommand(
            pickupCity = "Warszawa", pickupStreet = "ul. Odbiorcza 1",
            deliveryCity = "Kraków", deliveryStreet = "ul. Dostawcza 2", notes = "Domofon 42"
        )))

        // Drugie wywołanie bez doorToDoor = użytkownik odznaczył opcję w formularzu.
        handler.handle(baseCommand(doorToDoor = null))

        val reloaded = reloadAppointment()
        assertNull(reloaded.d2dPickupCity)
        assertNull(reloaded.d2dPickupStreet)
        assertNull(reloaded.d2dDeliveryCity)
        assertNull(reloaded.d2dDeliveryStreet)
        assertNull(reloaded.d2dNotes)
    }

    // ─── wszystkie pola naraz — kontrola integracyjna spinająca całość ─────────────────

    @Test
    fun `zmiana wszystkich pol naraz zapisuje sie spojnie`() = runBlocking {
        val otherColor = persist(appointmentColorEntity().also { it.name = "Priorytet" })
        entityManager.flush()

        handler.handle(baseCommand(
            customer = CustomerIdentity.Update(
                customerId = CustomerId(customer.id), firstName = "Ewa", lastName = "Zielińska",
                phone = "+48111222333", email = "ewa@example.pl",
                companyName = null, companyNip = null, companyRegon = null, companyAddress = null
            ),
            vehicle = VehicleIdentity.Update(
                vehicleId = VehicleId(vehicle.id), brand = "Skoda", model = "Octavia",
                year = 2020, licensePlate = "GD1 23456"
            ),
            services = listOf(customServiceItem(name = "Detailing pełny", basePriceNet = 50_000L)),
            schedule = ScheduleCommand(
                isAllDay = false,
                startDateTime = Instant.parse("2026-06-01T08:00:00Z"),
                endDateTime = Instant.parse("2026-06-01T12:00:00Z")
            ),
            appointmentTitle = "Zmieniony tytuł",
            appointmentColorId = AppointmentColorId(otherColor.id),
            note = "Nowa notatka", internalNote = "Nowa wewnętrzna", protocolNote = "Nowa protokołowa",
            doorToDoor = DoorToDoorAppointmentCommand("Poznań", "ul. Nowa 1", "Poznań", "ul. Nowa 2", "Uwaga")
        ))

        val reloadedAppt = reloadAppointment()
        assertEquals("Zmieniony tytuł", reloadedAppt.appointmentTitle)
        assertEquals(otherColor.id, reloadedAppt.appointmentColorId)
        assertEquals("Nowa notatka", reloadedAppt.note)
        assertEquals("Nowa wewnętrzna", reloadedAppt.internalNote)
        assertEquals("Nowa protokołowa", reloadedAppt.protocolNote)
        assertEquals(Instant.parse("2026-06-01T08:00:00Z"), reloadedAppt.startDateTime)
        assertEquals(Instant.parse("2026-06-01T12:00:00Z"), reloadedAppt.endDateTime)
        assertEquals("Poznań", reloadedAppt.d2dPickupCity)
        assertEquals(1, reloadedAppt.lineItems.size)
        assertEquals("Detailing pełny", reloadedAppt.lineItems[0].serviceName)

        val reloadedCustomer = reloadCustomer(customer.id)
        assertEquals("Ewa", reloadedCustomer.firstName)
        assertEquals("Zielińska", reloadedCustomer.lastName)

        val reloadedVehicle = reloadVehicle(vehicle.id)
        assertEquals("Skoda", reloadedVehicle.brand)
        assertEquals(2020, reloadedVehicle.yearOfProduction)
    }
}
