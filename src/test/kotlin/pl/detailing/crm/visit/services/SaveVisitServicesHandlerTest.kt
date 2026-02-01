package pl.detailing.crm.visit.services

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import pl.detailing.crm.shared.*
import pl.detailing.crm.visit.infrastructure.VisitEntity
import pl.detailing.crm.visit.infrastructure.VisitRepository
import pl.detailing.crm.visit.domain.Visit
import pl.detailing.crm.shared.VisitStatus
import java.time.Instant
import java.util.*

class SaveVisitServicesHandlerTest {

    private val visitRepository = mockk<VisitRepository>()
    private val handler = SaveVisitServicesHandler(visitRepository)

    @Test
    fun `should correctly handle prices in cents from payload`() = runBlocking {
        // Given
        val visitId = VisitId.random()
        val studioId = StudioId.random()
        val userId = UserId.random()
        
        val visitEntity = mockk<VisitEntity>(relaxed = true)
        val visitDomain = createMinimalVisit(visitId, studioId)
        
        coEvery { visitRepository.findByIdAndStudioId(visitId.value, studioId.value) } returns visitEntity
        coEvery { visitEntity.toDomain() } returns visitDomain
        
        val payload = ServicesChangesPayload(
            notifyCustomer = false,
            added = listOf(
                AddedService(
                    serviceId = ServiceId.random().toString(),
                    serviceName = "Test Service",
                    basePriceNet = 10000L, // 100.00 PLN in cents
                    vatRate = 23,
                    adjustment = null,
                    note = null
                )
            ),
            updated = emptyList(),
            deleted = emptyList()
        )

        val savedEntitySlot = slot<VisitEntity>()
        coEvery { visitRepository.save(capture(savedEntitySlot)) } returns mockk()

        // When
        handler.handle(visitId, studioId, userId, payload)

        // Then
        val savedVisit = savedEntitySlot.captured.toDomain()
        val addedService = savedVisit.serviceItems.first()
        
        // basePriceNet should be 10000 cents, not 1000000 cents
        assertEquals(10000L, addedService.basePriceNet.amountInCents)
    }

    @Test
    fun `should correctly handle custom service without serviceId`() = runBlocking {
        // Given
        val visitId = VisitId.random()
        val studioId = StudioId.random()
        val userId = UserId.random()

        val visitEntity = mockk<VisitEntity>(relaxed = true)
        val visitDomain = createMinimalVisit(visitId, studioId)

        coEvery { visitRepository.findByIdAndStudioId(visitId.value, studioId.value) } returns visitEntity
        coEvery { visitEntity.toDomain() } returns visitDomain

        val payload = ServicesChangesPayload(
            notifyCustomer = false,
            added = listOf(
                AddedService(
                    serviceId = null, // Custom service
                    serviceName = "Custom Service",
                    basePriceNet = 5000L,
                    vatRate = 23,
                    adjustment = null,
                    note = "Custom note"
                )
            ),
            updated = emptyList(),
            deleted = emptyList()
        )

        val savedEntitySlot = slot<VisitEntity>()
        coEvery { visitRepository.save(capture(savedEntitySlot)) } returns mockk()

        // When
        handler.handle(visitId, studioId, userId, payload)

        // Then
        val savedVisit = savedEntitySlot.captured.toDomain()
        val addedService = savedVisit.serviceItems.first()

        assertEquals(null, addedService.serviceId)
        assertEquals("Custom Service", addedService.serviceName)
        assertEquals(5000L, addedService.basePriceNet.amountInCents)
        assertEquals("Custom note", addedService.customNote)
    }

    private fun createMinimalVisit(id: VisitId, studioId: StudioId): Visit {
        return Visit(
            id = id,
            studioId = studioId,
            visitNumber = "V/1",
            customerId = CustomerId.random(),
            vehicleId = VehicleId.random(),
            appointmentId = AppointmentId.random(),
            appointmentColorId = null,
            brandSnapshot = "Brand",
            modelSnapshot = "Model",
            licensePlateSnapshot = null,
            vinSnapshot = null,
            yearOfProductionSnapshot = null,
            colorSnapshot = null,
            status = VisitStatus.IN_PROGRESS,
            scheduledDate = Instant.now(),
            estimatedCompletionDate = null,
            actualCompletionDate = null,
            pickupDate = null,
            mileageAtArrival = null,
            keysHandedOver = false,
            documentsHandedOver = false,
            inspectionNotes = null,
            technicalNotes = null,
            serviceItems = emptyList(),
            photos = emptyList(),
            damageMapFileId = null,
            createdBy = UserId.random(),
            updatedBy = UserId.random(),
            createdAt = Instant.now(),
            updatedAt = Instant.now()
        )
    }
}
