package pl.detailing.crm.visit.complete

import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.core.context.SecurityContextImpl
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import pl.detailing.crm.appointment.domain.AdjustmentType
import pl.detailing.crm.audit.domain.AuditService
import pl.detailing.crm.auth.UserPrincipal
import pl.detailing.crm.config.GlobalExceptionHandler
import pl.detailing.crm.customer.infrastructure.CustomerRepository
import pl.detailing.crm.finance.document.CreateFinancialDocumentHandler
import pl.detailing.crm.finance.domain.FinancialDocument
import pl.detailing.crm.finance.domain.FinancialDocumentId
import pl.detailing.crm.shared.*
import pl.detailing.crm.visit.VisitTransitionController
import pl.detailing.crm.visit.domain.Visit
import pl.detailing.crm.visit.domain.VisitPhoto
import pl.detailing.crm.visit.infrastructure.VisitEntity
import pl.detailing.crm.visit.infrastructure.VisitRepository
import pl.detailing.crm.visit.transitions.archive.ArchiveVisitHandler
import pl.detailing.crm.visit.transitions.complete.CompleteVisitHandler
import pl.detailing.crm.visit.transitions.markready.MarkVisitReadyForPickupHandler
import pl.detailing.crm.visit.transitions.reject.RejectVisitHandler
import java.time.Instant

/**
 * Standalone MockMvc E2E test for POST /api/visits/{id}/complete.
 *
 * Verifies the full HTTP request → controller → handler → repository chain without
 * a real Spring context or database. The critical regression being guarded:
 *
 *  - The handler must call findByIdAndStudioIdWithPhotos (eager FETCH via JOIN),
 *    not findByIdAndStudioId (lazy — throws LazyInitializationException when the
 *    LAZY photos collection is touched after withContext(Dispatchers.IO) switches threads).
 *
 *  - Photos must survive the complete round-trip: toDomain() → complete() → fromDomain() → save().
 *    Without the eager fetch, fromDomain() receives an empty photos list and save() with
 *    orphanRemoval=true would silently delete all photo rows from the database.
 */
class CompleteVisitE2ETest {

    private val visitRepository = mockk<VisitRepository>()
    private val customerRepository = mockk<CustomerRepository>()
    private val auditService = mockk<AuditService>(relaxed = true)
    private val createFinancialDocumentHandler = mockk<CreateFinancialDocumentHandler>()

    private lateinit var mockMvc: MockMvc

    private val studioId = StudioId.random()
    private val userId = UserId.random()
    private val visitId = VisitId.random()

    @BeforeEach
    fun setUp() {
        val completeVisitHandler = CompleteVisitHandler(
            visitRepository,
            customerRepository,
            auditService,
            createFinancialDocumentHandler
        )

        val controller = VisitTransitionController(
            markVisitReadyForPickupHandler = mockk(relaxed = true),
            completeVisitHandler = completeVisitHandler,
            rejectVisitHandler = mockk(relaxed = true),
            archiveVisitHandler = mockk(relaxed = true)
        )

        mockMvc = MockMvcBuilders
            .standaloneSetup(controller)
            .setControllerAdvice(GlobalExceptionHandler(mockk(relaxed = true)))
            .build()

        givenAuthenticatedOwner()

        coEvery { customerRepository.findByIdAndStudioId(any(), any()) } returns null
        every { createFinancialDocumentHandler.handle(any()) } returns mockk(relaxed = true)
    }

    @AfterEach
    fun tearDown() {
        SecurityContextHolder.clearContext()
    }

    // ─── Happy path ──────────────────────────────────────────────────────────

    @Test
    fun `POST complete returns 200 OK`() {
        coEvery { visitRepository.findByIdAndStudioIdWithPhotos(visitId.value, studioId.value) } returns readyForPickupEntity()
        coEvery { visitRepository.save(any()) } returns mockk(relaxed = true)

        mockMvc.perform(completeRequest())
            .andExpect(status().isOk)
    }

