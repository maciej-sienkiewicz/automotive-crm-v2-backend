package pl.detailing.crm.customer.importing

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

/**
 * Skąd przyszły kontakty. Ma znaczenie dla interfejsu (inny opis, inne instrukcje przy
 * błędzie) i dla dziennika — „zaimportowano 34 kontakty z telefonu" to inna informacja
 * niż „zaimportowano 34 kontakty z pliku".
 */
enum class CustomerImportSource {
    /** Systemowy wybór kontaktów na Androidzie, przesłany z telefonu po zeskanowaniu QR. */
    ANDROID_PICKER,

    /** Plik `.vcf` wgrany na komputerze — jedyna droga dla iPhone'a. */
    VCARD_FILE
}

enum class CustomerImportStatus {
    /** Sesja czeka, aż telefon prześle kontakty. Dotyczy wyłącznie ścieżki z kodem QR. */
    AWAITING_CONTACTS,

    /** Kontakty są na miejscu, można pokazać podgląd i zatwierdzić. */
    READY,

    /** Import wykonany. Sesja zostaje jako ślad, ale nie da się jej użyć drugi raz. */
    COMMITTED
}

/**
 * Kontakt zapisany w sesji — dokładnie w tej postaci, w jakiej przyszedł z telefonu albo
 * z pliku, przed jakąkolwiek normalizacją.
 *
 * Surowość jest celowa: podgląd liczy się przy każdym odczycie, na żywych danych studia,
 * bo między przesłaniem kontaktów a kliknięciem „Zapisz" ktoś inny mógł dodać klienta.
 * Gdybyśmy zapisali gotowe statusy, podgląd pokazywałby stan sprzed pięciu minut.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class StoredContact(
    val firstName: String? = null,
    val lastName: String? = null,
    val displayName: String? = null,
    val phones: List<String> = emptyList(),
    val emails: List<String> = emptyList(),
    val companyName: String? = null
)

@Entity
@Table(name = "customer_import_sessions")
class CustomerImportSessionEntity(
    @Id
    @Column(name = "id", columnDefinition = "uuid")
    val id: UUID,

    @Column(name = "studio_id", nullable = false, columnDefinition = "uuid")
    val studioId: UUID,

    @Column(name = "created_by", nullable = false, columnDefinition = "uuid")
    val createdBy: UUID,

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 20)
    var source: CustomerImportSource,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    var status: CustomerImportStatus,

    /**
     * Sekret sesji zaszyty w kodzie QR. Osobny od `users.mobile_token` i krótkożyjący:
     * zdjęcie ekranu z kodem nie może dawać bezterminowego prawa wysyłania danych do
     * studia. Zerowany po przesłaniu kontaktów — kod działa raz.
     */
    @Column(name = "handoff_token", length = 64)
    var handoffToken: String?,

    /**
     * Lista kontaktów jako tekst JSON, nie jako zmapowana kolekcja.
     *
     * Hibernate potrafi zmapować `jsonb` na listę obiektów, ale wtedy kształt zapisanych
     * danych zaczyna zależeć od klasy Kotlina — a to jest ładunek z zewnątrz, przyjmowany
     * od telefonu i z pliku, o którym z góry wiadomo, że będzie się różnił między
     * urządzeniami. Tekst plus jawna deserializacja w serwisie znaczy, że dodanie pola
     * niczego nie psuje, a stara sesja da się odczytać.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "contacts", nullable = false, columnDefinition = "jsonb")
    var contactsJson: String = "[]",

    /** „Samsung Galaxy S23" — żeby na komputerze było widać, z czego przyszły dane. */
    @Column(name = "device_label", length = 120)
    var deviceLabel: String? = null,

    @Column(name = "imported_count")
    var importedCount: Int? = null,

    @Column(name = "created_at", nullable = false, columnDefinition = "timestamp with time zone")
    val createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false, columnDefinition = "timestamp with time zone")
    var updatedAt: Instant = Instant.now(),

    @Column(name = "expires_at", nullable = false, columnDefinition = "timestamp with time zone")
    var expiresAt: Instant,

    @Column(name = "committed_at", columnDefinition = "timestamp with time zone")
    var committedAt: Instant? = null
) {
    fun isExpired(now: Instant = Instant.now()): Boolean = expiresAt.isBefore(now)
}

@Repository
interface CustomerImportSessionRepository : JpaRepository<CustomerImportSessionEntity, UUID> {

    fun findByIdAndStudioId(id: UUID, studioId: UUID): CustomerImportSessionEntity?

    fun findByHandoffToken(handoffToken: String): CustomerImportSessionEntity?

    /**
     * Sesje sprzątane po wygaśnięciu. Niosą książkę adresową człowieka — dane osobowe
     * osób, które nigdy nie zostały klientami studia — więc nie mogą leżeć bezterminowo
     * tylko dlatego, że ktoś zamknął kartę przeglądarki.
     */
    @Modifying
    @Query("DELETE FROM CustomerImportSessionEntity s WHERE s.expiresAt < :threshold")
    fun deleteExpired(@Param("threshold") threshold: Instant): Int
}
