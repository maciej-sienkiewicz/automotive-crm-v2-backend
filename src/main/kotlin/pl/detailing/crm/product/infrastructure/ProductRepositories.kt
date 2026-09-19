package pl.detailing.crm.product.infrastructure

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.util.UUID

/**
 * Repozytoria modułu produktów w jednym pliku — wzorzec z
 * `subscription/entitlement/infrastructure/EntitlementRepositories.kt`. Cztery z pięciu
 * dotyczą danych PRYWATNYCH studia i każde zapytanie filtruje po `studioId`; jedyny
 * wyjątek to [ProductRepository] nad globalnym katalogiem.
 */

@Repository
interface ProductRepository : JpaRepository<ProductEntity, UUID> {

    fun findByGtin(gtin: String): ProductEntity?

    /**
     * Klucz zapasowy dla produktów bez kodu kreskowego (chemia luzem): znormalizowana
     * (marka, nazwa, wielkość opakowania) W OBRĘBIE WŁAŚCICIELA. Wiersze bez kodu są
     * prywatne, więc dwa studia mogą mieć własną „Pastę polerską 1kg" — to nie kolizja.
     */
    @Query(
        """
        SELECT p FROM ProductEntity p
        WHERE p.gtin IS NULL
          AND p.ownerStudioId = :ownerStudioId
          AND LOWER(p.brand) = LOWER(:brand)
          AND LOWER(p.name) = LOWER(:name)
          AND p.packageSizeValue = :sizeValue
          AND p.packageSizeUnit = :sizeUnit
        """
    )
    fun findByNaturalKey(
        @Param("ownerStudioId") ownerStudioId: UUID,
        @Param("brand") brand: String,
        @Param("name") name: String,
        @Param("sizeValue") sizeValue: java.math.BigDecimal,
        @Param("sizeUnit") sizeUnit: UnitOfMeasureRef
    ): ProductEntity?

    /**
     * Katalog TEGO studia: proste, wielkoliterowo-niewrażliwe wyszukiwanie po nazwie,
     * marce i kodzie. Wycofane pomijamy.
     *
     * Tabela produktów jest współdzielona przez wszystkie studia, ale służy jako CACHE
     * rozpoznawania po kodzie kreskowym — nie jako sklep, po którym się chodzi. Lista
     * pokazuje więc wyłącznie to, co studio samo dodało; wiersz obcego najemcy, który
     * akurat siedzi w cache, nie ma tu czego szukać. „Nasze" to jedno z czterech:
     *
     *  - wiersz prywatny studia (produkt bez poprawnego kodu),
     *  - wiersz założony przez to studio (ręcznie albo ze skanu),
     *  - wiersz z nakładką studia (cena, nazwa własna, ulubiony, ukryty),
     *  - wiersz użyty przy którejkolwiek wizycie tego studia.
     *
     * Warunek jest w zapytaniu, nie w filtrze na liście: cache rośnie o produkty
     * wszystkich najemców, więc ściąganie go w całości do pamięci przy każdym otwarciu
     * listy skończyłoby się tym, że jeden warsztat płaci pamięcią za cudze skany.
     */
    @Query(
        """
        SELECT p FROM ProductEntity p
        WHERE p.isWithdrawn = false
          AND (p.ownerStudioId IS NULL OR p.ownerStudioId = :studioId)
          AND (
                p.ownerStudioId = :studioId
             OR p.createdByStudioId = :studioId
             OR EXISTS (SELECT 1 FROM ProductStudioEntity s
                        WHERE s.productId = p.id AND s.studioId = :studioId)
             OR EXISTS (SELECT 1 FROM VisitProductEntity v
                        WHERE v.productId = p.id AND v.studioId = :studioId)
          )
          AND (:search = '' OR
               LOWER(p.name) LIKE LOWER(CONCAT('%', :search, '%')) OR
               LOWER(p.brand) LIKE LOWER(CONCAT('%', :search, '%')) OR
               p.gtin LIKE CONCAT('%', :search, '%'))
        """
    )
    fun search(@Param("studioId") studioId: UUID, @Param("search") search: String): List<ProductEntity>

    /** Prywatne wiersze studia — kasowane przy „Wyczyść konto". */
    fun findByOwnerStudioId(ownerStudioId: UUID): List<ProductEntity>
}

