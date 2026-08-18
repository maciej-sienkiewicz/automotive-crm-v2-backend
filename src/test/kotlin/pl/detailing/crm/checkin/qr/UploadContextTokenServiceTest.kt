package pl.detailing.crm.checkin.qr

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations
import java.time.Duration
import java.util.UUID

/**
 * The desktop check-in wizard re-requests an upload token every time the photo step
 * mounts. Issuing a fresh token there used to revoke the QR code a phone had already
 * scanned, so uploads from that phone started failing with 403 ("nie da się dodać
 * zdjęcia" after leaving and re-entering the step).
 */
class UploadContextTokenServiceTest {

    private val redisTemplate = mockk<StringRedisTemplate>(relaxed = true)
    private val valueOps = mockk<ValueOperations<String, String>>(relaxed = true)
    private val objectMapper = ObjectMapper().registerModule(JavaTimeModule())

    private val service = UploadContextTokenService(redisTemplate, objectMapper, ttlHours = 3)

    private val tenantId = UUID.randomUUID().toString()
    private val checkinId = UUID.randomUUID().toString()
    private val userId = UUID.randomUUID().toString()

    private val contextKey = "checkin:upload-context:$tenantId:$checkinId"

    init {
        every { redisTemplate.opsForValue() } returns valueOps
    }

    @Test
    fun `reuses the active token and extends its TTL instead of issuing a new one`() {
        val activeToken = "already-issued-token"
        every { valueOps.get(contextKey) } returns activeToken
        every { redisTemplate.hasKey("checkin:upload-token:$activeToken") } returns true

        val result = service.generateToken(tenantId, checkinId, userId)

        assertEquals(activeToken, result.token)
        verify(exactly = 0) { redisTemplate.delete("checkin:upload-token:$activeToken") }
        verify { redisTemplate.expire("checkin:upload-token:$activeToken", Duration.ofHours(3)) }
        verify { redisTemplate.expire(contextKey, Duration.ofHours(3)) }
        verify { redisTemplate.expire("checkin:mobile-done:$activeToken", Duration.ofHours(3)) }
        verify { redisTemplate.expire("checkin:visit-created:$activeToken", Duration.ofHours(3)) }
    }

    @Test
    fun `issues a new token when the previous one already expired`() {
        val deadToken = "expired-token"
        every { valueOps.get(contextKey) } returns deadToken
        every { redisTemplate.hasKey("checkin:upload-token:$deadToken") } returns false

        val result = service.generateToken(tenantId, checkinId, userId)

        assertNotEquals(deadToken, result.token)
        verify { redisTemplate.delete("checkin:upload-token:$deadToken") }
        verify { valueOps.set("checkin:upload-token:${result.token}", any(), Duration.ofHours(3)) }
        verify { valueOps.set(contextKey, result.token, Duration.ofHours(3)) }
    }

    @Test
    fun `rotate revokes the active token and issues a new one`() {
        val activeToken = "still-valid-token"
        every { valueOps.get(contextKey) } returns activeToken
        every { redisTemplate.hasKey("checkin:upload-token:$activeToken") } returns true

        val result = service.generateToken(tenantId, checkinId, userId, rotate = true)

        assertNotEquals(activeToken, result.token)
        verify { redisTemplate.delete("checkin:upload-token:$activeToken") }
        verify { valueOps.set(contextKey, result.token, Duration.ofHours(3)) }
    }

    @Test
    fun `issues a token when the checkin has none yet`() {
        every { valueOps.get(contextKey) } returns null

        val result = service.generateToken(tenantId, checkinId, userId)

        verify { valueOps.set("checkin:upload-token:${result.token}", any(), Duration.ofHours(3)) }
        verify { valueOps.set(contextKey, result.token, Duration.ofHours(3)) }
    }
}
