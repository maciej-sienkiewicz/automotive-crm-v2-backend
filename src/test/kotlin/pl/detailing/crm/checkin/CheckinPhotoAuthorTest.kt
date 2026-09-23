package pl.detailing.crm.checkin

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import pl.detailing.crm.shared.UserId
import pl.detailing.crm.shared.VisitPhotoId
import java.util.UUID

/**
 * Zdjęcia z przyjęcia pojazdu (sesja zdjęć, telefon przez QR) zapisywały się bez autora,
 * więc galeria pokazywała przy nich puste „Dodał/a". Autorem jest osoba przyjmująca pojazd.
 */
class CheckinPhotoAuthorTest {

    @Test
    fun `zdjecie z przyjecia ma autora - osobe przyjmujaca pojazd`() {
        val userId = UserId(UUID.randomUUID())
        val photoId = UUID.randomUUID()

        val photo = checkinPhoto(photoId, "studio/visits/v/photos/p", "przod.jpg", userId, "Krzysztof Niemier")

        assertEquals(VisitPhotoId(photoId), photo.id)
        assertEquals("studio/visits/v/photos/p", photo.fileId)
        assertEquals("przod.jpg", photo.fileName)
        assertEquals(userId.value, photo.uploadedBy)
        assertEquals("Krzysztof Niemier", photo.uploadedByName)
        assertNull(photo.description)
    }
}
