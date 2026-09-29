package pl.detailing.crm.checkin

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import pl.detailing.crm.appointment.infrastructure.AppointmentColorRepository
import pl.detailing.crm.customer.infrastructure.CustomerRepository
import pl.detailing.crm.shared.AppointmentColorId
import pl.detailing.crm.shared.EntityNotFoundException
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import pl.detailing.crm.shared.ValidationException
import pl.detailing.crm.vehicle.infrastructure.VehicleRepository
import java.time.Instant

/**
 * Przyjęcie bez rezerwacji nie jest atomowe: `@Transactional` nie obejmuje ciała funkcji
 * `suspend` przeniesionego na Dispatchers.IO, więc zapis klienta i pojazdu nie cofa się,
 * gdy później coś się nie uda. Brak koloru wychodził dopiero po ich założeniu, a ponowna
 * próba kończyła się „Klient z podanym numerem telefonu już istnieje w tym studiu".
 * (Znalezione przy pełnym teście przyjęcie → tablet na lokalnym backendzie.)
 */
class WalkInValidationBeforeWritesTest {

    private val studioId = StudioId.random()
    private val customers = mockk<CustomerRepository>(relaxed = true)
    private val vehicles = mockk<VehicleRepository>(relaxed = true)
    private val colors = mockk<AppointmentColorRepository>(relaxed = true)

    private val handler = CreateVisitFromReservationHandler(
        visitNumberGenerator = mockk(relaxed = true),
        visitRepository = mockk(relaxed = true),
        appointmentRepository = mockk(relaxed = true),
        customerRepository = customers,
        vehicleRepository = vehicles,
        vehicleOwnerRepository = mockk(relaxed = true),
        damageMapReportService = mockk(relaxed = true),
        visitDamageMapStore = mockk(relaxed = true),
        damageMarkingService = mockk(relaxed = true),
        s3DamageMapStorageService = mockk(relaxed = true),
        documentService = mockk(relaxed = true),
        serviceRepository = mockk(relaxed = true),
        photoSessionService = mockk(relaxed = true),
        checkinPhotoService = mockk(relaxed = true),
        checkinDamagePointsService = mockk(relaxed = true),
        uploadContextTokenService = mockk(relaxed = true),
        auditService = mockk(relaxed = true),
        businessEventPublisher = mockk(relaxed = true),
        appointmentCommunicationLinker = mockk(relaxed = true),
        doorToDoorRepository = mockk(relaxed = true),
        appointmentVehicleResolver = mockk(relaxed = true),
        openDraftVisitService = mockk(relaxed = true),
        appointmentColorRepository = colors,
    )

    private fun command(colorId: AppointmentColorId?) = WalkInVisitCommand(
        studioId = studioId,
        userId = UserId.random(),
        userName = "Martyna Niemier",
        startDateTime = Instant.parse("2026-09-29T11:36:00Z"),
        endDateTime = Instant.parse("2026-09-29T15:00:00Z"),
        title = null,
        customer = CustomerData.New("Jan", "Czekaj", "+48600100200", null, null, null),
        customerAlias = null,
        vehicle = VehicleData.New("Audi", "A6 Avant", 2019, "WZ 1054A", null, null),
        technicalState = TechnicalStateRequest(120_000, DepositItemRequest(keys = true, registrationDocument = true), ""),
        vehicleHandoff = null,
        photoIds = emptyList(),
        damagePoints = emptyList(),
        services = emptyList(),
        appointmentColorId = colorId,
    )

    @Test
    fun `brak koloru odrzuca przyjecie zanim powstanie klient i pojazd`() {
        assertThrows<ValidationException> { runBlocking { handler.handleWalkIn(command(colorId = null)) } }

        verify(exactly = 0) { customers.save(any()) }
        verify(exactly = 0) { vehicles.save(any()) }
    }

    @Test
    fun `kolor spoza studia odrzuca przyjecie zanim powstanie klient i pojazd`() {
        val foreign = AppointmentColorId.random()
        every { colors.findByIdAndStudioId(foreign.value, studioId.value) } returns null

        assertThrows<EntityNotFoundException> { runBlocking { handler.handleWalkIn(command(colorId = foreign)) } }

        verify(exactly = 0) { customers.save(any()) }
        verify(exactly = 0) { vehicles.save(any()) }
    }
}
