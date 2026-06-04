package pl.detailing.crm.appointment.recurrence.update

import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import pl.detailing.crm.appointment.domain.AdjustmentType
import pl.detailing.crm.appointment.domain.AppointmentStatus
import pl.detailing.crm.appointment.infrastructure.AppointmentEntity
import pl.detailing.crm.appointment.infrastructure.AppointmentLineItemEntity
import pl.detailing.crm.appointment.infrastructure.AppointmentRepository
import pl.detailing.crm.appointment.recurrence.infrastructure.RecurrenceSeriesEntity
import pl.detailing.crm.appointment.recurrence.infrastructure.RecurrenceSeriesRepository
import pl.detailing.crm.audit.domain.AuditService
import pl.detailing.crm.shared.*
import java.time.Instant
import java.util.UUID

class UpdateRecurringAppointmentHandlerTest {

    private val appointmentRepository = mockk<AppointmentRepository>()
    private val recurrenceSeriesRepository = mockk<RecurrenceSeriesRepository>()
    private val auditService = mockk<AuditService>(relaxed = true)

    private val handler = UpdateRecurringAppointmentHandler(
        appointmentRepository,
        recurrenceSeriesRepository,
        auditService
    )

    private val studioId = StudioId.random()
    private val userId = UserId.random()
    private val seriesId = UUID.randomUUID()

    @BeforeEach
    fun setUp() {
        val seriesEntity = mockk<RecurrenceSeriesEntity>(relaxed = true)
        every { recurrenceSeriesRepository.findByIdAndStudioId(seriesId, studioId.value) } returns seriesEntity
        every { appointmentRepository.saveAll(any<List<AppointmentEntity>>()) } returns emptyList()
    }

    // ─── scope=THIS ──────────────────────────────────────────────────────────

    @Test
    fun `scope THIS marks anchor as detached`() = runBlocking {
        val anchor = appointmentEntity(index = 0)
        every { appointmentRepository.findByIdAndStudioId(anchor.id, studioId.value) } returns anchor

        handler.handle(baseCommand(anchor, RecurrenceEditScope.THIS))

        assertTrue(anchor.isDetached)
    }

    @Test
    fun `scope THIS updates only the anchor — does not query other series members`() = runBlocking {
        val anchor = appointmentEntity(index = 0)
        every { appointmentRepository.findByIdAndStudioId(anchor.id, studioId.value) } returns anchor

        handler.handle(baseCommand(anchor, RecurrenceEditScope.THIS))

        verify(exactly = 0) { appointmentRepository.findNonDetachedBySeriesId(any()) }
        verify(exactly = 0) { appointmentRepository.findBySeriesIdAndIndexGreaterThanEqual(any(), any()) }
    }

    @Test
    fun `scope THIS saves exactly one appointment`() = runBlocking {
        val anchor = appointmentEntity(index = 0)
        every { appointmentRepository.findByIdAndStudioId(anchor.id, studioId.value) } returns anchor
        val savedSlot = slot<List<AppointmentEntity>>()
        every { appointmentRepository.saveAll(capture(savedSlot)) } returns emptyList()

        handler.handle(baseCommand(anchor, RecurrenceEditScope.THIS))

        assertEquals(1, savedSlot.captured.size)
    }

    // ─── scope=ALL ───────────────────────────────────────────────────────────

    @Test
    fun `scope ALL updates all non-detached series members`() = runBlocking {
        val anchor = appointmentEntity(index = 0)
        val other1 = appointmentEntity(index = 1)
        val other2 = appointmentEntity(index = 2)
        every { appointmentRepository.findByIdAndStudioId(anchor.id, studioId.value) } returns anchor
        every { appointmentRepository.findNonDetachedBySeriesId(seriesId) } returns listOf(anchor, other1, other2)
        val savedSlot = slot<List<AppointmentEntity>>()
        every { appointmentRepository.saveAll(capture(savedSlot)) } returns emptyList()

        handler.handle(baseCommand(anchor, RecurrenceEditScope.ALL))

        assertEquals(3, savedSlot.captured.size)
    }

