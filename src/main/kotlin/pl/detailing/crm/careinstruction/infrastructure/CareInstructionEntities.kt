package pl.detailing.crm.careinstruction.infrastructure

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * Jedna instrukcja pielęgnacyjna ze słownika studia — cegiełka sekcji „Jak utrzymać
 * efekt" na certyfikacie jakości.
 *
 * Słownik zamiast pola tekstowego przy usłudze: ta sama instrukcja („nie myj przez
 * 7 dni") dotyczy zwykle kilku usług, a przepisana w kilku miejscach rozjeżdża się po
 * pierwszej poprawce. Przypisanie do usług jest osobną relacją
 * ([ServiceCareInstructionEntity]), więc zmiana treści działa wstecz wszędzie.
 */
@Entity
@Table(name = "care_instructions")
class CareInstructionEntity(
    @Id
    @Column(name = "id", columnDefinition = "uuid")
    val id: UUID,

    @Column(name = "studio_id", nullable = false, columnDefinition = "uuid")
    val studioId: UUID,

    /** Etykieta na liście wyboru przy generowaniu certyfikatu. */
    @Column(name = "title", nullable = false, length = 200)
    var title: String,

    /** Treść drukowana na dokumencie. */
    @Column(name = "content", nullable = false, columnDefinition = "TEXT")
    var content: String,

    /**
     * Zaznaczaj przy każdym certyfikacie. Tak działają zasady prawdziwe zawsze
     * (mycie dwoma wiadrami, pH) — ale pracownik może je odznaczyć w oknie.
     */
    @Column(name = "is_default_selected", nullable = false)
    var isDefaultSelected: Boolean = false,

    @Column(name = "sort_order", nullable = false)
    var sortOrder: Int = 0,

    @Column(name = "created_at", nullable = false, columnDefinition = "timestamp with time zone")
    val createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false, columnDefinition = "timestamp with time zone")
    var updatedAt: Instant = Instant.now()
)

/**
 * Przypisanie instrukcji do usługi z cennika.
 *
 * Surogat zamiast klucza złożonego: reszta repozytorium trzyma się `id UUID` + unikat
 * na parze, a `@IdClass` zmusiłoby do osobnej klasy klucza w każdym miejscu, które
 * czyta tę tabelę.
 */
@Entity
@Table(name = "service_care_instructions")
class ServiceCareInstructionEntity(
    @Id
    @Column(name = "id", columnDefinition = "uuid")
    val id: UUID,

    @Column(name = "studio_id", nullable = false, columnDefinition = "uuid")
    val studioId: UUID,

    @Column(name = "service_id", nullable = false, columnDefinition = "uuid")
    val serviceId: UUID,

    @Column(name = "care_instruction_id", nullable = false, columnDefinition = "uuid")
    val careInstructionId: UUID,

    @Column(name = "created_at", nullable = false, columnDefinition = "timestamp with time zone")
    val createdAt: Instant = Instant.now()
)
