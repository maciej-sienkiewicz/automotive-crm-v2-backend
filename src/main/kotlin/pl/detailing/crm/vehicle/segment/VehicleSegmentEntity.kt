package pl.detailing.crm.vehicle.segment

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

/**
 * Zapamiętana klasyfikacja jednego modelu auta. Globalna, nie per studio —
 * „Toyota Corolla to kompakt marki popularnej" jest faktem o świecie.
 */
@Entity
@Table(name = "vehicle_segments")
class VehicleSegmentEntity(
    @Id
    @Column(name = "id", columnDefinition = "uuid")
    val id: UUID = UUID.randomUUID(),

    /** Znormalizowany klucz wyszukiwania — lower + trim. */
    @Column(name = "brand_key", nullable = false, length = 120)
    val brandKey: String,

    /** Pusty łańcuch dla auta bez modelu: w indeksie unikalnym NULL nie równa się NULL. */
    @Column(name = "model_key", nullable = false, length = 160)
    val modelKey: String,

    @Column(name = "brand", nullable = false, length = 120)
    val brand: String,

    @Column(name = "model", length = 160)
    val model: String?,

    @Enumerated(EnumType.STRING)
    @Column(name = "size_segment", nullable = false, length = 20)
    var sizeSegment: VehicleSizeSegment,

    @Enumerated(EnumType.STRING)
    @Column(name = "market_tier", nullable = false, length = 20)
    var marketTier: VehicleMarketTier,

    @Column(name = "source", nullable = false, length = 20)
    var source: String = "LLM",

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now()
)

@Repository
interface VehicleSegmentRepository : JpaRepository<VehicleSegmentEntity, UUID> {

    fun findByBrandKeyAndModelKey(brandKey: String, modelKey: String): VehicleSegmentEntity?

    /** Cała tablica naraz — analityka potrzebuje jej dla wszystkich leadów okna. */
    fun findByBrandKeyIn(brandKeys: Collection<String>): List<VehicleSegmentEntity>
}
