package pl.detailing.crm.product.usage

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.product.infrastructure.ProductRepository
import pl.detailing.crm.product.infrastructure.VisitProductEntity
import pl.detailing.crm.product.infrastructure.VisitProductRepository
import pl.detailing.crm.shared.Pagination
import pl.detailing.crm.visit.infrastructure.VisitRepository
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
    private val productRepository: ProductRepository,
    private val visitRepository: VisitRepository
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

    /**
     * „Wykorzystano podczas wizyty" — wizyty TEGO studia, na których dopięto produkt.
     *
     * Wzbogacone o dane wizyty (numer, pojazd, termin), bo sam identyfikator niczego nie
     * mówi człowiekowi patrzącemu na kartę produktu. Filtrowanie i stronicowanie robimy
     * w pamięci: powiązań jednego produktu z wizytami są dziesiątki, nie miliony, a
     * fraza dotyczy pól z DWÓCH tabel (numer/pojazd z wizyty, notatka z powiązania),
     * więc jedno zapytanie SQL i tak nie obsłużyłoby jej bez joina przez cały moduł wizyt.
     */
    @Transactional(readOnly = true)
    fun listVisitsForProduct(
        studioId: StudioId,
        productId: UUID,
        search: String,
        page: Int,
        limit: Int
    ): ProductVisitUsagePage {
        val links = visitProductRepository.findByProduct(studioId.value, productId)
        val safePage = Pagination.normalizePage(page)
        val safeLimit = Pagination.normalizeLimit(limit, max = 100)
        if (links.isEmpty()) return ProductVisitUsagePage(emptyList(), safePage, 0, 0, safeLimit)

        val visits = visitRepository.findAllById(links.map { it.visitId })
            // Pas bezpieczeństwa: powiązanie niesie studio_id, ale wizyta jest źródłem
            // prawdy o przynależności — nie pokazujemy cudzej wizyty nawet przez pomyłkę.
            .filter { it.studioId == studioId.value }
            .associateBy { it.id }

        val rows = links.mapNotNull { link ->
            val visit = visits[link.visitId] ?: return@mapNotNull null
            ProductVisitUsage(
                linkId = link.id.toString(),
                visitId = link.visitId.toString(),
                visitNumber = visit.visitNumber,
                title = visit.title,
                vehicle = listOfNotNull(
                    "${visit.brandSnapshot} ${visit.modelSnapshot}".trim().ifBlank { null },
                    visit.licensePlateSnapshot
                ).joinToString(" · "),
                status = visit.status.name,
                scheduledDate = visit.scheduledDate,
                note = link.note,
                addedByName = link.createdByName,
                addedAt = link.createdAt
            )
        }

        val phrase = search.trim().lowercase()
        val matching = if (phrase.isEmpty()) rows else rows.filter { row ->
            listOfNotNull(row.visitNumber, row.title, row.vehicle, row.note, row.addedByName)
                .any { it.lowercase().contains(phrase) }
        }

        return ProductVisitUsagePage(
            items = Pagination.slice(matching, safePage, safeLimit),
            currentPage = safePage,
            totalPages = Pagination.totalPages(matching.size, safeLimit),
            totalItems = matching.size,
            itemsPerPage = safeLimit
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

/** Jedna wizyta na liście „Wykorzystano podczas wizyty". */
data class ProductVisitUsage(
    val linkId: String,
    val visitId: String,
    val visitNumber: String,
    val title: String?,
    val vehicle: String,
    val status: String,
    val scheduledDate: Instant,
    val note: String?,
    val addedByName: String,
    val addedAt: Instant
)

data class ProductVisitUsagePage(
    val items: List<ProductVisitUsage>,
    val currentPage: Int,
    val totalPages: Int,
    val totalItems: Int,
    val itemsPerPage: Int
)
