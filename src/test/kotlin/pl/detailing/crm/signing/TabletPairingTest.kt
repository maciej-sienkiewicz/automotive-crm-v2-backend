package pl.detailing.crm.signing

import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations
import pl.detailing.crm.signing.infrastructure.SigningTabletEntity
import pl.detailing.crm.signing.infrastructure.SigningTabletRepository
import pl.detailing.crm.signing.infrastructure.TabletSessionService
import java.time.Instant
import java.util.UUID

/**
 * Umowa z użytkownikiem brzmi: raz sparowany tablet działa, dopóki ktoś świadomie go
 * nie odłączy. Wcześniej łamało ją samo wdrożenie — token żył w cache'u bez trwałego
 * magazynu, więc restart Redisa rozparowywał wszystkie urządzenia. Te testy pilnują
 * dwóch rzeczy: że parowanie jest trwałe i że jedyną drogą jego zakończenia jest
 * odłączenie urządzenia.
 */
class TabletPairingTest {

    private val repository = mockk<SigningTabletRepository>(relaxed = true)
    private val redis = mockk<StringRedisTemplate>(relaxed = true)
    private val valueOps = mockk<ValueOperations<String, String>>(relaxed = true)
    private val service = TabletSessionService(repository, redis, ObjectMapper(), pairingCodeTtlMinutes = 5L)

    private val studioId = UUID.randomUUID()

    private fun tablet(revokedAt: Instant? = null) = SigningTabletEntity(
        id = UUID.randomUUID(),
        studioId = studioId,
        deviceName = "Tablet recepcja",
        tokenHash = "hash",
        pairedAt = Instant.now().minusSeconds(400L * 24 * 3600), // sparowany ponad rok temu
        revokedAt = revokedAt
    )

    @Test
    fun `tablet sparowany dawno temu dalej dziala`() {
        every { repository.findByTokenHash(any()) } returns tablet()

        val session = service.validateToken("token-urzadzenia")

        assertNotNull(session, "Parowanie nie ma prawa wygasnąć z upływem czasu")
        assertEquals(studioId.toString(), session!!.tenantId)
    }

    @Test
    fun `odlaczone urzadzenie przestaje byc wpuszczane`() {
        every { repository.findByTokenHash(any()) } returns tablet(revokedAt = Instant.now())

        assertNull(service.validateToken("token-urzadzenia"))
    }

    @Test
    fun `nieznany token nie otwiera niczego`() {
        every { repository.findByTokenHash(any()) } returns null
        every { redis.opsForValue() } returns valueOps
        every { valueOps.get(any()) } returns null

        assertNull(service.validateToken("token-z-sufitu"))
    }

    @Test
    fun `odlaczenie oznacza urzadzenie jako odlaczone, nie kasuje jego historii`() {
        val existing = tablet()
        every { repository.findByIdAndStudioId(existing.id, studioId) } returns existing
        every { repository.save(any()) } answers { firstArg() }

        service.revokeTablet(studioId.toString(), existing.id.toString())

        assertNotNull(existing.revokedAt, "Odłączenie musi zostawić ślad w rekordzie")
        // Rekord zostaje: audyt podpisów nadal ma z czego odczytać nazwę urządzenia.
        verify { repository.save(existing) }
    }

    @Test
    fun `token nie trafia do bazy w postaci jawnej`() {
        val saved = slot<SigningTabletEntity>()
        every { redis.opsForValue() } returns valueOps
        every { valueOps.getAndDelete(any()) } returns """{"tenantId":"$studioId","createdBy":"u1"}"""
        every { repository.save(capture(saved)) } answers { saved.captured }

        val paired = service.pairTablet("123456", "Tablet recepcja")

        assertNotNull(paired)
        // Token na okaziciela: w bazie zostaje wyłącznie jego skrót.
        assertNotEquals(paired!!.token, saved.captured.tokenHash)
        assertEquals(64, saved.captured.tokenHash.length, "SHA-256 zapisany szesnastkowo ma 64 znaki")
    }
}
