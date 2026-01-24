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
 * Multi-tenancy enforcement for WebSocket subscriptions.
 * Ensures a user can ONLY subscribe to topics belonging to their own studio.
 *
 * Rule: /topic/studio.{studioId}.dashboard → studioId must match the user's session studioId.
 */
@Component
class WebSocketSecurityInterceptor : ChannelInterceptor {

    private val log = LoggerFactory.getLogger(WebSocketSecurityInterceptor::class.java)

    companion object {
        private val STUDIO_TOPIC_PATTERN = Regex("^/topic/studio\\.([a-f0-9\\-]+)\\.dashboard$")
    }

    override fun preSend(message: Message<*>, channel: MessageChannel): Message<*>? {
        val accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor::class.java)
            ?: return message

        val command = accessor.command
        log.debug("[WS-SEC] STOMP command={}, destination={}, user={}",
            command, accessor.destination, accessor.user?.name)

        if (command == StompCommand.CONNECT) {
            log.info("[WS-SEC] STOMP CONNECT from user={}", accessor.user?.name ?: "anonymous")
        }

        if (command == StompCommand.SUBSCRIBE) {
            val destination = accessor.destination ?: return message
            val principal = accessor.user

            log.info("[WS-SEC] SUBSCRIBE request: destination={}, principal={}, principalType={}",
                destination, principal?.name, principal?.javaClass?.simpleName)

            val match = STUDIO_TOPIC_PATTERN.matchEntire(destination)
            if (match == null) {
                log.debug("[WS-SEC] Destination does not match studio pattern, allowing: {}", destination)
                return message
            }

            // Topic matches studio pattern - enforce isolation
            val requestedStudioId = match.groupValues[1]

            if (principal == null || principal !is UserPrincipal) {
                log.warn("[WS-SEC] REJECTED: No UserPrincipal for studio topic subscription. principal={}, type={}",
                    principal?.name, principal?.javaClass?.simpleName)
                throw IllegalArgumentException("Authentication required to subscribe to studio topics")
            }

            val userStudioId = principal.studioId

            if (userStudioId.value.toString() != requestedStudioId) {
                log.warn("[WS-SEC] REJECTED: Studio mismatch. userStudio={}, requestedStudio={}",
                    userStudioId.value, requestedStudioId)
                throw IllegalArgumentException(
                    "Access denied: cannot subscribe to another studio's dashboard"
                )
            }

            log.info("[WS-SEC] ALLOWED: Subscription to {} for user={} (studio={})",
                destination, principal.email, userStudioId.value)
        }

        return message
    }
}