    @Test
    fun `POST complete returns completed status in response body`() {
        coEvery { visitRepository.findByIdAndStudioIdWithPhotos(visitId.value, studioId.value) } returns readyForPickupEntity()
        coEvery { visitRepository.save(any()) } returns mockk(relaxed = true)

        mockMvc.perform(completeRequest())
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.newStatus").value("completed"))
    }

    @Test
    fun `POST complete returns visitId in response body`() {
        coEvery { visitRepository.findByIdAndStudioIdWithPhotos(visitId.value, studioId.value) } returns readyForPickupEntity()
        coEvery { visitRepository.save(any()) } returns mockk(relaxed = true)

        mockMvc.perform(completeRequest())
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.visitId").value(visitId.value.toString()))
    }

    @Test
    fun `POST complete returns 404 when visit does not exist for this studio`() {
        coEvery { visitRepository.findByIdAndStudioIdWithPhotos(any(), any()) } returns null

        mockMvc.perform(completeRequest())
            .andExpect(status().isNotFound)
    }

    @Test
    fun `POST complete returns financialDocumentId when visit has service items`() {
        val docId = FinancialDocumentId.random()
        val finDoc = mockk<FinancialDocument>(relaxed = true) {
            every { id } returns docId
            every { documentNumber } returns "PAR/2026/0001"
        }
        coEvery { visitRepository.findByIdAndStudioIdWithPhotos(visitId.value, studioId.value) } returns readyForPickupEntity(serviceItemCount = 1)
        coEvery { visitRepository.save(any()) } returns mockk(relaxed = true)
        every { createFinancialDocumentHandler.handle(any()) } returns finDoc

        mockMvc.perform(completeRequest())
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.financialDocumentId").value(docId.value.toString()))
            .andExpect(jsonPath("$.financialDocumentNumber").value("PAR/2026/0001"))
    }

