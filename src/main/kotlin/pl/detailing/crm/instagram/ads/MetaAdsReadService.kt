package pl.detailing.crm.instagram.ads

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.instagram.infrastructure.InstagramProfileRepository
import pl.detailing.crm.instagram.infrastructure.StudioInstagramProfileRepository
import pl.detailing.crm.shared.InstagramProfileStatus
import pl.detailing.crm.shared.StudioId
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/**
 * Odczyt zakładki „Reklamy": kalendarz roku, podsumowanie i szczegóły kampanii.
 *
 * Wszystko liczymy z własnych migawek — do Meta nie chodzimy przy odczycie.
 * Ekran ma być tani i zawsze dostępny, także gdy token wygasł albo Meta ma awarię.
 */
@Service
class MetaAdsReadService(
    private val studioProfileRepository: StudioInstagramProfileRepository,
    private val profileRepository: InstagramProfileRepository,
    private val snapshotRepository: MetaAdSnapshotRepository,
    private val client: MetaAdLibraryClient
) {
    private val log = LoggerFactory.getLogger(MetaAdsReadService::class.java)


    @Transactional(readOnly = true)
    fun calendar(studioId: StudioId, year: Int): AdCalendarResponse {
        val today = LocalDate.now(ZoneOffset.UTC)
        val windowStart = LocalDate.of(year, 1, 1)
        // Rok bieżący kończy się dziś; kalendarz nie rysuje przyszłości, bo jej nie ma.
        val windowEnd = minOf(LocalDate.of(year, 12, 31), today)

        val links = studioProfileRepository
            .findByStudioIdAndStatus(studioId.value, InstagramProfileStatus.ACTIVE)
        if (links.isEmpty()) {
            return AdCalendarResponse(year, today.toString(), 0, emptyList(), emptyList(), client.enabled)
        }

        val profiles = profileRepository.findAllById(links.map { it.profileId }).associateBy { it.id }
        val linked = links.filter { profiles[it.profileId]?.facebookPageId != null }
        val unlinked = links
            .filter { profiles[it.profileId]?.facebookPageId == null }
            .mapNotNull { link ->
                profiles[link.profileId]?.let { UnlinkedProfileDto(it.id.toString(), it.username) }
            }
            .sortedBy { it.username }

        val snapshots = if (linked.isEmpty()) emptyList()
        else snapshotRepository.findByProfileIdIn(linked.map { it.profileId })

        val byProfile = snapshots
            .filter { AdCalendarMath.daysInWindow(it.deliveryStart, it.deliveryStop, windowStart, windowEnd) > 0 }
            .groupBy { it.profileId }

        val rows = linked.mapNotNull { link ->
            val profile = profiles[link.profileId] ?: return@mapNotNull null
            buildRow(
                profileId = link.profileId,
                username = profile.username,
                isSelf = link.isSelf,
                facebookPageId = profile.facebookPageId,
                ads = byProfile[link.profileId].orEmpty(),
                windowStart = windowStart,
                windowEnd = windowEnd,
                today = today
            )
        }.sortedWith(compareByDescending<AdCalendarRowDto> { it.sponsoredDays }.thenBy { it.username })

        return AdCalendarResponse(
            year = year,
            today = today.toString(),
            activeToday = rows.sumOf { it.activeNow },
            rows = rows,
            unlinked = unlinked,
            configured = client.enabled
        )
    }

    private fun buildRow(
        profileId: UUID,
        username: String,
        isSelf: Boolean,
        facebookPageId: String?,
        ads: List<MetaAdSnapshotEntity>,
        windowStart: LocalDate,
        windowEnd: LocalDate,
        today: LocalDate
    ): AdCalendarRowDto {
        val ordered = ads.sortedWith(compareBy({ it.deliveryStart }, { it.adArchiveId }))
        val lanes = AdCalendarMath.assignLanes(
            ordered.map { it.deliveryStart to (it.deliveryStop ?: windowEnd) }
        )

        val bars = ordered.mapIndexed { index, ad ->
            AdBarDto(
                adId = ad.adArchiveId,
                title = ad.title,
                start = ad.deliveryStart.toString(),
                stop = ad.deliveryStop?.toString(),
                days = AdCalendarMath.daysInWindow(ad.deliveryStart, ad.deliveryStop, windowStart, windowEnd),
                reach = ad.reachPl ?: ad.reachEu,
                platforms = MetaAdCodec.decodePlatforms(ad.platforms),
                lane = lanes[index]
            )
        }

        val reachSum = bars.mapNotNull { it.reach }
        return AdCalendarRowDto(
            profileId = profileId.toString(),
            username = username,
            isSelf = isSelf,
            facebookPageId = facebookPageId,
            campaigns = bars.size,
            activeNow = ordered.count { it.deliveryStop == null && !it.deliveryStart.isAfter(today) },
            sponsoredDays = bars.sumOf { it.days },
            reachTotal = reachSum.takeIf { it.isNotEmpty() }?.sum(),
            lanes = (lanes.maxOrNull() ?: -1) + 1,
            ads = bars
        )
    }

    /**
     * Szczegóły jednej kampanii. Dostęp tylko do profili, które to studio faktycznie
     * obserwuje — identyfikator reklamy jest publiczny, więc bez tego sprawdzenia
     * byłby otwartą furtką do cudzych obserwowanych.
     */
    @Transactional(readOnly = true)
    fun detail(studioId: StudioId, adId: String): AdDetailDto? {
        val ad = snapshotRepository.findByAdArchiveId(adId) ?: return null
        val watched = studioProfileRepository
            .findByStudioIdAndStatus(studioId.value, InstagramProfileStatus.ACTIVE)
            .any { it.profileId == ad.profileId }
        if (!watched) return null

        val profile = profileRepository.findById(ad.profileId).orElse(null) ?: return null
        val today = LocalDate.now(ZoneOffset.UTC)

        val rawBuckets = MetaAdCodec.decodeBreakdown(ad.reachBreakdown)
        val buckets = rawBuckets.map { bucket ->
            AdReachBucketDto(
                ageRange = bucket.ageRange,
                male = bucket.male,
                female = bucket.female,
                inTargetAge = AdCalendarMath.inTargetAge(bucket.ageRange, ad.targetAges)
            )
        }
        val outOfTarget = rawBuckets
            .filterNot { AdCalendarMath.inTargetAge(it.ageRange, ad.targetAges) }
            .sumOf { it.male + it.female + it.unknown }

        return AdDetailDto(
            adId = ad.adArchiveId,
            profileId = ad.profileId.toString(),
            username = profile.username,
            title = ad.title,
            start = ad.deliveryStart.toString(),
            stop = ad.deliveryStop?.toString(),
            days = AdCalendarMath.daysInWindow(ad.deliveryStart, ad.deliveryStop, ad.deliveryStart, today),
            active = ad.deliveryStop == null,
            reach = ad.reachPl ?: ad.reachEu,
            platforms = MetaAdCodec.decodePlatforms(ad.platforms),
            targetAges = ad.targetAges,
            targetGender = ad.targetGender,
            locations = MetaAdCodec.decodeLocations(ad.targetLocations)
                .map { AdLocationDto(it.name, it.type, it.excluded) }
                .sortedBy { it.excluded },
            payer = ad.payer,
            // Beneficjent równy płatnikowi nie niesie informacji — front go wtedy nie rysuje.
            beneficiary = ad.beneficiary?.takeIf { it != ad.payer },
            breakdown = buckets,
            outOfTargetAgeReach = outOfTarget,
            snapshotUrl = ad.snapshotUrl
        )
    }

    /**
     * Powiązanie profilu ze stroną na Facebooku. Robi to człowiek, bo Meta nie
     * udostępnia mostu profil IG → strona FB, a wyszukiwanie po nazwie trafia
     * na zbieżności („Auto Spa" jest w każdym mieście).
     *
     * Zwraca znormalizowany identyfikator strony (null = odmowa), bo wołający
     * ma zaraz po tym pobrać reklamy tej strony — powiązanie bez pobrania jest
     * ruchem bez skutku: wiersz pojawia się w kalendarzu pusty i nic więcej.
     *
     * Wskazanie INNEJ strony kasuje migawki poprzedniej. To reklamy innej firmy —
     * zostawione w bazie zmieszałyby w kalendarzu dwa różne studia pod jedną nazwą.
     */
    @Transactional
    fun linkFacebookPage(studioId: StudioId, profileId: UUID, request: LinkFacebookPageRequest): String? {
        if (!watches(studioId, profileId)) return null

        val pageId = request.pageId.trim()
        if (pageId.isEmpty() || !pageId.all { it.isDigit() } || pageId.length > 40) return null

        val profile = profileRepository.findById(profileId).orElse(null) ?: return null
        val previous = profile.facebookPageId
        if (previous != null && previous != pageId) {
            snapshotRepository.deleteByProfileId(profileId)
            log.info("Meta Ad Library: profil {} zmienia stronę {} → {}, migawki skasowane", profileId, previous, pageId)
        }

        profile.facebookPageId = pageId
        profile.facebookPageName = request.pageName?.trim()?.take(200)?.takeIf { it.isNotBlank() }
        profile.facebookPageLinkedAt = Instant.now()
        profileRepository.save(profile)
        log.info("Meta Ad Library: profil {} (@{}) powiązany ze stroną {}", profileId, profile.username, pageId)
        return pageId
    }

    /**
     * Odpięcie strony. Migawki idą razem z nią: opisują reklamy strony, której
     * już nie śledzimy, a zostawione udawałyby historię tego profilu.
     *
     * Powiązanie jest własnością profilu, nie studia — tak samo jak reszta danych
     * profilu w tym module. Odpięcie działa więc dla wszystkich, którzy go obserwują;
     * to ta sama zasada, na której działa wskazanie strony.
     */
    @Transactional
    fun unlinkFacebookPage(studioId: StudioId, profileId: UUID): Boolean {
        if (!watches(studioId, profileId)) return false

        val profile = profileRepository.findById(profileId).orElse(null) ?: return false
        if (profile.facebookPageId == null) return false

        snapshotRepository.deleteByProfileId(profileId)
        log.info("Meta Ad Library: profil {} (@{}) odpięty od strony {}", profileId, profile.username, profile.facebookPageId)
        profile.facebookPageId = null
        profile.facebookPageName = null
        profile.facebookPageLinkedAt = null
        profileRepository.save(profile)
        return true
    }

    /**
     * Szukanie strony po nazwie — zamiast kazać komuś polować na numer.
     *
     * Biblioteka reklam pokazuje w panelu albo numer strony, albo jej nazwę
     * użytkownika, więc połowa reklamodawców jest nie do wpisania z ręki.
     * Każda odpowiedź `ads_archive` niesie za to `page_id` obok `page_name`.
     */
    fun searchPages(input: String): List<PageCandidateDto> =
        // Cudzysłów zamykający musi być typograficzny: zwykły " zamknąłby literał.
        runCatching { resolve(input) }
            .onFailure { log.warn("Meta Ad Library: szukanie strony „{}” nie powiodło się — {}", input, it.message) }
            .getOrDefault(emptyList())

    /**
     * Wklejony adres, alias albo nazwa — jedno pole na wszystko, co człowiek ma
     * pod ręką. Facebook pokazuje tę samą stronę raz jako numer, raz jako alias,
     * więc rozpoznawanie postaci jest naszą robotą, nie jego.
     */
    private fun resolve(input: String): List<PageCandidateDto> = when (val parsed = MetaPageInput.parse(input)) {
        is PageInput.Empty -> emptyList()

        // Numer wprost: nie szukamy, tylko sprawdzamy, CZYJ on jest. Gdy Meta milczy,
        // oddajemy sam numer bez nazwy — zapisać go i tak wolno, ale bez potwierdzenia.
        is PageInput.Id -> listOf(client.describePage(parsed.pageId)?.toDto() ?: unnamed(parsed.pageId))

        is PageInput.Term -> resolveTerm(parsed.term)
    }

    /**
     * Alias najpierw próbujemy odczytać wprost — to jedyna droga dająca numer TEJ
     * strony, a nie strony o podobnej nazwie. Gdy Meta odmawia (brak Page Public
     * Content Access), zostaje wyszukiwanie po nazwie: alias rozbity na słowa
     * („CarArtDetailing" → „Car Art Detailing") idzie do biblioteki reklam.
     */
    private fun resolveTerm(term: String): List<PageCandidateDto> {
        if (!term.contains(' ')) {
            client.resolveAlias(term)?.let { exact ->
                val described = client.describePage(exact.pageId)
                return listOf(
                    PageCandidateDto(
                        pageId = exact.pageId,
                        pageName = exact.pageName.ifBlank { described?.pageName.orEmpty() },
                        ads = described?.ads ?: 0,
                        lastStart = described?.lastStart?.toString()
                    )
                )
            }
        }
        return client.searchPages(MetaPageInput.toSearchTerm(term)).map { it.toDto() }
    }

    private fun MetaPageCandidate.toDto() =
        PageCandidateDto(pageId = pageId, pageName = pageName, ads = ads, lastStart = lastStart?.toString())

    /** Numer bez potwierdzonej nazwy: strona nic nie reklamowała albo numer jest cudzy. */
    private fun unnamed(pageId: String) =
        PageCandidateDto(pageId = pageId, pageName = "", ads = 0, lastStart = null)

    private fun watches(studioId: StudioId, profileId: UUID): Boolean =
        studioProfileRepository.findByStudioId(studioId.value).any { it.profileId == profileId }
}
