package pl.detailing.crm.product.usage

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.product.infrastructure.ProductRepository
import pl.detailing.crm.product.infrastructure.VisitProductEntity
import pl.detailing.crm.product.infrastructure.VisitProductRepository
import pl.detailing.crm.shared.ConflictException
import pl.detailing.crm.shared.EntityNotFoundException
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import java.time.Instant
import java.util.UUID

/**
 * Relacja wiele-do-wielu WIZYTA ↔ PRODUKT — czysto informacyjna: „do tej wizyty
 * użyliśmy tego produktu". Bez ilości, bez ceny, bez kosztu i bez wpływu na jakąkolwiek
 * statystykę czy `totalCost` wizyty (decyzja właściciela produktu).
 */
@Service
class VisitProductService(
    private val visitProductRepository: VisitProductRepository,
    private val productRepository: ProductRepository
) {
    @Transactional(readOnly = true)
    fun listForVisit(studioId: StudioId, visitId: UUID): List<VisitProductDto> {
        val links = visitProductRepository.findByVisit(studioId.value, visitId)
        if (links.isEmpty()) return emptyList()
        val products = productRepository.findAllById(links.map { it.productId }).associateBy { it.id }
        return links.map { link ->
            val p = products[link.productId]
            VisitProductDto(
                id = link.id.toString(),
                productId = link.productId.toString(),
                productName = p?.name ?: "(produkt usunięty z katalogu)",
                brand = p?.brand,
                packageLabel = p?.let {
                    "${it.packageSizeValue.stripTrailingZeros().toPlainString()} ${it.packageSizeUnit.displayName}"
                },
                imageFileId = p?.imageFileId,
                note = link.note,
                addedByName = link.createdByName,
                addedAt = link.createdAt
            )
        }
    }

    @Transactional(readOnly = true)
    fun listVisitIdsForProduct(studioId: StudioId, productId: UUID): List<VisitProductBackref> =
        visitProductRepository.findByProduct(studioId.value, productId).map {
            VisitProductBackref(
                linkId = it.id.toString(),
                visitId = it.visitId.toString(),
                note = it.note,
                addedByName = it.createdByName,
                addedAt = it.createdAt
            )
        }

    @Transactional
    fun link(
        studioId: StudioId,
        userId: UserId,
        userName: String,
        visitId: UUID,
        productId: UUID,
        note: String?
    ): VisitProductDto {
        productRepository.findById(productId).orElseThrow {
            EntityNotFoundException("Produkt nie został znaleziony")
        }
        // Ten sam produkt na jednej wizycie drugi raz to zwykle pomyłka — blokujemy,
        // ale dopięcie z inną notatką jest legalne (dwie osoby, dwa etapy), więc dublet
        // odrzucamy tylko przy braku notatki.
        if (note.isNullOrBlank() &&
            visitProductRepository.existsByStudioIdAndVisitIdAndProductId(studioId.value, visitId, productId)
        ) {
            throw ConflictException("Ten produkt jest już dopięty do tej wizyty.")
        }
        val entity = VisitProductEntity(
            id = UUID.randomUUID(),
            studioId = studioId.value,
            visitId = visitId,
            productId = productId,
            note = note?.trim()?.ifBlank { null },
            createdBy = userId.value,
            createdByName = userName,
            createdAt = Instant.now()
        )
        visitProductRepository.save(entity)
        return listForVisit(studioId, visitId).first { it.id == entity.id.toString() }
    }

    @Transactional
    fun updateNote(studioId: StudioId, linkId: UUID, note: String?) {
        val link = visitProductRepository.findByIdAndStudioId(linkId, studioId.value)
            ?: throw EntityNotFoundException("Powiązanie nie zostało znalezione")
        link.note = note?.trim()?.ifBlank { null }
        visitProductRepository.save(link)
    }

    @Transactional
    fun unlink(studioId: StudioId, linkId: UUID) {
        val link = visitProductRepository.findByIdAndStudioId(linkId, studioId.value)
            ?: throw EntityNotFoundException("Powiązanie nie zostało znalezione")
        visitProductRepository.delete(link)
    }
}

data class VisitProductDto(
    val id: String,
    val productId: String,
    val productName: String,
    val brand: String?,
    val packageLabel: String?,
    val imageFileId: String?,
    val note: String?,
    val addedByName: String,
    val addedAt: Instant
)

data class VisitProductBackref(
    val linkId: String,
    val visitId: String,
    val note: String?,
    val addedByName: String,
    val addedAt: Instant
)
