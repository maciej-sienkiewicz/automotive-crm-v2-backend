package pl.detailing.crm.appointment.update

import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
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
 * Ta sama regresja co [pl.detailing.crm.appointment.create.CreateAppointmentHandlerTest],
 * na ścieżce edycji: PUT /v1/appointments/{id} dzieli identyczny (skopiowany) kod budowania
 * pozycji niestandardowej co POST, więc miał ten sam brak — usługa dopisana przy edycji
 * rezerwacji w QuickEventModal z ceną brutto 1900,00 zł zapisywała się jako 1900,01 zł.
 */
class UpdateAppointmentHandlerServicesTest {

    private val validatorComposite: CreateAppointmentValidatorComposite = mockk()
    private val appointmentRepository: AppointmentRepository = mockk(relaxed = true)
    private val customerRepository: CustomerRepository = mockk(relaxed = true)
    private val vehicleRepository: VehicleRepository = mockk(relaxed = true)
    private val vehicleOwnerRepository: VehicleOwnerRepository = mockk(relaxed = true)
    private val serviceRepository: ServiceRepository = mockk()
    private val auditService: AuditService = mockk(relaxed = true)
    private val vehicleResolver: AppointmentVehicleResolver = mockk(relaxed = true)
    private val leadQuoteSync: LeadQuoteSyncService = mockk(relaxed = true)

    private val handler = UpdateAppointmentHandler(
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

    private val studioId = StudioId.random()
    private val userId = UserId.random()
    private val customerId = CustomerId.random()
    private val appointmentId = AppointmentId.random()

    private fun existingEntity() = AppointmentEntity(
        id = appointmentId.value,
        studioId = studioId.value,
        customerId = customerId.value,
        vehicleId = null,
        appointmentTitle = "Wizyta wstępna",
        appointmentColorId = UUID.randomUUID(),
        isAllDay = false,
        startDateTime = Instant.parse("2026-09-15T09:00:00Z"),
        endDateTime = Instant.parse("2026-09-15T10:00:00Z"),
        status = AppointmentStatus.CREATED,
        note = null,
        createdBy = userId.value,
        updatedBy = userId.value
    )

    private fun setUp() {
        coEvery { validatorComposite.validate(any()) } just Runs
        every { serviceRepository.findActiveByStudioId(any()) } returns emptyList()
        every {
            appointmentRepository.findByIdAndStudioId(appointmentId.value, studioId.value)
        } returns existingEntity()
        // Sama sygnatura save() jest generyczna (JpaRepository<T, ID>) - relaxed mock nie
        // potrafi jej sam odtworzyć i rzuca ClassCastException, więc trzeba ją ustalić
        // jawnie. Handler i tak buduje wynik z encji, którą zmutował lokalnie, nie z tego,
        // co save() zwróci.
        every { appointmentRepository.save(any<AppointmentEntity>()) } answers { firstArg() }
    }

    private fun customServiceLineItem(basePriceNet: Long, basePriceGross: Long?, vatRate: Int = 23) =
        ServiceLineItemCommand(
            serviceId = null,
            serviceName = "Nowa usługa",
            basePriceNet = basePriceNet,
            basePriceGross = basePriceGross,
            vatRate = vatRate,
            adjustmentType = AdjustmentType.PERCENT,
            adjustmentValue = 0.0,
            customNote = null
        )

    private fun command(services: List<ServiceLineItemCommand>) = UpdateAppointmentCommand(
        appointmentId = appointmentId,
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
        appointmentTitle = "Wizyta wstępna",
        appointmentColorId = AppointmentColorId.random(),
        note = null
    )

    @Test
    fun `dopisanie usluznej pozycji z brutto 1900,00 przy edycji zapisuje dokladnie 1900,00, nie 1900,01`() = runBlocking {
        setUp()
        val result = handler.handle(command(listOf(
            customServiceLineItem(basePriceNet = 154_472L, basePriceGross = 190_000L)
        )))

        assertEquals(190_000L, result.totalGross.amountInCents)
    }

    @Test
    fun `bez basePriceGross przy edycji brutto jest odtwarzane z netta jak dawniej`() = runBlocking {
        setUp()
        val result = handler.handle(command(listOf(
            customServiceLineItem(basePriceNet = 154_472L, basePriceGross = null)
        )))

        assertEquals(190_001L, result.totalGross.amountInCents)
    }
}
