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
 * - checkin:mobile-done:{token}              → "true" when user clicked Gotowe (TTL = 3h)
 * - checkin:visit-created:{token}            → "true" when visit was saved on desktop (TTL = 3h)
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
        private const val DONE_KEY_PREFIX = "checkin:mobile-done:"
        private const val VISIT_CREATED_KEY_PREFIX = "checkin:visit-created:"
    }

    /**
     * Generate a new upload token for the given checkin.
     * If a token already exists for this checkin it is revoked first so the old QR
     * code stops working immediately instead of remaining valid for its original TTL.
     */
    fun generateToken(tenantId: String, checkinId: String, userId: String): GeneratedUploadToken {
        // Revoke previous token so the old QR is no longer usable
        val oldToken = redisTemplate.opsForValue().get("$CONTEXT_KEY_PREFIX$tenantId:$checkinId")
        if (oldToken != null) {
            redisTemplate.delete(TOKEN_KEY_PREFIX + oldToken)
        }

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

    /**
     * Mark that the mobile user has clicked "Gotowe" (done reviewing).
     * Stored independently so the status endpoint can return userDone even after token revocation.
     */
    fun markUserDone(token: String) {
        redisTemplate.opsForValue().set(DONE_KEY_PREFIX + token, "true", Duration.ofHours(ttlHours))
        logger.info("Mobile user marked done for token prefix=${token.take(8)}")
    }

    /**
     * Called when a visit is created on desktop for this checkin.
     * Records the visitCreated flag (indexed by token) then revokes the upload token
     * so further photo uploads are rejected while status polling still works.
     */
    fun markVisitCreated(tenantId: String, checkinId: String) {
        val token = getTokenForCheckin(tenantId, checkinId) ?: return
        redisTemplate.opsForValue().set(VISIT_CREATED_KEY_PREFIX + token, "true", Duration.ofHours(ttlHours))
        redisTemplate.delete(TOKEN_KEY_PREFIX + token)
        logger.info("Visit created for checkin=$checkinId tenant=$tenantId, upload token revoked")
    }

    /**
     * Return the current status of the mobile session identified by [token].
     * Safe to call even after the upload token has been revoked.
     */
    fun getStatus(token: String): MobileSessionStatus {
        val sessionActive = redisTemplate.hasKey(TOKEN_KEY_PREFIX + token) == true
        val visitCreated = redisTemplate.hasKey(VISIT_CREATED_KEY_PREFIX + token) == true
        val userDone = redisTemplate.hasKey(DONE_KEY_PREFIX + token) == true
        return MobileSessionStatus(sessionActive = sessionActive, visitCreated = visitCreated, userDone = userDone)
    }

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

data class MobileSessionStatus(
    val sessionActive: Boolean,
    val visitCreated: Boolean,
    val userDone: Boolean
)
