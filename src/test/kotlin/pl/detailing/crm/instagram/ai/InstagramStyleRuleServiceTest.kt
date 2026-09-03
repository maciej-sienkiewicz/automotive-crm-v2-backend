package pl.detailing.crm.instagram.ai

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pl.detailing.crm.instagram.ai.infrastructure.InstagramStyleRuleEntity
import pl.detailing.crm.instagram.ai.infrastructure.InstagramStyleRuleRepository
import pl.detailing.crm.instagram.ai.model.CreateStyleRuleRequest
import pl.detailing.crm.instagram.ai.model.UpdateStyleRuleRequest
import pl.detailing.crm.instagram.ai.rules.InstagramStyleRuleService
import pl.detailing.crm.shared.EntityNotFoundException
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.ValidationException
import java.time.Instant
import java.util.UUID

/**
 * Reguły stylistyczne studia.
 *
 * Dwa ograniczenia są tu istotne dla działania, nie kosmetyczne: limit aktywnych reguł
 * (każda idzie do promptu w całości, więc bez limitu budżet tokenów przestaje się spinać)
 * oraz to, że reguła innego studia jest nieodróżnialna od nieistniejącej.
 */
class InstagramStyleRuleServiceTest {

    private val repository = mockk<InstagramStyleRuleRepository>()
    private val service = InstagramStyleRuleService(repository)

    private val studio = StudioId.random()

    private fun rule(
        id: UUID = UUID.randomUUID(),
        text: String = "Nie używaj emoji",
        active: Boolean = true
    ) = InstagramStyleRuleEntity(
        id = id,
        studioId = studio.value,
        ruleText = text,
        active = active,
        createdAt = Instant.now(),
        updatedAt = Instant.now()
    )

    @Test
    fun `dwudziesta pierwsza aktywna regula jest odrzucana`() {
        every { repository.countByStudioIdAndActiveTrue(studio.value) } returns
            InstagramStyleRuleService.MAX_ACTIVE_RULES.toLong()

        val ex = assertThrows(ValidationException::class.java) {
            service.create(studio, CreateStyleRuleRequest("Pisz krótkimi zdaniami"))
        }
        assertTrue(ex.message!!.contains("limit"))
    }

    @Test
    fun `pusta i za dluga regula nie przechodzi walidacji`() {
        assertThrows(ValidationException::class.java) {
            service.create(studio, CreateStyleRuleRequest("   "))
        }
        every { repository.countByStudioIdAndActiveTrue(studio.value) } returns 0L
        assertThrows(ValidationException::class.java) {
            service.create(studio, CreateStyleRuleRequest("x".repeat(InstagramStyleRuleService.MAX_RULE_LENGTH + 1)))
        }
    }

    @Test
    fun `regula miesci sie w limicie i zapisuje przycieta tresc`() {
        every { repository.countByStudioIdAndActiveTrue(studio.value) } returns 5L
        val saved = slot<InstagramStyleRuleEntity>()
        every { repository.save(capture(saved)) } answers { saved.captured }

        val response = service.create(studio, CreateStyleRuleRequest("  Nie używaj emoji  "))

        assertEquals("Nie używaj emoji", response.ruleText)
        assertTrue(response.active)
        assertEquals(studio.value, saved.captured.studioId)
    }

    @Test
    fun `cudza regula jest nie do odroznienia od nieistniejacej`() {
        val foreignId = UUID.randomUUID()
        every { repository.findByIdAndStudioId(foreignId, studio.value) } returns null

        assertThrows(EntityNotFoundException::class.java) {
            service.update(studio, foreignId, UpdateStyleRuleRequest(ruleText = "Cokolwiek"))
        }
        assertThrows(EntityNotFoundException::class.java) {
            service.delete(studio, foreignId)
        }
    }

    @Test
    fun `reaktywacja reguly liczy sie do limitu aktywnych`() {
        val inactive = rule(active = false)
        every { repository.findByIdAndStudioId(inactive.id, studio.value) } returns inactive
        every { repository.countByStudioIdAndActiveTrue(studio.value) } returns
            InstagramStyleRuleService.MAX_ACTIVE_RULES.toLong()

        assertThrows(ValidationException::class.java) {
            service.update(studio, inactive.id, UpdateStyleRuleRequest(active = true))
        }
    }
}
