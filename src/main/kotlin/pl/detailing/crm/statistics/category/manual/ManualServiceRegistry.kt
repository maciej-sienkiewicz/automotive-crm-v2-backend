package pl.detailing.crm.statistics.category.manual

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Lazily registers manual service names in the [ManualServiceEntity] table.
 *
 * Each unique (studioId, serviceName) pair gets a deterministic UUID derived via
 * UUID.nameUUIDFromBytes — the same inputs always produce the same UUID, so
 * records are safe to upsert across multiple requests and app restarts.
 */
@Service
class ManualServiceRegistry(
    private val manualServiceRepository: ManualServiceRepository
) {

    /**
     * Returns existing manual service records for the given names, creating
     * rows for any name not yet registered. Result is keyed by service name.
     */
    @Transactional
    fun findOrCreateAll(studioId: UUID, names: Set<String>): Map<String, ManualServiceEntity> {
        if (names.isEmpty()) return emptyMap()

        val existing = manualServiceRepository.findByStudioIdAndServiceNameIn(studioId, names)
        val existingByName = existing.associateBy { it.serviceName }

        val newNames = names - existingByName.keys
        if (newNames.isEmpty()) return existingByName

        val newEntities = newNames.map { name ->
            ManualServiceEntity(
                id = deterministicId(studioId, name),
                studioId = studioId,
                serviceName = name
            )
        }
        manualServiceRepository.saveAll(newEntities)
        return existingByName + newEntities.associateBy { it.serviceName }
    }

    companion object {
        /** Deterministic type-3 UUID from studio + name — stable across restarts. */
        fun deterministicId(studioId: UUID, name: String): UUID =
            UUID.nameUUIDFromBytes("$studioId:$name".toByteArray(Charsets.UTF_8))
    }
}
