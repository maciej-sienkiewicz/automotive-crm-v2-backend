package pl.detailing.crm.careinstruction

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.careinstruction.infrastructure.CareInstructionEntity
import pl.detailing.crm.careinstruction.infrastructure.CareInstructionRepository
import pl.detailing.crm.careinstruction.infrastructure.ServiceCareInstructionEntity
import pl.detailing.crm.careinstruction.infrastructure.ServiceCareInstructionRepository
import pl.detailing.crm.shared.EntityNotFoundException
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.ValidationException
import java.time.Instant
import java.util.UUID

/**
 * Słownik instrukcji pielęgnacyjnych studia i ich przypisania do usług z cennika.
 */
@Service
class CareInstructionService(
    private val repository: CareInstructionRepository,
    private val linkRepository: ServiceCareInstructionRepository,
    private val provisioner: DefaultCareInstructionProvisioner
) {
    companion object {
        private const val MAX_TITLE = 200
        private const val MAX_CONTENT = 2000
    }

    /**
     * Lista słownika wraz z przypisaniami do usług.
     *
     * Zasiew domyślnych wpisów robimy przy odczycie, nie tylko przy starcie aplikacji:
     * studio założone minutę temu ma zobaczyć gotowy słownik, a nie pustą listę do
     * następnego wdrożenia. Wywołanie jest idempotentne (znacznik w ustawieniach).
     */
    @Transactional
    fun list(studioId: StudioId): List<CareInstructionDto> {
        provisioner.ensureDefaults(studioId)

        val links = linkRepository.findByStudioId(studioId.value)
            .groupBy({ it.careInstructionId }, { it.serviceId.toString() })

        return repository.findAllByStudio(studioId.value).map { it.toDto(links[it.id].orEmpty()) }
    }

    @Transactional
    fun create(studioId: StudioId, request: SaveCareInstructionRequest): CareInstructionDto {
        val title = request.title.trim()
        val content = request.content.trim()
        validate(title, content)

        val now = Instant.now()
        val entity = repository.save(
            CareInstructionEntity(
                id = UUID.randomUUID(),
                studioId = studioId.value,
                title = title,
                content = content,
                isDefaultSelected = request.isDefaultSelected,
                sortOrder = repository.maxSortOrder(studioId.value) + 1,
                createdAt = now,
                updatedAt = now
            )
        )
        return entity.toDto(emptyList())
    }

    @Transactional
    fun update(studioId: StudioId, id: UUID, request: SaveCareInstructionRequest): CareInstructionDto {
        val entity = repository.findByIdAndStudioId(id, studioId.value)
            ?: throw EntityNotFoundException("Instrukcja nie została znaleziona")

        val title = request.title.trim()
        val content = request.content.trim()
        validate(title, content)

        entity.title = title
        entity.content = content
        entity.isDefaultSelected = request.isDefaultSelected
        entity.updatedAt = Instant.now()
        repository.save(entity)

        val serviceIds = linkRepository.findByStudioId(studioId.value)
            .filter { it.careInstructionId == id }
            .map { it.serviceId.toString() }
        return entity.toDto(serviceIds)
    }

    @Transactional
    fun delete(studioId: StudioId, id: UUID) {
        val entity = repository.findByIdAndStudioId(id, studioId.value)
            ?: throw EntityNotFoundException("Instrukcja nie została znaleziona")
        // Najpierw przypisania: osierocony wiersz w tabeli łączącej zaznaczałby
        // na certyfikacie instrukcję, której już nie ma.
        linkRepository.deleteByInstruction(studioId.value, id)
        repository.delete(entity)
    }

    /** Podmienia komplet przypisań usługi. Pusta lista = usługa nie niesie żadnych instrukcji. */
    @Transactional
    fun setForService(studioId: StudioId, serviceId: UUID, instructionIds: List<String>) {
        val wanted = instructionIds
            .mapNotNull { runCatching { UUID.fromString(it) }.getOrNull() }
            .distinct()
            // Cudza instrukcja nie ma prawa przykleić się do naszej usługi.
            .filter { repository.findByIdAndStudioId(it, studioId.value) != null }

        linkRepository.deleteByService(studioId.value, serviceId)
        // flush przez zapis w tej samej transakcji: bez usunięcia najpierw
        // unikat na parze wywaliłby powtórne wstawienie tej samej instrukcji.
        linkRepository.flush()

        wanted.forEach { instructionId ->
            linkRepository.save(
                ServiceCareInstructionEntity(
                    id = UUID.randomUUID(),
                    studioId = studioId.value,
                    serviceId = serviceId,
                    careInstructionId = instructionId
                )
            )
        }
    }

    /** Treści wybrane do wydruku, w kolejności ze słownika. */
    @Transactional(readOnly = true)
    fun contentsFor(studioId: StudioId, ids: List<String>): List<String> {
        if (ids.isEmpty()) return emptyList()
        val wanted = ids.mapNotNull { runCatching { UUID.fromString(it) }.getOrNull() }.toSet()
        if (wanted.isEmpty()) return emptyList()
        return repository.findAllByStudio(studioId.value)
            .filter { it.id in wanted }
            .map { it.content }
    }

    private fun validate(title: String, content: String) {
        if (title.isBlank()) throw ValidationException("Nazwa instrukcji jest wymagana")
        if (title.length > MAX_TITLE) throw ValidationException("Nazwa może mieć maksymalnie $MAX_TITLE znaków")
        if (content.isBlank()) throw ValidationException("Treść instrukcji jest wymagana")
        if (content.length > MAX_CONTENT) throw ValidationException("Treść może mieć maksymalnie $MAX_CONTENT znaków")
    }

    private fun CareInstructionEntity.toDto(serviceIds: List<String>) = CareInstructionDto(
        id = id.toString(),
        title = title,
        content = content,
        isDefaultSelected = isDefaultSelected,
        sortOrder = sortOrder,
        serviceIds = serviceIds
    )
}
