package pl.detailing.crm.checkin.qr

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Service
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.Base64

/**
 * Manages temporary upload context tokens stored in Redis.
 *
 * Token lifecycle:
 * - Generated per checkinId (appointmentId), valid for 3 hours
 * - Multi-use: the same token can be used for many photo uploads within TTL
 * - Single-purpose: bound exclusively to one checkinId + tenantId pair
 *
 * Redis keys:
 * - checkin:upload-token:{token}              → JSON metadata (TTL = 3h)
 * - checkin:upload-context:{tenantId}:{checkinId} → token string (TTL = 3h)
 */
@Service
class UploadContextTokenService(
    private val redisTemplate: StringRedisTemplate,
    private val objectMapper: ObjectMapper,
    @Value("\${checkin.upload-token-ttl-hours:3}") private val ttlHours: Long
) {
    companion object {
        private val logger = LoggerFactory.getLogger(UploadContextTokenService::class.java)
        private val SECURE_RANDOM = SecureRandom()
        private const val TOKEN_KEY_PREFIX = "checkin:upload-token:"
        private const val CONTEXT_KEY_PREFIX = "checkin:upload-context:"
    }

    /**
     * Generate a new upload token for the given checkin.
     * If a token already exists for this checkin it is replaced with a fresh one.
     */
    fun generateToken(tenantId: String, checkinId: String, userId: String): GeneratedUploadToken {
        val token = generateSecureToken()
        val expiresAt = Instant.now().plusSeconds(ttlHours * 3600)
        val ttl = Duration.ofHours(ttlHours)

        val metadata = UploadContextMetadata(
            tenantId = tenantId,
            checkinId = checkinId,
            userId = userId,
            createdAt = Instant.now()
        )

        val json = objectMapper.writeValueAsString(metadata)

        // token → metadata (main lookup used by mobile)
        redisTemplate.opsForValue().set(TOKEN_KEY_PREFIX + token, json, ttl)

        // tenantId+checkinId → token (reverse lookup used at finalization)
        redisTemplate.opsForValue().set(
            "$CONTEXT_KEY_PREFIX$tenantId:$checkinId",
            token,
            ttl
        )

        logger.info("Generated upload token for checkin=$checkinId tenant=$tenantId expires=$expiresAt")
        return GeneratedUploadToken(token = token, expiresAt = expiresAt)
    }

    /**
     * Validate an upload token and return its metadata, or null if invalid/expired.
     */
    fun validateToken(token: String): UploadContextMetadata? {
        val json = redisTemplate.opsForValue().get(TOKEN_KEY_PREFIX + token) ?: return null
        return try {
            objectMapper.readValue(json, UploadContextMetadata::class.java)
        } catch (e: Exception) {
            logger.warn("Failed to deserialize upload token metadata: ${e.message}")
            null
        }
    }

    /**
     * Look up the active token for a given tenant+checkin combination.
     * Used during finalization to verify that an upload context existed.
     */
    fun getTokenForCheckin(tenantId: String, checkinId: String): String? =
        redisTemplate.opsForValue().get("$CONTEXT_KEY_PREFIX$tenantId:$checkinId")

    private fun generateSecureToken(): String {
        val bytes = ByteArray(32)
        SECURE_RANDOM.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}

data class UploadContextMetadata(
    val tenantId: String,
    val checkinId: String,
    val userId: String,
    val createdAt: Instant = Instant.now()
)

data class GeneratedUploadToken(
    val token: String,
    val expiresAt: Instant
)
