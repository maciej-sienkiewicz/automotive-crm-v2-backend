package pl.detailing.crm.appointment.update

import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pl.detailing.crm.appointment.create.*
import pl.detailing.crm.appointment.domain.AdjustmentType
import pl.detailing.crm.appointment.domain.AppointmentStatus
import pl.detailing.crm.appointment.infrastructure.AppointmentEntity
import pl.detailing.crm.appointment.infrastructure.AppointmentRepository
import pl.detailing.crm.audit.domain.AuditService
import pl.detailing.crm.customer.infrastructure.CustomerRepository
import pl.detailing.crm.leads.appointment.LeadQuoteSyncService
import pl.detailing.crm.service.infrastructure.ServiceRepository
import pl.detailing.crm.shared.*
import pl.detailing.crm.vehicle.infrastructure.VehicleOwnerRepository
import pl.detailing.crm.vehicle.infrastructure.VehicleRepository
import java.time.Instant
import java.util.UUID

/**
 * Ekran edycji rezerwacji nie ma przełącznika „całodniowa" i odsyłał flagę oryginału razem
 * z przesuniętymi datami: całodniowa z 24.09 przeciągnięta do 28.09 23:45 zapisywała się
 * jako całodniowa na pięć dni. Całodniowa może być tylko wizyta jednodniowa.
 */
class UpdateAppointmentHandlerAllDayTest {

    private val validatorComposite: CreateAppointmentValidatorComposite = mockk()
    private val appointmentRepository: AppointmentRepository = mockk(relaxed = true)
    private val serviceRepository: ServiceRepository = mockk()

    private val handler = UpdateAppointmentHandler(
        validatorComposite = validatorComposite,
        appointmentRepository = appointmentRepository,
        customerRepository = mockk<CustomerRepository>(relaxed = true),
        vehicleRepository = mockk<VehicleRepository>(relaxed = true),
        vehicleOwnerRepository = mockk<VehicleOwnerRepository>(relaxed = true),
        serviceRepository = serviceRepository,
        auditService = mockk<AuditService>(relaxed = true),
        vehicleResolver = mockk<AppointmentVehicleResolver>(relaxed = true),
        leadQuoteSync = mockk<LeadQuoteSyncService>(relaxed = true)
    )

    private val studioId = StudioId.random()
    private val userId = UserId.random()
    private val customerId = CustomerId.random()
    private val appointmentId = AppointmentId.random()
    private val saved = slot<AppointmentEntity>()

    /** Rezerwacja utworzona jako całodniowa na 24.09 (czas polski). */
    private fun givenAllDayReservation() {
        coEvery { validatorComposite.validate(any()) } just Runs
        every { serviceRepository.findActiveByStudioId(any()) } returns emptyList()
        every { appointmentRepository.findByIdAndStudioId(appointmentId.value, studioId.value) } returns AppointmentEntity(
            id = appointmentId.value,
            studioId = studioId.value,
            customerId = customerId.value,
            vehicleId = null,
            appointmentTitle = "LIDL - BMW 5, pełne zabezpieczenie",
            appointmentColorId = UUID.randomUUID(),
            isAllDay = true,
            startDateTime = Instant.parse("2026-09-23T22:00:00Z"),
            endDateTime = Instant.parse("2026-09-24T21:59:59Z"),
            status = AppointmentStatus.CREATED,
            note = null,
            createdBy = userId.value,
            updatedBy = userId.value
        )
        every { appointmentRepository.save(capture(saved)) } answers { firstArg() }
    }

    private fun command(isAllDay: Boolean, start: String, end: String) = UpdateAppointmentCommand(
        appointmentId = appointmentId,
        studioId = studioId,
        userId = userId,
        customer = CustomerIdentity.Existing(customerId),
        vehicle = VehicleIdentity.None,
        services = listOf(
            ServiceLineItemCommand(
                serviceId = null,
                serviceName = "Pełne zabezpieczenie",
                basePriceNet = 250_000L,
                vatRate = 23,
                adjustmentType = AdjustmentType.PERCENT,
                adjustmentValue = 0.0,
                customNote = null
            )
        ),
        schedule = ScheduleCommand(isAllDay = isAllDay, startDateTime = Instant.parse(start), endDateTime = Instant.parse(end)),
        appointmentTitle = "LIDL - BMW 5, pełne zabezpieczenie",
        appointmentColorId = AppointmentColorId.random(),
        note = null
    )

    @Test
    fun `calodniowa przeciagnieta na kilka dni zapisuje sie z godzinami, daty bez zmian`() = runBlocking {
        givenAllDayReservation()

        handler.handle(command(isAllDay = true, start = "2026-09-23T22:00:00Z", end = "2026-09-28T21:45:00Z"))

        assertFalse(saved.captured.isAllDay)
        assertEquals(Instant.parse("2026-09-23T22:00:00Z"), saved.captured.startDateTime)
        assertEquals(Instant.parse("2026-09-28T21:45:00Z"), saved.captured.endDateTime)
    }

    @Test
    fun `calodniowa w obrebie jednego dnia zostaje calodniowa`() = runBlocking {
        givenAllDayReservation()

        handler.handle(command(isAllDay = true, start = "2026-09-24T22:00:00Z", end = "2026-09-25T21:59:59Z"))

        assertTrue(saved.captured.isAllDay)
    }
}
