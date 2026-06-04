package pl.detailing.crm.appointment.get

import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import pl.detailing.crm.appointment.domain.AdjustmentType
import pl.detailing.crm.appointment.domain.AppointmentStatus
import pl.detailing.crm.appointment.infrastructure.AppointmentColorEntity
import pl.detailing.crm.appointment.infrastructure.AppointmentColorRepository
import pl.detailing.crm.appointment.infrastructure.AppointmentEntity
import pl.detailing.crm.appointment.infrastructure.AppointmentLineItemEntity
import pl.detailing.crm.appointment.infrastructure.AppointmentRepository
import pl.detailing.crm.customer.infrastructure.CustomerEntity
import pl.detailing.crm.customer.infrastructure.CustomerRepository
import pl.detailing.crm.shared.*
import pl.detailing.crm.smscampaigns.infrastructure.SmsLogJpaRepository
import pl.detailing.crm.vehicle.infrastructure.VehicleEntity
import pl.detailing.crm.vehicle.infrastructure.VehicleRepository
import java.time.Instant
import java.util.Optional
import java.util.UUID

class GetAppointmentHandlerTest {

    private val appointmentRepository = mockk<AppointmentRepository>()
    private val customerRepository = mockk<CustomerRepository>()
    private val vehicleRepository = mockk<VehicleRepository>()
    private val appointmentColorRepository = mockk<AppointmentColorRepository>()
    private val smsLogRepository = mockk<SmsLogJpaRepository>()

    private val handler = GetAppointmentHandler(
        appointmentRepository,
        customerRepository,
        vehicleRepository,
        appointmentColorRepository,
        smsLogRepository
    )

    private val studioId = StudioId.random()
    private val userId = UserId.random()
    private val appointmentId = AppointmentId.random()
    private val colorId = UUID.randomUUID()
    private val seriesId = UUID.randomUUID()

    // ─── recurrenceInfo ───────────────────────────────────────────────────────

    @Test
    fun `recurrenceInfo is null when appointment has no series`() = runBlocking {
        val entity = appointmentEntity(recurrenceSeriesId = null, recurrenceIndex = null)
        givenAppointmentFound(entity)

        val result = handler.handle(appointmentId, studioId)

        assertNull(result.recurrenceInfo)
    }

    @Test
    fun `recurrenceInfo is populated when appointment belongs to a series`() = runBlocking {
        val entity = appointmentEntity(recurrenceSeriesId = seriesId, recurrenceIndex = 1)
        givenAppointmentFound(entity)
        every { appointmentRepository.countBySeriesId(seriesId) } returns 4L

        val result = handler.handle(appointmentId, studioId)

        assertNotNull(result.recurrenceInfo)
    }

    @Test
    fun `recurrenceInfo seriesId matches appointment seriesId`() = runBlocking {
        val entity = appointmentEntity(recurrenceSeriesId = seriesId, recurrenceIndex = 1)
        givenAppointmentFound(entity)
        every { appointmentRepository.countBySeriesId(seriesId) } returns 4L

        val result = handler.handle(appointmentId, studioId)

        assertEquals(seriesId.toString(), result.recurrenceInfo?.seriesId)
    }

    @Test
    fun `recurrenceInfo recurrenceIndex matches appointment index`() = runBlocking {
        val entity = appointmentEntity(recurrenceSeriesId = seriesId, recurrenceIndex = 3)
        givenAppointmentFound(entity)
        every { appointmentRepository.countBySeriesId(seriesId) } returns 5L

        val result = handler.handle(appointmentId, studioId)

        assertEquals(3, result.recurrenceInfo?.recurrenceIndex)
    }

    @Test
    fun `recurrenceInfo totalInSeries comes from countBySeriesId`() = runBlocking {
        val entity = appointmentEntity(recurrenceSeriesId = seriesId, recurrenceIndex = 0)
        givenAppointmentFound(entity)
        every { appointmentRepository.countBySeriesId(seriesId) } returns 7L

        val result = handler.handle(appointmentId, studioId)

        assertEquals(7L, result.recurrenceInfo?.totalInSeries)
    }

