package pl.detailing.crm.leads.tags

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.leads.domain.LeadTag
import pl.detailing.crm.shared.ConflictException
import pl.detailing.crm.shared.NotFoundException
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.ValidationException
import java.text.Normalizer
import java.time.Instant
import java.util.UUID

/**
 * Definicja tagu leada — słownik, którym studio zarządza samo.
 *
 * Kod jest niezmienny od chwili wydania, bo to on leży w `lead_tags` przy każdym
 * zapytaniu z przeszłości. Etykieta jest tylko wyświetlana i wolno ją poprawić.
 */
@Entity
@Table(
    name = "lead_tag_definitions",
    indexes = [Index(name = "idx_lead_tag_definitions_studio", columnList = "studio_id, sort_order")]
)
class LeadTagDefinitionEntity(
    @Id
    @Column(name = "id", columnDefinition = "uuid")
    val id: UUID,

    @Column(name = "studio_id", nullable = false, columnDefinition = "uuid")
    val studioId: UUID,

    @Column(name = "code", nullable = false, length = 50)
    val code: String,

    @Column(name = "label", nullable = false, length = 80)
    var label: String,

    @Column(name = "sort_order", nullable = false)
    var position: Int = 0,

    /** Usunięcie jest miękkie — leady sprzed usunięcia mają nadal czytelną etykietę. */
    @Column(name = "archived_at")
    var archivedAt: Instant? = null,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now()
)

@Repository
interface LeadTagDefinitionRepository : JpaRepository<LeadTagDefinitionEntity, UUID> {

    fun findByStudioIdOrderByPositionAscLabelAsc(studioId: UUID): List<LeadTagDefinitionEntity>

    fun findByStudioIdAndCode(studioId: UUID, code: String): LeadTagDefinitionEntity?

    fun countByStudioId(studioId: UUID): Long
}

/**
 * Katalog tagów studia: odczyt, dodanie i usunięcie.
 *
 * Zasiew jest leniwy. Migracja wypełnia studia, które istniały w chwili wdrożenia,
 * ale studio założone później też musi zobaczyć sensowną listę startową — a nie pustkę
 * w oknie „Oznacz jako lead”. [ensureSeeded] dopisuje domyślne kody przy pierwszym
 * odczycie i nic nie robi przy kolejnych; celowo NIE dopisuje ich ponownie, gdy studio
 * skasuje wszystkie tagi — puste jest wtedy świadomą decyzją użytkownika, dlatego
 * warunkiem jest brak JAKICHKOLWIEK definicji, także zarchiwizowanych.
 */
@Service
class LeadTagCatalogService(
    private val repository: LeadTagDefinitionRepository
) {

    @Transactional
    fun listActive(studioId: StudioId): List<LeadTagDefinitionEntity> {
        ensureSeeded(studioId)
        return repository.findByStudioIdOrderByPositionAscLabelAsc(studioId.value)
            .filter { it.archivedAt == null }
    }

    /** Etykiety wszystkich definicji, także usuniętych — do wyświetlania historii. */
    @Transactional(readOnly = true)
    fun labelsByCode(studioId: StudioId): Map<String, String> =
        repository.findByStudioIdOrderByPositionAscLabelAsc(studioId.value)
            .associate { it.code to it.label }

    /** Sprawdza, że każdy kod istnieje i nie jest zarchiwizowany. */
    @Transactional
    fun validate(studioId: StudioId, codes: List<String>): List<String> {
        if (codes.isEmpty()) return emptyList()
        val active = listActive(studioId).associateBy { it.code }
        val unknown = codes.filterNot(active::containsKey)
        if (unknown.isNotEmpty()) {
            throw ValidationException("Nieznany tag zapytania: ${unknown.joinToString(", ")}")
        }
        return codes.distinct()
    }

    @Transactional
    fun create(studioId: StudioId, rawLabel: String): LeadTagDefinitionEntity {
        val label = rawLabel.trim()
        if (label.isBlank()) throw ValidationException("Podaj nazwę tagu")
        if (label.length > MAX_LABEL) throw ValidationException("Nazwa tagu może mieć najwyżej $MAX_LABEL znaków")

        val existing = repository.findByStudioIdOrderByPositionAscLabelAsc(studioId.value)
        existing.firstOrNull { it.label.equals(label, ignoreCase = true) }?.let { duplicate ->
            // Nazwa wraca po usunięciu — przywracamy tę samą definicję zamiast tworzyć
            // bliźniaczą, dzięki czemu dawne leady odzyskują swój tag.
            if (duplicate.archivedAt != null) {
                duplicate.archivedAt = null
                return repository.save(duplicate)
            }
            throw ConflictException("Tag „$label” już istnieje")
        }

        val code = uniqueCode(label, existing.map { it.code }.toSet())
        return repository.save(
            LeadTagDefinitionEntity(
                id = UUID.randomUUID(),
                studioId = studioId.value,
                code = code,
                label = label,
                position = (existing.maxOfOrNull { it.position } ?: -1) + 1
            )
        )
    }

    @Transactional
    fun archive(studioId: StudioId, code: String) {
        val definition = repository.findByStudioIdAndCode(studioId.value, code)
            ?: throw NotFoundException("Nie znaleziono tagu")
        if (definition.archivedAt != null) return
        definition.archivedAt = Instant.now()
        repository.save(definition)
    }

    private fun ensureSeeded(studioId: StudioId) {
        if (repository.countByStudioId(studioId.value) > 0) return
        repository.saveAll(
            LeadTag.entries.mapIndexed { index, tag ->
                LeadTagDefinitionEntity(
                    id = UUID.randomUUID(),
                    studioId = studioId.value,
                    code = tag.name,
                    label = tag.label,
                    position = index
                )
            }
        )
    }

    /**
     * Kod z etykiety: bez polskich znaków, wielkimi literami, podkreślenia zamiast spacji.
     * Kolizję rozstrzyga sufiks — dwa różne tagi nie mogą dzielić kodu, bo kod jest tym,
     * po czym analityka je rozróżnia.
     */
    private fun uniqueCode(label: String, taken: Set<String>): String {
        val base = Normalizer.normalize(label, Normalizer.Form.NFD)
            .replace(DIACRITICS, "")
            .replace("ł", "l").replace("Ł", "L")
            .uppercase()
            .replace(NON_ALPHANUMERIC, "_")
            .trim('_')
            .take(40)
            .ifBlank { "TAG" }
        if (base !in taken) return base
        var suffix = 2
        while ("${base}_$suffix" in taken) suffix++
        return "${base}_$suffix"
    }

    private companion object {
        const val MAX_LABEL = 80
        val DIACRITICS = Regex("\\p{M}+")
        val NON_ALPHANUMERIC = Regex("[^A-Z0-9]+")
    }
}
