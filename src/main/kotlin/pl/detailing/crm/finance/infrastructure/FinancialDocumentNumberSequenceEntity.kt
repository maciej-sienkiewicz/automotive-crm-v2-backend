package pl.detailing.crm.finance.infrastructure

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.IdClass
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.io.Serializable
import java.util.UUID

/**
 * Ostatni nadany numer dokumentu finansowego w serii {prefiks}/{rok} studia.
 *
 * Numer liczony jako „liczba dokumentów w roku + 1" wracał: po usunięciu dokumentu
 * następny dostawał numer już wydany, a dwa równoległe wystawienia liczyły to samo
 * COUNT i dostawały ten sam numer. Licznik rośnie tylko w górę i jest zwiększany
 * pod blokadą wiersza (upsert), więc numer raz wydany nie wraca nigdy.
 *
 * Encja istnieje głównie po to, by Hibernate (`ddl-auto=update`) zakładał tabelę
 * lokalnie; na wdrożeniach zakłada ją V162. Odczyt i zapis idą natywnym upsertem.
 */
@Entity
@Table(name = "financial_document_number_sequences")
@IdClass(FinancialDocumentNumberSequenceId::class)
class FinancialDocumentNumberSequenceEntity(
    @Id
    @Column(name = "studio_id", nullable = false, columnDefinition = "uuid")
    val studioId: UUID,

    @Id
    @Column(name = "prefix", nullable = false, length = 10)
    val prefix: String,

    @Id
    @Column(name = "year", nullable = false)
    val year: Int,

    @Column(name = "last_value", nullable = false)
    var lastValue: Long
)

data class FinancialDocumentNumberSequenceId(
    val studioId: UUID = UUID(0, 0),
    val prefix: String = "",
    val year: Int = 0
) : Serializable

@Repository
interface FinancialDocumentNumberSequenceRepository :
    JpaRepository<FinancialDocumentNumberSequenceEntity, FinancialDocumentNumberSequenceId> {

    /**
     * Następna wartość serii. Pierwsze wywołanie zakłada wiersz z [seed], każde kolejne
     * zwiększa go o 1. ON CONFLICT bierze blokadę wiersza do końca transakcji, więc
     * równoległe wystawienia czekają na siebie; wycofana transakcja nie zostawia luki.
     */
    @Query(
        value = """
            INSERT INTO financial_document_number_sequences (studio_id, prefix, year, last_value)
            VALUES (:studioId, :prefix, :year, :seed)
            ON CONFLICT (studio_id, prefix, year)
            DO UPDATE SET last_value = financial_document_number_sequences.last_value + 1
            RETURNING last_value
        """,
        nativeQuery = true
    )
    fun nextValue(
        @Param("studioId") studioId: UUID,
        @Param("prefix") prefix: String,
        @Param("year") year: Int,
        @Param("seed") seed: Long
    ): Long

    /**
     * Najwyższy numer serii już zapisany na dokumentach — także usuniętych, bo ich numery
     * zostały wydane. Punkt startowy licznika dla serii sprzed jego wprowadzenia.
     * [pattern] ma jedną grupę z numerem, np. `^PAR/2026/([0-9]+)$`.
     */
    @Query(
        value = """
            SELECT COALESCE(MAX(CAST(SUBSTRING(document_number FROM :pattern) AS BIGINT)), 0)
            FROM financial_documents
            WHERE studio_id = :studioId
              AND document_number ~ :pattern
        """,
        nativeQuery = true
    )
    fun maxIssuedSequence(
        @Param("studioId") studioId: UUID,
        @Param("pattern") pattern: String
    ): Long
}
