package pl.detailing.crm.leads.intake

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.shared.ConflictException
import pl.detailing.crm.shared.NotFoundException
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.ValidationException
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Base64
import java.util.UUID

data class LeadIntakeWebhookDto(
    val id: String,
    val name: String,
    val tokenHint: String,
    val active: Boolean,
    val fieldMapping: String?,
    val defaultTagCodes: List<String>,
    val createdAt: Instant,
    val lastReceivedAt: Instant?,
    val receivedCount: Long
)

data class LeadIntakeDeliveryDto(
    val id: String,
    val receivedAt: Instant,
    val status: String,
    val reason: String?,
    val leadId: String?,
    val payload: String
)

/** Token widać jeden raz — przy tworzeniu. Potem zostaje tylko skrót. */
data class CreatedWebhookDto(val webhook: LeadIntakeWebhookDto, val token: String)

/**
 * Zarządzanie webhookami formularzy i przyjmowanie zgłoszeń.
 *
 * Uwierzytelnienie jest tokenem w adresie — tak samo jak przy karcie wizyty i zdalnym
 * podpisie. To świadoma decyzja, nie skrót: wtyczka formularza w WordPressie ma jedno
 * pole „URL webhooka" i nic więcej. Podpis HMAC byłby bezpieczniejszy, ale wymagałby
 * pisania kodu po stronie studia, czyli tego, przed czym ta funkcja ma chronić. Token
 * jest długi, losowy i odwoływalny, a każde zgłoszenie ląduje w dzienniku.
 */
