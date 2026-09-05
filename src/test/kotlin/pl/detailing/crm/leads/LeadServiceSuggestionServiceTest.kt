package pl.detailing.crm.leads

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher
import pl.detailing.crm.leads.appointment.LeadQuoteSyncService
import pl.detailing.crm.leads.infrastructure.LeadEntity
import pl.detailing.crm.leads.infrastructure.LeadRepository
import pl.detailing.crm.leads.infrastructure.LeadServiceItemEntity
import pl.detailing.crm.leads.infrastructure.LeadServiceItemRepository
import pl.detailing.crm.leads.infrastructure.LeadServiceItemSource
import pl.detailing.crm.leads.infrastructure.LeadServiceItemStatus
import pl.detailing.crm.leads.infrastructure.LeadServicePriceSource
import pl.detailing.crm.leads.similar.LeadServiceIntent
import pl.detailing.crm.leads.similar.LeadServiceIntentService
import pl.detailing.crm.leads.similar.LeadServiceSuggestionService
import pl.detailing.crm.leads.similar.LeadSimilarMatchesEntity
import pl.detailing.crm.leads.similar.LeadSimilarMatchesRepository
import pl.detailing.crm.leads.similar.MatchTier
import pl.detailing.crm.leads.similar.PendingSuggestionPriceException
import pl.detailing.crm.leads.similar.ServiceIntentStatus
import pl.detailing.crm.leads.similar.SimilarVisitReadRepository
import pl.detailing.crm.leads.update.LeadServiceItemsService
import pl.detailing.crm.service.infrastructure.ServiceEntity
import pl.detailing.crm.service.taxonomy.ServiceScope
import pl.detailing.crm.shared.LeadSource
import pl.detailing.crm.shared.LeadStatus
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.VisitId
import pl.detailing.crm.shared.VisitServiceStatus
import pl.detailing.crm.visit.domain.VisitFixtures
import pl.detailing.crm.visit.infrastructure.VisitEntity
import java.util.UUID

/**
 * „Sugerowane usługi": AI dobiera pozycje cennika, człowiek je akceptuje albo odrzuca.
 *
 * Najważniejsza własność jest niewidoczna, dopóki nie zawiedzie: MODEL NIE PODAJE CEN.
 * Cena stała bierze się z cennika, cena „wyceny niestandardowej" — z historii, a jej
 * brak zostawia pozycję bez ceny (nie zmyśloną). Te testy przybijają każdą z tych
 * ścieżek plus reguły akceptacji, odrzucenia i przeniesienia do rezerwacji.
 */
class LeadServiceSuggestionServiceTest {

    private val leadRepository = mockk<LeadRepository>()
    private val itemRepository = mockk<LeadServiceItemRepository>(relaxed = true)
    private val serviceRepository = mockk<pl.detailing.crm.service.infrastructure.ServiceRepository>()
    private val intentService = mockk<LeadServiceIntentService>()
    private val matchesRepository = mockk<LeadSimilarMatchesRepository>()
    private val visitRepository = mockk<SimilarVisitReadRepository>()
    private val itemsService = mockk<LeadServiceItemsService>(relaxed = true)
    private val quoteSync = mockk<LeadQuoteSyncService>(relaxed = true)
    private val eventPublisher = mockk<ApplicationEventPublisher>(relaxed = true)

    private val studioId = StudioId(UUID.randomUUID())
    private val leadId = UUID.randomUUID()
    private val fixedServiceId = UUID.randomUUID()
    private val manualServiceId = UUID.randomUUID()

    private val service = LeadServiceSuggestionService(
        leadRepository, itemRepository, serviceRepository, intentService,
        matchesRepository, visitRepository, itemsService, quoteSync, eventPublisher
    )

    @BeforeEach
    fun setUp() {
        every { leadRepository.findByIdAndStudioId(leadId, studioId.value) } returns lead()
        every { itemRepository.deleteByLeadIdAndStatusAndSource(any(), any(), any()) } returns Unit
        every { itemRepository.findByLeadIdOrderByCreatedAtAsc(leadId) } returns emptyList()
        every { matchesRepository.findById(leadId) } returns java.util.Optional.empty()
        every { itemRepository.save(any()) } answers { firstArg() }
    }

    @Test
    fun `usluga o stalej cenie dostaje cene z cennika`() {
        matchedIntent(fixedServiceId)
        every { serviceRepository.findAllByIdInAndStudioId(any(), studioId.value) } returns
            listOf(catalogService(fixedServiceId, "Mycie detailingowe", basePriceGross = 15000, manualPrice = false))
        val saved = slot<LeadServiceItemEntity>()
        every { itemRepository.save(capture(saved)) } answers { firstArg() }

        service.recompute(studioId, leadId, force = false)

        assertEquals(LeadServiceItemStatus.SUGGESTED, saved.captured.status)
        assertEquals(LeadServiceItemSource.AI, saved.captured.source)
        assertEquals(LeadServicePriceSource.CATALOG, saved.captured.priceSource)
        assertEquals(15000L, saved.captured.priceGross)
    }

