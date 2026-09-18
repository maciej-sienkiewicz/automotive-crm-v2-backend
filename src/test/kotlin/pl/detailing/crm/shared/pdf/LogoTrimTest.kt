package pl.detailing.crm.shared.pdf

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/**
 * Margines wtopiony w plik logo skaluje się razem ze znakiem, więc zjada wysokość slotu
 * w nagłówku dokumentu. To tu decyduje się, czy logo na fakturze ma 20 pt, czy 56 pt.
 */
class LogoTrimTest {

    private fun png(width: Int, height: Int, background: Color?, ink: (BufferedImage) -> Unit): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        background?.let { color ->
            val g = image.createGraphics()
            g.color = color
            g.fillRect(0, 0, width, height)
            g.dispose()
        }
        ink(image)
        val out = ByteArrayOutputStream()
        ImageIO.write(image, "png", out)
        return out.toByteArray()
    }

    private fun sizeOf(bytes: ByteArray): Pair<Int, Int> =
        ImageIO.read(ByteArrayInputStream(bytes)).let { it.width to it.height }

    private fun markAt(image: BufferedImage, x: Int, y: Int, w: Int, h: Int, color: Color = Color(0x11, 0x17, 0x29)) {
        val g = image.createGraphics()
        g.color = color
        g.fillRect(x, y, w, h)
        g.dispose()
    }

    @Test
    fun `odcina przezroczysty margines`() {
        val bytes = png(400, 200, background = null) { markAt(it, 150, 60, 80, 60) }
        assertEquals(80 to 60, sizeOf(LogoTrim.trim(bytes)))
    }

    /** Logo wyeksportowane na białym tle to ten sam problem, tylko alfa go nie zdradza. */
    @Test
    fun `odcina jednolite białe tło`() {
        val bytes = png(400, 200, background = Color.WHITE) { markAt(it, 20, 70, 100, 40) }
        assertEquals(100 to 40, sizeOf(LogoTrim.trim(bytes)))
    }

    /**
     * Regresja z produkcji: logotyp BELLISSIMOTO to biały napis na czarnej płycie 595 × 336 px.
     * Pierwsza wersja ścięła płytę do 437 × 160 px, czyli do samego tuszu — napis dotykał
     * wtedy krawędzi i na fakturze wyglądało to jak logo obcięte nożyczkami. Czarne tło widać
     * na wydruku, więc jest grafiką, nie marginesem.
     */
    @Test
    fun `nie rusza znaku na ciemnej płycie`() {
        val bytes = png(595, 336, background = Color.BLACK) { markAt(it, 79, 88, 437, 160, Color.WHITE) }
        assertArrayEquals(bytes, LogoTrim.trim(bytes))
    }

    /** Kolorowa płyta to ta sama historia co czarna. */
    @Test
    fun `nie rusza znaku na kolorowej płycie`() {
        val bytes = png(400, 200, background = Color(0x0F, 0x17, 0x2A)) { markAt(it, 150, 60, 80, 60, Color.WHITE) }
        assertArrayEquals(bytes, LogoTrim.trim(bytes))
    }

    /** Gradient w tle znaczy, że „margines" jest częścią znaku — lepiej nie ruszać. */
    @Test
    fun `nie rusza obrazu o niejednolitych narożnikach`() {
        val bytes = png(200, 200, background = Color.WHITE) {
            val g = it.createGraphics()
            g.color = Color.BLACK
            g.fillRect(150, 150, 50, 50)
            g.dispose()
        }
        assertArrayEquals(bytes, LogoTrim.trim(bytes))
    }

    /** Logo bez marginesu nie jest przekodowywane — nie ma czego zyskać. */
    @Test
    fun `nie rusza logo wypełniającego kadr`() {
        val bytes = png(120, 80, background = null) { markAt(it, 0, 0, 120, 80) }
        assertArrayEquals(bytes, LogoTrim.trim(bytes))
    }

    /** Uszkodzony plik nie może wywrócić generowania dokumentu. */
    @Test
    fun `bajty nie do odczytania wracają bez zmian`() {
        val bytes = byteArrayOf(1, 2, 3, 4)
        assertArrayEquals(bytes, LogoTrim.trim(bytes))
    }
}
