package pl.detailing.crm.signing.infrastructure

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID

/**
 * Parowanie tabletów do podpisu.
 *
 * Sparowane urządzenie mieszka w bazie, nie w cache'u. Wcześniej token żył wyłącznie
 * w Redisie, który jest tu uruchomiony bez trwałego magazynu — każdy restart cache'a,
 * czyli każde wdrożenie, po cichu rozparowywał wszystkie tablety we wszystkich
 * studiach i ktoś musiał podejść do urządzenia i wpisać nowy kod. Sparowane
 * urządzenie to nie wpis w cache'u, tylko fakt o sprzęcie studia: ma przeżyć restart
 * infrastruktury, a kończyć się wyłącznie wtedy, gdy ktoś je odłączy.
 *
 * W Redisie zostaje KOD PAROWANIA i tylko on: żyje pięć minut, a jego utrata kosztuje
 * tyle, co wygenerowanie następnego.
 *
 * Przepływ:
 * 1. Pracownik (zalogowany w CRM) generuje sześciocyfrowy kod.
 * 2. Aplikacja na tablecie wymienia kod na token urządzenia — bezterminowy.
 * 3. Endpointy tabletowe uwierzytelnia wyłącznie nagłówek X-Tablet-Token; przypisanie
 *    do studia czytamy z rekordu urządzenia, nigdy z danych przysłanych przez klienta.
 *
 * Token trzymamy jako skrót SHA-256. To poświadczenie na okaziciela — kto je odczyta,
 * ten podpisuje dokumenty jako tablet tego studia — więc wyciek zrzutu bazy nie może
 * oddawać działających urządzeń. Sól jest tu zbędna: token to 32 bajty z CSPRNG,
 * nie ma czego zgadywać ani przewidywać.
 */