@Service
class LeadIntakeService(
    private val webhookRepository: LeadIntakeWebhookRepository,
    private val deliveryRepository: LeadIntakeDeliveryRepository,
    private val objectMapper: ObjectMapper
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val random = SecureRandom()

    // ── Zarządzanie ──────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    fun list(studioId: StudioId): List<LeadIntakeWebhookDto> =
        webhookRepository.findByStudioIdOrderByCreatedAtDesc(studioId.value).map { it.toDto() }

    @Transactional
    fun create(studioId: StudioId, rawName: String, defaultTagCodes: List<String>): CreatedWebhookDto {
        val name = rawName.trim().ifBlank { "Formularz na stronie" }.take(120)
        val token = generateToken()
        val entity = webhookRepository.save(
            LeadIntakeWebhookEntity(
                id = UUID.randomUUID(),
                studioId = studioId.value,
                name = name,
                tokenHash = hash(token),
                tokenHint = token.takeLast(TOKEN_HINT_LENGTH),
                defaultTagCodes = objectMapper.writeValueAsString(defaultTagCodes)
            )
        )
        return CreatedWebhookDto(entity.toDto(), token)
    }

    @Transactional
    fun update(
        studioId: StudioId,
        webhookId: UUID,
        name: String?,
        active: Boolean?,
        fieldMapping: String?,
        defaultTagCodes: List<String>?
    ): LeadIntakeWebhookDto {
        val entity = webhookRepository.findByIdAndStudioId(webhookId, studioId.value)
            ?: throw NotFoundException("Nie znaleziono webhooka")
        name?.trim()?.takeIf { it.isNotBlank() }?.let { entity.name = it.take(120) }
        active?.let { entity.active = it }
        fieldMapping?.let { raw ->
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) {
                entity.fieldMapping = null
            } else {
                // Nie zapisujemy mapowania, którego potem nie da się odczytać —
                // cicho zignorowana konfiguracja to najgorszy rodzaj konfiguracji.
                runCatching { objectMapper.readTree(trimmed) }.getOrElse {
                    throw ValidationException("Mapowanie pól musi być poprawnym JSON-em")
                }
                entity.fieldMapping = trimmed
            }
        }
        defaultTagCodes?.let { entity.defaultTagCodes = objectMapper.writeValueAsString(it) }
        return webhookRepository.save(entity).toDto()
    }

    @Transactional
    fun delete(studioId: StudioId, webhookId: UUID) {
        val entity = webhookRepository.findByIdAndStudioId(webhookId, studioId.value)
            ?: throw NotFoundException("Nie znaleziono webhooka")
        webhookRepository.delete(entity)
    }

    @Transactional(readOnly = true)
    fun deliveries(studioId: StudioId, webhookId: UUID): List<LeadIntakeDeliveryDto> {
        webhookRepository.findByIdAndStudioId(webhookId, studioId.value)
            ?: throw NotFoundException("Nie znaleziono webhooka")
        return deliveryRepository.findTop20ByWebhookIdOrderByReceivedAtDesc(webhookId).map {
            LeadIntakeDeliveryDto(
                id = it.id.toString(),
                receivedAt = it.receivedAt,
                status = it.status,
                reason = it.reason,
                leadId = it.leadId?.toString(),
                payload = it.payload
            )
        }
    }

    // ── Przyjmowanie ─────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    fun resolveByToken(token: String): LeadIntakeWebhookEntity {
        val webhook = webhookRepository.findByTokenHash(hash(token))
            ?: throw NotFoundException("Nieznany adres webhooka")
        if (!webhook.active) throw ConflictException("Ten webhook jest wyłączony")
        return webhook
    }

    fun defaultTagsOf(webhook: LeadIntakeWebhookEntity): List<String> =
        webhook.defaultTagCodes?.let { raw ->
            runCatching {
                objectMapper.readValue(raw, Array<String>::class.java).toList()
            }.getOrDefault(emptyList())
        } ?: emptyList()

    /**
     * Czy ten sam ładunek przyszedł z tego formularza chwilę temu.
     *
     * Podwójne kliknięcie „Wyślij" i ponowienie po timeoucie to codzienność wtyczek
     * formularzy. Dwa identyczne leady w tabeli wyglądają jak błąd aplikacji, a nie
     * jak dwa zapytania — bo nimi nie są.
     */
    @Transactional(readOnly = true)
    fun isDuplicate(webhook: LeadIntakeWebhookEntity, payload: String): Boolean =
        deliveryRepository.existsByWebhookIdAndPayloadAndReceivedAtAfter(
            webhook.id,
            payload,
            Instant.now().minus(DUPLICATE_WINDOW_MINUTES, ChronoUnit.MINUTES)
        )

    /**
     * Zapis doręczenia idzie WŁASNĄ transakcją: dziennik ma przetrwać także wtedy,
     * gdy tworzenie leada się wywróci. Wpis „przyszło, ale poległo" jest wart więcej
     * niż brak jakiegokolwiek śladu.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun recordDelivery(
        webhook: LeadIntakeWebhookEntity,
        status: String,
        payload: String,
        reason: String? = null,
        leadId: UUID? = null,
        remoteIp: String? = null
    ) {
        deliveryRepository.save(
            LeadIntakeDeliveryEntity(
                id = UUID.randomUUID(),
                studioId = webhook.studioId,
                webhookId = webhook.id,
                status = status,
                reason = reason?.take(300),
                leadId = leadId,
                payload = payload.take(MAX_PAYLOAD),
                remoteIp = remoteIp?.take(64)
            )
        )
        if (status == STATUS_CREATED) {
            webhookRepository.findById(webhook.id).ifPresent { fresh ->
                fresh.lastReceivedAt = Instant.now()
                fresh.receivedCount += 1
                webhookRepository.save(fresh)
            }
        }
    }

    private fun LeadIntakeWebhookEntity.toDto() = LeadIntakeWebhookDto(
        id = id.toString(),
        name = name,
        tokenHint = tokenHint,
        active = active,
        fieldMapping = fieldMapping,
        defaultTagCodes = defaultTagsOf(this),
        createdAt = createdAt,
        lastReceivedAt = lastReceivedAt,
        receivedCount = receivedCount
    )

    private fun generateToken(): String {
        val bytes = ByteArray(TOKEN_BYTES)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun hash(token: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(token.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    companion object {
        const val STATUS_CREATED = "CREATED"
        const val STATUS_DUPLICATE = "DUPLICATE"
        const val STATUS_REJECTED = "REJECTED"

        private const val TOKEN_BYTES = 24
        private const val TOKEN_HINT_LENGTH = 6
        private const val DUPLICATE_WINDOW_MINUTES = 10L
        private const val MAX_PAYLOAD = 20_000
    }
}
