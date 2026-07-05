package pl.detailing.crm.signing.infrastructure

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Service
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID

/**
 * Manages tablet pairing and device tokens in Redis (same pattern as the mobile
 * QR upload tokens — see UploadContextTokenService).
 *
 * Pairing flow:
 * 1. Employee (authenticated CRM session) generates a short-lived 6-digit pairing code.
 * 2. The tablet app exchanges the code for a long-lived device token.
 * 3. All tablet endpoints authenticate exclusively via the X-Tablet-Token header;
 *    tenant isolation is enforced by reading tenantId ONLY from Redis metadata.
 *
 * Redis keys:
 * - signing:pairing-code:{code}                → JSON {tenantId, createdBy}   (TTL = 5 min)
 * - signing:tablet-token:{token}               → JSON TabletSession           (TTL = 30 days, refreshed on use)
 * - signing:tablet-device:{tenantId}:{tabletId}→ token                        (reverse lookup / listing)
 */
@Service
class TabletSessionService(
    private val redisTemplate: StringRedisTemplate,
    private val objectMapper: ObjectMapper,
    @Value("\${signing.tablet.pairing-code-ttl-minutes:5}") private val pairingCodeTtlMinutes: Long,
    @Value("\${signing.tablet.token-ttl-days:30}") private val tokenTtlDays: Long
) {
    companion object {
        private val logger = LoggerFactory.getLogger(TabletSessionService::class.java)
        private val SECURE_RANDOM = SecureRandom()
        private const val PAIRING_KEY_PREFIX = "signing:pairing-code:"
        private const val TOKEN_KEY_PREFIX = "signing:tablet-token:"
        private const val DEVICE_KEY_PREFIX = "signing:tablet-device:"
    }

    /** Generate a one-time pairing code shown to the employee in the CRM. */
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
     * Exchange a pairing code for a device token. The code is single-use:
     * it is deleted atomically so a captured code cannot be reused.
     */
    fun pairTablet(pairingCode: String, deviceName: String): PairedTablet? {
        val json = redisTemplate.opsForValue().getAndDelete(PAIRING_KEY_PREFIX + pairingCode)
            ?: return null

        @Suppress("UNCHECKED_CAST")
        val data = objectMapper.readValue(json, Map::class.java) as Map<String, String>
        val tenantId = data["tenantId"] ?: return null

        val tabletId = UUID.randomUUID().toString()
        val token = generateSecureToken()
        val session = TabletSession(
            tenantId = tenantId,
            tabletId = tabletId,
            deviceName = deviceName.take(200),
            pairedAt = Instant.now()
        )

        val ttl = Duration.ofDays(tokenTtlDays)
        redisTemplate.opsForValue().set(TOKEN_KEY_PREFIX + token, objectMapper.writeValueAsString(session), ttl)
        redisTemplate.opsForValue().set("$DEVICE_KEY_PREFIX$tenantId:$tabletId", token, ttl)

        logger.info("Paired tablet '{}' (id={}) for tenant={}", session.deviceName, tabletId, tenantId)
        return PairedTablet(tabletId = tabletId, token = token, tenantId = tenantId)
    }

    /** Validate a tablet token; refreshes the TTL on every successful use. */
    fun validateToken(token: String): TabletSession? {
        val key = TOKEN_KEY_PREFIX + token
        val json = redisTemplate.opsForValue().get(key) ?: return null
        return try {
            val session = objectMapper.readValue(json, TabletSession::class.java)
            val ttl = Duration.ofDays(tokenTtlDays)
            redisTemplate.expire(key, ttl)
            redisTemplate.expire("$DEVICE_KEY_PREFIX${session.tenantId}:${session.tabletId}", ttl)
            session
        } catch (e: Exception) {
            logger.warn("Failed to deserialize tablet session: ${e.message}")
            null
        }
    }

    /**
     * List all paired tablets for a tenant. Scans the device reverse-lookup keys
     * (signing:tablet-device:{tenantId}:*) and joins with session data from token keys.
     * Tablets whose token has already expired are excluded automatically.
     */
    fun listTablets(tenantId: String): List<TabletInfo> {
        val pattern = "$DEVICE_KEY_PREFIX$tenantId:*"
        val deviceKeys = redisTemplate.keys(pattern) ?: return emptyList()

        return deviceKeys.mapNotNull { deviceKey ->
            try {
                val token = redisTemplate.opsForValue().get(deviceKey) ?: return@mapNotNull null
                val sessionJson = redisTemplate.opsForValue().get(TOKEN_KEY_PREFIX + token) ?: return@mapNotNull null
                val session = objectMapper.readValue(sessionJson, TabletSession::class.java)
                val ttlSeconds = redisTemplate.getExpire(TOKEN_KEY_PREFIX + token)
                val tokenExpiresAt = if (ttlSeconds != null && ttlSeconds > 0)
                    java.time.Instant.now().plusSeconds(ttlSeconds)
                else
                    null
                TabletInfo(
                    tabletId = session.tabletId,
                    deviceName = session.deviceName,
                    pairedAt = session.pairedAt,
                    tokenExpiresAt = tokenExpiresAt
                )
            } catch (e: Exception) {
                logger.warn("Skipping malformed tablet entry for key {}: {}", deviceKey, e.message)
                null
            }
        }.sortedBy { it.pairedAt }
    }

    /** Revoke a paired tablet (e.g. lost device). */
    fun revokeTablet(tenantId: String, tabletId: String) {
        val deviceKey = "$DEVICE_KEY_PREFIX$tenantId:$tabletId"
        val token = redisTemplate.opsForValue().get(deviceKey)
        if (token != null) {
            redisTemplate.delete(TOKEN_KEY_PREFIX + token)
        }
        redisTemplate.delete(deviceKey)
        logger.info("Revoked tablet {} for tenant {}", tabletId, tenantId)
    }

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
    val tokenExpiresAt: Instant?
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
