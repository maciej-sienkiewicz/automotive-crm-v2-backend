package pl.detailing.crm.visit.complete

import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import pl.detailing.crm.audit.domain.AuditAction
import pl.detailing.crm.audit.domain.AuditService
import pl.detailing.crm.audit.domain.LogAuditCommand
import pl.detailing.crm.customer.infrastructure.CustomerEntity
import pl.detailing.crm.customer.infrastructure.CustomerRepository
import pl.detailing.crm.finance.document.CreateFinancialDocumentCommand
import pl.detailing.crm.finance.document.CreateFinancialDocumentHandler
import pl.detailing.crm.appointment.domain.AdjustmentType
import pl.detailing.crm.finance.domain.DocumentType
import pl.detailing.crm.finance.domain.FinancialDocument
import pl.detailing.crm.finance.domain.PaymentMethod
import pl.detailing.crm.shared.*
import pl.detailing.crm.visit.domain.Visit
import pl.detailing.crm.visit.domain.VisitPhoto
import pl.detailing.crm.visit.infrastructure.VisitEntity
import pl.detailing.crm.visit.infrastructure.VisitRepository
import pl.detailing.crm.visit.transitions.complete.CompleteVisitCommand
import pl.detailing.crm.visit.transitions.complete.CompleteVisitHandler
import java.time.Instant
import java.util.UUID

class CompleteVisitHandlerTest {

    private val visitRepository = mockk<VisitRepository>()
    private val customerRepository = mockk<CustomerRepository>()
    private val auditService = mockk<AuditService>(relaxed = true)
    private val createFinancialDocumentHandler = mockk<CreateFinancialDocumentHandler>()

    private val handler = CompleteVisitHandler(
        visitRepository,
        customerRepository,
        auditService,
        createFinancialDocumentHandler
    )

    private val studioId = StudioId.random()
    private val userId = UserId.random()
    private val visitId = VisitId.random()

    @BeforeEach
    fun setUp() {
        coEvery { customerRepository.findByIdAndStudioId(any(), any()) } returns null
        every { createFinancialDocumentHandler.handle(any()) } returns mockk(relaxed = true)
    }

    // ─── Repository query selection ─────────────────────────────────────────

    @Test
    fun `uses findByIdAndStudioIdWithPhotos — not the bare findByIdAndStudioId`() = runBlocking {
        val entity = visitEntityWithPhotos(count = 0)
        coEvery { visitRepository.findByIdAndStudioIdWithPhotos(visitId.value, studioId.value) } returns entity
        coEvery { visitRepository.save(any()) } returns mockk(relaxed = true)

        handler.handle(baseCommand())

        coVerify(exactly = 1) { visitRepository.findByIdAndStudioIdWithPhotos(visitId.value, studioId.value) }
        coVerify(exactly = 0) { visitRepository.findByIdAndStudioId(any(), any()) }
    }

    @Test
    fun `throws EntityNotFoundException when visit does not exist`() = runBlocking<Unit> {
        coEvery { visitRepository.findByIdAndStudioIdWithPhotos(any(), any()) } returns null

        assertThrows<EntityNotFoundException> {
            runBlocking { handler.handle(baseCommand()) }
        }
    }

    // ─── Photo preservation ──────────────────────────────────────────────────

    @Test
    fun `photos are preserved in saved entity when visit has photos`() = runBlocking {
        val photo1 = photo()
        val photo2 = photo()
        val entity = visitEntityWithPhotos(photos = listOf(photo1, photo2))
        val savedSlot = slot<VisitEntity>()

        coEvery { visitRepository.findByIdAndStudioIdWithPhotos(visitId.value, studioId.value) } returns entity
        coEvery { visitRepository.save(capture(savedSlot)) } returns mockk(relaxed = true)

        handler.handle(baseCommand())

        val savedPhotos = savedSlot.captured.toDomain().photos
        assertEquals(2, savedPhotos.size)
        assertTrue(savedPhotos.any { it.id == photo1.id })
        assertTrue(savedPhotos.any { it.id == photo2.id })
    }