    @Test
    fun `recurrenceInfo isDetached reflects appointment flag`() = runBlocking {
        val entity = appointmentEntity(recurrenceSeriesId = seriesId, recurrenceIndex = 0, isDetached = true)
        givenAppointmentFound(entity)
        every { appointmentRepository.countBySeriesId(seriesId) } returns 3L

        val result = handler.handle(appointmentId, studioId)

        assertTrue(result.recurrenceInfo?.isDetached == true)
    }

    @Test
    fun `countBySeriesId is called with correct seriesId`() = runBlocking {
        val entity = appointmentEntity(recurrenceSeriesId = seriesId, recurrenceIndex = 0)
        givenAppointmentFound(entity)
        every { appointmentRepository.countBySeriesId(seriesId) } returns 2L

        handler.handle(appointmentId, studioId)

        verify(exactly = 1) { appointmentRepository.countBySeriesId(seriesId) }
    }

    @Test
    fun `countBySeriesId is not called when appointment has no series`() = runBlocking {
        val entity = appointmentEntity(recurrenceSeriesId = null, recurrenceIndex = null)
        givenAppointmentFound(entity)

        handler.handle(appointmentId, studioId)

        verify(exactly = 0) { appointmentRepository.countBySeriesId(any()) }
    }

    // ─── Basic appointment data ───────────────────────────────────────────────

    @Test
    fun `returns appointment id`() = runBlocking {
        val entity = appointmentEntity()
        givenAppointmentFound(entity)

        val result = handler.handle(appointmentId, studioId)

        assertEquals(appointmentId.value.toString(), result.id)
    }

    @Test
    fun `throws NotFoundException when appointment not found`() {
        every { appointmentRepository.findByIdAndStudioId(appointmentId.value, studioId.value) } returns null

        assertThrows<NotFoundException> {
            runBlocking { handler.handle(appointmentId, studioId) }
        }
    }

    @Test
    fun `returns correct totalNet from domain calculation`() = runBlocking {
        val entity = appointmentEntity(basePrice = 50000L)
        givenAppointmentFound(entity)

        val result = handler.handle(appointmentId, studioId)

        assertEquals(50000L, result.totalNet)
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private fun givenAppointmentFound(entity: AppointmentEntity) {
        every { appointmentRepository.findByIdAndStudioId(appointmentId.value, studioId.value) } returns entity
        every { customerRepository.findById(entity.customerId) } returns Optional.of(customerEntity())
        every { vehicleRepository.findById(entity.vehicleId ?: any()) } returns Optional.empty()
        every { appointmentColorRepository.findById(colorId) } returns Optional.of(colorEntity())
        every { smsLogRepository.findAllByAppointmentIdIn(listOf(entity.id)) } returns emptyList()
    }

    private fun appointmentEntity(
        recurrenceSeriesId: UUID? = null,
        recurrenceIndex: Int? = null,
        isDetached: Boolean = false,
        basePrice: Long = 30000L
    ): AppointmentEntity {
        val entity = AppointmentEntity(
            id = appointmentId.value,
            studioId = studioId.value,
            customerId = UUID.randomUUID(),
            vehicleId = null,
            appointmentTitle = null,
            appointmentColorId = colorId,
            isAllDay = false,
            startDateTime = Instant.now(),
            endDateTime = Instant.now().plusSeconds(3600),
            status = AppointmentStatus.CREATED,
            note = null,
            createdBy = userId.value,
            updatedBy = userId.value,
            recurrenceSeriesId = recurrenceSeriesId,
            recurrenceIndex = recurrenceIndex,
            isDetached = isDetached
        )
        entity.lineItems.add(
            AppointmentLineItemEntity(
                appointment = entity,
                serviceId = UUID.randomUUID(),
                serviceName = "Detailing",
                basePriceNet = basePrice,
                vatRate = 0,
                adjustmentType = AdjustmentType.FIXED_GROSS,
                adjustmentValue = 0L,
                finalPriceNet = basePrice,
                finalPriceGross = basePrice,
                customNote = null
            )
        )
        return entity
    }

    private fun customerEntity() = mockk<CustomerEntity>(relaxed = true) {
        every { firstName } returns "Jan"
        every { lastName } returns "Kowalski"
        every { phone } returns "+48600000000"
        every { email } returns "jan@test.pl"
    }

    private fun colorEntity() = mockk<AppointmentColorEntity>(relaxed = true) {
        every { name } returns "Standard"
        every { hexColor } returns "#3b82f6"
    }
}
