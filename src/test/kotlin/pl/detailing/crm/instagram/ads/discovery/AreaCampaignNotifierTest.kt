package pl.detailing.crm.instagram.ads.discovery

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import pl.detailing.crm.instagram.ads.MetaAdCodec
import pl.detailing.crm.instagram.ads.RawAdLocation
import pl.detailing.crm.instagram.ads.RawMetaAd
import pl.detailing.crm.push.notify.PushMessages
import pl.detailing.crm.push.notify.PushNotifier
import pl.detailing.crm.role.domain.Permission
import pl.detailing.crm.rolepreview.RolePreviewOutboundGuard
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.subscription.entitlement.capability.CapabilityService
import java.time.LocalDate
import java.util.UUID

/**
 * „Nowa kampania w Twoim rejonie": studio dostaje powiadomienie wyłącznie o tym,
 * co tabela pokazałaby mu jako nowe - w jego rejonie, spoza ukrytych, ruszające
 * w oknie nowości - i tylko o reklamach, które pojawiły się w tym odświeżeniu.
 */
class AreaCampaignNotifierTest {

    private val today = LocalDate.of(2026, 9, 25)
    private val phrase = AdDiscoveryCatalog.ALL.first().text
    private val studio = UUID.randomUUID()

    private val adRepository = mockk<AdDiscoveryAdRepository>()
    private val settingsRepository = mockk<AdAreaSettingsRepository>()
    private val advertiserRepository = mockk<AdDiscoveryAdvertiserRepository>()
    private val blockService = mockk<AdvertiserBlockService>()
    private val capabilities = mockk<CapabilityService>()
    private val guard = mockk<RolePreviewOutboundGuard>()
    private val pushNotifier = mockk<PushNotifier>(relaxed = true)

    private val notifier = AreaCampaignNotifier(
        adRepository, settingsRepository, advertiserRepository, blockService, capabilities, guard, pushNotifier
    )

    private var settings = AdAreaSettingsEntity(studioId = studio).apply { locations = AreaLists.encode(listOf("Poznań")) }

    init {
        every { settingsRepository.findAllConfigured() } answers { listOf(settings) }
        every { blockService.blockedPageIds(StudioId(studio)) } returns emptySet()
        every { capabilities.hasCapability(StudioId(studio), any()) } returns true
        every { guard.isSandbox(any()) } returns false
        every { advertiserRepository.findByPageIdIn(any()) } returns emptyList()
    }

    private fun ad(id: String, page: String, city: String, start: LocalDate) = AdDiscoveryAdEntity(
        id = UUID.randomUUID(), phrase = phrase, adArchiveId = id, pageId = page, pageName = "Firma $page",
        deliveryStart = start, targetLocations = MetaAdCodec.encodeLocations(listOf(RawAdLocation(city, "city", false)))
    )

    private fun cache(vararg ads: AdDiscoveryAdEntity) {
        every { adRepository.findByPhraseIn(listOf(phrase)) } returns ads.toList()
    }

    private fun sentMessage(): PushMessages.Message? {
        val message = slot<PushMessages.Message>()
        return runCatching {
            verify { pushNotifier.broadcast(StudioId(studio), Permission.MARKETING_MANAGE, capture(message), any(), any()) }
            message.captured
        }.getOrNull()
    }

    @Test
    fun `nowa reklama w rejonie studia daje powiadomienie z nazwa firmy`() {
        cache(ad("n1", "p1", "Poznań", today.minusDays(1)), ad("old", "p2", "Poznań", today.minusDays(1)))

        notifier.notifyStudios(AreaAdsAppearedEvent(phrase, setOf("n1")), today)

        val payload = sentMessage()!!.masked
        // Rejestr nie zna firmy - a jej najwcześniejszy start jest w oknie: debiutant.
        assertEquals("Nowa firma reklamuje się w Twoim rejonie", payload.title)
        assertEquals("Firma p1: 1 nowa reklama na Facebooku i Instagramie.", payload.body)
    }

    @Test
    fun `firma znana od dawna z nowa kreacja to nowa kampania, nie debiutant`() {
        cache(ad("n1", "p1", "Poznań", today))
        every { advertiserRepository.findByPageIdIn(any()) } returns
            listOf(AdDiscoveryAdvertiserEntity(pageId = "p1", firstDeliveryStart = today.minusDays(200)))

        notifier.notifyStudios(AreaAdsAppearedEvent(phrase, setOf("n1")), today)

        assertEquals("Nowa kampania w Twoim rejonie", sentMessage()!!.masked.title)
    }

    @Test
    fun `reklama spoza rejonu, stara albo ukrytej firmy - cisza`() {
        cache(
            ad("far", "p1", "Gdańsk", today),
            ad("stale", "p2", "Poznań", today.minusDays(40)), // wróciła do cache, trwa od dawna
            ad("hidden", "p3", "Poznań", today)
        )
        every { blockService.blockedPageIds(StudioId(studio)) } returns setOf("p3")

        notifier.notifyStudios(AreaAdsAppearedEvent(phrase, setOf("far", "stale", "hidden")), today)

        verify(exactly = 0) { pushNotifier.broadcast(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `odznaczona fraza, odznaczone nowosci albo brak modulu - cisza`() {
        cache(ad("n1", "p1", "Poznań", today.minusDays(2)))
        val event = AreaAdsAppearedEvent(phrase, setOf("n1"))

        settings.excludedPhraseIds = AreaLists.encode(listOf(AdDiscoveryCatalog.ALL.first().id))
        notifier.notifyStudios(event, today)

        settings.excludedPhraseIds = ""
        settings.noveltyAckedThrough = today.minusDays(1) // „widziałem wszystko do wczoraj"
        notifier.notifyStudios(event, today)

        settings.noveltyAckedThrough = null
        every { capabilities.hasCapability(StudioId(studio), any()) } returns false
        notifier.notifyStudios(event, today)

        verify(exactly = 0) { pushNotifier.broadcast(any(), any(), any(), any(), any()) }
    }
}
