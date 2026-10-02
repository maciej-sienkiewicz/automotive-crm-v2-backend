package pl.detailing.crm.user.presence

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import pl.detailing.crm.user.infrastructure.UserRepository
import java.time.Instant
import java.util.UUID

/**
 * „Ostatnio w aplikacji" zapisuje się najwyżej raz na godzinę na osobę, a błąd zapisu
 * nigdy nie psuje zapytania, w którym wypadł.
 */
class UserPresenceServiceTest {

    private val userRepository = mockk<UserRepository>()
    private val service = UserPresenceService(userRepository)
    private val anna = UUID.randomUUID()
    private val t0 = Instant.parse("2026-10-02T08:00:00Z")

    init {
        every { userRepository.markSeen(any(), any()) } returns 1
        every { userRepository.markLoggedIn(any(), any()) } returns 1
    }

    @Test
    fun `kolejne zapytania w ciagu godziny nie pisza do bazy`() {
        service.touch(anna, t0)
        service.touch(anna, t0.plusSeconds(60))
        service.touch(anna, t0.plusSeconds(59 * 60))

        verify(exactly = 1) { userRepository.markSeen(anna, t0) }
    }

    @Test
    fun `po godzinie aktywnosc zapisuje sie znowu`() {
        service.touch(anna, t0)
        service.touch(anna, t0.plusSeconds(3600))

        verify(exactly = 2) { userRepository.markSeen(anna, any()) }
    }

    @Test
    fun `kazda osoba ma wlasny licznik`() {
        val bartosz = UUID.randomUUID()
        service.touch(anna, t0)
        service.touch(bartosz, t0.plusSeconds(5))

        verify(exactly = 1) { userRepository.markSeen(anna, t0) }
        verify(exactly = 1) { userRepository.markSeen(bartosz, t0.plusSeconds(5)) }
    }

    @Test
    fun `logowanie zapisuje sie zawsze i liczy sie jako aktywnosc`() {
        service.recordLogin(anna, t0)
        service.touch(anna, t0.plusSeconds(120))

        verify(exactly = 1) { userRepository.markLoggedIn(anna, t0) }
        verify(exactly = 0) { userRepository.markSeen(any(), any()) }
    }

    @Test
    fun `blad bazy nie wychodzi poza serwis, a nastepne zapytanie probuje ponownie`() {
        every { userRepository.markSeen(anna, t0) } throws IllegalStateException("db down")

        assertDoesNotThrow { service.touch(anna, t0) }
        service.touch(anna, t0.plusSeconds(10))

        verify(exactly = 1) { userRepository.markSeen(anna, t0.plusSeconds(10)) }
    }

    @Test
    fun `blad zapisu logowania nie przerywa logowania`() {
        every { userRepository.markLoggedIn(anna, t0) } throws IllegalStateException("db down")

        assertDoesNotThrow { service.recordLogin(anna, t0) }
    }
}
