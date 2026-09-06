package pl.detailing.crm.instagram.ads

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Reklamy widziane oczami dwóch istniejących ekranów: Pulsu i tygodniowego
 * podsumowania. Osobny serwis, bo obie te sekcje pytają o to samo, a żadna
 * z nich nie powinna wiedzieć, jak wyglądają migawki.
 *
 * Kampania trwa tygodniami, więc ZDARZENIEM jest wyłącznie jej start i koniec.
 * Gdyby zdarzeniem było „trwa", jedna kampania wypełniłaby Puls na dwa miesiące
 * i wypchnęła z niego wszystko inne.
 */
@Service
class MetaAdsActivityService(
    private val snapshotRepository: MetaAdSnapshotRepository
) {

    /** Wszystkie znane kampanie tych profili — potrzebne, żeby policzyć równoległość. */
    @Transactional(readOnly = true)
    fun allFor(profileIds: Collection<UUID>): Map<UUID, List<MetaAdSnapshotEntity>> {
        if (profileIds.isEmpty()) return emptyMap()
        return snapshotRepository.findByProfileIdIn(profileIds).groupBy { it.profileId }
    }

    /** Kampanie uruchomione w oknie — zdarzenie „uruchomił reklamę". */
    @Transactional(readOnly = true)
    fun startedSince(profileIds: Collection<UUID>, from: LocalDate, today: LocalDate):
        Map<UUID, List<MetaAdSnapshotEntity>> {
        if (profileIds.isEmpty()) return emptyMap()
        return snapshotRepository
            .findByProfileIdInAndDeliveryStartGreaterThanEqual(profileIds, from)
            .filter { !it.deliveryStart.isAfter(today) }
            .groupBy { it.profileId }
    }

    /**
     * Kampanie, których koniec WYKRYLIŚMY w oknie.
     *
     * Świadomie po dacie wykrycia, nie po deklarowanej dacie zakończenia: Meta
     * potrafi wpisać `ad_delivery_stop_time` z opóźnieniem, a nam chodzi o to,
     * żeby zdarzenie pojawiło się w Pulsie raz i w tygodniu, w którym je zobaczyliśmy.
     */
    @Transactional(readOnly = true)
    fun endedSince(profileIds: Collection<UUID>, from: Instant): Map<UUID, List<MetaAdSnapshotEntity>> {
        if (profileIds.isEmpty()) return emptyMap()
        return snapshotRepository
            .findByProfileIdInAndEndedDetectedAtGreaterThanEqual(profileIds, from)
            .groupBy { it.profileId }
    }

    /**
     * Ile kampanii tego profilu trwało w dniu [day] — po to, żeby zdarzenie
     * „uruchomił reklamę" mogło powiedzieć, że to już druga równolegle.
     */
    fun concurrentOn(ads: List<MetaAdSnapshotEntity>, day: LocalDate): Int =
        ads.count { !it.deliveryStart.isAfter(day) && (it.deliveryStop == null || !it.deliveryStop!!.isBefore(day)) }

    /**
     * Pigułki do tygodniowego podsumowania. Stan bierzemy KOŃCOWY: kampania
     * uruchomiona i wyłączona w tym samym tygodniu jest „zakończona", bo taki
     * jest jej stan w chwili czytania — „uruchomił i zakończył" byłoby zagadką.
     */
    @Transactional(readOnly = true)
    fun weekActivity(
        profileIds: Collection<UUID>,
        weekStart: LocalDate,
        weekEnd: LocalDate,
        today: LocalDate
    ): Map<UUID, List<DigestAdDto>> {
        if (profileIds.isEmpty()) return emptyMap()

        return snapshotRepository.findByProfileIdIn(profileIds)
            .mapNotNull { ad -> toDigestAd(ad, weekStart, weekEnd, today)?.let { ad.profileId to it } }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, ads) -> ads.sortedByDescending { it.days } }
    }

    private fun toDigestAd(
        ad: MetaAdSnapshotEntity,
        weekStart: LocalDate,
        weekEnd: LocalDate,
        today: LocalDate
    ): DigestAdDto? {
        val stop = ad.deliveryStop
        val startedBeforeWeekEnd = !ad.deliveryStart.isAfter(weekEnd)
        if (!startedBeforeWeekEnd) return null

        val state = when {
            // Skończyła się przed tym tygodniem — to już nie jest wiadomość.
            stop != null && stop.isBefore(weekStart) -> return null
            stop != null -> "ENDED"
            !ad.deliveryStart.isBefore(weekStart) -> "STARTED"
            else -> "RUNNING"
        }

        val until = stop ?: today
        return DigestAdDto(
            adId = ad.adArchiveId,
            state = state,
            title = ad.title,
            days = AdCalendarMath.daysInWindow(ad.deliveryStart, stop, ad.deliveryStart, until),
            reach = ad.reachPl ?: ad.reachEu,
            startedOn = ad.deliveryStart.toString()
        )
    }
}
