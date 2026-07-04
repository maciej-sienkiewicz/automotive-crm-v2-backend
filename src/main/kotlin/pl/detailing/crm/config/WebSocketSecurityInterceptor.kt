package pl.detailing.crm.config

import org.slf4j.LoggerFactory
import org.springframework.messaging.Message
import org.springframework.messaging.MessageChannel
import org.springframework.messaging.simp.stomp.StompCommand
import org.springframework.messaging.simp.stomp.StompHeaderAccessor
import org.springframework.messaging.support.ChannelInterceptor
import org.springframework.messaging.support.MessageHeaderAccessor
import org.springframework.stereotype.Component
import pl.detailing.crm.auth.UserPrincipal

/**
 * Multi-tenancy enforcement for WebSocket connections and subscriptions.
 *
 * Rules:
 *  - CONNECT requires an authenticated [UserPrincipal] (handshake is guarded by the
 *    HTTP session, this is defense in depth).
 *  - SUBSCRIBE to any `/topic/studio.{studioId}...` destination (dashboard, checkin.*
 *    and any future studio-scoped topic) is allowed only when {studioId} matches the
 *    authenticated user's studio.
 *  - SUBSCRIBE to any other destination is rejected — the application only publishes
 *    to studio-scoped topics, so nothing else is legitimate to listen on.
 */
@Component
class WebSocketSecurityInterceptor : ChannelInterceptor {

    private val log = LoggerFactory.getLogger(WebSocketSecurityInterceptor::class.java)

    companion object {
        // Captures the studioId segment of every studio-scoped topic,
        // e.g. /topic/studio.{id}.dashboard or /topic/studio.{id}.checkin.{checkinId}
        private val STUDIO_TOPIC_PATTERN = Regex("^/topic/studio\\.([0-9a-fA-F\\-]+)(?:\\..+)?$")
    }

    override fun preSend(message: Message<*>, channel: MessageChannel): Message<*>? {
        val accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor::class.java)
            ?: return message

        when (accessor.command) {
            StompCommand.CONNECT -> {
                val principal = accessor.user
                if (principal !is UserPrincipal) {
                    log.warn("[WS-SEC] REJECTED CONNECT: unauthenticated session (principal={})",
                        principal?.javaClass?.simpleName)
                    throw IllegalArgumentException("Authentication required")
                }
                log.info("[WS-SEC] STOMP CONNECT user={} studio={}", principal.email, principal.studioId.value)
            }

            StompCommand.SUBSCRIBE -> {
                val destination = accessor.destination
                if (destination == null) {
                    log.warn("[WS-SEC] REJECTED SUBSCRIBE without destination")
                    throw IllegalArgumentException("Subscription destination required")
                }

                val principal = accessor.user
                if (principal !is UserPrincipal) {
                    log.warn("[WS-SEC] REJECTED SUBSCRIBE to {}: unauthenticated", destination)
                    throw IllegalArgumentException("Authentication required to subscribe")
                }

                val match = STUDIO_TOPIC_PATTERN.matchEntire(destination)
                if (match == null) {
                    log.warn("[WS-SEC] REJECTED SUBSCRIBE to non-studio destination {} by user={}",
                        destination, principal.email)
                    throw IllegalArgumentException("Access denied: unknown destination")
                }

                val requestedStudioId = match.groupValues[1]
                val userStudioId = principal.studioId.value.toString()
                if (!userStudioId.equals(requestedStudioId, ignoreCase = true)) {
                    log.warn("[WS-SEC] REJECTED SUBSCRIBE: studio mismatch. userStudio={}, requested={}, user={}",
                        userStudioId, requestedStudioId, principal.email)
                    throw IllegalArgumentException("Access denied: cannot subscribe to another studio's topics")
                }

                log.debug("[WS-SEC] ALLOWED subscription to {} for user={} (studio={})",
                    destination, principal.email, userStudioId)
            }

            else -> { /* other frames (SEND, DISCONNECT, heartbeats) pass through */ }
        }

        return message
    }
}