    @Test
    fun `wycena niestandardowa z historia przepisuje najnowsza cene`() {
        matchedIntent(manualServiceId)
        every { serviceRepository.findAllByIdInAndStudioId(any(), studioId.value) } returns
            listOf(catalogService(manualServiceId, "Oklejenie PPF", basePriceGross = 0, manualPrice = true))
        // Dwie wizyty z tą usługą: starsza 1800, nowsza 2200 — wygrywa nowsza.
        val old = UUID.randomUUID(); val fresh = UUID.randomUUID()
        every { matchesRepository.findById(leadId) } returns java.util.Optional.of(
            LeadSimilarMatchesEntity(
                leadId = leadId, studioId = studioId.value,
                matches = LeadSimilarMatchesEntity.serialize(
                    listOf(old to MatchTier.SAME_MODEL_SAME_SERVICE, fresh to MatchTier.SAME_MODEL_SAME_SERVICE)
                )
            )
        )
        every { visitRepository.findByStudioIdAndIdIn(studioId.value, any()) } returns listOf(
            visitWith(old, "Oklejenie PPF", 180000, java.time.Instant.parse("2024-01-01T00:00:00Z")),
            visitWith(fresh, "Oklejenie PPF", 220000, java.time.Instant.parse("2025-06-01T00:00:00Z"))
        )
        val saved = slot<LeadServiceItemEntity>()
        every { itemRepository.save(capture(saved)) } answers { firstArg() }

        service.recompute(studioId, leadId, force = false)

        assertEquals(LeadServicePriceSource.HISTORY, saved.captured.priceSource)
        assertEquals(220000L, saved.captured.priceGross)
    }

    @Test
    fun `wycena niestandardowa bez historii czeka na kwote`() {
        matchedIntent(manualServiceId)
        every { serviceRepository.findAllByIdInAndStudioId(any(), studioId.value) } returns
            listOf(catalogService(manualServiceId, "Renowacja skóry", basePriceGross = 0, manualPrice = true))
        val saved = slot<LeadServiceItemEntity>()
        every { itemRepository.save(capture(saved)) } answers { firstArg() }

        service.recompute(studioId, leadId, force = false)

        assertEquals(LeadServicePriceSource.PENDING, saved.captured.priceSource)
        assertNull(saved.captured.priceGross)
    }

    @Test
    fun `robota spoza cennika nie tworzy zadnej sugestii`() {
        every { intentService.intentFor(studioId, leadId, any(), false) } returns
            LeadServiceIntent(ServiceIntentStatus.NOT_IN_CATALOG, emptySet(), emptySet(), ServiceScope.UNKNOWN)

        service.recompute(studioId, leadId, force = false)

        verify(exactly = 0) { itemRepository.save(any()) }
        // Poprzednie sugestie i tak zostały skasowane, a potencjał przeliczony.
        verify { itemRepository.deleteByLeadIdAndStatusAndSource(leadId, LeadServiceItemStatus.SUGGESTED, LeadServiceItemSource.AI) }
        verify { itemsService.recomputeEstimatedValue(any()) }
    }

    @Test
    fun `usluga juz obecna na leadzie nie jest sugerowana drugi raz`() {
        matchedIntent(fixedServiceId)
        every { itemRepository.findByLeadIdOrderByCreatedAtAsc(leadId) } returns listOf(
            LeadServiceItemEntity(
                id = UUID.randomUUID(), studioId = studioId.value, leadId = leadId,
                serviceId = fixedServiceId, name = "Mycie", priceGross = 15000, quantity = 1,
                status = LeadServiceItemStatus.ACCEPTED, source = LeadServiceItemSource.MANUAL
            )
        )
        every { serviceRepository.findAllByIdInAndStudioId(any(), studioId.value) } returns
            listOf(catalogService(fixedServiceId, "Mycie", basePriceGross = 15000, manualPrice = false))

        service.recompute(studioId, leadId, force = false)

        verify(exactly = 0) { itemRepository.save(any()) }
    }

    @Test
    fun `akceptacja bez ceny dla wyceny niestandardowej jest odrzucana`() {
        val itemId = UUID.randomUUID()
        every { itemRepository.findByLeadIdAndIdAndStatus(leadId, itemId, LeadServiceItemStatus.SUGGESTED) } returns
            pendingItem(itemId)

        val ex = assertThrows(pl.detailing.crm.shared.ValidationException::class.java) {
            service.accept(studioId, leadId, itemId, priceGross = null, userName = "Anna")
        }
        assertTrue(ex.message!!.contains("Podaj kwotę"))
        verify(exactly = 0) { quoteSync.pushToAppointment(any()) }
    }

