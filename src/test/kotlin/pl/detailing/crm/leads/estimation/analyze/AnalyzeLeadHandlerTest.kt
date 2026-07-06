package pl.detailing.crm.leads.estimation.analyze

import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher
import pl.detailing.crm.audit.domain.AuditService
import pl.detailing.crm.leads.estimation.domain.LeadAnalysisResult
import pl.detailing.crm.leads.estimation.domain.LeadAnalyzer
import pl.detailing.crm.leads.estimation.infrastructure.LeadEstimationEntity
import pl.detailing.crm.leads.estimation.infrastructure.LeadEstimationRepository
import pl.detailing.crm.leads.infrastructure.LeadEntity
import pl.detailing.crm.leads.infrastructure.LeadRepository
import pl.detailing.crm.service.infrastructure.ServiceEntity
import pl.detailing.crm.service.infrastructure.ServiceRepository
import pl.detailing.crm.shared.LeadId
import pl.detailing.crm.shared.LeadSource
import pl.detailing.crm.shared.LeadStatus
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.visit.infrastructure.VisitRepository
import java.time.Instant
import java.util.Optional
import java.util.UUID

class AnalyzeLeadHandlerTest {

    private val leadRepository = mockk<LeadRepository>()
    private val serviceRepository = mockk<ServiceRepository>()
    private val leadEstimationRepository = mockk<LeadEstimationRepository>()
    private val visitRepository = mockk<VisitRepository>(relaxed = true)
    private val leadAnalyzer = mockk<LeadAnalyzer>()
    private val auditService = mockk<AuditService>(relaxed = true)
    private val eventPublisher = mockk<ApplicationEventPublisher>(relaxed = true)

    private val handler = AnalyzeLeadHandler(
        leadRepository,
        serviceRepository,
        leadEstimationRepository,
        visitRepository,
        leadAnalyzer,
        auditService,
        eventPublisher
    )

    private val studioId = StudioId.random()
    private val leadId = LeadId.random()

    private val manualPriceServiceId = UUID.randomUUID()
    private val fixedPriceServiceId = UUID.randomUUID()

    private lateinit var savedEstimation: LeadEstimationEntity

    @BeforeEach
    fun setUp() {
        every { leadRepository.findById(leadId.value) } returns Optional.of(lead())
        every { leadRepository.save(any()) } answers { firstArg() }
        every { leadEstimationRepository.findByLeadId(leadId.value) } returns null
        every { leadEstimationRepository.save(any()) } answers {
            savedEstimation = firstArg()
            savedEstimation
        }
        every { leadEstimationRepository.findById(any()) } answers { Optional.of(savedEstimation) }
        every { serviceRepository.findActiveByStudioId(studioId.value) } returns listOf(
            service(id = manualPriceServiceId, name = "Polerowanie lakieru", priceNet = 81, requireManualPrice = true),
            service(id = fixedPriceServiceId, name = "Mycie podwozia", priceNet = 16260, requireManualPrice = false)
        )
    }

    @Test
    fun `deduplicates services matched by multiple needs`() = runBlocking {
        coEvery { leadAnalyzer.analyze(any(), any(), any(), any(), any()) } returns LeadAnalysisResult(
            extractedNeeds = listOf("korekta one step", "korekta two step", "mycie podwozia"),
            matchedServiceIds = listOf(
                manualPriceServiceId.toString(),
                manualPriceServiceId.toString(),
                fixedPriceServiceId.toString()
            ),
            unmatchedNeeds = emptyList()
        )

        handler.handle(AnalyzeLeadCommand(leadId = leadId, studioId = studioId))

        assertEquals(2, savedEstimation.items.size)
        assertEquals(
            listOf(manualPriceServiceId, fixedPriceServiceId),
            savedEstimation.items.map { it.serviceId }
        )
    }

    @Test
    fun `zeroes prices of manual-price services and excludes them from totals`() = runBlocking {
        coEvery { leadAnalyzer.analyze(any(), any(), any(), any(), any()) } returns LeadAnalysisResult(
            extractedNeeds = listOf("korekta lakieru", "mycie podwozia"),
            matchedServiceIds = listOf(manualPriceServiceId.toString(), fixedPriceServiceId.toString()),
            unmatchedNeeds = emptyList()
        )

        handler.handle(AnalyzeLeadCommand(leadId = leadId, studioId = studioId))

        val manualItem = savedEstimation.items.first { it.serviceId == manualPriceServiceId }
        assertTrue(manualItem.requiresManualPrice)
        assertEquals(0L, manualItem.priceNet)
        assertEquals(0L, manualItem.priceGross)

        val fixedItem = savedEstimation.items.first { it.serviceId == fixedPriceServiceId }
        assertFalse(fixedItem.requiresManualPrice)
        assertEquals(16260L, fixedItem.priceNet)
        assertEquals(19999L, fixedItem.priceGross)

        // Total (and thus the pre-filled lead.estimatedValue) contains only auto-priced items
        assertEquals(19999L, savedEstimation.totalGross)
    }

    private fun lead() = LeadEntity(
        id = leadId.value,
        studioId = studioId.value,
        source = LeadSource.EMAIL,
        status = LeadStatus.NEW,
        contactIdentifier = "klient@example.com",
        customerName = "Kamil Czajka",
        initialMessage = "Proszę o wycenę",
        estimatedValue = 0L,
        requiresVerification = false,
        vehicleBrand = null,
        vehicleModel = null,
        customerId = null,
        appointmentId = null,
        visitId = null,
        assignedUserId = null,
        assignedUserName = null,
        lostReason = null,
        stagnantAlertSentAt = null,
        newActivityAt = null
    )

    private fun service(id: UUID, name: String, priceNet: Long, requireManualPrice: Boolean) = ServiceEntity(
        id = id,
        studioId = studioId.value,
        name = name,
        basePriceNet = priceNet,
        vatRate = 23,
        isActive = true,
        requireManualPrice = requireManualPrice,
        isPackage = false,
        replacesServiceId = null,
        createdBy = UUID.randomUUID(),
        updatedBy = UUID.randomUUID(),
        createdAt = Instant.now(),
        updatedAt = Instant.now()
    )
}
