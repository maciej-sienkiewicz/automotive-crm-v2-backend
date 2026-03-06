package pl.detailing.crm.checkin.qr

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.data.redis.connection.Message
import org.springframework.data.redis.connection.MessageListener
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.stereotype.Component
import java.time.Instant

/**
 * Bridges Redis Pub/Sub → WebSocket STOMP.
 *
 * Subscribes to the "checkin:photo-uploaded" Redis channel.
 * On every received message it pushes a STOMP message to:
 *   /topic/studio.{tenantId}.checkin.{checkinId}
 *
 * This design supports horizontal scaling: even if the mobile upload
 * lands on a different server instance than the one holding the PC's
 * WebSocket connection, the notification still reaches the browser
 * through Redis Pub/Sub.
 */
@Component
class CheckinPhotoUploadedMessageListener(
    private val messagingTemplate: SimpMessagingTemplate,
    private val objectMapper: ObjectMapper
) : MessageListener {

    private val logger = LoggerFactory.getLogger(CheckinPhotoUploadedMessageListener::class.java)

    override fun onMessage(message: Message, pattern: ByteArray?) {
        try {
            val body = message.body.toString(Charsets.UTF_8)

            @Suppress("UNCHECKED_CAST")
            val payload = objectMapper.readValue(body, Map::class.java) as Map<String, Any?>

            val tenantId = payload["tenantId"] as? String ?: run {
                logger.warn("Missing tenantId in photo-uploaded Redis message")
                return
            }
            val checkinId = payload["checkinId"] as? String ?: run {
                logger.warn("Missing checkinId in photo-uploaded Redis message")
                return
            }
            val photoId = payload["photoId"] as? String ?: ""
            val fileName = payload["fileName"] as? String ?: ""
            val thumbnailUrl = payload["thumbnailUrl"] as? String ?: ""

            val wsMessage = CheckinPhotoUploadedWsMessage(
                type = "CHECKIN_PHOTO_UPLOADED",
                checkinId = checkinId,
                photoId = photoId,
                fileName = fileName,
                thumbnailUrl = thumbnailUrl,
                timestamp = Instant.now()
            )

            val destination = "/topic/studio.$tenantId.checkin.$checkinId"
            messagingTemplate.convertAndSend(destination, wsMessage)

            logger.debug(
                "Forwarded photo-uploaded event to WebSocket: dest={} photoId={} checkin={}",
                destination, photoId, checkinId
            )
        } catch (e: Exception) {
            logger.error("Error processing photo-uploaded Redis message: ${e.message}", e)
        }
    }
}

data class CheckinPhotoUploadedWsMessage(
    val type: String,
    val checkinId: String,
    val photoId: String,
    val fileName: String,
    val thumbnailUrl: String,
    val timestamp: Instant
)