    @Test
    fun `akceptacja z kwota domyka pozycje i synchronizuje rezerwacje`() {
        val itemId = UUID.randomUUID()
        val item = pendingItem(itemId)
        every { itemRepository.findByLeadIdAndIdAndStatus(leadId, itemId, LeadServiceItemStatus.SUGGESTED) } returns item

        service.accept(studioId, leadId, itemId, priceGross = 250000, userName = "Anna")

        assertEquals(LeadServiceItemStatus.ACCEPTED, item.status)
        assertEquals(250000L, item.priceGross)
        assertEquals(LeadServicePriceSource.MANUAL, item.priceSource)
        verify { quoteSync.pushToAppointment(any()) }
    }

    @Test
    fun `odrzucenie kasuje sugestie twardo`() {
        val itemId = UUID.randomUUID()
        val item = pendingItem(itemId)
        every { itemRepository.findByLeadIdAndIdAndStatus(leadId, itemId, LeadServiceItemStatus.SUGGESTED) } returns item

        service.reject(studioId, leadId, itemId)

        verify { itemRepository.delete(item) }
    }

    @Test
    fun `stworz rezerwacje blokuje, gdy sugestia czeka na kwote`() {
        every { itemRepository.findByLeadIdAndStatus(leadId, LeadServiceItemStatus.SUGGESTED) } returns listOf(
            pendingItem(UUID.randomUUID()).apply { }
        )

        val ex = assertThrows(PendingSuggestionPriceException::class.java) {
            service.acceptAllForBooking(studioId, leadId)
        }
        assertEquals(listOf("Renowacja skóry"), ex.serviceNames)
        verify(exactly = 0) { quoteSync.pushToAppointment(any()) }
    }

    @Test
    fun `stworz rezerwacje przenosi wszystkie wycenione sugestie`() {
        val a = pricedItem(UUID.randomUUID(), 15000)
        val b = pricedItem(UUID.randomUUID(), 220000)
        every { itemRepository.findByLeadIdAndStatus(leadId, LeadServiceItemStatus.SUGGESTED) } returns listOf(a, b)
        every { itemRepository.saveAll(any<List<LeadServiceItemEntity>>()) } answers { firstArg() }

        val moved = service.acceptAllForBooking(studioId, leadId)

        assertEquals(2, moved)
        assertTrue(listOf(a, b).all { it.status == LeadServiceItemStatus.ACCEPTED })
        verify { quoteSync.pushToAppointment(any()) }
    }

    // ── fabryki ──────────────────────────────────────────────────────────────

    private fun matchedIntent(vararg serviceIds: UUID) {
        every { intentService.intentFor(studioId, leadId, any(), any()) } returns LeadServiceIntent(
            ServiceIntentStatus.MATCHED, emptySet(), setOf("k"), ServiceScope.UNKNOWN, serviceIds.toList()
        )
    }

    private fun lead() = LeadEntity(
        id = leadId, studioId = studioId.value, source = LeadSource.EMAIL, status = LeadStatus.NEW,
        contactIdentifier = "k@example.com", customerName = null, initialMessage = "ile za PPF?",
        estimatedValue = 0, requiresVerification = false, vehicleBrand = "Porsche", vehicleModel = "Panamera",
        customerId = null, appointmentId = null, visitId = null, assignedUserId = null,
        assignedUserName = null, lostReason = null, stagnantAlertSentAt = null
    )

    private fun catalogService(id: UUID, name: String, basePriceGross: Long, manualPrice: Boolean) =
        ServiceEntity(
            id = id, studioId = studioId.value, name = name,
            basePriceNet = (basePriceGross / 1.23).toLong(), basePriceGross = basePriceGross,
            vatRate = 23, isActive = true, requireManualPrice = manualPrice, isPackage = false,
            replacesServiceId = null, createdBy = UUID.randomUUID(), updatedBy = UUID.randomUUID()
        )

    private fun pendingItem(itemId: UUID) = LeadServiceItemEntity(
        id = itemId, studioId = studioId.value, leadId = leadId, serviceId = manualServiceId,
        name = "Renowacja skóry", priceGross = null, quantity = 1,
        status = LeadServiceItemStatus.SUGGESTED, source = LeadServiceItemSource.AI,
        priceSource = LeadServicePriceSource.PENDING
    )

    private fun pricedItem(itemId: UUID, price: Long) = LeadServiceItemEntity(
        id = itemId, studioId = studioId.value, leadId = leadId, serviceId = UUID.randomUUID(),
        name = "Usługa", priceGross = price, quantity = 1,
        status = LeadServiceItemStatus.SUGGESTED, source = LeadServiceItemSource.AI,
        priceSource = LeadServicePriceSource.CATALOG
    )

    private fun visitWith(visitId: UUID, serviceName: String, priceGross: Long, at: java.time.Instant): VisitEntity {
        val domain = VisitFixtures.visit(
            studioId = studioId,
            items = listOf(
                VisitFixtures.serviceItem(finalPriceGross = priceGross, status = VisitServiceStatus.CONFIRMED)
                    .copy(serviceName = serviceName)
            )
        ).copy(id = VisitId(visitId), actualCompletionDate = at)
        return VisitEntity.fromDomain(domain)
    }
}
