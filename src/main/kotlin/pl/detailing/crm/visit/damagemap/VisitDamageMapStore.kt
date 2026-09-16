package pl.detailing.crm.visit.damagemap

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import pl.detailing.crm.shared.VisitId
import pl.detailing.crm.visit.domain.DamageAnnotationPoint
import pl.detailing.crm.visit.domain.DamageAnnotationStroke
import pl.detailing.crm.visit.domain.DamagePhoto
import pl.detailing.crm.visit.domain.DamagePoint
import java.time.Instant

/**
 * Czyta i zapisuje punkty uszkodzeń wizyty.
 *
 * Jedyne miejsce, które zna format kolumny `damage_points`. Osobne DTO-ki zamiast
 * serializacji [DamagePoint] wprost: model domenowy waliduje w `init` (x, y w
 * 0..100, id > 0), więc jeden uszkodzony wiersz w bazie wywracałby cały odczyt
 * wizyty wyjątkiem z konstruktora. Tu zły punkt zostaje pominięty, a reszta mapy
 * się otwiera — przy dokumencie, po który ktoś sięga w sporze z klientem, to
 * różnica między „brakuje jednego punktu" i „nie ma mapy".
 */
@Service
class VisitDamageMapStore(
    private val repository: VisitDamageMapRepository
) {
    private val mapper = jacksonObjectMapper()

    companion object {
        private val logger = LoggerFactory.getLogger(VisitDamageMapStore::class.java)
    }

    @Transactional(readOnly = true)
    fun load(visitId: VisitId, studioId: StudioId): StoredDamageMap? {
        val entity = repository.findByVisitIdAndStudioId(visitId.value, studioId.value) ?: return null
        return StoredDamageMap(
            damagePoints = deserialize(entity.damagePointsJson, visitId),
            vehicleType = entity.vehicleType,
            documentS3Key = entity.documentS3Key,
            revision = entity.revision,
            updatedAt = entity.updatedAt,
            updatedByName = entity.updatedByName
        )
    }

    /**
     * Zapisuje mapę (upsert). [documentS3Key] = null zostawia poprzedni klucz —
     * zapis punktów i wygenerowanie pliku to dwa osobne kroki i pierwszy nie może
     * skasować śladu po drugim.
     *
     * @param bumpRevision false przy zapisie mapy z przyjęcia (to jest wersja 1),
     *        true przy każdej późniejszej aktualizacji.
     */
    @Transactional
    fun save(
        visitId: VisitId,
        studioId: StudioId,
        damagePoints: List<DamagePoint>,
        vehicleType: String?,
        documentS3Key: String? = null,
        userId: UserId? = null,
        userName: String? = null,
        bumpRevision: Boolean = true
    ): Int {
        val existing = repository.findByVisitIdAndStudioId(visitId.value, studioId.value)
        val json = mapper.writeValueAsString(damagePoints.map(::toDto))

        val entity = existing ?: VisitDamageMapEntity(
            visitId = visitId.value,
            studioId = studioId.value,
            revision = 0
        )

        entity.damagePointsJson = json
        entity.vehicleType = vehicleType
        documentS3Key?.let { entity.documentS3Key = it }
        entity.revision = if (existing == null) 1 else if (bumpRevision) entity.revision + 1 else entity.revision
        entity.updatedAt = Instant.now()
        entity.updatedBy = userId?.value
        entity.updatedByName = userName

        return repository.save(entity).revision
    }

    private fun deserialize(json: String, visitId: VisitId): List<DamagePoint> =
        try {
            mapper.readValue<List<DamagePointDto>>(json).mapNotNull { dto ->
                try {
                    dto.toDomain()
                } catch (e: IllegalArgumentException) {
                    logger.warn("Pomijam uszkodzony punkt mapy [visit={} point={}]: {}", visitId, dto.id, e.message)
                    null
                }
            }
        } catch (e: Exception) {
            logger.error("Nie udało się odczytać mapy uszkodzeń [visit={}]: {}", visitId, e.message)
            emptyList()
        }

    private fun toDto(point: DamagePoint) = DamagePointDto(
        id = point.id,
        x = point.x,
        y = point.y,
        note = point.note,
        photos = point.photos.map { photo ->
            DamagePhotoDto(
                photoId = photo.photoId,
                strokes = photo.strokes.map { stroke ->
                    StrokeDto(
                        color = stroke.color,
                        width = stroke.width,
                        points = stroke.points.map { PointDto(it.x, it.y) }
                    )
                }
            )
        }
    )
}

data class StoredDamageMap(
    val damagePoints: List<DamagePoint>,
    val vehicleType: String?,
    val documentS3Key: String?,
    val revision: Int,
    val updatedAt: Instant,
    val updatedByName: String?
)

// ─── Kształt kolumny jsonb ────────────────────────────────────────────────────
// Świadomie niezależny od modelu domenowego: @JsonIgnoreProperties znaczy, że
// mapa zapisana nowszą wersją aplikacji (z dodatkowym polem) nadal się odczyta
// starszą — inaczej wdrożenie kanarkowe wywracało by odczyt.

@JsonIgnoreProperties(ignoreUnknown = true)
internal data class DamagePointDto(
    val id: Int = 0,
    val x: Double = 0.0,
    val y: Double = 0.0,
    val note: String? = null,
    val photos: List<DamagePhotoDto> = emptyList()
) {
    fun toDomain() = DamagePoint(
        id = id,
        x = x,
        y = y,
        note = note,
        photos = photos.map { photo ->
            DamagePhoto(
                photoId = photo.photoId,
                strokes = photo.strokes.map { stroke ->
                    DamageAnnotationStroke(
                        color = stroke.color,
                        width = stroke.width,
                        points = stroke.points.map { DamageAnnotationPoint(x = it.x, y = it.y) }
                    )
                }
            )
        }
    )
}

@JsonIgnoreProperties(ignoreUnknown = true)
internal data class DamagePhotoDto(
    val photoId: String = "",
    val strokes: List<StrokeDto> = emptyList()
)

@JsonIgnoreProperties(ignoreUnknown = true)
internal data class StrokeDto(
    val color: String = "#EF4444",
    val width: Double = 1.0,
    val points: List<PointDto> = emptyList()
)

@JsonIgnoreProperties(ignoreUnknown = true)
internal data class PointDto(
    val x: Double = 0.0,
    val y: Double = 0.0
)
