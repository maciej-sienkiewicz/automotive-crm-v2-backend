package pl.detailing.crm.instagram.ads.discovery

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import java.time.LocalDate
import java.util.Optional
import java.util.UUID

/**
 * „Odznacz nowe" — data idzie tylko do przodu i nigdy nie przywraca pigułek,
 * które ktoś już odznaczył.
 */
class AdAreaSettingsAckTest {

    private val repository = mockk<AdAreaSettingsRepository>()
    private val service = AdAreaSettingsService(repository, maxLocations = 20)

    private val studioId = StudioId(UUID.randomUUID())
    private val userId = UserId(UUID.randomUUID())
    private val today = LocalDate.of(2026, 9, 19)

    private fun entity(ack: LocalDate? = null) = AdAreaSettingsEntity(
        studioId = studioId.value,
        locations = "Poznań",
        noveltyAckedThrough = ack
    )

    @Test
    fun `pierwsze odznaczenie zapisuje dzisiejszy dzien`() {
        val entity = entity()
        every { repository.findById(studioId.value) } returns Optional.of(entity)
        every { repository.save(entity) } returns entity

        assertEquals(today, service.acknowledgeNovelty(studioId, userId, today))
        assertEquals(today, entity.noveltyAckedThrough)
        assertEquals(userId.value, entity.updatedByUserId)
    }

    @Test
    fun `powtorne odznaczenie tego samego dnia nie rusza bazy`() {
        val entity = entity(ack = today)
        every { repository.findById(studioId.value) } returns Optional.of(entity)

        assertEquals(today, service.acknowledgeNovelty(studioId, userId, today))
        verify(exactly = 0) { repository.save(any()) }
    }

    @Test
    fun `data nie cofa sie - starsze odznaczenie nie przywraca pigulek`() {
        val entity = entity(ack = today)
        every { repository.findById(studioId.value) } returns Optional.of(entity)

        assertEquals(today, service.acknowledgeNovelty(studioId, userId, today.minusDays(5)))
        assertEquals(today, entity.noveltyAckedThrough)
        verify(exactly = 0) { repository.save(any()) }
    }

    @Test
    fun `studio bez rejonu nie ma czego odznaczac`() {
        every { repository.findById(studioId.value) } returns Optional.empty()

        assertNull(service.acknowledgeNovelty(studioId, userId, today))
        verify(exactly = 0) { repository.save(any()) }
    }
}
