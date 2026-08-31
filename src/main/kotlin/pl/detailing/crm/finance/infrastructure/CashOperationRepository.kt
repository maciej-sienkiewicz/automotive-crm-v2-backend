package pl.detailing.crm.finance.infrastructure

import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

@Repository
interface CashOperationRepository : JpaRepository<CashOperationEntity, UUID> {

    /**
     * Historia zawężona filtrami z widoku „Kasa".
     *
     * `from` jest włączające, `to` **wyłączające** — bo pochodzi ze startu dnia
     * następnego po `dateTo` (patrz DateRangeFilter). Porównanie `<=` policzyłoby
     * ostatnią dobę na krawędzi strefy czasowej.
     *
     * Kierunek: wpłata to `amount > 0`, wypłata `amount < 0`. Zero nie jest ani
     * jednym, ani drugim — korekta zerowa jest odrzucana przy zapisie, ale gdyby
     * kiedyś taki wiersz powstał, widać go tylko bez filtra kierunku, i to jest
     * uczciwsze niż doliczanie go do dowolnej ze stron.
     */
    @Query("""
        SELECT op FROM CashOperationEntity op
        WHERE op.studioId = :studioId
          AND (:from IS NULL OR op.createdAt >= :from)
          AND (:to   IS NULL OR op.createdAt <  :to)
          AND (:onlyIn  = FALSE OR op.amount > 0)
          AND (:onlyOut = FALSE OR op.amount < 0)
        ORDER BY op.createdAt DESC
    """)
    fun findFiltered(
        studioId: UUID,
        from: Instant?,
        to: Instant?,
        onlyIn: Boolean,
        onlyOut: Boolean,
        pageable: Pageable
    ): Page<CashOperationEntity>

    /**
     * Suma wpłat w okresie — w groszach, dodatnia; `null` gdy w okresie nie ma
     * ani jednej wpłaty (SUM po pustym zbiorze).
     *
     * Świadomie bez filtra kierunku: podsumowanie ma pokazywać obie strony okresu
     * naraz, więc przełączenie „tylko wpłaty/tylko wypłaty" zawęża listę, ale nie
     * rusza tego wiersza. Liczy po całym okresie, nie po bieżącej stronie —
     * podsumowanie jednej strony trzydziestu wierszy nie znaczyłoby nic.
     */
    @Query("""
        SELECT SUM(op.amount) FROM CashOperationEntity op
        WHERE op.studioId = :studioId
          AND op.amount > 0
          AND (:from IS NULL OR op.createdAt >= :from)
          AND (:to   IS NULL OR op.createdAt <  :to)
    """)
    fun sumInflow(studioId: UUID, from: Instant?, to: Instant?): Long?

    /**
     * Suma wypłat w okresie — w groszach i **ujemna**, bo `amount` jest podpisane;
     * `null` gdy w okresie nie ma ani jednej wypłaty. Znak i pustkę obsługuje
     * handler; tutaj zostaje to, co jest w bazie.
     */
    @Query("""
        SELECT SUM(op.amount) FROM CashOperationEntity op
        WHERE op.studioId = :studioId
          AND op.amount < 0
          AND (:from IS NULL OR op.createdAt >= :from)
          AND (:to   IS NULL OR op.createdAt <  :to)
    """)
    fun sumOutflow(studioId: UUID, from: Instant?, to: Instant?): Long?

    /**
     * Finds all operations linked to a specific financial document.
     * Typically zero or one result (PAYMENT_IN / PAYMENT_OUT).
     */
    @Query("""
        SELECT op FROM CashOperationEntity op
        WHERE op.studioId           = :studioId
          AND op.financialDocumentId = :documentId
        ORDER BY op.createdAt ASC
    """)
    fun findByDocumentId(studioId: UUID, documentId: UUID): List<CashOperationEntity>
}
