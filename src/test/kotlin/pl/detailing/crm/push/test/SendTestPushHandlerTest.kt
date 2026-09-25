package pl.detailing.crm.push.test

import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import pl.detailing.crm.push.infrastructure.PushDeviceEntity
import pl.detailing.crm.push.infrastructure.PushDeviceRepository
import pl.detailing.crm.push.infrastructure.sha256Hex
import pl.detailing.crm.push.send.PushDeliveryStatus
import pl.detailing.crm.push.send.WebPushSender
import pl.detailing.crm.shared.NotFoundException
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UnprocessableEntityException
import pl.detailing.crm.shared.UserId
import java.time.Instant
import java.util.UUID

/**
 * Powiadomienie próbne z kreatora parowania: idzie produkcyjną ścieżką do urządzenia,
 * które o nie poprosiło - i wyłącznie do niego, i wyłącznie gdy należy do pytającego.
 */
class SendTestPushHandlerTest {

    private val studioId = UUID.randomUUID()
    private val userId = UUID.randomUUID()
    private val endpoint = "https://fcm.googleapis.com/fcm/send/abc"

    private val repository = mockk<PushDeviceRepository>(relaxed = true)
    private val sender = mockk<WebPushSender>()
    private val handler = SendTestPushHandler(repository, sender, ObjectMapper())

    private fun device(owner: UUID = userId, revokedAt: Instant? = null) = PushDeviceEntity(
        id = UUID.randomUUID(),
        studioId = studioId,
        userId = owner,
        deviceName = "Android, Chrome",
        userAgent = null,
        endpoint = endpoint,
        endpointHash = sha256Hex(endpoint),
        p256dh = "p256dh",
        auth = "auth",
        revokedAt = revokedAt
    )

    private fun command() = SendTestPushCommand(StudioId(studioId), UserId(userId), endpoint)

    init {
        every { sender.isConfigured } returns true
        every { repository.save(any<PushDeviceEntity>()) } answers { firstArg() }
    }

    @Test
    fun `wysyla powiadomienie TEST z ikona aplikacji do urzadzenia pytajacego`() {
        val entity = device()
        every { repository.findByEndpointHash(sha256Hex(endpoint)) } returns entity
        val payload = slot<String>()
        every { sender.send(any(), capture(payload), 60) } returns PushDeliveryStatus.DELIVERED

        runBlocking { handler.handle(command()) }

        val json = ObjectMapper().readTree(payload.captured)
        assertEquals("TEST", json["type"].asText())
        assertEquals("APP", json["icon"].asText())
        // Service Worker pokazuje wyłącznie payload z tytułem i treścią - bez nich push
        // przyszedłby „pusty" i Chrome ukarałby origin za niewidoczne powiadomienie.
        assertEquals(false, json["title"].asText().isBlank())
        assertEquals(false, json["body"].asText().isBlank())
        assertNotNull(entity.lastUsedAt)
    }

    @Test
    fun `cudzy endpoint wyglada jak nieistniejacy i nic nie wychodzi`() {
        every { repository.findByEndpointHash(any()) } returns device(owner = UUID.randomUUID())

        assertThrows<NotFoundException> { runBlocking { handler.handle(command()) } }
        verify(exactly = 0) { sender.send(any(), any(), any()) }
    }

    @Test
    fun `odlaczone urzadzenie nie dostaje powiadomienia probnego`() {
        every { repository.findByEndpointHash(any()) } returns device(revokedAt = Instant.now())

        assertThrows<NotFoundException> { runBlocking { handler.handle(command()) } }
        verify(exactly = 0) { sender.send(any(), any(), any()) }
    }

    @Test
    fun `wygasla subskrypcja zostaje odlaczona i wraca jako powod do pokazania`() {
        val entity = device()
        every { repository.findByEndpointHash(any()) } returns entity
        every { sender.send(any(), any(), any()) } returns PushDeliveryStatus.SUBSCRIPTION_GONE

        assertThrows<UnprocessableEntityException> { runBlocking { handler.handle(command()) } }
        assertNotNull(entity.revokedAt)
        assertNull(entity.lastUsedAt)
    }
}
