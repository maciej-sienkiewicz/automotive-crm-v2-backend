package pl.detailing.crm.product.scan

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.stereotype.Service
import pl.detailing.crm.product.domain.Gtin
import pl.detailing.crm.shared.EntityNotFoundException
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import pl.detailing.crm.shared.ValidationException
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID

/**
 * Sesja skanowania telefonem — komputer pokazuje kod QR, telefon go skanuje i otwiera
 * aparat, a wykryte kody wracają na komputer. Dokładnie ten model współpracy co mapa
 * uszkodzeń (telefon jest urządzeniem wejściowym otwartego okna, nie drugim edytorem).
 *
 * Stan sesji leży w Redisie, nie w pamięci przeglądarki: zamknięcie karty na komputerze
 * nie kasuje pracy telefonu. `handoffToken` jest JEDNORAZOWY i krótkoterminowy (15 min) —
 * NIE jest to stały `users.mobile_token`: zdjęcie ekranu z kodem QR nie może dawać
 * bezterminowego prawa wysyłania danych do studia (ta sama decyzja co przy imporcie
 * kontaktów, V99).
 *
 * Kody lecą do komputera po WebSocketcie (SimpMessagingTemplate), a odpytywanie GET jest
 * zapasem na słaby WiFi w warsztacie.
 */
@Service
class ProductScanSessionService(
    private val redisTemplate: StringRedisTemplate,
    private val objectMapper: ObjectMapper,
    private val messagingTemplate: SimpMessagingTemplate,
    @Value("\${crm.products.scan-session.ttl-minutes:15}") private val ttlMinutes: Long,
    @Value("\${crm.products.scan-session.max-codes-per-minute:60}") private val maxCodesPerMinute: Long
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val secureRandom = SecureRandom()

    companion object {
        private const val SESSION_KEY = "product:scan-session:"       // by sessionId
        private const val TOKEN_KEY = "product:scan-token:"           // handoffToken → sessionId
        private const val RATE_KEY = "product:scan-rate:"            // sliding-ish per-minute counter
    }

    fun open(studioId: StudioId, userId: UserId): ScanSession {
        val session = ScanSession(
            sessionId = UUID.randomUUID().toString(),
            studioId = studioId.value.toString(),
            createdBy = userId.value.toString(),
            handoffToken = generateToken(),
            status = "OPEN",
            scannedCodes = emptyList(),
            createdAt = Instant.now().toString(),
            expiresAt = Instant.now().plus(Duration.ofMinutes(ttlMinutes)).toString()
        )
        persist(session)
        log.info("[PRODUCT_SCAN] Otwarto sesję skanowania [session={} studio={}]", session.sessionId, studioId.value)
        return session
    }

    fun getForStudio(studioId: StudioId, sessionId: String): ScanSession {
        val session = read(sessionId) ?: throw EntityNotFoundException("Sesja skanowania wygasła lub nie istnieje")
        if (session.studioId != studioId.value.toString()) {
            throw EntityNotFoundException("Sesja skanowania nie należy do tego studia")
        }
        return session
    }

    /** Kontekst dla telefonu (trasa publiczna po tokenie). Zwraca minimum — bez danych studia. */
    fun resolveByToken(handoffToken: String): ScanSession {
        val sessionId = redisTemplate.opsForValue().get(TOKEN_KEY + handoffToken)
            ?: throw EntityNotFoundException("Sesja skanowania wygasła lub nie istnieje")
        return read(sessionId) ?: throw EntityNotFoundException("Sesja skanowania wygasła lub nie istnieje")
    }

    /**
     * Telefon zgłasza wykryte kody. Każdy jest walidowany (suma kontrolna GTIN) i
     * dopisywany idempotentnie (ten sam kod w obrębie sesji nie dubluje wpisu). Wynik
     * leci na komputer po WebSocketcie.
     */
    fun submitCodes(handoffToken: String, codes: List<String>): ScanSession {
        val session = resolveByToken(handoffToken)
        if (session.status != "OPEN") throw ValidationException("Sesja skanowania jest już zamknięta.")
        enforceRateLimit(session.sessionId, codes.size)

        val existing = session.scannedCodes.map { it.code }.toMutableSet()
        val additions = mutableListOf<ScannedCode>()
        for (raw in codes) {
            val gtin = Gtin.parseOrNull(raw) ?: continue   // śmieci pomijamy po cichu
            if (existing.add(gtin.value)) {
                additions += ScannedCode(code = gtin.value, scannedAt = Instant.now().toString())
            }
        }
        if (additions.isEmpty()) return session

        val updated = session.copy(scannedCodes = session.scannedCodes + additions)
        persist(updated)
        notifyDesktop(updated)
        log.info("[PRODUCT_SCAN] Telefon dosłał {} kod(y) [session={}]", additions.size, session.sessionId)
        return updated
    }

    fun close(studioId: StudioId, sessionId: String) {
        val session = getForStudio(studioId, sessionId)
        persist(session.copy(status = "CONSUMED"))
        redisTemplate.delete(TOKEN_KEY + session.handoffToken)
    }

    // ── helpers ──
    private fun notifyDesktop(session: ScanSession) {
        try {
            val destination = "/topic/studio.${session.studioId}.product-scan.${session.sessionId}"
            messagingTemplate.convertAndSend(destination, session)
        } catch (e: Exception) {
            // WebSocket to wygoda; odpytywanie GET jest zapasem. Brak pushu nie może
            // wywrócić dopisania kodu.
            log.warn("[PRODUCT_SCAN] Push po WebSocketcie nie powiódł się [session={}]: {}", session.sessionId, e.message)
        }
    }

    private fun enforceRateLimit(sessionId: String, count: Int) {
        try {
            val key = RATE_KEY + sessionId
            val current = redisTemplate.opsForValue().increment(key, count.toLong()) ?: count.toLong()
            if (current == count.toLong()) redisTemplate.expire(key, Duration.ofMinutes(1))
            if (current > maxCodesPerMinute) {
                throw ValidationException("Za dużo kodów w krótkim czasie — zwolnij tempo skanowania.")
            }
        } catch (e: ValidationException) {
            throw e
        } catch (e: Exception) {
            log.debug("[PRODUCT_SCAN] Rate-limit pominięty (Redis): {}", e.message)
        }
    }

    private fun persist(session: ScanSession) {
        val ttl = Duration.ofMinutes(ttlMinutes)
        val json = objectMapper.writeValueAsString(session)
        redisTemplate.opsForValue().set(SESSION_KEY + session.sessionId, json, ttl)
        if (session.status == "OPEN") {
            redisTemplate.opsForValue().set(TOKEN_KEY + session.handoffToken, session.sessionId, ttl)
        }
    }

    private fun read(sessionId: String): ScanSession? =
        redisTemplate.opsForValue().get(SESSION_KEY + sessionId)?.let {
            runCatching { objectMapper.readValue<ScanSession>(it) }.getOrNull()
        }

    private fun generateToken(): String {
        val bytes = ByteArray(32)
        secureRandom.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}

data class ScanSession(
    val sessionId: String,
    val studioId: String,
    val createdBy: String,
    val handoffToken: String,
    val status: String,
    val scannedCodes: List<ScannedCode>,
    val createdAt: String,
    val expiresAt: String
)

data class ScannedCode(
    val code: String,
    val scannedAt: String
)
