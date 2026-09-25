package pl.detailing.crm.push.notify

import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import pl.detailing.crm.push.domain.PushDevice
import pl.detailing.crm.push.infrastructure.PushDeviceEntity
import pl.detailing.crm.push.infrastructure.PushDeviceRepository
import pl.detailing.crm.push.send.PushDeliveryStatus
import pl.detailing.crm.push.send.WebPushSender
import pl.detailing.crm.role.domain.Permission
import pl.detailing.crm.role.permission.PermissionCheckService
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import pl.detailing.crm.user.infrastructure.UserEntity
import pl.detailing.crm.user.infrastructure.UserRepository
import java.util.UUID

/**
 * Kto dostaje które powiadomienie: dane klienta tylko z `CUSTOMERS_VIEW` (właściciel
 * zawsze), autor zdarzenia nie dostaje własnej wiadomości, a bez uprawnienia do
 * rodzaju powiadomienia - nic.
 */
class PushNotifierTest {

    private val studio = StudioId(UUID.randomUUID())
    private val owner = UUID.randomUUID()
    private val receptionist = UUID.randomUUID() // VISITS_VIEW + CUSTOMERS_VIEW
    private val detailer = UUID.randomUUID()     // VISITS_VIEW bez danych osobowych
    private val accountant = UUID.randomUUID()   // bez VISITS_VIEW

    private val devices = mockk<PushDeviceRepository>(relaxed = true)
    private val users = mockk<UserRepository>()
    private val permissions = mockk<PermissionCheckService>()
    private val sender = mockk<WebPushSender>()
    private val sent = mutableMapOf<UUID, String>()

    private val notifier = PushNotifier(devices, users, permissions, sender, ObjectMapper())

    init {
        every { sender.isConfigured } returns true
        every { devices.save(any<PushDeviceEntity>()) } answers { firstArg() }
        every { devices.findByStudioIdAndRevokedAtIsNull(studio.value) } returns
            listOf(owner, receptionist, detailer, accountant).map(::device)
        listOf(owner, receptionist, detailer, accountant).forEach { id ->
            every { users.findByIdAndStudioId(id, studio.value) } returns mockk<UserEntity> {
                every { isActive } returns true
                every { isOwner } returns (id == owner)
            }
        }
        grant(receptionist, Permission.VISITS_VIEW, Permission.CUSTOMERS_VIEW)
        grant(detailer, Permission.VISITS_VIEW)
        grant(accountant)
        val deviceSlot = slot<PushDevice>()
        val json = slot<String>()
        every { sender.send(capture(deviceSlot), capture(json), any()) } answers {
            sent[deviceSlot.captured.userId.value] = json.captured
            PushDeliveryStatus.DELIVERED
        }
    }

    private fun grant(user: UUID, vararg granted: Permission) {
        every { permissions.hasPermission(UserId(user), studio, any()) } answers { thirdArg<Permission>() in granted }
    }

    private fun device(user: UUID) = PushDeviceEntity(
        id = UUID.randomUUID(), studioId = studio.value, userId = user, deviceName = "Telefon",
        userAgent = null, endpoint = "https://push.example/$user", endpointHash = user.toString(),
        p256dh = "k", auth = "a"
    )

    private val message = PushMessages.Message(
        masked = PushPayload(PushNotificationType.VEHICLE_CHECKED_IN, "Przyjęto pojazd", "WA 1.", "/visits/1", PushIcon.CHECKIN, "t"),
        personal = PushPayload(PushNotificationType.VEHICLE_CHECKED_IN, "Przyjęto pojazd", "Jan Kowalski, WA 1.", "/visits/1", PushIcon.CHECKIN, "t")
    )

    private fun body(user: UUID) = ObjectMapper().readTree(sent[user])["body"].asText()

    @Test
    fun `dane klienta tylko dla uprawnionych, reszta dostaje wariant bez nich`() {
        notifier.broadcast(studio, Permission.VISITS_VIEW, message)

        assertEquals("Jan Kowalski, WA 1.", body(owner))
        assertEquals("Jan Kowalski, WA 1.", body(receptionist))
        assertEquals("WA 1.", body(detailer))
        // Bez uprawnienia do wizyt - nic, nawet w wersji bez danych.
        assertEquals(false, sent.containsKey(accountant))
    }

    @Test
    fun `autor zdarzenia nie dostaje powiadomienia o wlasnym dzialaniu`() {
        notifier.broadcast(studio, Permission.VISITS_VIEW, message, excludeUserId = UserId(receptionist))

        assertEquals(setOf(owner, detailer), sent.keys)
        verify(exactly = 2) { sender.send(any(), any(), any()) }
    }
}
