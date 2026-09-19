package pl.detailing.crm.instagram.ads.discovery

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pl.detailing.crm.instagram.ads.RawMetaAd
import java.time.Instant
import java.time.LocalDate

/**
 * Rejestr reklamodawców: rośnie, pamięta najwcześniejszy start i nie da się go „odmłodzić".
 */
class AdvertiserLedgerTest {

    private val repository = mockk<AdDiscoveryAdvertiserRepository>()
    private val ledger = AdvertiserLedger(repository)

    private fun ad(id: String, pageId: String, start: LocalDate, pageName: String? = "Firma") = RawMetaAd(
        adArchiveId = id,
        pageId = pageId,
        pageName = pageName,
        title = null,
        body = null,
        linkDescription = null,
        linkCaption = null,
        deliveryStart = start,
        deliveryStop = null,
        reachEu = null,
        platforms = emptyList(),
        targetAges = null,
        targetGender = null,
        targetLocations = emptyList(),
        payer = null,
        beneficiary = null,
        polandBreakdown = emptyList()
    )

    private fun captureSaved(existing: List<AdDiscoveryAdvertiserEntity>): () -> List<AdDiscoveryAdvertiserEntity> {
        val saved = slot<Iterable<AdDiscoveryAdvertiserEntity>>()
        every { repository.findByPageIdIn(any()) } returns existing
        every { repository.saveAll(capture(saved)) } answers { saved.captured.toList() }
        return { saved.captured.toList() }
    }

    @Test
    fun `nieznana strona dostaje wpis z najwczesniejszym startem z paczki`() {
        val saved = captureSaved(emptyList())
        val now = Instant.parse("2026-09-19T10:00:00Z")

        ledger.record(
            listOf(
                ad("1", "100", LocalDate.of(2026, 9, 10)),
                ad("2", "100", LocalDate.of(2026, 3, 1), pageName = null)
            ),
            now
        )

        val entity = saved().single()
        assertEquals("100", entity.pageId)
        assertEquals(LocalDate.of(2026, 3, 1), entity.firstDeliveryStart)
        assertEquals("Firma", entity.pageName)
        assertEquals(now, entity.firstSeenAt)
        assertEquals(now, entity.lastSeenAt)
    }

    @Test
    fun `znana strona nie daje sie odmlodzic nowa kampania, ale postarza sie starsza`() {
        val existing = AdDiscoveryAdvertiserEntity(
            pageId = "100",
            pageName = "Stara nazwa",
            firstDeliveryStart = LocalDate.of(2026, 5, 1),
            firstSeenAt = Instant.parse("2026-05-02T00:00:00Z"),
            lastSeenAt = Instant.parse("2026-05-02T00:00:00Z")
        )
        val saved = captureSaved(listOf(existing))
        val now = Instant.parse("2026-09-19T10:00:00Z")

        ledger.record(listOf(ad("1", "100", LocalDate.of(2026, 9, 18))), now)
        assertEquals(LocalDate.of(2026, 5, 1), saved().single().firstDeliveryStart)
        assertEquals(now, saved().single().lastSeenAt)
        assertEquals("Firma", saved().single().pageName)

        ledger.record(listOf(ad("2", "100", LocalDate.of(2025, 12, 24), pageName = "  ")), now)
        assertEquals(LocalDate.of(2025, 12, 24), saved().single().firstDeliveryStart)
        // Pusta nazwa z Meta nie nadpisuje znanej.
        assertEquals("Firma", saved().single().pageName)
    }

    @Test
    fun `pusta paczka nie dotyka bazy`() {
        ledger.record(emptyList())
        assertTrue(true)
    }
}
