package pl.detailing.crm.visit.damagemap

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.shared.EntityNotFoundException
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.VisitId
import pl.detailing.crm.visit.domain.DamagePoint
import pl.detailing.crm.visit.infrastructure.VisitRepository
import java.time.Instant

/**
 * Stan mapy uszkodzeń wizyty, czyli to, od czego startuje „Zaktualizuj uszkodzenia".
 *
 * Kluczowe pole to [pointsRecoverable]. Dla wizyt sprzed wprowadzenia tabeli
 * `visit_damage_maps` istnieje wyłącznie wygenerowany PDF — współrzędnych nikt
 * nigdy nie zapisał i nie da się ich odzyskać z obrazka. UI musi to powiedzieć
 * wprost, zanim operator zacznie klikać, bo inaczej „aktualizacja" cicho
 * skasowałaby wszystkie oznaczenia z przyjęcia.
 */
@Service
class GetVisitDamageMapHandler(
    private val visitRepository: VisitRepository,
    private val damageMapStore: VisitDamageMapStore
) {

    @Transactional(readOnly = true)
    fun handle(visitId: VisitId, studioId: StudioId): VisitDamageMapState {
        val visit = visitRepository.findByIdAndStudioId(visitId.value, studioId.value)
            ?: throw EntityNotFoundException("Visit not found: $visitId")

        val stored = damageMapStore.load(visitId, studioId)

        return VisitDamageMapState(
            damagePoints = stored?.damagePoints ?: emptyList(),
            vehicleType = stored?.vehicleType,
            revision = stored?.revision ?: 0,
            hasDocument = visit.damageMapFileId != null,
            pointsRecoverable = stored != null,
            updatedAt = stored?.updatedAt,
            updatedByName = stored?.updatedByName
        )
    }
}

data class VisitDamageMapState(
    val damagePoints: List<DamagePoint>,
    val vehicleType: String?,
    /** 0 = mapy nigdy nie zapisano w tej postaci. */
    val revision: Int,
    /** Czy wizyta ma w ogóle wygenerowany PDF mapy. */
    val hasDocument: Boolean,
    /**
     * false = wizyta ma (albo miała) mapę, ale jej punktów nie da się odtworzyć.
     * UI ostrzega wtedy, że aktualizacja oznacza rysowanie od zera.
     */
    val pointsRecoverable: Boolean,
    val updatedAt: Instant?,
    val updatedByName: String?
)
