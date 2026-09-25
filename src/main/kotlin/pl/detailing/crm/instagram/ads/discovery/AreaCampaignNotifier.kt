package pl.detailing.crm.instagram.ads.discovery

import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener
import pl.detailing.crm.push.notify.PushMessages
import pl.detailing.crm.push.notify.PushNotifier
import pl.detailing.crm.role.domain.Permission
import pl.detailing.crm.rolepreview.RolePreviewOutboundGuard
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.subscription.entitlement.capability.CapabilityKey
import pl.detailing.crm.subscription.entitlement.capability.CapabilityService
import java.time.LocalDate

/**
 * Reklamy, które przy odświeżeniu frazy pojawiły się w cache po raz pierwszy.
 * Publikowane przez [AdDiscoveryCacheWriter] w transakcji podmiany.
 */
data class AreaAdsAppearedEvent(val phrase: String, val adArchiveIds: Set<String>)

/**
 * „Nowa kampania w Twoim rejonie" - powiadomienie push o konkurencji, która zaczęła
 * się reklamować tam, gdzie studio śledzi rynek.
 *
 * Nowość liczona DOKŁADNIE jak odznaki w tabeli i pasek na Tablicy
 * ([AdDiscoveryReadService.novelty]): ten sam [AreaAdvertiserSummary] (rejon, tryb
 * dopasowania, ukryci reklamodawcy), to samo okno [AreaNovelty] i to samo
 * odznaczenie „widziałem". Powiadomienie, które mówi o firmie, której tabela nie
 * pokazuje jako nowej, byłoby gorsze niż żadne.
 *
 * Dwa warunki naraz, każdy z innego powodu:
 *   - reklama POJAWIŁA SIĘ w tym odświeżeniu (nie było jej w poprzednim stanie
 *     frazy) - inaczej każde odświeżenie co kilkanaście godzin powtarzałoby te
 *     same kampanie przez całe dwa tygodnie okna;
 *   - jej emisja RUSZYŁA w oknie nowości - reklama, która wypadła z cache przez
 *     ucięcie paginacji i wróciła, trwa od miesięcy i nie jest „nową kampanią".
 *
 * Tanio mimo wspólnego cache dla całej instalacji: liczymy wyłącznie na reklamach,
 * które się pojawiły (garść), a debiutanta rozpoznajemy po rejestrze
 * reklamodawców, bez ładowania całego cache każdego studia.
 */
@Component
class AreaCampaignNotifier(
    private val adRepository: AdDiscoveryAdRepository,
    private val settingsRepository: AdAreaSettingsRepository,
    private val advertiserRepository: AdDiscoveryAdvertiserRepository,
    private val blockService: AdvertiserBlockService,
    private val capabilityService: CapabilityService,
    private val rolePreviewGuard: RolePreviewOutboundGuard,
    private val pushNotifier: PushNotifier
) {
    private val log = LoggerFactory.getLogger(AreaCampaignNotifier::class.java)

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    fun onAdsAppeared(event: AreaAdsAppearedEvent) {
        runCatching { notifyStudios(event) }
            .onFailure { log.warn("[push] Kampanie w rejonie - fraza „{}”: {}", event.phrase, it.message) }
    }

    internal fun notifyStudios(event: AreaAdsAppearedEvent, today: LocalDate = AreaNovelty.today()) {
        val fresh = adRepository.findByPhraseIn(listOf(event.phrase))
            .filter { it.adArchiveId in event.adArchiveIds }
            .map { it.toDiscovered() }
            .filter { it.active && AreaNovelty.isNew(it.deliveryStart, today) }
        if (fresh.isEmpty()) return

        val knownSince = advertiserRepository.findByPageIdIn(fresh.map { it.pageId }.distinct())
            .associate { it.pageId to it.firstDeliveryStart }

        settingsRepository.findAllConfigured().forEach { settings ->
            runCatching { notifyStudio(settings, event.phrase, fresh, knownSince, today) }
                .onFailure { log.warn("[push] Kampanie w rejonie - studio {}: {}", settings.studioId, it.message) }
        }
    }

    private fun notifyStudio(
        settings: AdAreaSettingsEntity,
        phrase: String,
        fresh: List<DiscoveredAd>,
        knownSince: Map<String, LocalDate>,
        today: LocalDate
    ) {
        val tracked = AdDiscoveryCatalog.phrasesExcept(AreaLists.decode(settings.excludedPhraseIds))
            .mapNotNull(AdDiscoveryPhrase::normalizeValid)
        if (phrase !in tracked) return

        val studioId = StudioId(settings.studioId)
        // Piaskownica podglądu roli i studio bez modułu monitoringu nie widzą tej tabeli,
        // więc nie dostają też wiadomości o niej.
        if (rolePreviewGuard.isSandbox(studioId.value)) return
        if (!capabilityService.hasCapability(studioId, CapabilityKey.INSTAGRAM_MONITOR)) return

        val rows = AreaAdvertiserSummary.summarize(
            ads = fresh,
            requestedCities = AreaLists.decode(settings.locations),
            mode = settings.matchMode,
            blockedPageIds = blockService.blockedPageIds(studioId),
            knownSince = knownSince,
            ackedThrough = settings.noveltyAckedThrough,
            today = today
        ).filter { it.newCampaigns > 0 }

        val payload = PushMessages.areaCampaigns(
            rows.map { PushMessages.AreaAdvertiser(it.pageId, it.companyName, it.newCampaigns, it.newAdvertiser) },
            today
        ) ?: return

        pushNotifier.broadcast(
            studioId = studioId,
            requiredPermission = Permission.MARKETING_MANAGE,
            message = PushMessages.Message(payload),
            // Konkurencja nie znika w godzinę - wiadomość ma sens także rano po nocy offline.
            ttlSeconds = 24 * 3600
        )
    }
}