// Alias żeby zapytanie JPQL po enumie było czytelne w sygnaturze repo.
typealias UnitOfMeasureRef = pl.detailing.crm.product.domain.UnitOfMeasure

@Repository
interface ProductStudioRepository : JpaRepository<ProductStudioEntity, UUID> {

    fun findByStudioIdAndProductId(studioId: UUID, productId: UUID): ProductStudioEntity?

    @Query("SELECT ps FROM ProductStudioEntity ps WHERE ps.studioId = :studioId AND ps.productId IN :productIds")
    fun findByStudioIdAndProductIds(
        @Param("studioId") studioId: UUID,
        @Param("productIds") productIds: Collection<UUID>
    ): List<ProductStudioEntity>

    @Query("SELECT ps FROM ProductStudioEntity ps WHERE ps.studioId = :studioId")
    fun findAllByStudioId(@Param("studioId") studioId: UUID): List<ProductStudioEntity>
}

@Repository
interface ProductNoteRepository : JpaRepository<ProductNoteEntity, UUID> {

    @Query(
        """
        SELECT n FROM ProductNoteEntity n
        WHERE n.studioId = :studioId AND n.productId = :productId AND n.isDeleted = false
        ORDER BY n.createdAt DESC
        """
    )
    fun findActive(
        @Param("studioId") studioId: UUID,
        @Param("productId") productId: UUID
    ): List<ProductNoteEntity>

    fun findByIdAndStudioId(id: UUID, studioId: UUID): ProductNoteEntity?

    @Query(
        """
        SELECT COUNT(n) FROM ProductNoteEntity n
        WHERE n.studioId = :studioId AND n.productId = :productId AND n.isDeleted = false
        """
    )
    fun countActive(
        @Param("studioId") studioId: UUID,
        @Param("productId") productId: UUID
    ): Long
}

@Repository
interface ProductRatingRepository : JpaRepository<ProductRatingEntity, UUID> {
    fun findByStudioIdAndProductId(studioId: UUID, productId: UUID): ProductRatingEntity?
}

@Repository
interface VisitProductRepository : JpaRepository<VisitProductEntity, UUID> {

    @Query(
        """
        SELECT vp FROM VisitProductEntity vp
        WHERE vp.studioId = :studioId AND vp.visitId = :visitId
        ORDER BY vp.createdAt ASC
        """
    )
    fun findByVisit(
        @Param("studioId") studioId: UUID,
        @Param("visitId") visitId: UUID
    ): List<VisitProductEntity>

    @Query(
        """
        SELECT vp FROM VisitProductEntity vp
        WHERE vp.studioId = :studioId AND vp.productId = :productId
        ORDER BY vp.createdAt DESC
        """
    )
    fun findByProduct(
        @Param("studioId") studioId: UUID,
        @Param("productId") productId: UUID
    ): List<VisitProductEntity>

    fun findByIdAndStudioId(id: UUID, studioId: UUID): VisitProductEntity?

    fun existsByStudioIdAndVisitIdAndProductId(studioId: UUID, visitId: UUID, productId: UUID): Boolean

    /**
     * Użycie produktów JEDNYM zapytaniem, nie po jednym na wiersz listy.
     *
     * `COUNT(DISTINCT vp.visitId)`, nie `COUNT(*)`: tabela świadomie nie ma klucza
     * unikalnego (visit_id, product_id), bo ten sam produkt bywa dopięty do wizyty
     * przez dwie osoby. Liczy się liczba wizyt, w których produkt poszedł w ruch.
     *
     * Indeks (studio_id, product_id, created_at) obsługuje i grupowanie, i MAX.
     */
    @Query(
        """
        SELECT new pl.detailing.crm.product.infrastructure.ProductUsageRow(
            vp.productId, COUNT(DISTINCT vp.visitId), MAX(vp.createdAt)
        )
        FROM VisitProductEntity vp
        WHERE vp.studioId = :studioId AND vp.productId IN :productIds
        GROUP BY vp.productId
        """
    )
    fun usageByProduct(
        @Param("studioId") studioId: UUID,
        @Param("productIds") productIds: Collection<UUID>
    ): List<ProductUsageRow>
}

/** Ile wizyt i kiedy ostatnio - wynik agregatu [VisitProductRepository.usageByProduct]. */
data class ProductUsageRow(
    val productId: UUID,
    val visitCount: Long,
    val lastUsedAt: java.time.Instant
)