    @Test
    fun `scope ALL skips detached appointments`() = runBlocking {
        val anchor = appointmentEntity(index = 0)
        val detached = appointmentEntity(index = 1, isDetached = true)
        every { appointmentRepository.findByIdAndStudioId(anchor.id, studioId.value) } returns anchor
        every { appointmentRepository.findNonDetachedBySeriesId(seriesId) } returns listOf(anchor, detached)
        val savedSlot = slot<List<AppointmentEntity>>()
        every { appointmentRepository.saveAll(capture(savedSlot)) } returns emptyList()

        handler.handle(baseCommand(anchor, RecurrenceEditScope.ALL))

        // detached is included by findNonDetachedBySeriesId (the query filter), but handler also checks
        // In practice findNonDetachedBySeriesId won't return detached ones — but the handler double-checks
        assertTrue(savedSlot.captured.none { it.isDetached && it.id != anchor.id })
    }

    @Test
    fun `scope ALL skips CONVERTED appointments`() = runBlocking {
        val anchor = appointmentEntity(index = 0)
        val converted = appointmentEntity(index = 1, status = AppointmentStatus.CONVERTED)
        every { appointmentRepository.findByIdAndStudioId(anchor.id, studioId.value) } returns anchor
        every { appointmentRepository.findNonDetachedBySeriesId(seriesId) } returns listOf(anchor, converted)
        val savedSlot = slot<List<AppointmentEntity>>()
        every { appointmentRepository.saveAll(capture(savedSlot)) } returns emptyList()

        handler.handle(baseCommand(anchor, RecurrenceEditScope.ALL))

        assertTrue(savedSlot.captured.none { it.status == AppointmentStatus.CONVERTED })
    }

    @Test
    fun `scope ALL result contains correct updatedCount`() = runBlocking {
        val anchor = appointmentEntity(index = 0)
        val other = appointmentEntity(index = 1)
        every { appointmentRepository.findByIdAndStudioId(anchor.id, studioId.value) } returns anchor
        every { appointmentRepository.findNonDetachedBySeriesId(seriesId) } returns listOf(anchor, other)
        every { appointmentRepository.saveAll(any<List<AppointmentEntity>>()) } returns emptyList()

        val result = handler.handle(baseCommand(anchor, RecurrenceEditScope.ALL))

        assertEquals(2, result.updatedCount)
    }

    // ─── copyLineItemsFromAnchor ──────────────────────────────────────────────

    @Test
    fun `copyLineItemsFromAnchor copies anchor line items to other series members`() = runBlocking {
        val anchor = appointmentEntity(index = 0, serviceCount = 2)
        val other = appointmentEntity(index = 1, serviceCount = 1)
        every { appointmentRepository.findByIdAndStudioId(anchor.id, studioId.value) } returns anchor
        every { appointmentRepository.findNonDetachedBySeriesId(seriesId) } returns listOf(anchor, other)
        val savedSlot = slot<List<AppointmentEntity>>()
        every { appointmentRepository.saveAll(capture(savedSlot)) } returns emptyList()

        handler.handle(
            baseCommand(anchor, RecurrenceEditScope.ALL).copy(copyLineItemsFromAnchor = true)
        )

        val savedOther = savedSlot.captured.first { it.id == other.id }
        assertEquals(2, savedOther.lineItems.size)
    }

    @Test
    fun `copyLineItemsFromAnchor does not re-replace anchor line items`() = runBlocking {
        val anchor = appointmentEntity(index = 0, serviceCount = 2)
        val other = appointmentEntity(index = 1, serviceCount = 0)
        every { appointmentRepository.findByIdAndStudioId(anchor.id, studioId.value) } returns anchor
        every { appointmentRepository.findNonDetachedBySeriesId(seriesId) } returns listOf(anchor, other)
        val savedSlot = slot<List<AppointmentEntity>>()
        every { appointmentRepository.saveAll(capture(savedSlot)) } returns emptyList()

        handler.handle(
            baseCommand(anchor, RecurrenceEditScope.ALL).copy(copyLineItemsFromAnchor = true)
        )

        val savedAnchor = savedSlot.captured.first { it.id == anchor.id }
        assertEquals(2, savedAnchor.lineItems.size)
    }

