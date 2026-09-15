package pl.detailing.crm.leads.similar.vision

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.IdClass
import jakarta.persistence.Index
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.io.Serializable
import java.time.Instant
import java.util.UUID

/**
 * Fakty odczytane ze zdjęcia załączonego do leada — patrz V132.
 *
 * KLUCZ ZŁOŻONY (studio_id, content_sha256): ten sam plik u dwóch najemców to dwa
 * wiersze — klucz po samym haszu byłby kolizją i wyciekiem opisu zdjęcia między
 * studiami. Hasz BAJTÓW, nie attachment_id: ten sam plik przesłany dwa razy
 * (forward, drugi mail) to jedna odpowiedź modelu i jedna zapłata.
 *
 * Fakty NIGDY nie są twardą bramką dopasowania. Mają dokładnie dwa uprawnienia:
 *  1. readable=false przy zapytaniu odsyłającym do zdjęć → NEEDS_INSPECTION
 *     (wyłączenie ścieżki cenowej, nie podmiana odpowiedzi);
 *  2. gdy tekst milczy o części auta — wypełniają ją Z ETYKIETĄ pochodzenia,
 *     a comp oparty na takiej osi nie może dostać klasy DIRECT.
 * Na ekranie zawsze z dopiskiem „odczytane ze zdjęć, do potwierdzenia przy oględzinach".
 */
@Entity
@Table(
    name = "lead_attachment_facts",
    indexes = [Index(name = "ix_laf_lead", columnList = "lead_id")]
)
@IdClass(AttachmentFactsId::class)
class LeadAttachmentFactsEntity(
    @Id
    @Column(name = "studio_id", nullable = false, columnDefinition = "uuid")
    val studioId: UUID,

    @Id
    @Column(name = "content_sha256", nullable = false, length = 64)
    val contentSha256: String,

    @Column(name = "lead_id", nullable = false, columnDefinition = "uuid")
    val leadId: UUID,

    @Column(name = "attachment_id", nullable = false, columnDefinition = "uuid")
    val attachmentId: UUID,

    /** false = zdjęcie nieczytelne / nie pokazuje auta — i to też jest werdykt. */
    @Column(name = "readable", nullable = false)
    val readable: Boolean,

    /** Część auta widoczna na zdjęciu — kod z [pl.detailing.crm.service.taxonomy.ServicePart]. */
    @Column(name = "part", length = 20)
    val part: String? = null,

    /** Sugestia rzemiosła (np. REPAIR przy rozdarciu) — kod z ServiceOperation, tylko podpowiedź. */
    @Column(name = "operation_hint", length = 20)
    val operationHint: String? = null,

    /** WEAR | TEAR | BURN | STAIN | SCRATCH | DENT | DELAMINATION | NONE_VISIBLE */
    @Column(name = "damage_type", length = 30)
    val damageType: String? = null,

    /** LIGHT | MODERATE | HEAVY | UNKNOWN */
    @Column(name = "severity", length = 20)
    val severity: String? = null,

    @Column(name = "spot_count")
    val spotCount: Int? = null,

    /** Jedno zdanie po polsku na ekran — zawsze z etykietą pochodzenia. */
    @Column(name = "summary_pl", length = 300)
    val summaryPl: String? = null,

    @Column(name = "model", nullable = false, length = 60)
    val model: String,

    @Column(name = "prompt_version", nullable = false, length = 20)
    val promptVersion: String,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now()
)

/** Klucz złożony encji faktów — patrz KDoc encji: izolacja najemców jest w KLUCZU. */
data class AttachmentFactsId(
    val studioId: UUID = UUID(0, 0),
    val contentSha256: String = ""
) : Serializable

@Repository
interface LeadAttachmentFactsRepository : JpaRepository<LeadAttachmentFactsEntity, AttachmentFactsId> {
    fun findByLeadId(leadId: UUID): List<LeadAttachmentFactsEntity>
    fun findByStudioIdAndContentSha256(studioId: UUID, contentSha256: String): LeadAttachmentFactsEntity?
}
