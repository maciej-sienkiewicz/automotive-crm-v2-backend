package pl.detailing.crm.shared.image

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.awt.image.BufferedImage

/**
 * Każda z ośmiu orientacji EXIF sprawdzona piksel po pikselu względem definicji
 * ze specyfikacji: dla wyświetlanego punktu (x', y') - który punkt zapisanego obrazu
 * ma się tam znaleźć. Obraz 3×2 z unikalnym kolorem każdego piksela.
 */
class ExifOrientationTest {

    private val w = 3
    private val h = 2

    private fun color(x: Int, y: Int) = ((x * 60 + 40) shl 16) or ((y * 90 + 50) shl 8) or 0x20

    private val stored = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB).apply {
        for (x in 0 until w) for (y in 0 until h) setRGB(x, y, color(x, y))
    }

    /** Punkt zapisanego obrazu, który ma być widoczny w (x', y') - wprost z definicji EXIF. */
    private fun sourceOf(orientation: Int, x: Int, y: Int): Pair<Int, Int> = when (orientation) {
        2 -> (w - 1 - x) to y                 // odbicie w poziomie
        3 -> (w - 1 - x) to (h - 1 - y)       // 180°
        4 -> x to (h - 1 - y)                 // odbicie w pionie
        5 -> y to x                           // transpozycja
        6 -> y to (h - 1 - x)                 // 90° w prawo
        7 -> (w - 1 - y) to (h - 1 - x)       // transwersja
        8 -> (w - 1 - y) to x                 // 90° w lewo
        else -> x to y
    }

    @ParameterizedTest(name = "orientacja {0}")
    @ValueSource(ints = [2, 3, 4, 5, 6, 7, 8])
    fun `piksele trafiaja tam, gdzie wg EXIF pokazuje je przegladarka`(orientation: Int) {
        val out = ExifOrientation.upright(stored, orientation)

        val swapped = orientation >= 5
        assertEquals(if (swapped) h else w, out.width, "szerokość")
        assertEquals(if (swapped) w else h, out.height, "wysokość")
        for (x in 0 until out.width) for (y in 0 until out.height) {
            val (sx, sy) = sourceOf(orientation, x, y)
            assertEquals(color(sx, sy) and 0xFFFFFF, out.getRGB(x, y) and 0xFFFFFF, "piksel ($x, $y)")
        }
    }

    @Test
    fun `zdjecie juz pionowe i znacznik spoza zakresu zostaja bez zmian`() {
        assertSame(stored, ExifOrientation.upright(stored, 1))
        assertSame(stored, ExifOrientation.upright(stored, 0))
        assertSame(stored, ExifOrientation.upright(stored, 9))
    }

    @Test
    fun `plik bez EXIF albo nie-obraz to orientacja pionowa`() {
        assertEquals(ExifOrientation.UPRIGHT, ExifOrientation.read(ByteArray(0)))
        assertEquals(ExifOrientation.UPRIGHT, ExifOrientation.read("to nie jest zdjęcie".toByteArray()))
    }
}
