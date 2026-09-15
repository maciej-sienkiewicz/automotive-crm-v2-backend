package pl.detailing.crm.instagram.ads.discovery.ig

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant

/**
 * Wynik próby odczytania nazwy profilu na Instagramie ze strony reklamodawcy.
 *
 * Rozróżnienie [EMPTY] od pozostałych stanów niepowodzenia jest sednem całego
 * mechanizmu alertowania. „Reklamodawca nie podał Instagrama" to normalny,
 * poprawny wynik i zdarza się często. „Nie udało się sprawdzić" to sygnał, że
 * coś po drodze przestało działać — a gdy zdarza się masowo, znaczy, że Meta
 * przebudowała stronę i nasza ścieżka kliknięć przestała pasować.
 */
enum class IgLookupStatus {
    /** Odczytano nazwę profilu. */
    OK,

    /** Doszliśmy do danych o stronie; pola z Instagramem w nich nie było. */
    EMPTY,

    /** Strona się nie wczytała albo nie dotarliśmy do sekcji z danymi. */
    BLOCKED,

    /** Przekroczony czas — zwykle wolna odpowiedź Meta, warto ponowić. */
    TIMEOUT,

    /** Wszystko inne: błąd sidecara, sieci, nieoczekiwany wyjątek. */
    ERROR;

    /** Czy ten wynik świadczy o awarii mechanizmu, a nie o danych. */
    val isFailure: Boolean get() = this == BLOCKED || this == TIMEOUT || this == ERROR
}

/**
 * Nazwa profilu IG strony reklamodawcy — jeden wiersz na stronę, wspólny dla
 * wszystkich studiów.
 *
 * Uchwyt należy do strony na Facebooku, nie do reklamy ani do najemcy: profil
 * „Auto Spa Poznań" jest ten sam niezależnie od tego, kto na niego patrzy.
 * Dlatego brak tu `studioId` — to ta sama zasada, co przy wspólnym cache fraz.
 */
@Entity
@Table(name = "meta_page_ig_lookup")
class MetaPageIgLookupEntity(
    @Id
    @Column(name = "page_id", nullable = false, length = 40)
    val pageId: String,

    /** Bez małpy. Null znaczy „sprawdzono i nie ma" — od „nie sprawdzano" jest brak wiersza. */
    @Column(name = "ig_username", nullable = true, length = 30)
    var igUsername: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    var status: IgLookupStatus = IgLookupStatus.ERROR,

    @Column(name = "checked_at", nullable = false, columnDefinition = "timestamp with time zone")
    var checkedAt: Instant = Instant.now(),

    @Column(name = "attempts", nullable = false)
    var attempts: Int = 1
)

interface MetaPageIgLookupRepository : JpaRepository<MetaPageIgLookupEntity, String> {

    /**
     * Strony warte (po)sprawdzenia: nigdy nierozstrzygnięte albo sprawdzone
     * dawno temu bez wyniku.
     *
     * Wiersz z nazwą nie wraca tu nigdy — uchwyt się nie zmienia, a każde
     * zbędne wejście to uruchomienie przeglądarki i ruch do Meta.
     */
    @Query(
        """
        SELECT l.pageId FROM MetaPageIgLookupEntity l
        WHERE l.igUsername IS NULL AND l.checkedAt < :before
        ORDER BY l.checkedAt ASC
        """
    )
    fun stalePageIds(@Param("before") before: Instant): List<String>
}