    @Test
    fun `copyLineItemsFromAnchor copies service names correctly`() = runBlocking {
        val anchor = appointmentEntity(index = 0, serviceCount = 1, serviceName = "Detailing premium")
        val other = appointmentEntity(index = 1, serviceCount = 0)
        every { appointmentRepository.findByIdAndStudioId(anchor.id, studioId.value) } returns anchor
        every { appointmentRepository.findNonDetachedBySeriesId(seriesId) } returns listOf(anchor, other)
        val savedSlot = slot<List<AppointmentEntity>>()
        every { appointmentRepository.saveAll(capture(savedSlot)) } returns emptyList()

        handler.handle(
            baseCommand(anchor, RecurrenceEditScope.ALL).copy(copyLineItemsFromAnchor = true)
        )

        val savedOther = savedSlot.captured.first { it.id == other.id }
        assertEquals("Detailing premium", savedOther.lineItems.single().serviceName)
    }

    @Test
    fun `copyLineItemsFromAnchor=false does not change line items on other appointments`() = runBlocking {
        val anchor = appointmentEntity(index = 0, serviceCount = 2)
        val other = appointmentEntity(index = 1, serviceCount = 1)
        every { appointmentRepository.findByIdAndStudioId(anchor.id, studioId.value) } returns anchor
        every { appointmentRepository.findNonDetachedBySeriesId(seriesId) } returns listOf(anchor, other)
        val savedSlot = slot<List<AppointmentEntity>>()
        every { appointmentRepository.saveAll(capture(savedSlot)) } returns emptyList()

        handler.handle(
            baseCommand(anchor, RecurrenceEditScope.ALL).copy(copyLineItemsFromAnchor = false)
        )

        val savedOther = savedSlot.captured.first { it.id == other.id }
        // other still has its original 1 line item unchanged
        assertEquals(1, savedOther.lineItems.size)
    }

    // ─── scope=THIS_AND_FUTURE ────────────────────────────────────────────────

    @Test
    fun `scope THIS_AND_FUTURE queries from anchor index`() = runBlocking {
        val anchor = appointmentEntity(index = 2)
        every { appointmentRepository.findByIdAndStudioId(anchor.id, studioId.value) } returns anchor
        every { appointmentRepository.findBySeriesIdAndIndexGreaterThanEqual(seriesId, 2) } returns listOf(anchor)
        every { appointmentRepository.saveAll(any<List<AppointmentEntity>>()) } returns emptyList()

        handler.handle(baseCommand(anchor, RecurrenceEditScope.THIS_AND_FUTURE))

        verify(exactly = 1) { appointmentRepository.findBySeriesIdAndIndexGreaterThanEqual(seriesId, 2) }
    }

    @Test
    fun `scope THIS_AND_FUTURE does not update appointments before anchor`() = runBlocking {
        val anchor = appointmentEntity(index = 2)
        val future = appointmentEntity(index = 3)
        every { appointmentRepository.findByIdAndStudioId(anchor.id, studioId.value) } returns anchor
        every { appointmentRepository.findBySeriesIdAndIndexGreaterThanEqual(seriesId, 2) } returns listOf(anchor, future)
        val savedSlot = slot<List<AppointmentEntity>>()
        every { appointmentRepository.saveAll(capture(savedSlot)) } returns emptyList()

        handler.handle(baseCommand(anchor, RecurrenceEditScope.THIS_AND_FUTURE))

        val savedIndices = savedSlot.captured.mapNotNull { it.recurrenceIndex }
        assertTrue(savedIndices.all { it >= 2 })
    }

    // ─── Field updates ────────────────────────────────────────────────────────

    @Test
    fun `appointmentTitle is updated on all targets`() = runBlocking {
        val anchor = appointmentEntity(index = 0)
        val other = appointmentEntity(index = 1)
        every { appointmentRepository.findByIdAndStudioId(anchor.id, studioId.value) } returns anchor
        every { appointmentRepository.findNonDetachedBySeriesId(seriesId) } returns listOf(anchor, other)
        val savedSlot = slot<List<AppointmentEntity>>()
        every { appointmentRepository.saveAll(capture(savedSlot)) } returns emptyList()

        handler.handle(baseCommand(anchor, RecurrenceEditScope.ALL).copy(appointmentTitle = "Nowy tytuł"))

        assertTrue(savedSlot.captured.all { it.appointmentTitle == "Nowy tytuł" })
    }

