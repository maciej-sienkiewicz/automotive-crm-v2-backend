package pl.detailing.crm.leads.similar.feedback

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.IdClass
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.io.Serializable
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Mediana cen ZREALIZOWANYCH per nazwa usługi — patrz V132 i [RealizedPriceCalibrator].
 *
 * Kotwica zastępcza dla usług `requireManualPrice`, których basePriceGross wynosi 0
 * (wymusza to UpdateServiceHandler) — bez niej bramka skali wyłącza się dokładnie
 * przy najdroższych i najrzadszych robotach. Cena ZREALIZOWANA bije cenę wycenioną:
 * klient mógł oferty nie przyjąć, a pole strukturalne bije parsowanie prozy.
 *
 * Pętla w 100% deterministyczna, w 100% SQL-owa — zero LLM, zero kliknięć.
 */
@Entity
@Table(name = "studio_price_anchors")
@IdClass(StudioPriceAnchorId::class)
class StudioPriceAnchorEntity(
    @Id
    @Column(name = "studio_id", nullable = false, columnDefinition = "uuid")
    val studioId: UUID,

    @Id
    @Column(name = "name_key", nullable = false, length = 220)
    val nameKey: String,

    @Column(name = "median_realized_gross", nullable = false)
    var medianRealizedGross: Long,

    /** IQR/mediana — szerokość rozrzutu; null przy <4 obserwacjach. */
    @Column(name = "iqr_ratio", precision = 6, scale = 3)
    var iqrRatio: BigDecimal? = null,

    @Column(name = "observations", nullable = false)
    var observations: Int,

    @Column(name = "window_from", nullable = false)
    var windowFrom: LocalDate,

    @Column(name = "computed_at", nullable = false)
    var computedAt: Instant = Instant.now()
)

data class StudioPriceAnchorId(
    val studioId: UUID = UUID(0, 0),
    val nameKey: String = ""
) : Serializable

@Repository
interface StudioPriceAnchorRepository : JpaRepository<StudioPriceAnchorEntity, StudioPriceAnchorId> {
    fun findByStudioIdAndNameKey(studioId: UUID, nameKey: String): StudioPriceAnchorEntity?
    fun deleteByStudioId(studioId: UUID)
}
