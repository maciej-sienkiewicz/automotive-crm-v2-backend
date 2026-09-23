package pl.detailing.crm.visit.photos

import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import pl.detailing.crm.shared.VisitPhotoId
import pl.detailing.crm.visit.domain.VisitPhoto
import pl.detailing.crm.visit.infrastructure.VisitEntity
import pl.detailing.crm.visit.infrastructure.VisitPhotoEntity
import java.time.Instant
import java.util.UUID

/**
 * Dodanie i usunięcie zdjęcia przepisuje całą kolekcję zdjęć wizyty. Ręczna kopia pól
 * w AddVisitPhotoHandler / DeleteVisitPhotoHandler gubiła thumbnailFileId, więc każda
 * taka operacja kasowała miniatury pozostałych zdjęć (galeria ładowała wtedy oryginały).
 * Oba handlery idą teraz przez [VisitPhotoEntity.fromDomain] - to mapowanie nie może
 * zgubić żadnego pola.
 */
class VisitPhotoEntityMappingTest {

    @Test
    fun `fromDomain przenosi wszystkie pola - miniature i autora tez`() {
        val photo = VisitPhoto(
            id = VisitPhotoId(UUID.randomUUID()),
            fileId = "studio/visits/v/photos/p",
            fileName = "przod.jpg",
            description = "Przód przed myciem",
            uploadedAt = Instant.parse("2026-09-10T08:15:00Z"),
            uploadedBy = UUID.randomUUID(),
            uploadedByName = "Krzysztof Niemier",
            thumbnailFileId = "studio/visits/v/photos/p_thumb.jpg"
        )

        val entity = VisitPhotoEntity.fromDomain(photo, mockk<VisitEntity>(relaxed = true))

        assertEquals("studio/visits/v/photos/p_thumb.jpg", entity.thumbnailFileId)
        assertEquals("Krzysztof Niemier", entity.uploadedByName)
        assertEquals(photo, entity.toDomain())
    }
}
