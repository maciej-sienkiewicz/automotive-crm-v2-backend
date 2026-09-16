package pl.detailing.crm.visit.damagemap

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import pl.detailing.crm.shared.VisitId
import pl.detailing.crm.visit.domain.DamageAnnotationPoint
import pl.detailing.crm.visit.domain.DamageAnnotationStroke
import pl.detailing.crm.visit.domain.DamagePhoto
import pl.detailing.crm.visit.domain.DamagePoint
import java.util.UUID

/**
 * Punkty uszkodzeń są dowodem w sporze „kto zrobił tę rysę", więc przejście
 * pamięć → jsonb → pamięć musi być stratne w niczym: współrzędne, opis, zdjęcia
 * i pociągnięcia pisaka włącznie.
 */
class VisitDamageMapStoreTest {

    private val repository: VisitDamageMapRepository = mockk()
    private val store = VisitDamageMapStore(repository)

    private val visitId = VisitId(UUID.randomUUID())
    private val studioId = StudioId(UUID.randomUUID())
    private val userId = UserId(UUID.randomUUID())

    private val points = listOf(
        DamagePoint(
            id = 1,
            x = 12.5,
            y = 78.25,
            note = "głęboka rysa na masce",
            photos = listOf(
                DamagePhoto(
                    photoId = "photo-1",
                    strokes = listOf(
                        DamageAnnotationStroke(
                            color = "#EF4444",
                            width = 0.8,
                            points = listOf(DamageAnnotationPoint(10.0, 20.0), DamageAnnotationPoint(30.5, 40.5))
                        )
                    )
                )
            )
        ),
        DamagePoint(id = 2, x = 0.0, y = 100.0, note = null)
    )

    private fun captureSavedJson(existing: VisitDamageMapEntity? = null): String {
        every { repository.findByVisitIdAndStudioId(visitId.value, studioId.value) } returns existing
        val saved = slot<VisitDamageMapEntity>()
        every { repository.save(capture(saved)) } answers { saved.captured }

        store.save(
            visitId = visitId,
            studioId = studioId,
            damagePoints = points,
            vehicleType = "suv",
            userId = userId,
            userName = "Anna Kowalska"
        )
        return saved.captured.damagePointsJson
    }

    @Test
    fun `zapis i odczyt nie gubia ani wspolrzednych, ani zaznaczen na zdjeciach`() {
        val json = captureSavedJson()

        every { repository.findByVisitIdAndStudioId(visitId.value, studioId.value) } returns
            VisitDamageMapEntity(
                visitId = visitId.value,
                studioId = studioId.value,
                damagePointsJson = json,
                vehicleType = "suv"
            )

        val loaded = store.load(visitId, studioId)!!

        assertEquals(points, loaded.damagePoints)
        assertEquals("suv", loaded.vehicleType)
    }

    @Test
    fun `pierwszy zapis to rewizja 1, kolejny ja podnosi`() {
        every { repository.findByVisitIdAndStudioId(visitId.value, studioId.value) } returns null
        val saved = slot<VisitDamageMapEntity>()
        every { repository.save(capture(saved)) } answers { saved.captured }

        assertEquals(1, store.save(visitId, studioId, points, "suv"))

        val existing = VisitDamageMapEntity(
            visitId = visitId.value,
            studioId = studioId.value,
            damagePointsJson = "[]",
            revision = 1
        )
        every { repository.findByVisitIdAndStudioId(visitId.value, studioId.value) } returns existing

        assertEquals(2, store.save(visitId, studioId, points, "suv"))
        // Mapa z przyjęcia zapisuje się bez podnoszenia licznika — to JEST wersja 1.
        assertEquals(2, store.save(visitId, studioId, points, "suv", bumpRevision = false))
    }

    @Test
    fun `klucz dokumentu bez wartosci nie kasuje poprzedniego`() {
        val existing = VisitDamageMapEntity(
            visitId = visitId.value,
            studioId = studioId.value,
            damagePointsJson = "[]",
            documentS3Key = "studio/visits/v/damage-map.pdf"
        )
        every { repository.findByVisitIdAndStudioId(visitId.value, studioId.value) } returns existing
        every { repository.save(existing) } returns existing

        store.save(visitId, studioId, points, "suv", documentS3Key = null)

        assertEquals("studio/visits/v/damage-map.pdf", existing.documentS3Key)
    }

    @Test
    fun `uszkodzony punkt jest pomijany, a reszta mapy sie otwiera`() {
        // y = 250 nie przejdzie walidacji DamagePoint. Cała mapa nie może z tego
        // powodu przestać się czytać — dokument, po który ktoś sięga w sporze z
        // klientem, ma pokazać to, co da się pokazać.
        val brokenJson = """
            [
              {"id":1,"x":10.0,"y":20.0,"note":"rysa","photos":[]},
              {"id":2,"x":10.0,"y":250.0,"note":"punkt z bledna wspolrzedna","photos":[]},
              {"id":3,"x":50.0,"y":50.0,"note":"wgniecenie","photos":[]}
            ]
        """.trimIndent()
        every { repository.findByVisitIdAndStudioId(visitId.value, studioId.value) } returns
            VisitDamageMapEntity(
                visitId = visitId.value,
                studioId = studioId.value,
                damagePointsJson = brokenJson
            )

        val loaded = store.load(visitId, studioId)!!

        assertEquals(listOf(1, 3), loaded.damagePoints.map { it.id })
    }

    @Test
    fun `nieznane pole w zapisanej mapie nie wywraca odczytu`() {
        // Wdrożenie kanarkowe: nowsza instancja dopisała pole, starsza musi czytać.
        every { repository.findByVisitIdAndStudioId(visitId.value, studioId.value) } returns
            VisitDamageMapEntity(
                visitId = visitId.value,
                studioId = studioId.value,
                damagePointsJson = """[{"id":1,"x":1.0,"y":2.0,"note":"rysa","severity":"HIGH"}]"""
            )

        val loaded = store.load(visitId, studioId)!!

        assertEquals(1, loaded.damagePoints.single().id)
        assertTrue(loaded.damagePoints.single().photos.isEmpty())
    }
}
