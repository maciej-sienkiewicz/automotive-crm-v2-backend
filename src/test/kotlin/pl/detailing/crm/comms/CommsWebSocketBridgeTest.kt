package pl.detailing.crm.comms

import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.messaging.simp.SimpMessagingTemplate
import pl.detailing.crm.comms.domain.CommMessageReadEvent
import pl.detailing.crm.comms.domain.CommReadSource
import pl.detailing.crm.comms.domain.CommThreadChangedEvent
import pl.detailing.crm.shared.CommThreadUpdatedPayload
import pl.detailing.crm.shared.DashboardEvent
import java.util.UUID

/**
 * „Przekroczono limit żądań" w poczcie: jedno zdarzenie na wiadomość kazało każdej
 * karcie w studiu odświeżać listy. Zdarzenia tego samego wątku z krótkiego okna
 * idą jednym pushem.
 */
class CommsWebSocketBridgeTest {

    private val template: SimpMessagingTemplate = mockk(relaxed = true)
    private val bridge = CommsWebSocketBridge(template)
    private val studio = UUID.randomUUID()
    private val thread = UUID.randomUUID()

    @AfterEach
    fun tearDown() = bridge.shutdown()

    @Test
    fun `otwarcie watku z dziesiecioma nieprzeczytanymi to jeden push, nie dziesiec`() {
        repeat(10) { bridge.onMessageRead(CommMessageReadEvent(studio, thread, UUID.randomUUID(), CommReadSource.CRM)) }

        bridge.flushNow()

        verify(exactly = 1) { template.convertAndSend("/topic/studio.$studio.dashboard", any<Any>()) }
    }

    @Test
    fun `paczka importu jednego watku to jeden push, a przychodzaca wiadomosc nie ginie`() {
        val sent = slot<Any>()
        bridge.onThreadChanged(CommThreadChangedEvent(studio, thread, newMessage = false))
        bridge.onThreadChanged(CommThreadChangedEvent(studio, thread, newMessage = true))
        bridge.onThreadChanged(CommThreadChangedEvent(studio, thread, newMessage = false))

        bridge.flushNow()

        verify(exactly = 1) { template.convertAndSend(any<String>(), capture(sent)) }
        assertTrue(((sent.captured as DashboardEvent<*>).payload as CommThreadUpdatedPayload).newMessage)
    }

    @Test
    fun `rozne watki i rozne studia ida osobno`() {
        val otherStudio = UUID.randomUUID()
        bridge.onThreadChanged(CommThreadChangedEvent(studio, thread, newMessage = false))
        bridge.onThreadChanged(CommThreadChangedEvent(studio, UUID.randomUUID(), newMessage = false))
        bridge.onThreadChanged(CommThreadChangedEvent(otherStudio, thread, newMessage = false))

        bridge.flushNow()

        verify(exactly = 2) { template.convertAndSend("/topic/studio.$studio.dashboard", any<Any>()) }
        verify(exactly = 1) { template.convertAndSend("/topic/studio.$otherStudio.dashboard", any<Any>()) }
    }

    @Test
    fun `bez recznego oprozniania push wychodzi sam po oknie`() {
        bridge.onThreadChanged(CommThreadChangedEvent(studio, thread, newMessage = true))

        Thread.sleep(CommsWebSocketBridge.COALESCE_MS + 400)

        verify(exactly = 1) { template.convertAndSend(any<String>(), any<Any>()) }
    }
}
