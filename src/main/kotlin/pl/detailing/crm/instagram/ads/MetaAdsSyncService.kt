package pl.detailing.crm.instagram.ads

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import pl.detailing.crm.instagram.infrastructure.InstagramProfileRepository
import java.time.Instant
import java.util.UUID

/**
 * Pobranie reklam obserwowanych profili i zapisanie ich jako migawek.
 *
 * Dlaczego w ogóle zapisujemy, skoro API jest publiczne: Biblioteka reklam
 * pokazuje wyłącznie STAN NA TERAZ. Reklama, która skończyła się wczoraj, jutro
 * wygląda tak samo jak ta, która skończyła się pół roku temu, a po roku znika
 * z biblioteki bez śladu. Kalendarz roczny, „ile dni się reklamowali" i zdarzenia
 * w Pulsie istnieją wyłącznie dzięki temu, że mamy własny zapis w czasie.
 *
 * Zakończenie emisji rozpoznajemy po jednej rzeczy: `ad_delivery_stop_time`
 * przestaje być puste. Zapisujemy wtedy [MetaAdSnapshotEntity.endedDetectedAt]
 * — datę NASZEGO odczytu, bo Meta nie mówi, kiedy naprawdę wyłączyła emisję.
 */
@Service
class MetaAdsSyncService(
    private val client: MetaAdLibraryClient,
    private val snapshotRepository: MetaAdSnapshotRepository,
    private val profileRepository: InstagramProfileRepository
) {
    private val log = LoggerFactory.getLogger(MetaAdsSyncService::class.java)

    data class SyncResult(
        val pagesChecked: Int,
        val adsSeen: Int,
        val adsNew: Int,
        val adsEnded: Int,
        /**
         * Nazwa strony tak, jak zwróciła ją Meta. Jedyne potwierdzenie, że wskazany
         * numer należy do tej firmy, o którą chodziło — sam numer nic nie mówi,
         * a pomyłka wciąga do kalendarza reklamy zupełnie obcego przedsiębiorstwa.
         */
        val pageName: String? = null
    )

    /**
     * Jeden przebieg dla wszystkich profili z powiązaną stroną na Facebooku.
     * Profile bez powiązania są pomijane — nie ma po czym ich szukać.
     *
     * Świadomie BEZ wspólnej transakcji: w środku siedzi wywołanie HTTP do Meta,
     * a transakcja obejmująca sieć trzyma połączenie z bazą przez cały czas
     * odpowiedzi obcego serwera. Zapisy są idempotentne (klucz naturalny to
     * `ad_archive_id`), więc przerwany przebieg dokończy się nazajutrz.
     */
    fun syncAll(): SyncResult {
        if (!client.enabled) {
            log.debug("Meta Ad Library: pominięte, brak tokena")
            return SyncResult(0, 0, 0, 0)
        }

        val profiles = profileRepository.findAllWithFacebookPage()
        if (profiles.isEmpty()) return SyncResult(0, 0, 0, 0)

        val profileByPage: Map<String, UUID> = profiles
            .mapNotNull { profile -> profile.facebookPageId?.let { it to profile.id } }
            .toMap()

        val ads = try {
            client.fetchAdsForPages(profileByPage.keys.toList())
        } catch (e: MetaAdsException) {
            log.warn("Meta Ad Library: sync przerwany — {}", e.message)
            return SyncResult(profileByPage.size, 0, 0, 0)
        }

        val result = persist(ads, profileByPage, pagesChecked = profileByPage.size)
        log.info(
            "Meta Ad Library: {} stron, {} reklam ({} nowych, {} zakończonych)",
            result.pagesChecked, result.adsSeen, result.adsNew, result.adsEnded
        )
        return result
    }

    /**
     * Jedna strona, natychmiast — wołane zaraz po wskazaniu strony na Facebooku.
     *
     * Bez tego powiązanie profilu było ruchem bez skutku: właściciel wpisywał
     * identyfikator, wiersz pojawiał się w kalendarzu pusty i nic więcej się nie
     * działo aż do nocnego przebiegu. Skoro człowiek właśnie powiedział nam, gdzie
     * patrzeć, patrzymy od razu.
     */
    fun syncProfile(profileId: UUID, pageId: String): SyncResult {
        if (!client.enabled) {
            log.info("Meta Ad Library: profil {} bez pobrania — brak tokena (meta.ads.token)", profileId)
            return SyncResult(0, 0, 0, 0)
        }

        val ads = try {
            client.fetchAdsForPages(listOf(pageId))
        } catch (e: MetaAdsException) {
            log.warn("Meta Ad Library: pobranie dla strony {} nie powiodło się — {}", pageId, e.message)
            return SyncResult(1, 0, 0, 0)
        }

        val result = persist(ads.filter { it.pageId == pageId }, mapOf(pageId to profileId), pagesChecked = 1)
        log.info(
            "Meta Ad Library: strona {} → {} reklam ({} nowych) dla profilu {}",
            pageId, result.adsSeen, result.adsNew, profileId
        )
        return result
    }

    /** Wspólny zapis migawek — ta sama arytmetyka dla przebiegu nocnego i pojedynczej strony. */
    private fun persist(
        ads: List<RawMetaAd>,
        profileByPage: Map<String, UUID>,
        pagesChecked: Int
    ): SyncResult {
        var created = 0
        var ended = 0
        val now = Instant.now()
        val existing = snapshotRepository.findByAdArchiveIdIn(ads.map { it.adArchiveId })
            .associateBy { it.adArchiveId }

        ads.forEach { ad ->
            // Reklama strony, której nikt u nas nie obserwuje (Meta bywa hojna
            // w dopasowaniu) — nie mamy jej gdzie przypiąć.
            val profileId = profileByPage[ad.pageId] ?: return@forEach

            val row = existing[ad.adArchiveId]
            if (row == null) {
                snapshotRepository.save(toEntity(ad, profileId, now))
                created++
            } else if (update(row, ad, now)) {
                ended++
            }
        }

        return SyncResult(
            pagesChecked = pagesChecked,
            adsSeen = ads.size,
            adsNew = created,
            adsEnded = ended,
            pageName = ads.firstNotNullOfOrNull { it.pageName?.trim()?.takeIf(String::isNotBlank) }
        )
    }

    private fun toEntity(ad: RawMetaAd, profileId: UUID, now: Instant) = MetaAdSnapshotEntity(
        id = UUID.randomUUID(),
        adArchiveId = ad.adArchiveId,
        pageId = ad.pageId,
        profileId = profileId,
        title = ad.title,
        deliveryStart = ad.deliveryStart,
        deliveryStop = ad.deliveryStop,
        reachEu = ad.reachEu,
        reachPl = ad.reachPoland,
        platforms = MetaAdCodec.encodePlatforms(ad.platforms),
        targetAges = ad.targetAges,
        targetGender = ad.targetGender,
        targetLocations = MetaAdCodec.encodeLocations(ad.targetLocations),
        payer = ad.payer?.take(200),
        beneficiary = ad.beneficiary?.take(200),
        reachBreakdown = MetaAdCodec.encodeBreakdown(ad.polandBreakdown),
        snapshotUrl = ad.snapshotUrl,
        firstSeenAt = now,
        lastSeenAt = now,
        // Reklama, którą widzimy PIERWSZY raz już jako zakończoną, nie jest
        // zdarzeniem „ktoś właśnie skończył się reklamować" — nie widzieliśmy jej trwania.
        endedDetectedAt = null
    )

    /** Zwraca true, gdy to wywołanie wykryło zakończenie emisji. */
    private fun update(row: MetaAdSnapshotEntity, ad: RawMetaAd, now: Instant): Boolean {
        val justEnded = row.deliveryStop == null && ad.deliveryStop != null

        row.title = ad.title ?: row.title
        row.deliveryStart = ad.deliveryStart
        row.deliveryStop = ad.deliveryStop
        row.reachEu = ad.reachEu ?: row.reachEu
        row.reachPl = ad.reachPoland ?: row.reachPl
        row.platforms = MetaAdCodec.encodePlatforms(ad.platforms).ifBlank { row.platforms }
        row.targetAges = ad.targetAges ?: row.targetAges
        row.targetGender = ad.targetGender ?: row.targetGender
        row.targetLocations = MetaAdCodec.encodeLocations(ad.targetLocations).ifBlank { row.targetLocations }
        row.payer = ad.payer?.take(200) ?: row.payer
        row.beneficiary = ad.beneficiary?.take(200) ?: row.beneficiary
        row.reachBreakdown = MetaAdCodec.encodeBreakdown(ad.polandBreakdown).ifBlank { row.reachBreakdown }
        row.snapshotUrl = ad.snapshotUrl ?: row.snapshotUrl
        row.lastSeenAt = now
        row.updatedAt = now
        if (justEnded) row.endedDetectedAt = now

        snapshotRepository.save(row)
        return justEnded
    }
}
