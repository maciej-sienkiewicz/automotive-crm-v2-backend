package pl.detailing.crm.instagram.ads.discovery

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.time.Instant
import java.time.LocalDate

/**
 * Rejestr reklamodawców odkrywania — jeden wiersz na stronę na Facebooku, WSPÓLNY
 * dla wszystkich najemców i nigdy nie kasowany.
 *
 * Cache reklam ([AdDiscoveryAdEntity]) nie prowadzi historii, a bez historii nie
 * da się odróżnić „nowa firma w rejonie" od „znana firma podmieniła kreację":
 * w obu przypadkach cache widzi tylko świeżą kampanię. Ten rejestr pamięta
 * [firstDeliveryStart] — najwcześniejszy start kampanii, jaką u tej strony
 * kiedykolwiek widzieliśmy — i to po nim [AreaAdvertiserSummary] rozstrzyga, czy
 * firma jest nowa, czy tylko jej kampania.
 *
 * Rejestr NIE jest per studio: to samo pytanie („od kiedy ta firma się reklamuje")
 * ma tę samą odpowiedź dla każdego, kto ją obserwuje. Rejon i wykluczenia
 * studia nakładają się dopiero przy odczycie, jak w całym module.
 */
@Entity
@Table(name = "meta_ad_discovery_advertisers")
class AdDiscoveryAdvertiserEntity(
    @Id
    @Column(name = "page_id", length = 40)
    val pageId: String,

    @Column(name = "page_name", nullable = true, length = 200)
    var pageName: String? = null,

    /** Najwcześniejszy `delivery_start` wśród wszystkich reklam tej strony, jakie widzieliśmy. */
    @Column(name = "first_delivery_start", nullable = false)
    var firstDeliveryStart: LocalDate,

    @Column(name = "first_seen_at", nullable = false, columnDefinition = "timestamp with time zone")
    val firstSeenAt: Instant = Instant.now(),

    @Column(name = "last_seen_at", nullable = false, columnDefinition = "timestamp with time zone")
    var lastSeenAt: Instant = Instant.now()
)

@Repository
interface AdDiscoveryAdvertiserRepository : JpaRepository<AdDiscoveryAdvertiserEntity, String> {
    fun findByPageIdIn(pageIds: Collection<String>): List<AdDiscoveryAdvertiserEntity>
}
