package pl.detailing.crm.instagram.ai

import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import pl.detailing.crm.instagram.ai.infrastructure.InstagramGeneratedPostEntity
import pl.detailing.crm.instagram.ai.infrastructure.InstagramGeneratedPostRepository
import pl.detailing.crm.instagram.ai.model.GeneratedPostRating
import pl.detailing.crm.instagram.ai.model.RateGeneratedPostRequest
import pl.detailing.crm.instagram.ai.rating.GeneratedPostVectorIndexer
import pl.detailing.crm.instagram.ai.rating.InstagramGeneratedPostService
import pl.detailing.crm.shared.EntityNotFoundException
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.ValidationException
import java.time.Instant
import java.util.UUID

/**
 * Ocena wygenerowanego posta — wejście do pętli uczenia.
 *
 * Komentarz ma sens wyłącznie przy ocenie negatywnej: to on niesie instrukcję
 * („za dużo wykrzykników") dla kolejnych generowań. Przy ocenie pozytywnej nie ma czego
 * poprawiać, więc komentarz jest błędem żądania, a nie po cichu ignorowanym polem.
 */
class InstagramGeneratedPostRatingTest {

    private val repository = mockk<InstagramGeneratedPostRepository>()
    private val vectorIndexer = mockk<GeneratedPostVectorIndexer>(relaxed = true)
    private val service = InstagramGeneratedPostService(repository, vectorIndexer)

    private val studio = StudioId.random()

    private fun post() = InstagramGeneratedPostEntity(
        id = UUID.randomUUID(),
        studioId = studio.value,
        topic = "PPF na BMW M4",
        additionalContext = null,
        requestedTone = "premium",
        requestedLength = "full",
        content = "Treść posta",
        verificationReportJson = null,
        rulesSnapshotJson = "[\"Nie używaj emoji\"]",
        createdAt = Instant.now()
    )

    private fun stubSave(entity: InstagramGeneratedPostEntity) {
        every { repository.findByIdAndStudioId(entity.id, studio.value) } returns entity
        every { repository.save(entity) } returns entity
    }

    @Test
    fun `komentarz przy ocenie pozytywnej jest bledem zadania`() {
        val entity = post()
        stubSave(entity)

        assertThrows(ValidationException::class.java) {
            service.rate(studio, entity.id, RateGeneratedPostRequest(GeneratedPostRating.POSITIVE, "super"))
        }
        assertNull(entity.rating, "Odrzucone żądanie nie może zostawić śladu na encji")
    }

    @Test
    fun `ocena negatywna zapisuje komentarz, date i trafia do bazy wektorowej`() {
        val entity = post()
        stubSave(entity)

        val response = service.rate(
            studio, entity.id,
            RateGeneratedPostRequest(GeneratedPostRating.NEGATIVE, "  za dużo wykrzykników  ")
        )

        assertEquals("NEGATIVE", response.rating)
        assertEquals("za dużo wykrzykników", response.ratingComment)
        assertNotNull(response.ratedAt)
        assertEquals(listOf("Nie używaj emoji"), response.rulesSnapshot)
        coVerify(timeout = 2000) {
            vectorIndexer.index(studio, entity.id, "Treść posta", GeneratedPostRating.NEGATIVE, "za dużo wykrzykników")
        }
    }

    @Test
    fun `ponowna ocena nadpisuje poprzednia`() {
        val entity = post()
        stubSave(entity)

        service.rate(studio, entity.id, RateGeneratedPostRequest(GeneratedPostRating.NEGATIVE, "za nachalne"))
        val second = service.rate(studio, entity.id, RateGeneratedPostRequest(GeneratedPostRating.POSITIVE, null))

        assertEquals("POSITIVE", second.rating)
        assertNull(second.ratingComment, "Komentarz z poprzedniej oceny nie może przetrwać zmiany werdyktu")
        coVerify(timeout = 2000) {
            vectorIndexer.index(studio, entity.id, "Treść posta", GeneratedPostRating.POSITIVE, null)
        }
    }

    @Test
    fun `cudzy post jest nie do odroznienia od nieistniejacego`() {
        val foreignId = UUID.randomUUID()
        every { repository.findByIdAndStudioId(foreignId, studio.value) } returns null

        assertThrows(EntityNotFoundException::class.java) {
            service.rate(studio, foreignId, RateGeneratedPostRequest(GeneratedPostRating.POSITIVE, null))
        }
    }
}
