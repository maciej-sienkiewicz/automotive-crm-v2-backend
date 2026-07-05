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
import pl.detailing.crm.signing.infrastructure.TabletPrincipal
import pl.detailing.crm.signing.infrastructure.TabletSessionService

/**
 * Multi-tenancy enforcement for WebSocket connections and subscriptions.
 *
 * Rules:
 *  - CONNECT requires an authenticated [UserPrincipal] (handshake is guarded by the
 *    HTTP session, this is defense in depth), OR a valid X-Tablet-Token native header
 *    presented by a paired signing tablet (session-free device) — the token is
 *    exchanged for a [TabletPrincipal] for the lifetime of the STOMP session.
 *  - SUBSCRIBE by a [UserPrincipal] to any `/topic/studio.{studioId}...` destination
 *    (dashboard, checkin.*, signature.* and any future studio-scoped topic) is allowed
 *    only when {studioId} matches the authenticated user's studio.
 *  - SUBSCRIBE by a [TabletPrincipal] is allowed ONLY to its own studio's tablet topic
 *    `/topic/studio.{tenantId}.tablet.signature` — tablets cannot listen on dashboards,
 *    check-in topics or other studios' destinations.
 *  - SUBSCRIBE to any other destination is rejected — the application only publishes
 *    to studio-scoped topics, so nothing else is legitimate to listen on.
 */
@Component
class WebSocketSecurityInterceptor(
    private val tabletSessionService: TabletSessionService
) : ChannelInterceptor {

    private val log = LoggerFactory.getLogger(WebSocketSecurityInterceptor::class.java)

    companion object {
        // Captures the studioId segment of every studio-scoped topic,
        // e.g. /topic/studio.{id}.dashboard or /topic/studio.{id}.checkin.{checkinId}
        private val STUDIO_TOPIC_PATTERN = Regex("^/topic/studio\\.([0-9a-fA-F\\-]+)(?:\\..+)?$")
        private const val TABLET_TOKEN_HEADER = "X-Tablet-Token"
    }

    override fun preSend(message: Message<*>, channel: MessageChannel): Message<*>? {
        val accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor::class.java)
            ?: return message

        when (accessor.command) {
            StompCommand.CONNECT -> {
                val principal = accessor.user
                if (principal is UserPrincipal) {
                    log.info("[WS-SEC] STOMP CONNECT user={} studio={}", principal.email, principal.studioId.value)
                    return message
                }

                // Session-free signing tablet: authenticate via X-Tablet-Token STOMP header
                val tabletToken = accessor.getFirstNativeHeader(TABLET_TOKEN_HEADER)
                if (tabletToken != null) {
                    val session = tabletSessionService.validateToken(tabletToken)
                    if (session != null) {
                        accessor.user = TabletPrincipal(
                            tenantId = session.tenantId,
                            tabletId = session.tabletId,
                            deviceName = session.deviceName
                        )
                        log.info("[WS-SEC] STOMP CONNECT tablet={} studio={}", session.tabletId, session.tenantId)
                        return message
                    }
                    log.warn("[WS-SEC] REJECTED CONNECT: invalid tablet token")
                    throw IllegalArgumentException("Invalid tablet token")
                }

                log.warn("[WS-SEC] REJECTED CONNECT: unauthenticated session (principal={})",
                    principal?.javaClass?.simpleName)
                throw IllegalArgumentException("Authentication required")
            }

            StompCommand.SUBSCRIBE -> {
                val destination = accessor.destination
                if (destination == null) {
                    log.warn("[WS-SEC] REJECTED SUBSCRIBE without destination")
                    throw IllegalArgumentException("Subscription destination required")
                }

                when (val principal = accessor.user) {
                    is TabletPrincipal -> {
                        val allowed = "/topic/studio.${principal.tenantId}.tablet.signature"
                        if (!destination.equals(allowed, ignoreCase = true)) {
                            log.warn("[WS-SEC] REJECTED tablet SUBSCRIBE to {} (tablet={}, allowed={})",
                                destination, principal.tabletId, allowed)
                            throw IllegalArgumentException("Access denied: tablets may only subscribe to their signature topic")
                        }
                        log.debug("[WS-SEC] ALLOWED tablet subscription to {} (tablet={})",
                            destination, principal.tabletId)
                    }

                    is UserPrincipal -> {
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

                    else -> {
                        log.warn("[WS-SEC] REJECTED SUBSCRIBE to {}: unauthenticated", destination)
                        throw IllegalArgumentException("Authentication required to subscribe")
                    }
                }
            }

            else -> { /* other frames (SEND, DISCONNECT, heartbeats) pass through */ }
        }

        return message
    }
}
