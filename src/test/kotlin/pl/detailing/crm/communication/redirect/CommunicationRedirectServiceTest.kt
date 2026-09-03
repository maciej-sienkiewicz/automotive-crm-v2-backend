package pl.detailing.crm.communication.redirect

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.ValidationException
import java.time.Instant
import java.util.UUID

class CommunicationRedirectServiceTest {

    private val repository: CommunicationRedirectJpaRepository = mockk()
    private val service = CommunicationRedirectService(repository)
    private val studio = StudioId(UUID.randomUUID())

    private fun stubSave() {
        val saved = slot<CommunicationRedirectEntity>()
        every { repository.save(capture(saved)) } answers { saved.captured }
    }

    @Test
    fun `a studio that never touched the switch sends to customers`() {
        every { repository.findByStudioId(studio.value) } returns null
        assertNull(service.activeFor(studio.value))
        assertFalse(service.settings(studio).enabled)
    }

    @Test
    fun `enabling requires both targets`() {
        every { repository.findByStudioId(studio.value) } returns null
        assertThrows(ValidationException::class.java) { service.update(studio, true, "", "owner@studio.pl", null) }
        assertThrows(ValidationException::class.java) { service.update(studio, true, "500100200", "", null) }
        assertThrows(ValidationException::class.java) { service.update(studio, true, "500100200", "not-an-email", null) }
        assertThrows(ValidationException::class.java) { service.update(studio, true, "12345", "owner@studio.pl", null) }
    }

    @Test
    fun `phone is stored in E164 and email lowercased`() {
        every { repository.findByStudioId(studio.value) } returns null
        stubSave()

        val settings = service.update(studio, true, "500 100 200", "Owner@Studio.PL", null)

        assertEquals("+48500100200", settings.phone)
        assertEquals("owner@studio.pl", settings.email)
        assertEquals(true, settings.enabled)
    }

    @Test
    fun `switching off keeps the targets but stops redirecting`() {
        val row = CommunicationRedirectEntity(UUID.randomUUID(), studio.value, true, "+48500100200", "owner@studio.pl", Instant.now(), null)
        every { repository.findByStudioId(studio.value) } returns row
        stubSave()

        val settings = service.update(studio, false, row.phone, row.email, null)

        assertFalse(settings.enabled)
        assertEquals("+48500100200", settings.phone)
        assertNull(service.activeFor(studio.value))
    }

    @Test
    fun `a half filled row never redirects`() {
        every { repository.findByStudioId(studio.value) } returns
            CommunicationRedirectEntity(UUID.randomUUID(), studio.value, true, "+48500100200", "", Instant.now(), null)
        assertNull(service.activeFor(studio.value))
    }

    @Test
    fun `an enabled row with both targets is active`() {
        every { repository.findByStudioId(studio.value) } returns
            CommunicationRedirectEntity(UUID.randomUUID(), studio.value, true, "+48500100200", "owner@studio.pl", Instant.now(), null)
        val active = service.activeFor(studio.value)
        assertNotNull(active)
        assertEquals("[TEST → +48600700800] ", active!!.prefixFor("+48600700800"))
    }
}
