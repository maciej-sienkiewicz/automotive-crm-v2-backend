package pl.detailing.crm.checkin.qr

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.data.redis.connection.Message
import org.springframework.data.redis.connection.MessageListener
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.stereotype.Component
import java.time.Instant

/**
 * Bridges Redis Pub/Sub → WebSocket STOMP for damage point updates.
 *
 * Subscribes to the [CheckinDamagePointsService.REDIS_DAMAGE_UPDATED_CHANNEL] Redis channel.
 * On each message, pushes a STOMP message to:
 *   /topic/studio.{tenantId}.checkin.{checkinId}
 *
 * This keeps the desktop check-in wizard in sync with changes made on mobile
 * without requiring a page reload.
 */
@Component
class CheckinDamageUpdatedMessageListener(
    private val messagingTemplate: SimpMessagingTemplate,
    private val objectMapper: ObjectMapper,
    private val checkinPhotoService: CheckinPhotoService
) : MessageListener {

    private val logger = LoggerFactory.getLogger(CheckinDamageUpdatedMessageListener::class.java)

    override fun onMessage(message: Message, pattern: ByteArray?) {
        try {
            val body = message.body.toString(Charsets.UTF_8)

            @Suppress("UNCHECKED_CAST")
            val payload = objectMapper.readValue(body, Map::class.java) as Map<String, Any?>

            val tenantId = payload["tenantId"] as? String ?: run {
                logger.warn("Missing tenantId in damage-updated Redis message")
                return
            }
            val checkinId = payload["checkinId"] as? String ?: run {
                logger.warn("Missing checkinId in damage-updated Redis message")
                return
            }

            @Suppress("UNCHECKED_CAST")
            val rawPoints = payload["damagePoints"] as? List<Map<String, Any?>> ?: emptyList()
            val damagePoints = rawPoints.map { p ->
                @Suppress("UNCHECKED_CAST")
                val rawPhotos = p["photos"] as? List<Map<String, Any?>> ?: emptyList()
                DamagePointWsItem(
                    id = (p["id"] as? Number)?.toInt() ?: 0,
                    x = (p["x"] as? Number)?.toDouble() ?: 0.0,
                    y = (p["y"] as? Number)?.toDouble() ?: 0.0,
                    note = p["note"] as? String,
                    photos = rawPhotos.map { photo ->
                        @Suppress("UNCHECKED_CAST")
                        val rawStrokes = photo["strokes"] as? List<Map<String, Any?>> ?: emptyList()
                        DamagePhotoWsItem(
                            photoId = photo["photoId"] as? String ?: "",
                            thumbnailUrl = (photo["s3Key"] as? String)?.let { key ->
                                runCatching { checkinPhotoService.generateDownloadUrl(key) }.getOrNull()
                            },
                            strokes = rawStrokes.map { stroke ->
                                @Suppress("UNCHECKED_CAST")
                                val rawStrokePoints = stroke["points"] as? List<Map<String, Any?>> ?: emptyList()
                                AnnotationStrokeWsItem(
                                    color = stroke["color"] as? String ?: "#EF4444",
                                    width = (stroke["width"] as? Number)?.toDouble() ?: 1.0,
                                    points = rawStrokePoints.map { sp ->
                                        AnnotationPointWsItem(
                                            x = (sp["x"] as? Number)?.toDouble() ?: 0.0,
                                            y = (sp["y"] as? Number)?.toDouble() ?: 0.0
                                        )
                                    }
                                )
                            }
                        )
                    }
                )
            }

            val updatedAtRaw = payload["updatedAt"] as? String
            val updatedAt = updatedAtRaw?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: Instant.now()

            val wsMessage = CheckinDamageUpdatedWsMessage(
                type = "CHECKIN_DAMAGE_UPDATED",
                checkinId = checkinId,
                damagePoints = damagePoints,
                updatedAt = updatedAt
            )

            val destination = "/topic/studio.$tenantId.checkin.$checkinId"
            messagingTemplate.convertAndSend(destination, wsMessage)

            logger.debug(
                "Forwarded damage-updated event to WebSocket: dest={} points={} checkin={}",
                destination, damagePoints.size, checkinId
            )
        } catch (e: Exception) {
            logger.error("Error processing damage-updated Redis message: ${e.message}", e)
        }
    }
}

data class DamagePointWsItem(
    val id: Int,
    val x: Double,
    val y: Double,
    val note: String?,
    val photos: List<DamagePhotoWsItem> = emptyList()
)

data class DamagePhotoWsItem(
    val photoId: String,
    val thumbnailUrl: String?,
    val strokes: List<AnnotationStrokeWsItem> = emptyList()
)

data class AnnotationStrokeWsItem(
    val color: String,
    val width: Double,
    val points: List<AnnotationPointWsItem> = emptyList()
)

data class AnnotationPointWsItem(
    val x: Double,
    val y: Double
)

data class CheckinDamageUpdatedWsMessage(
    val type: String,
    val checkinId: String,
    val damagePoints: List<DamagePointWsItem>,
    val updatedAt: Instant
)
