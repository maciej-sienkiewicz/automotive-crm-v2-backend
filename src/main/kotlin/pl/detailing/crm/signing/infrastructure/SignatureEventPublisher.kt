package pl.detailing.crm.signing.infrastructure

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.data.redis.connection.Message
import org.springframework.data.redis.connection.MessageListener
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import java.time.Instant

/**
 * Publishes signature-request lifecycle events over Redis Pub/Sub so that every
 * application instance can forward them to its own WebSocket clients
 * (same multi-instance pattern as the check-in QR photo events).
 *
 * STOMP destinations:
 *  - /topic/studio.{tenantId}.tablet.signature            → tablets ("a document is waiting")
 *  - /topic/studio.{tenantId}.signature.{requestId}       → CRM desktop (status updates)
 */
@Service
class SignatureEventPublisher(
    private val redisTemplate: StringRedisTemplate,
    private val objectMapper: ObjectMapper
) {
    companion object {
        const val REDIS_CHANNEL = "signing:request-events"
    }

    fun publish(
        tenantId: String,
        requestId: String,
        eventType: String,
        tabletId: String? = null,
        documentName: String? = null,
        signerName: String? = null,
        status: String? = null
    ) {
        val payload = objectMapper.writeValueAsString(
            mapOf(
                "tenantId" to tenantId,
                "requestId" to requestId,
                "eventType" to eventType,
                "tabletId" to tabletId,
                "documentName" to documentName,
                "signerName" to signerName,
                "status" to status,
                "occurredAt" to Instant.now().toString()
            )
        )
        redisTemplate.convertAndSend(REDIS_CHANNEL, payload)
    }

    /**
     * Publish a tablet management event (TABLET_PAIRED / TABLET_REVOKED).
     * These are routed exclusively to /topic/studio.{tenantId}.tablets (CRM desktop),
     * not to the tablet's own signature topic.
     */
    fun publishTabletEvent(tenantId: String, tabletId: String, deviceName: String, eventType: String) {
        val payload = objectMapper.writeValueAsString(
            mapOf(
                "tenantId" to tenantId,
                "eventType" to eventType,
                "tabletId" to tabletId,
                "deviceName" to deviceName,
                "occurredAt" to Instant.now().toString()
            )
        )
        redisTemplate.convertAndSend(REDIS_CHANNEL, payload)
    }
}

/**
 * Bridges Redis Pub/Sub → WebSocket STOMP for signature request events.
 * Registered on [SignatureEventPublisher.REDIS_CHANNEL] in RedisConfig.
 */
@Component
class SignatureRequestMessageListener(
    private val messagingTemplate: SimpMessagingTemplate,
    private val objectMapper: ObjectMapper
) : MessageListener {

    private val logger = LoggerFactory.getLogger(SignatureRequestMessageListener::class.java)

    private val tabletManagementEvents = setOf("TABLET_PAIRED", "TABLET_REVOKED")

    override fun onMessage(message: Message, pattern: ByteArray?) {
        try {
            val body = message.body.toString(Charsets.UTF_8)

            @Suppress("UNCHECKED_CAST")
            val payload = objectMapper.readValue(body, Map::class.java) as Map<String, Any?>

            val tenantId = payload["tenantId"] as? String ?: return
            val eventType = payload["eventType"] as? String ?: return
            val occurredAt = (payload["occurredAt"] as? String)
                ?.let { runCatching { Instant.parse(it) }.getOrNull() }
                ?: Instant.now()

            if (eventType in tabletManagementEvents) {
                // Tablet pairing/revocation events: only the CRM "Tablety" tab cares.
                val wsMessage = TabletManagementWsMessage(
                    type = eventType,
                    tabletId = payload["tabletId"] as? String ?: return,
                    deviceName = payload["deviceName"] as? String ?: "",
                    occurredAt = occurredAt
                )
                messagingTemplate.convertAndSend("/topic/studio.$tenantId.tablets", wsMessage)
                return
            }

            val requestId = payload["requestId"] as? String ?: return
            val wsMessage = SignatureRequestWsMessage(
                type = eventType,
                requestId = requestId,
                tabletId = payload["tabletId"] as? String,
                documentName = payload["documentName"] as? String,
                signerName = payload["signerName"] as? String,
                status = payload["status"] as? String,
                occurredAt = occurredAt
            )

            // Tablets listen on the studio-wide tablet topic
            messagingTemplate.convertAndSend("/topic/studio.$tenantId.tablet.signature", wsMessage)
            // CRM desktop listens per request for live status
            messagingTemplate.convertAndSend("/topic/studio.$tenantId.signature.$requestId", wsMessage)
        } catch (e: Exception) {
            logger.error("Error processing signature-request Redis message: ${e.message}", e)
        }
    }
}

data class SignatureRequestWsMessage(
    val type: String,
    val requestId: String,
    val tabletId: String?,
    val documentName: String?,
    val signerName: String?,
    val status: String?,
    val occurredAt: Instant
)

data class TabletManagementWsMessage(
    val type: String,
    val tabletId: String,
    val deviceName: String,
    val occurredAt: Instant
)
