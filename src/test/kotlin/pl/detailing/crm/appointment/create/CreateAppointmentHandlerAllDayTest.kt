package pl.detailing.crm.appointment.create

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
import pl.detailing.crm.appointment.domain.AdjustmentType
import pl.detailing.crm.appointment.infrastructure.AppointmentEntity
import pl.detailing.crm.appointment.infrastructure.AppointmentRepository
import pl.detailing.crm.audit.domain.AuditService
import pl.detailing.crm.customer.infrastructure.CustomerRepository
import pl.detailing.crm.service.infrastructure.ServiceRepository
import pl.detailing.crm.shared.*
import pl.detailing.crm.vehicle.infrastructure.VehicleOwnerRepository
import pl.detailing.crm.vehicle.infrastructure.VehicleRepository
import java.time.Instant

/** Całodniowa może być tylko wizyta jednodniowa - także przy tworzeniu rezerwacji. */
class CreateAppointmentHandlerAllDayTest {

    private val validatorComposite: CreateAppointmentValidatorComposite = mockk()
    private val appointmentRepository: AppointmentRepository = mockk(relaxed = true)
    private val serviceRepository: ServiceRepository = mockk()

    private val handler = CreateAppointmentHandler(
        validatorComposite = validatorComposite,
        appointmentRepository = appointmentRepository,
        customerRepository = mockk<CustomerRepository>(relaxed = true),
        vehicleRepository = mockk<VehicleRepository>(relaxed = true),
        vehicleOwnerRepository = mockk<VehicleOwnerRepository>(relaxed = true),
        serviceRepository = serviceRepository,
        auditService = mockk<AuditService>(relaxed = true),
        vehicleResolver = mockk<AppointmentVehicleResolver>(relaxed = true),
        businessEventPublisher = mockk(relaxed = true)
    )

    private val saved = slot<AppointmentEntity>()

    private fun create(isAllDay: Boolean, start: String, end: String) = runBlocking {
        coEvery { validatorComposite.validate(any()) } just Runs
        every { serviceRepository.findActiveByStudioId(any()) } returns emptyList()
        every { appointmentRepository.save(capture(saved)) } answers { firstArg() }

        handler.handle(CreateAppointmentCommand(
            studioId = StudioId.random(),
            userId = UserId.random(),
            customer = CustomerIdentity.Existing(CustomerId.random()),
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
        ))
        saved.captured
    }

    @Test
    fun `isAllDay z koncem kilka dni dalej zapisuje wizyte z godzinami`() {
        val entity = create(isAllDay = true, start = "2026-09-23T22:00:00Z", end = "2026-09-28T21:45:00Z")

        assertFalse(entity.isAllDay)
        assertEquals(Instant.parse("2026-09-28T21:45:00Z"), entity.endDateTime)
    }

    @Test
    fun `calodniowa na jeden dzien zostaje calodniowa`() {
        assertTrue(create(isAllDay = true, start = "2026-09-23T22:00:00Z", end = "2026-09-24T21:59:59Z").isAllDay)
    }
}
