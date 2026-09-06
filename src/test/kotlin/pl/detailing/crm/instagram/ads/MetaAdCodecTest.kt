package pl.detailing.crm.instagram.ads

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Lokalizacje i rozbicie zasięgu jadą do bazy jako tekst z separatorami, więc
 * wartość zawierająca separator rozjechałaby cały wiersz. Nazwy lokalizacji
 * przychodzą od Meta i nie mamy nad nimi kontroli — stąd te testy.
 */
class MetaAdCodecTest {

    @Test
    fun `lokalizacje wracają w tej samej postaci`() {
        val input = listOf(
            RawAdLocation("Kraków", "city", excluded = false),
            RawAdLocation("Zakopane", "city", excluded = true)
        )
        assertEquals(input, MetaAdCodec.decodeLocations(MetaAdCodec.encodeLocations(input)))
    }

    @Test
    fun `separator w nazwie lokalizacji nie rozbija wiersza`() {
        val encoded = MetaAdCodec.encodeLocations(
            listOf(RawAdLocation("Kraków; Nowa Huta | centrum", "city", excluded = false))
        )
        val decoded = MetaAdCodec.decodeLocations(encoded)
        assertEquals(1, decoded.size)
        assertEquals("city", decoded.first().type)
        assertTrue(decoded.first().name.startsWith("Kraków"))
    }

    @Test
    fun `rozbicie zasiegu wraca w tej samej postaci`() {
        val input = listOf(
            RawAgeGenderReach("25-34", male = 10150, female = 3200, unknown = 0),
            RawAgeGenderReach("65+", male = 495, female = 95, unknown = 12)
        )
        assertEquals(input, MetaAdCodec.decodeBreakdown(MetaAdCodec.encodeBreakdown(input)))
    }

    @Test
    fun `pusty tekst daje pustą listę, a nie wiersz smieci`() {
        assertTrue(MetaAdCodec.decodeBreakdown("").isEmpty())
        assertTrue(MetaAdCodec.decodeLocations("").isEmpty())
        assertTrue(MetaAdCodec.decodePlatforms("").isEmpty())
    }

    @Test
    fun `platformy sa normalizowane i odduplikowane`() {
        assertEquals(
            listOf("FACEBOOK", "INSTAGRAM"),
            MetaAdCodec.decodePlatforms(MetaAdCodec.encodePlatforms(listOf("facebook", "Instagram", "FACEBOOK")))
        )
    }
}
