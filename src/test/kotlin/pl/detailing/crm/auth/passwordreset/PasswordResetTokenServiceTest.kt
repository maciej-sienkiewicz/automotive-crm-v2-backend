package pl.detailing.crm.auth.passwordreset

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations
import java.time.Duration
import java.util.UUID

/**
 * Reset hasła i zaproszenie pracownika jadą tym samym mechanizmem, różnią się tylko
 * czasem życia. Test pinuje oba TTL-e: reset ma zostać krótki (30 min), zaproszenie
 * długie (48 h) — i żadna z tych zmian nie może po cichu podmienić drugiej.
 */
class PasswordResetTokenServiceTest {

    private val redisTemplate = mockk<StringRedisTemplate>()
    private val valueOperations = mockk<ValueOperations<String, String>>()
    private val properties = PasswordResetProperties()
    private val service = PasswordResetTokenService(redisTemplate, properties)

    private val storedKey = slot<String>()
    private val storedValue = slot<String>()
    private val storedTtl = slot<Duration>()

    init {
        every { redisTemplate.opsForValue() } returns valueOperations
        every { valueOperations.set(capture(storedKey), capture(storedValue), capture(storedTtl)) } returns Unit
    }

    @Test
    fun `token resetu hasla zyje 30 minut`() {
        val userId = UUID.randomUUID()

        val raw = service.issueToken(userId)

        assertEquals(Duration.ofMinutes(30), storedTtl.captured)
        assertEquals(userId.toString(), storedValue.captured)
        assertTrue(storedKey.captured.startsWith("auth:pwreset:token:"))
        assertFalse(raw in storedKey.captured, "w Redisie ma być hash, nie surowy token")
    }

    @Test
    fun `token zaproszenia pracownika zyje 48 godzin`() {
        val userId = UUID.randomUUID()

        val raw = service.issueInvitationToken(userId)

        assertEquals(Duration.ofHours(48), storedTtl.captured)
        assertEquals(userId.toString(), storedValue.captured)
        assertTrue(storedKey.captured.startsWith("auth:pwreset:token:"), "ten sam prefiks — konsumuje go ten sam endpoint")
        assertFalse(raw in storedKey.captured)
    }

    @Test
    fun `czas zycia zaproszenia jest konfigurowalny niezaleznie od resetu`() {
        val custom = PasswordResetTokenService(
            redisTemplate,
            PasswordResetProperties(tokenTtlMinutes = 15, invitationTokenTtlHours = 72)
        )

        custom.issueInvitationToken(UUID.randomUUID())
        assertEquals(Duration.ofHours(72), storedTtl.captured)

        custom.issueToken(UUID.randomUUID())
        assertEquals(Duration.ofMinutes(15), storedTtl.captured)
    }

    @Test
    fun `kazde wystawienie daje inny token`() {
        val first = service.issueInvitationToken(UUID.randomUUID())
        val second = service.issueInvitationToken(UUID.randomUUID())
        assertNotEquals(first, second)
        assertTrue(first.length >= 40, "32 losowe bajty w base64url: $first")
    }
}