    @Test
    fun `photo fileId is preserved exactly after complete`() = runBlocking {
        val originalPhoto = photo(fileId = "s3://bucket/file-xyz.jpg")
        val entity = visitEntityWithPhotos(photos = listOf(originalPhoto))
        val savedSlot = slot<VisitEntity>()

        coEvery { visitRepository.findByIdAndStudioIdWithPhotos(visitId.value, studioId.value) } returns entity
        coEvery { visitRepository.save(capture(savedSlot)) } returns mockk(relaxed = true)

        handler.handle(baseCommand())

        val savedPhoto = savedSlot.captured.toDomain().photos.single()
        assertEquals("s3://bucket/file-xyz.jpg", savedPhoto.fileId)
    }

    @Test
    fun `photo fileName is preserved exactly after complete`() = runBlocking {
        val originalPhoto = photo(fileName = "damage-front.png")
        val entity = visitEntityWithPhotos(photos = listOf(originalPhoto))
        val savedSlot = slot<VisitEntity>()

        coEvery { visitRepository.findByIdAndStudioIdWithPhotos(visitId.value, studioId.value) } returns entity
        coEvery { visitRepository.save(capture(savedSlot)) } returns mockk(relaxed = true)

        handler.handle(baseCommand())

        val savedPhoto = savedSlot.captured.toDomain().photos.single()
        assertEquals("damage-front.png", savedPhoto.fileName)
    }

    @Test
    fun `photo description is preserved after complete`() = runBlocking {
        val originalPhoto = photo(description = "Zarysowanie tylnego zderzaka")
        val entity = visitEntityWithPhotos(photos = listOf(originalPhoto))
        val savedSlot = slot<VisitEntity>()

        coEvery { visitRepository.findByIdAndStudioIdWithPhotos(visitId.value, studioId.value) } returns entity
        coEvery { visitRepository.save(capture(savedSlot)) } returns mockk(relaxed = true)

        handler.handle(baseCommand())

        val savedPhoto = savedSlot.captured.toDomain().photos.single()
        assertEquals("Zarysowanie tylnego zderzaka", savedPhoto.description)
    }

    @Test
    fun `saved entity has empty photos list when visit has no photos`() = runBlocking {
        val entity = visitEntityWithPhotos(count = 0)
        val savedSlot = slot<VisitEntity>()

        coEvery { visitRepository.findByIdAndStudioIdWithPhotos(visitId.value, studioId.value) } returns entity
        coEvery { visitRepository.save(capture(savedSlot)) } returns mockk(relaxed = true)

        handler.handle(baseCommand())

        assertEquals(0, savedSlot.captured.toDomain().photos.size)
    }

    @Test
    fun `photos from other visits are not mixed in`() = runBlocking {
        val ownPhoto = photo()
        val entity = visitEntityWithPhotos(photos = listOf(ownPhoto))
        val savedSlot = slot<VisitEntity>()

        coEvery { visitRepository.findByIdAndStudioIdWithPhotos(visitId.value, studioId.value) } returns entity
        coEvery { visitRepository.save(capture(savedSlot)) } returns mockk(relaxed = true)

        handler.handle(baseCommand())

        val savedPhotos = savedSlot.captured.toDomain().photos
        assertEquals(1, savedPhotos.size)
        assertEquals(ownPhoto.id, savedPhotos.single().id)
    }

    // ─── Status transition ───────────────────────────────────────────────────

    @Test
    fun `result contains COMPLETED status`() = runBlocking {
        val entity = visitEntityWithPhotos(count = 0)
        coEvery { visitRepository.findByIdAndStudioIdWithPhotos(visitId.value, studioId.value) } returns entity
        coEvery { visitRepository.save(any()) } returns mockk(relaxed = true)

        val result = handler.handle(baseCommand())

        assertEquals(VisitStatus.COMPLETED, result.newStatus)
    }

    @Test
    fun `saved entity status is COMPLETED`() = runBlocking {
        val entity = visitEntityWithPhotos(count = 0)
        val savedSlot = slot<VisitEntity>()

        coEvery { visitRepository.findByIdAndStudioIdWithPhotos(visitId.value, studioId.value) } returns entity
        coEvery { visitRepository.save(capture(savedSlot)) } returns mockk(relaxed = true)

        handler.handle(baseCommand())

        assertEquals(VisitStatus.COMPLETED, savedSlot.captured.toDomain().status)
    }