    @Test
    fun `note is updated on all targets`() = runBlocking {
        val anchor = appointmentEntity(index = 0)
        val other = appointmentEntity(index = 1)
        every { appointmentRepository.findByIdAndStudioId(anchor.id, studioId.value) } returns anchor
        every { appointmentRepository.findNonDetachedBySeriesId(seriesId) } returns listOf(anchor, other)
        val savedSlot = slot<List<AppointmentEntity>>()
        every { appointmentRepository.saveAll(capture(savedSlot)) } returns emptyList()

        handler.handle(baseCommand(anchor, RecurrenceEditScope.ALL).copy(note = "Uwaga testowa"))

        assertTrue(savedSlot.captured.all { it.note == "Uwaga testowa" })
    }

    // ─── Error cases ──────────────────────────────────────────────────────────

    @Test
    fun `throws EntityNotFoundException when appointment not found`() {
        val id = AppointmentId.random()
        every { appointmentRepository.findByIdAndStudioId(id.value, studioId.value) } returns null

        assertThrows<EntityNotFoundException> {
            runBlocking { handler.handle(baseCommand(id = id, scope = RecurrenceEditScope.ALL)) }
        }
    }

    @Test
    fun `throws IllegalStateException when appointment has no series`() {
        val entity = appointmentEntity(index = 0, withSeries = false)
        every { appointmentRepository.findByIdAndStudioId(entity.id, studioId.value) } returns entity

        assertThrows<IllegalStateException> {
            runBlocking { handler.handle(baseCommand(entity, RecurrenceEditScope.ALL)) }
        }
    }

    @Test
    fun `throws EntityNotFoundException when series not found for studio`() {
        val anchor = appointmentEntity(index = 0)
        every { appointmentRepository.findByIdAndStudioId(anchor.id, studioId.value) } returns anchor
        every { recurrenceSeriesRepository.findByIdAndStudioId(seriesId, studioId.value) } returns null

        assertThrows<EntityNotFoundException> {
            runBlocking { handler.handle(baseCommand(anchor, RecurrenceEditScope.ALL)) }
        }
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private fun baseCommand(
        entity: AppointmentEntity? = null,
        scope: RecurrenceEditScope = RecurrenceEditScope.ALL,
        id: AppointmentId = entity?.let { AppointmentId(it.id) } ?: AppointmentId.random()
    ) = UpdateRecurringAppointmentCommand(
        appointmentId = id,
        studioId = studioId,
        userId = userId,
        scope = scope,
        appointmentTitle = null,
        appointmentColorId = null,
        note = null,
        sendReminderSms = null
    )

    private fun appointmentEntity(
        index: Int,
        isDetached: Boolean = false,
        status: AppointmentStatus = AppointmentStatus.CREATED,
        withSeries: Boolean = true,
        serviceCount: Int = 0,
        serviceName: String = "Usługa testowa"
    ): AppointmentEntity {
        val colorId = UUID.randomUUID()
        val entity = AppointmentEntity(
            id = UUID.randomUUID(),
            studioId = studioId.value,
            customerId = UUID.randomUUID(),
            vehicleId = null,
            appointmentTitle = null,
            appointmentColorId = colorId,
            isAllDay = false,
            startDateTime = Instant.now(),
            endDateTime = Instant.now().plusSeconds(3600),
            status = status,
            note = null,
            createdBy = userId.value,
            updatedBy = userId.value,
            recurrenceSeriesId = if (withSeries) seriesId else null,
            recurrenceIndex = index,
            isDetached = isDetached
        )
        if (serviceCount > 0) {
            val items = (1..serviceCount).map {
                AppointmentLineItemEntity(
                    appointment = entity,
                    serviceId = UUID.randomUUID(),
                    serviceName = serviceName,
                    basePriceNet = 10000L,
                    vatRate = 0,
                    adjustmentType = AdjustmentType.FIXED_GROSS,
                    adjustmentValue = 0L,
                    finalPriceNet = 10000L,
                    finalPriceGross = 10000L,
                    customNote = null
                )
            }
            entity.lineItems.addAll(items)
        }
        return entity
    }
}
