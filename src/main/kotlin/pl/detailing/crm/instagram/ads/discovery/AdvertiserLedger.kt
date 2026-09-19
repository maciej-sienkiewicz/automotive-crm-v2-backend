package pl.detailing.crm.instagram.ads.discovery

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.instagram.ads.RawMetaAd
import java.time.Instant
import java.time.LocalDate

/**
 * Dopisywanie do rejestru reklamodawców ([AdDiscoveryAdvertiserEntity]) przy
 * każdym odświeżeniu frazy.
 *
 * Wołane z tej samej transakcji, w której [AdDiscoveryCacheWriter] podmienia
 * cache frazy: reklama, którą widzieliśmy, ma zostać zapamiętana razem z tym
 * podmienieniem albo wcale. Rejestr tylko rośnie — wiersz raz założony nigdy
 * nie znika, bo cała jego wartość polega na pamiętaniu tego, czego w cache już nie ma.
 */
@Service
class AdvertiserLedger(
    private val repository: AdDiscoveryAdvertiserRepository
) {

    @Transactional
    fun record(ads: Collection<RawMetaAd>, now: Instant = Instant.now()) {
        if (ads.isEmpty()) return

        val sightings = ads.groupBy { it.pageId }.mapValues { (_, group) -> Sighting.of(group) }
        val known = repository.findByPageIdIn(sightings.keys).associateBy { it.pageId }

        val toSave = sightings.map { (pageId, sighting) ->
            val entity = known[pageId]
            if (entity == null) {
                AdDiscoveryAdvertiserEntity(
                    pageId = pageId,
                    pageName = sighting.pageName,
                    firstDeliveryStart = sighting.earliestStart,
                    firstSeenAt = now,
                    lastSeenAt = now
                )
            } else {
                entity.lastSeenAt = now
                // Nazwa bywa pusta w części odpowiedzi Meta — nie nadpisujemy znanej pustką.
                sighting.pageName?.let { entity.pageName = it }
                // Tylko w tył: rejestr pamięta NAJWCZEŚNIEJSZY start, jaki kiedykolwiek
                // widzieliśmy. Nowsza kampania nie ma prawa „odmłodzić" firmy.
                if (sighting.earliestStart.isBefore(entity.firstDeliveryStart)) {
                    entity.firstDeliveryStart = sighting.earliestStart
                }
                entity
            }
        }
        repository.saveAll(toSave)
    }

    /** To, co z jednej paczki reklam strony trafia do rejestru. */
    data class Sighting(val pageName: String?, val earliestStart: LocalDate) {
        companion object {
            fun of(group: List<RawMetaAd>) = Sighting(
                pageName = group.firstNotNullOfOrNull { ad ->
                    ad.pageName?.trim()?.take(200)?.takeIf { it.isNotBlank() }
                },
                earliestStart = group.minOf { it.deliveryStart }
            )
        }
    }
}
