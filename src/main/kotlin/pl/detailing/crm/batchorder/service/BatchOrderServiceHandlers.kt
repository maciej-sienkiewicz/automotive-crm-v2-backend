package pl.detailing.crm.batchorder.service

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.batchorder.infrastructure.BatchOrderServiceEntity
import pl.detailing.crm.batchorder.infrastructure.BatchOrderServiceRepository
import pl.detailing.crm.shared.BatchOrderServiceId
import pl.detailing.crm.shared.EntityNotFoundException
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.ValidationException
import java.time.Instant

/** VAT rates the module accepts. -1 is "zwolniony", where gross equals net. */
private val ALLOWED_VAT_RATES = setOf(23, 8, 5, 0, -1)

private const val MAX_NAME_LENGTH = 500

data class BatchServiceItem(
    val id: String,
    val name: String,
    val netAmountCents: Long,
    val grossAmountCents: Long,
    val vatRate: Int,
    val createdAt: String,
    val updatedAt: String
)

fun BatchOrderServiceEntity.toItem() = BatchServiceItem(
    id = id.toString(),
    name = name,
    netAmountCents = netAmountCents,
    grossAmountCents = grossAmountCents,
    vatRate = vatRate,
    createdAt = createdAt.toString(),
    updatedAt = updatedAt.toString()
)

private fun validate(name: String, netAmountCents: Long, grossAmountCents: Long, vatRate: Int): String {
    val trimmed = name.trim()
    if (trimmed.isBlank()) throw ValidationException("Nazwa usługi jest wymagana")
    if (trimmed.length > MAX_NAME_LENGTH) throw ValidationException("Nazwa usługi jest za długa")
    if (netAmountCents < 0 || grossAmountCents < 0) throw ValidationException("Kwota nie może być ujemna")
    if (vatRate !in ALLOWED_VAT_RATES) throw ValidationException("Nieprawidłowa stawka VAT: $vatRate")
    return trimmed
}

// ── Read ──────────────────────────────────────────────────────────────────────

@Service
class ListBatchServicesHandler(
    private val repository: BatchOrderServiceRepository
) {
    @Transactional(readOnly = true)
    suspend fun handle(studioId: StudioId, query: String?): List<BatchServiceItem> {
        val q = query?.trim().orEmpty()
        val entities = if (q.isEmpty()) {
            repository.findActiveByStudioId(studioId.value)
        } else {
            repository.searchActiveByStudioId(studioId.value, q)
        }
        return entities.map { it.toItem() }
    }
}

// ── Write ─────────────────────────────────────────────────────────────────────

data class SaveBatchServiceCommand(
    val studioId: StudioId,
    val name: String,
    val netAmountCents: Long,
    val grossAmountCents: Long,
    val vatRate: Int
)

@Service
class CreateBatchServiceHandler(
    private val repository: BatchOrderServiceRepository
) {
    @Transactional
    suspend fun handle(command: SaveBatchServiceCommand): BatchServiceItem {
        val name = validate(command.name, command.netAmountCents, command.grossAmountCents, command.vatRate)

        repository.findActiveByStudioIdAndName(command.studioId.value, name)?.let {
            throw ValidationException("Usługa o nazwie „$name\" już istnieje")
        }

        val saved = repository.save(
            BatchOrderServiceEntity(
                id = BatchOrderServiceId.random().value,
                studioId = command.studioId.value,
                name = name,
                netAmountCents = command.netAmountCents,
                grossAmountCents = command.grossAmountCents,
                vatRate = command.vatRate
            )
        )
        return saved.toItem()
    }
}

@Service
class UpdateBatchServiceHandler(
    private val repository: BatchOrderServiceRepository
) {
    @Transactional
    suspend fun handle(serviceId: BatchOrderServiceId, command: SaveBatchServiceCommand): BatchServiceItem {
        val name = validate(command.name, command.netAmountCents, command.grossAmountCents, command.vatRate)

        val entity = repository.findByIdAndStudioId(serviceId.value, command.studioId.value)
            ?: throw EntityNotFoundException("Usługa nie została znaleziona")

        repository.findActiveByStudioIdAndName(command.studioId.value, name)
            ?.takeIf { it.id != entity.id }
            ?.let { throw ValidationException("Usługa o nazwie „$name\" już istnieje") }

        // Edited in place rather than versioned like the retail catalog: entries carry
        // their own copy of the amounts, so there is no historical reader left to
        // protect from a price change here.
        entity.name = name
        entity.netAmountCents = command.netAmountCents
        entity.grossAmountCents = command.grossAmountCents
        entity.vatRate = command.vatRate
        entity.updatedAt = Instant.now()

        return repository.save(entity).toItem()
    }
}

@Service
class DeleteBatchServiceHandler(
    private val repository: BatchOrderServiceRepository
) {
    @Transactional
    suspend fun handle(serviceId: BatchOrderServiceId, studioId: StudioId) {
        val entity = repository.findByIdAndStudioId(serviceId.value, studioId.value)
            ?: throw EntityNotFoundException("Usługa nie została znaleziona")

        if (!entity.isActive) return

        entity.isActive = false
        entity.updatedAt = Instant.now()
        repository.save(entity)
    }
}

// ── Learning from entries ─────────────────────────────────────────────────────

/**
 * Records the services an entry was just saved with, so a name typed once is offered
 * the next time.
 *
 * A name already in the catalog is left exactly as it is. The alternative — letting
 * the last entry overwrite the catalog price — would mean a one-off discount typed
 * into a single entry silently became the studio's price for that service, with
 * nothing on screen to say it had happened. Prices change deliberately, in
 * "Zarządzaj usługami".
 *
 * Runs in its own transaction, *after* the entry has been committed, and the caller
 * swallows whatever it throws. Learning a name is a convenience; an operator must
 * never lose an entry they already filled in because two requests raced to register
 * the same service name.
 */
@Service
class RegisterBatchServicesHandler(
    private val repository: BatchOrderServiceRepository
) {
    @Transactional
    fun register(studioId: StudioId, services: List<SaveBatchServiceCommand>) {
        val seen = mutableSetOf<String>()
        services.forEach { candidate ->
            val name = candidate.name.trim()
            if (name.isBlank() || name.length > MAX_NAME_LENGTH) return@forEach
            if (candidate.vatRate !in ALLOWED_VAT_RATES) return@forEach
            if (candidate.netAmountCents < 0 || candidate.grossAmountCents < 0) return@forEach
            // One entry may list the same service twice; the unique index counts that
            // as a collision even though nothing was persisted in between.
            if (!seen.add(name.lowercase())) return@forEach
            if (repository.findActiveByStudioIdAndName(studioId.value, name) != null) return@forEach

            repository.save(
                BatchOrderServiceEntity(
                    id = BatchOrderServiceId.random().value,
                    studioId = studioId.value,
                    name = name,
                    netAmountCents = candidate.netAmountCents,
                    grossAmountCents = candidate.grossAmountCents,
                    vatRate = candidate.vatRate
                )
            )
        }
    }
}