    @Test
    fun `POST complete returns null financialDocumentId when visit has no service items`() {
        coEvery { visitRepository.findByIdAndStudioIdWithPhotos(visitId.value, studioId.value) } returns readyForPickupEntity(serviceItemCount = 0)
        coEvery { visitRepository.save(any()) } returns mockk(relaxed = true)

        mockMvc.perform(completeRequest())
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.financialDocumentId").doesNotExist()
                .or(jsonPath("$.financialDocumentId").value(null as String?)))
    }

    // ─── Photo preservation through the full HTTP cycle ──────────────────────

    @Test
    fun `photos are not deleted when completing a visit with photos`() {
        val entity = readyForPickupEntity(photoCount = 3)
        val savedSlot = slot<VisitEntity>()

        coEvery { visitRepository.findByIdAndStudioIdWithPhotos(visitId.value, studioId.value) } returns entity
        coEvery { visitRepository.save(capture(savedSlot)) } returns mockk(relaxed = true)

        mockMvc.perform(completeRequest()).andExpect(status().isOk)

        assertEquals(3, savedSlot.captured.toDomain().photos.size)
    }

    @Test
    fun `photo fileId survives complete HTTP cycle unchanged`() {
        val originalPhoto = VisitPhoto(
            id = VisitPhotoId.random(),
            fileId = "s3://bucket/original-photo.jpg",
            fileName = "original-photo.jpg",
            description = null,
            uploadedAt = Instant.now()
        )
        val entity = readyForPickupEntity(photos = listOf(originalPhoto))
        val savedSlot = slot<VisitEntity>()

        coEvery { visitRepository.findByIdAndStudioIdWithPhotos(visitId.value, studioId.value) } returns entity
        coEvery { visitRepository.save(capture(savedSlot)) } returns mockk(relaxed = true)

        mockMvc.perform(completeRequest()).andExpect(status().isOk)

        assertEquals("s3://bucket/original-photo.jpg", savedSlot.captured.toDomain().photos.single().fileId)
    }

    @Test
    fun `photo description survives complete HTTP cycle unchanged`() {
        val photo = VisitPhoto(
            id = VisitPhotoId.random(),
            fileId = "s3://bucket/damage.jpg",
            fileName = "damage.jpg",
            description = "Zarysowanie lewego błotnika",
            uploadedAt = Instant.now()
        )
        val entity = readyForPickupEntity(photos = listOf(photo))
        val savedSlot = slot<VisitEntity>()

        coEvery { visitRepository.findByIdAndStudioIdWithPhotos(visitId.value, studioId.value) } returns entity
        coEvery { visitRepository.save(capture(savedSlot)) } returns mockk(relaxed = true)

        mockMvc.perform(completeRequest()).andExpect(status().isOk)

        assertEquals("Zarysowanie lewego błotnika", savedSlot.captured.toDomain().photos.single().description)
    }

    @Test
    fun `all photo ids are preserved when completing a visit with multiple photos`() {
        val photos = (1..5).map {
            VisitPhoto(
                id = VisitPhotoId.random(),
                fileId = "s3://bucket/photo-$it.jpg",
                fileName = "photo-$it.jpg",
                description = null,
                uploadedAt = Instant.now()
            )
        }
        val originalIds = photos.map { it.id }.toSet()
        val entity = readyForPickupEntity(photos = photos)
        val savedSlot = slot<VisitEntity>()

        coEvery { visitRepository.findByIdAndStudioIdWithPhotos(visitId.value, studioId.value) } returns entity
        coEvery { visitRepository.save(capture(savedSlot)) } returns mockk(relaxed = true)

        mockMvc.perform(completeRequest()).andExpect(status().isOk)

        val savedIds = savedSlot.captured.toDomain().photos.map { it.id }.toSet()
        assertEquals(originalIds, savedIds)
    }

    // ─── Repository method selection ─────────────────────────────────────────

    @Test
    fun `findByIdAndStudioIdWithPhotos is used — not the lazy findByIdAndStudioId`() {
        coEvery { visitRepository.findByIdAndStudioIdWithPhotos(visitId.value, studioId.value) } returns readyForPickupEntity()
        coEvery { visitRepository.save(any()) } returns mockk(relaxed = true)

        mockMvc.perform(completeRequest()).andExpect(status().isOk)

        coVerify(exactly = 1) { visitRepository.findByIdAndStudioIdWithPhotos(visitId.value, studioId.value) }
        coVerify(exactly = 0) { visitRepository.findByIdAndStudioId(any(), any()) }
    }

    @Test
    fun `studio isolation is enforced — only visits belonging to the authenticated studio are found`() {
        val otherStudioId = StudioId.random()
        // Visit belongs to a different studio — query returns null
        coEvery { visitRepository.findByIdAndStudioIdWithPhotos(visitId.value, studioId.value) } returns null

        mockMvc.perform(completeRequest())
            .andExpect(status().isNotFound)
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private fun givenAuthenticatedOwner() {
        val principal = UserPrincipal(
            userId = userId,
            studioId = studioId,
            role = UserRole.OWNER,
            email = "owner@detailboost.pl",
            fullName = "Jan Właściciel",
            phoneNumber = "+48600000000"
        )
        SecurityContextHolder.setContext(SecurityContextImpl(principal))
    }

    private fun completeRequest() = post("/api/visits/${visitId.value}/complete")
        .contentType(MediaType.APPLICATION_JSON)
        .content("""{"signatureObtained":false,"payment":{"method":"CASH"}}""")

    private fun readyForPickupEntity(
        photoCount: Int = 0,
        photos: List<VisitPhoto> = (1..photoCount).map {
            VisitPhoto(
                id = VisitPhotoId.random(),
                fileId = "s3://bucket/photo-$it.jpg",
                fileName = "photo-$it.jpg",
                description = null,
                uploadedAt = Instant.now()
            )
        },
        serviceItemCount: Int = 0
    ): VisitEntity = VisitEntity.fromDomain(
        Visit(
            id = visitId,
            studioId = studioId,
            visitNumber = "V/2026/E2E",
            customerId = CustomerId.random(),
            vehicleId = VehicleId.random(),
            appointmentId = AppointmentId.random(),
            appointmentColorId = null,
            brandSnapshot = "BMW",
            modelSnapshot = "X5",
            licensePlateSnapshot = "WA 99999",
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