@Service
class TabletSessionService(
    private val tabletRepository: SigningTabletRepository,
    private val redisTemplate: StringRedisTemplate,
    private val objectMapper: ObjectMapper,
    @Value("\${signing.tablet.pairing-code-ttl-minutes:5}") private val pairingCodeTtlMinutes: Long
) {
    companion object {
        private val logger = LoggerFactory.getLogger(TabletSessionService::class.java)
        private val SECURE_RANDOM = SecureRandom()
        private const val PAIRING_KEY_PREFIX = "signing:pairing-code:"

        /** Klucze sprzed przeniesienia parowania do bazy — czytane tylko przy migracji. */
        private const val LEGACY_TOKEN_KEY_PREFIX = "signing:tablet-token:"
        private const val LEGACY_DEVICE_KEY_PREFIX = "signing:tablet-device:"

        /** Jak często odnotowujemy kontakt z urządzeniem. Rzadziej niż co żądanie. */
        private val LAST_SEEN_RESOLUTION = Duration.ofHours(1)
    }

    /** Jednorazowy kod parowania pokazywany pracownikowi w CRM. */
    fun generatePairingCode(tenantId: String, userId: String): GeneratedPairingCode {
        val code = "%06d".format(SECURE_RANDOM.nextInt(1_000_000))
        val ttl = Duration.ofMinutes(pairingCodeTtlMinutes)
        val payload = objectMapper.writeValueAsString(
            mapOf("tenantId" to tenantId, "createdBy" to userId)
        )
        redisTemplate.opsForValue().set(PAIRING_KEY_PREFIX + code, payload, ttl)
        return GeneratedPairingCode(code = code, expiresAt = Instant.now().plus(ttl))
    }

    /**
     * Wymiana kodu parowania na token urządzenia. Kod jest jednorazowy: kasujemy go
     * atomowo, żeby podejrzany kod nie dał się użyć drugi raz.
     */
    @Transactional
    fun pairTablet(pairingCode: String, deviceName: String): PairedTablet? {
        val json = redisTemplate.opsForValue().getAndDelete(PAIRING_KEY_PREFIX + pairingCode)
            ?: return null

        @Suppress("UNCHECKED_CAST")
        val data = objectMapper.readValue(json, Map::class.java) as Map<String, String>
        val tenantId = data["tenantId"] ?: return null

        val tabletId = UUID.randomUUID()
        val token = generateSecureToken()
        tabletRepository.save(
            SigningTabletEntity(
                id = tabletId,
                studioId = UUID.fromString(tenantId),
                deviceName = deviceName.take(200),
                tokenHash = hashToken(token)
            )
        )

        logger.info("Sparowano tablet '{}' (id={}) ze studiem {}", deviceName.take(200), tabletId, tenantId)
        return PairedTablet(tabletId = tabletId.toString(), token = token, tenantId = tenantId)
    }

    /**
     * Sprawdzenie tokenu urządzenia. Bezterminowo — dopóki nikt go nie odłączył.
     * Tokeny wydane przed przeniesieniem parowania do bazy przenosimy tu w locie,
     * więc urządzenia sparowane wcześniej działają dalej bez ponownego parowania
     * (o ile ich wpis jeszcze jest w cache'u).
     */
    @Transactional
    fun validateToken(token: String): TabletSession? {
        val tablet = tabletRepository.findByTokenHash(hashToken(token))
            ?: return migrateLegacyToken(token)

        if (tablet.revokedAt != null) return null

        val lastSeen = tablet.lastSeenAt
        val now = Instant.now()
        if (lastSeen == null || Duration.between(lastSeen, now) > LAST_SEEN_RESOLUTION) {
            tabletRepository.touchLastSeen(tablet.id, now)
        }

        return TabletSession(
            tenantId = tablet.studioId.toString(),
            tabletId = tablet.id.toString(),
            deviceName = tablet.deviceName,
            pairedAt = tablet.pairedAt
        )
    }

    /** Wszystkie urządzenia sparowane z tym studiem. */
    fun listTablets(tenantId: String): List<TabletInfo> =
        tabletRepository.findByStudioIdAndRevokedAtIsNullOrderByPairedAtAsc(UUID.fromString(tenantId))
            .map {
                TabletInfo(
                    tabletId = it.id.toString(),
                    deviceName = it.deviceName,
                    pairedAt = it.pairedAt,
                    lastSeenAt = it.lastSeenAt
                )
            }

    /** Odłączenie urządzenia (zgubione, wymienione). Jedyny sposób, w jaki kończy się parowanie. */
    @Transactional
    fun revokeTablet(tenantId: String, tabletId: String) {
        val studioId = UUID.fromString(tenantId)
        val id = runCatching { UUID.fromString(tabletId) }.getOrNull() ?: return
        val tablet = tabletRepository.findByIdAndStudioId(id, studioId) ?: return
        tablet.revokedAt = Instant.now()
        tabletRepository.save(tablet)

        // Sprzątamy też ewentualny wpis sprzed przeniesienia, żeby stary token
        // nie ożył przez ścieżkę migracyjną.
        redisTemplate.delete("$LEGACY_DEVICE_KEY_PREFIX$tenantId:$tabletId")
        logger.info("Odłączono tablet {} od studia {}", tabletId, tenantId)
    }

    /**
     * Przeniesienie tokenu wydanego przed zmianą magazynu. Wykonuje się najwyżej raz
     * na urządzenie — przy pierwszym żądaniu po wdrożeniu — i po nim tablet jest już
     * zapisany trwale.
     */
    private fun migrateLegacyToken(token: String): TabletSession? {
        val json = redisTemplate.opsForValue().get(LEGACY_TOKEN_KEY_PREFIX + token) ?: return null
        val session = try {
            objectMapper.readValue(json, TabletSession::class.java)
        } catch (e: Exception) {
            logger.warn("Pominięto niepoprawny wpis tabletu z cache'u: {}", e.message)
            return null
        }

        val tabletId = runCatching { UUID.fromString(session.tabletId) }.getOrNull() ?: UUID.randomUUID()
        tabletRepository.save(
            SigningTabletEntity(
                id = tabletId,
                studioId = UUID.fromString(session.tenantId),
                deviceName = session.deviceName,
                tokenHash = hashToken(token),
                pairedAt = session.pairedAt,
                lastSeenAt = Instant.now()
            )
        )
        redisTemplate.delete(LEGACY_TOKEN_KEY_PREFIX + token)
        redisTemplate.delete("$LEGACY_DEVICE_KEY_PREFIX${session.tenantId}:${session.tabletId}")

        logger.info("Przeniesiono parowanie tabletu {} do bazy — działa dalej bez ponownego parowania", tabletId)
        return session.copy(tabletId = tabletId.toString())
    }

    private fun hashToken(token: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(token.toByteArray())
            .joinToString("") { "%02x".format(it) }

    private fun generateSecureToken(): String {
        val bytes = ByteArray(32)
        SECURE_RANDOM.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}

data class TabletSession(
    val tenantId: String,
    val tabletId: String,
    val deviceName: String,
    val pairedAt: Instant
)

/**
 * STOMP principal for a paired signing tablet.
 *
 * Tablets have no HTTP session — they authenticate the WebSocket CONNECT frame with
 * the X-Tablet-Token native header, which [pl.detailing.crm.config.WebSocketSecurityInterceptor]
 * exchanges for this principal. Subscriptions are restricted to the studio's tablet topic.
 */
data class TabletPrincipal(
    val tenantId: String,
    val tabletId: String,
    val deviceName: String
) : java.security.Principal {
    override fun getName(): String = "tablet:$tabletId"
}

data class TabletInfo(
    val tabletId: String,
    val deviceName: String,
    val pairedAt: Instant,
    /** Ostatnie żądanie z urządzenia; null, gdy nie odezwało się od sparowania. */
    val lastSeenAt: Instant?
)

data class GeneratedPairingCode(
    val code: String,
    val expiresAt: Instant
)

data class PairedTablet(
    val tabletId: String,
    val token: String,
    val tenantId: String
)
