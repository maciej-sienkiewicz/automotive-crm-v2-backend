package pl.detailing.crm.studio.logo

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Slot logo w nagłówku A4: dopasowanie „contain" do kotwicy 188.16 pt szerokości i
 * 36 pt wysokości, wyśrodkowane w pionie na środku kotwicy z szablonów (32.42 pt
 * od góry). Wynik jest w układzie PDF (Y od dołu strony).
 */
class DocumentLogoPlacementTest {

    private val a4Height = 841.92f
    private val eps = 0.01f

    @Test
    fun `szeroki logotyp wypelnia szerokosc kotwicy`() {
        val box = DocumentLogoPlacement.fit(2000, 250, a4Height)

        assertEquals(DocumentLogoPlacement.MAX_WIDTH, box.width, eps)
        assertEquals(188.16f / 8, box.height, eps)
        assertEquals(DocumentLogoPlacement.LEFT, box.x, eps)
        // Wyśrodkowanie: górna krawędź = 32.42 - h/2 (mierzone od góry strony).
        val topFromPageTop = a4Height - box.y - box.height
        assertEquals(DocumentLogoPlacement.ANCHOR_CENTER_Y - box.height / 2, topFromPageTop, eps)
    }

    @Test
    fun `sygnet kwadratowy ogranicza sie wysokoscia slotu`() {
        val box = DocumentLogoPlacement.fit(1000, 1000, a4Height)

        assertEquals(DocumentLogoPlacement.MAX_HEIGHT, box.height, eps)
        assertEquals(DocumentLogoPlacement.MAX_HEIGHT, box.width, eps)
        val topFromPageTop = a4Height - box.y - box.height
        assertEquals(DocumentLogoPlacement.SLOT_TOP, topFromPageTop, eps)
    }

    @Test
    fun `logo nigdy nie wychodzi poza pasmo naglowka zgod (48 pt)`() {
        listOf(2000 to 250, 1000 to 1000, 300 to 2000).forEach { (w, h) ->
            val box = DocumentLogoPlacement.fit(w, h, a4Height)
            val top = a4Height - box.y - box.height
            val bottom = a4Height - box.y
            assertTrue(top >= 0f, "top $top for ${w}x$h")
            assertTrue(bottom <= 57.39f, "bottom $bottom for ${w}x$h przekracza początek tytułu zgody")
            assertTrue(box.x + box.width <= 439.2f, "logo wchodzi na ramkę USŁUGODAWCA")
        }
    }
}
