package pl.detailing.crm.comms.signature

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import org.slf4j.LoggerFactory
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.comms.infrastructure.EmailHtmlSanitizer
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import pl.detailing.crm.shared.ValidationException
import java.time.Instant
import java.util.UUID

/**
 * Mail signature of a single person. Two employees replying from the same shared
 * mailbox sign with their own name and phone, so the owner is the user, not the studio.
 */
@Entity
@Table(
    name = "comm_user_signatures",
    indexes = [Index(name = "idx_comm_user_signatures_studio", columnList = "studio_id")]
)
class CommUserSignatureEntity(
    @Id
    @Column(name = "user_id", columnDefinition = "uuid")
    val userId: UUID,

    @Column(name = "studio_id", nullable = false, columnDefinition = "uuid")
    val studioId: UUID,

    @Column(name = "body_html", nullable = false, columnDefinition = "text")
    var bodyHtml: String,

    /** Whether the composer's "add signature" toggle starts on; the per-message call stays the user's. */
    @Column(name = "enabled_by_default", nullable = false)
    var enabledByDefault: Boolean = true,

    /**
     * Projekt z konfiguratora ([MailSignatureDesign] jako JSON) albo `null` dla stopki
     * pisanej zwykłym tekstem. Nie jest źródłem wysyłanej treści — ta jest w [bodyHtml].
     */
    @Column(name = "design_json", columnDefinition = "text")
    var designJson: String? = null,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now()
)

@Repository
interface CommUserSignatureRepository : JpaRepository<CommUserSignatureEntity, UUID> {
    fun findByUserIdAndStudioId(userId: UUID, studioId: UUID): CommUserSignatureEntity?
}

data class UserMailSignature(
    val bodyHtml: String?,
    val enabledByDefault: Boolean,
    /** `null` — stopka tekstowa albo brak stopki. */
    val design: MailSignatureDesign? = null
)

/**
 * Reading and writing the signature. The HTML is sanitised on save and again on send
 * (SendMailHandler runs the whole body through the same filter), because it ends up in
 * someone else's inbox and deserves no more trust than the rest of the message.
 */
@Service
class UserMailSignatureService(
    private val repository: CommUserSignatureRepository,
    private val sanitizer: EmailHtmlSanitizer
) {

    private val log = LoggerFactory.getLogger(javaClass)

    fun get(studioId: StudioId, userId: UserId): UserMailSignature {
        val stored = repository.findByUserIdAndStudioId(userId.value, studioId.value)
        return UserMailSignature(
            bodyHtml = stored?.bodyHtml,
            // With no signature saved there is nothing to attach by default.
            enabledByDefault = stored?.enabledByDefault ?: false,
            design = stored?.let(::readDesign)
        )
    }

    /**
     * Zapis stopki. [design] obecny = stopka z konfiguratora (HTML to jego render),
     * nieobecny = stopka tekstowa; zapis tekstowej kasuje poprzedni projekt, bo zapisana
     * stopka jest dokładnie tym, co użytkownik zatwierdził ostatnio.
     */
    @Transactional
    fun save(
        studioId: StudioId,
        userId: UserId,
        bodyHtml: String,
        enabledByDefault: Boolean,
        design: MailSignatureDesign? = null
    ): UserMailSignature {
        val trimmed = bodyHtml.trim()
        if (trimmed.isBlank()) throw ValidationException("Stopka nie może być pusta")
        // Stopka z motywu to tabele ze stylami w każdej komórce — ta sama treść zajmuje
        // w HTML-u kilka razy więcej znaków niż tekst, więc limit tekstowy by ją odrzucił.
        val limit = if (design != null) MAX_DESIGNED_LENGTH else MAX_LENGTH
        if (trimmed.length > limit) {
            throw ValidationException("Stopka jest za długa (limit $limit znaków)")
        }
        val normalizedDesign = design?.normalized()

        val safeHtml = sanitizer.sanitize(trimmed, UUID.randomUUID(), emptyMap())
        val designJson = normalizedDesign?.let(mapper::writeValueAsString)
        val existing = repository.findByUserIdAndStudioId(userId.value, studioId.value)
        val saved = if (existing != null) {
            existing.bodyHtml = safeHtml
            existing.enabledByDefault = enabledByDefault
            existing.designJson = designJson
            existing.updatedAt = Instant.now()
            repository.save(existing)
        } else {
            repository.save(
                CommUserSignatureEntity(
                    userId = userId.value,
                    studioId = studioId.value,
                    bodyHtml = safeHtml,
                    enabledByDefault = enabledByDefault,
                    designJson = designJson
                )
            )
        }
        return UserMailSignature(
            bodyHtml = saved.bodyHtml,
            enabledByDefault = saved.enabledByDefault,
            design = normalizedDesign
        )
    }

    /**
     * Uszkodzony albo nieznany projekt nie może zablokować wysyłki ani ekranu — stopka
     * i tak ma swój HTML. Kreator otworzy się wtedy od nowa, jak dla stopki tekstowej.
     */
    private fun readDesign(entity: CommUserSignatureEntity): MailSignatureDesign? {
        val json = entity.designJson ?: return null
        return try {
            mapper.readValue<MailSignatureDesign>(json).takeIf { MailSignatureTemplate.fromId(it.template) != null }
        } catch (e: Exception) {
            log.warn("[COMMS] Nieczytelny projekt stopki użytkownika {}: {}", entity.userId, e.message)
            null
        }
    }

    @Transactional
    fun delete(studioId: StudioId, userId: UserId) {
        repository.findByUserIdAndStudioId(userId.value, studioId.value)?.let(repository::delete)
    }

    companion object {
        const val MAX_LENGTH = 4000
        const val MAX_DESIGNED_LENGTH = 16_000
        // Pole usunięte w przyszłej wersji kreatora nie może unieważnić starych projektów.
        private val mapper = jacksonObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    }
}