    @Test
    fun `result visitId matches command visitId`() = runBlocking {
        val entity = visitEntityWithPhotos(count = 0)
        coEvery { visitRepository.findByIdAndStudioIdWithPhotos(visitId.value, studioId.value) } returns entity
        coEvery { visitRepository.save(any()) } returns mockk(relaxed = true)

        val result = handler.handle(baseCommand())

        assertEquals(visitId, result.visitId)
    }

    @Test
    fun `pickupDate is set after completing visit`() = runBlocking {
        val entity = visitEntityWithPhotos(count = 0)
        coEvery { visitRepository.findByIdAndStudioIdWithPhotos(visitId.value, studioId.value) } returns entity
        coEvery { visitRepository.save(any()) } returns mockk(relaxed = true)

        val before = Instant.now()
        val result = handler.handle(baseCommand())
        val after = Instant.now()

        assertNotNull(result.completedAt)
        assertFalse(result.completedAt.isBefore(before))
        assertFalse(result.completedAt.isAfter(after))
    }

    // ─── Audit ───────────────────────────────────────────────────────────────

    @Test
    fun `audit log is written with VISIT_COMPLETED action`() = runBlocking {
        val entity = visitEntityWithPhotos(count = 0)
        coEvery { visitRepository.findByIdAndStudioIdWithPhotos(visitId.value, studioId.value) } returns entity
        coEvery { visitRepository.save(any()) } returns mockk(relaxed = true)

        handler.handle(baseCommand())

        coVerify(exactly = 1) {
            auditService.log(withArg<LogAuditCommand> { cmd ->
                assertEquals(AuditAction.VISIT_COMPLETED, cmd.action)
            })
        }
    }

    @Test
    fun `audit log contains correct studioId and userId`() = runBlocking {
        val entity = visitEntityWithPhotos(count = 0)
        coEvery { visitRepository.findByIdAndStudioIdWithPhotos(visitId.value, studioId.value) } returns entity
        coEvery { visitRepository.save(any()) } returns mockk(relaxed = true)

        handler.handle(baseCommand())

        coVerify(exactly = 1) {
            auditService.log(withArg<LogAuditCommand> { cmd ->
                assertEquals(studioId, cmd.studioId)
                assertEquals(userId, cmd.userId)
            })
        }
    }

    // ─── Financial document ──────────────────────────────────────────────────

    @Test
    fun `financial document is not created when visit has no service items`() = runBlocking {
        val entity = visitEntityWithPhotos(count = 0, serviceItemCount = 0)
        coEvery { visitRepository.findByIdAndStudioIdWithPhotos(visitId.value, studioId.value) } returns entity
        coEvery { visitRepository.save(any()) } returns mockk(relaxed = true)

        val result = handler.handle(baseCommand())

        verify(exactly = 0) { createFinancialDocumentHandler.handle(any()) }
        assertNull(result.financialDocumentId)
        assertNull(result.financialDocumentNumber)
    }

    @Test
    fun `financial document is created when visit has service items`() = runBlocking {
        val entity = visitEntityWithPhotos(count = 0, serviceItemCount = 1)
        val docId = FinancialDocumentId.random()
        val finDoc = mockk<FinancialDocument>(relaxed = true) {
            every { id } returns docId
            every { documentNumber } returns "PAR/2026/0001"
        }
        coEvery { visitRepository.findByIdAndStudioIdWithPhotos(visitId.value, studioId.value) } returns entity
        coEvery { visitRepository.save(any()) } returns mockk(relaxed = true)
        every { createFinancialDocumentHandler.handle(any()) } returns finDoc

        val result = handler.handle(baseCommand())

        verify(exactly = 1) { createFinancialDocumentHandler.handle(any()) }
        assertEquals(docId, result.financialDocumentId)
        assertEquals("PAR/2026/0001", result.financialDocumentNumber)
    }

