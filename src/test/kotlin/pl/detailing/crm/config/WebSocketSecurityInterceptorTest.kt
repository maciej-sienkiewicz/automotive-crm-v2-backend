package pl.detailing.crm.config

import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.messaging.Message
import org.springframework.messaging.MessageChannel
import org.springframework.messaging.simp.stomp.StompCommand
import org.springframework.messaging.simp.stomp.StompHeaderAccessor
import org.springframework.messaging.support.MessageBuilder
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.core.authority.AuthorityUtils
import pl.detailing.crm.signing.infrastructure.TabletPrincipal
import pl.detailing.crm.signing.infrastructure.TabletSession
import pl.detailing.crm.signing.infrastructure.TabletSessionService
import java.security.Principal
import java.time.Instant

/**
 * Handshake WebSocketu (`/ws-registry`) jest dopuszczony bez sesji HTTP (SecurityConfig), bo tablet
 * nie ma sesji - token podaje w ramce CONNECT. Od tej chwili JEDYNĄ bramką WebSocketu
 * jest ten interceptor, więc ten test pilnuje, że anonimowe połączenie nadal nic nie
 * dostaje: ani połączenia, ani subskrypcji.
 */
class WebSocketSecurityInterceptorTest {

    private val tablets = mockk<TabletSessionService>()
    private val interceptor = WebSocketSecurityInterceptor(tablets)
    private val channel = mockk<MessageChannel>(relaxed = true)
    private val studio = "3e77ebcd-b419-49f2-b01f-9db10450fc60"

    private fun frame(command: StompCommand, user: Principal? = null, token: String? = null, destination: String? = null): Message<ByteArray> {
        val accessor = StompHeaderAccessor.create(command)
        accessor.user = user
        token?.let { accessor.addNativeHeader("X-Tablet-Token", it) }
        destination?.let { accessor.destination = it }
        accessor.setLeaveMutable(true)
        return MessageBuilder.createMessage(ByteArray(0), accessor.messageHeaders)
    }

    @Test
    fun `anonimowy CONNECT bez tokenu jest odrzucany`() {
        assertThrows<IllegalArgumentException> { interceptor.preSend(frame(StompCommand.CONNECT), channel) }
    }

    @Test
    fun `CONNECT z anonimowym uwierzytelnieniem Spring Security jest odrzucany`() {
        val anonymous = AnonymousAuthenticationToken("key", "anonymousUser", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS"))
        assertThrows<IllegalArgumentException> { interceptor.preSend(frame(StompCommand.CONNECT, user = anonymous), channel) }
    }

    @Test
    fun `CONNECT z niewaznym tokenem tabletu jest odrzucany`() {
        every { tablets.validateToken("revoked") } returns null
        assertThrows<IllegalArgumentException> { interceptor.preSend(frame(StompCommand.CONNECT, token = "revoked"), channel) }
    }

    @Test
    fun `tablet z waznym tokenem laczy sie i slucha tylko swojego tematu`() {
        every { tablets.validateToken("ok") } returns TabletSession(studio, "tablet-1", "Recepcja 1", Instant.now())
        val connect = frame(StompCommand.CONNECT, token = "ok")
        interceptor.preSend(connect, channel)
        val principal = StompHeaderAccessor.wrap(connect).user
        assertTrue(principal is TabletPrincipal)

        interceptor.preSend(frame(StompCommand.SUBSCRIBE, user = principal, destination = "/topic/studio.$studio.tablet.signature"), channel)
        assertThrows<IllegalArgumentException> {
            interceptor.preSend(frame(StompCommand.SUBSCRIBE, user = principal, destination = "/topic/studio.$studio.dashboard"), channel)
        }
    }

    @Test
    fun `anonimowa subskrypcja jest odrzucana`() {
        assertThrows<IllegalArgumentException> {
            interceptor.preSend(frame(StompCommand.SUBSCRIBE, destination = "/topic/studio.$studio.tablet.signature"), channel)
        }
    }
}
