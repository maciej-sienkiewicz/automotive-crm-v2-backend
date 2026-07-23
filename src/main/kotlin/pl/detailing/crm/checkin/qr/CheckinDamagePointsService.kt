package pl.detailing.crm.checkin.qr

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.Instant

/**
 * Stores and retrieves mobile damage points in Redis for the duration of a check-in session.
 *
 * After saving, publishes to [REDIS_DAMAGE_UPDATED_CHANNEL] so that
 * [CheckinDamageUpdatedMessageListener] can bridge the event to WebSocket subscribers.
 *
 * Redis key: checkin:damage-points:{tenantId}:{checkinId}  (TTL matches upload token)
 */
@Service
class CheckinDamagePointsService(
    private val redisTemplate: StringRedisTemplate,
    private val objectMapper: ObjectMapper,
    @Value("\${checkin.upload-token-ttl-hours:3}") private val ttlHours: Long
) {
    companion object {
        private val logger = LoggerFactory.getLogger(CheckinDamagePointsService::class.java)
        private const val DAMAGE_POINTS_KEY_PREFIX = "checkin:damage-points:"
        const val REDIS_DAMAGE_UPDATED_CHANNEL = "checkin:damage-updated"
    }

    fun saveDamagePoints(
        tenantId: String,
        checkinId: String,
        damagePoints: List<DamagePointData>,
        vehicleType: String? = null
    ): DamagePointsResult {
        val savedAt = Instant.now()
        val stored = StoredDamagePoints(
            checkinId = checkinId,
            tenantId = tenantId,
            damagePoints = damagePoints,
            vehicleType = vehicleType,
            savedAt = savedAt
        )
        val json = objectMapper.writeValueAsString(stored)
        redisTemplate.opsForValue().set(
            "$DAMAGE_POINTS_KEY_PREFIX$tenantId:$checkinId",
            json,
            Duration.ofHours(ttlHours)
        )

        val pubSubPayload = objectMapper.writeValueAsString(
            mapOf(
                "tenantId" to tenantId,
                "checkinId" to checkinId,
                "damagePoints" to damagePoints,
                "vehicleType" to vehicleType,
                "updatedAt" to savedAt.toString()
            )
        )
        redisTemplate.convertAndSend(REDIS_DAMAGE_UPDATED_CHANNEL, pubSubPayload)

        logger.info("Saved ${damagePoints.size} damage points for checkin=$checkinId tenant=$tenantId vehicleType=$vehicleType")
        return DamagePointsResult(
            checkinId = checkinId,
            damagePoints = damagePoints,
            vehicleType = vehicleType,
            savedAt = savedAt
        )
    }

    fun getDamagePoints(tenantId: String, checkinId: String): DamagePointsResult {
        val json = redisTemplate.opsForValue().get("$DAMAGE_POINTS_KEY_PREFIX$tenantId:$checkinId")
            ?: return DamagePointsResult(checkinId = checkinId, damagePoints = emptyList(), vehicleType = null, savedAt = null)
        return try {
            val stored = objectMapper.readValue(json, StoredDamagePoints::class.java)
            DamagePointsResult(
                checkinId = stored.checkinId,
                damagePoints = stored.damagePoints,
                vehicleType = stored.vehicleType,
                savedAt = stored.savedAt
            )
        } catch (e: Exception) {
            logger.warn("Failed to deserialize damage points for checkin=$checkinId: ${e.message}")
            DamagePointsResult(checkinId = checkinId, damagePoints = emptyList(), vehicleType = null, savedAt = null)
        }
    }
}

data class DamagePointData(
    val id: Int = 0,
    val x: Double = 0.0,
    val y: Double = 0.0,
    val note: String? = null,
    val photos: List<DamagePointPhotoData> = emptyList()
)

/**
 * Photo attached to a damage point during a mobile check-in session.
 *
 * @param photoId ID of the QR-uploaded photo
 * @param s3Key   resolved S3 key of the photo in temp storage (stable, unlike presigned URLs);
 *                used to re-generate thumbnails and to embed the photo in the damage report
 */
data class DamagePointPhotoData(
    val photoId: String = "",
    val s3Key: String? = null,
    val strokes: List<AnnotationStrokeData> = emptyList()
)

data class AnnotationStrokeData(
    val color: String = "#EF4444",
    val width: Double = 1.0,
    val points: List<AnnotationPointData> = emptyList()
)

data class AnnotationPointData(
    val x: Double = 0.0,
    val y: Double = 0.0
)

data class StoredDamagePoints(
    val checkinId: String = "",
    val tenantId: String = "",
    val damagePoints: List<DamagePointData> = emptyList(),
    /** Vehicle body type the points were placed on (sedan, suv, ...) */
    val vehicleType: String? = null,
    val savedAt: Instant = Instant.now()
)

data class DamagePointsResult(
    val checkinId: String,
    val damagePoints: List<DamagePointData>,
    val vehicleType: String?,
    val savedAt: Instant?
)