    @Test
    fun `financial document command uses payment method from command`() = runBlocking {
        val entity = visitEntityWithPhotos(count = 0, serviceItemCount = 1)
        val cmdSlot = slot<CreateFinancialDocumentCommand>()

        coEvery { visitRepository.findByIdAndStudioIdWithPhotos(visitId.value, studioId.value) } returns entity
        coEvery { visitRepository.save(any()) } returns mockk(relaxed = true)
        every { createFinancialDocumentHandler.handle(capture(cmdSlot)) } returns mockk(relaxed = true)

        handler.handle(baseCommand(paymentMethod = PaymentMethod.TRANSFER))

        assertEquals(PaymentMethod.TRANSFER, cmdSlot.captured.paymentMethod)
    }

    @Test
    fun `repository save is called exactly once`() = runBlocking {
        val entity = visitEntityWithPhotos(count = 0)
        coEvery { visitRepository.findByIdAndStudioIdWithPhotos(visitId.value, studioId.value) } returns entity
        coEvery { visitRepository.save(any()) } returns mockk(relaxed = true)

        handler.handle(baseCommand())

        coVerify(exactly = 1) { visitRepository.save(any()) }
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private fun baseCommand(paymentMethod: PaymentMethod = PaymentMethod.CASH) = CompleteVisitCommand(
        studioId = studioId,
        userId = userId,
        visitId = visitId,
        userName = "Jan Kowalski",
        paymentMethod = paymentMethod,
        documentType = DocumentType.RECEIPT
    )

    private fun photo(
        fileId: String = "s3://bucket/${UUID.randomUUID()}.jpg",
        fileName: String = "photo.jpg",
        description: String? = null
    ) = VisitPhoto(
        id = VisitPhotoId.random(),
        fileId = fileId,
        fileName = fileName,
        description = description,
        uploadedAt = Instant.now()
    )

    /**
     * Builds a real VisitEntity (not a mock) in READY_FOR_PICKUP state so that
     * Visit.complete() transition is valid. Photos and service items are wired
     * with bidirectional references as Hibernate would do it.
     */
    private fun visitEntityWithPhotos(
        count: Int = 0,
        photos: List<VisitPhoto> = (1..count).map { photo() },
        serviceItemCount: Int = 0
    ): VisitEntity {
        val domain = baseVisit(photos = photos, serviceItemCount = serviceItemCount)
        return VisitEntity.fromDomain(domain)
    }

    private fun baseVisit(
        photos: List<VisitPhoto> = emptyList(),
        serviceItemCount: Int = 0
    ) = Visit(
        id = visitId,
        studioId = studioId,
        visitNumber = "V/2026/001",
        customerId = CustomerId.random(),
        vehicleId = VehicleId.random(),
        appointmentId = AppointmentId.random(),
        appointmentColorId = null,
        brandSnapshot = "Toyota",
        modelSnapshot = "Corolla",
        licensePlateSnapshot = "WA 12345",
        vinSnapshot = null,
        yearOfProductionSnapshot = null,
        colorSnapshot = null,
        status = VisitStatus.READY_FOR_PICKUP,
        scheduledDate = Instant.now(),
        estimatedCompletionDate = null,
        actualCompletionDate = null,
        pickupDate = null,
        mileageAtArrival = null,
        keysHandedOver = false,
        documentsHandedOver = false,
        inspectionNotes = null,
        technicalNotes = null,
        vehicleHandoff = null,
        serviceItems = buildServiceItems(serviceItemCount),
        photos = photos,
        damageMapFileId = null,
        smsReminderSuppressed = false,
        createdBy = userId,
        updatedBy = userId,
        createdAt = Instant.now(),
        updatedAt = Instant.now()
    )

    private fun buildServiceItems(count: Int): List<pl.detailing.crm.visit.domain.VisitServiceItem> {
        if (count == 0) return emptyList()
        return (1..count).map {
            pl.detailing.crm.visit.domain.VisitServiceItem.createPending(
                serviceId = null,
                serviceName = "Usługa $it",
                basePriceNet = Money(10000L),
                vatRate = VatRate.VAT_23,
                adjustmentType = AdjustmentType.PERCENT,
                adjustmentValue = 0L,
                customNote = null
            )
        }
    }
}
