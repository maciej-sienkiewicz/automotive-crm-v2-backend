package pl.detailing.crm.config

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

    companion object {
        private val STUDIO_TOPIC_PATTERN = Regex("^/topic/studio\\.([a-f0-9\\-]+)\\.dashboard$")
    }

    override fun preSend(message: Message<*>, channel: MessageChannel): Message<*>? {
        val accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor::class.java)
            ?: return message

        if (accessor.command == StompCommand.SUBSCRIBE) {
            val destination = accessor.destination ?: return message
            val principal = accessor.user

            val match = STUDIO_TOPIC_PATTERN.matchEntire(destination) ?: return message

            // Topic matches studio pattern - enforce isolation
            val requestedStudioId = match.groupValues[1]

            if (principal == null || principal !is UserPrincipal) {
                throw IllegalArgumentException("Authentication required to subscribe to studio topics")
            }

            val userStudioId = principal.studioId

            if (userStudioId.value.toString() != requestedStudioId) {
                throw IllegalArgumentException(
                    "Access denied: cannot subscribe to another studio's dashboard"
                )
            }
        }

        return message
    }
}
