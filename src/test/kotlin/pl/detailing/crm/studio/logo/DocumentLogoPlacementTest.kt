package pl.detailing.crm.studio.logo

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pl.detailing.crm.studio.logo.DocumentLogoPlacement.Slot

/**
 * Slot logo w nagłówku A4: dopasowanie „contain", wyrównanie do lewej i do kotwicy
 * z szablonów (32,42 pt od góry). Wynik jest w układzie PDF (Y od dołu strony).
 *
 * Granice wysokości nie są tu kwestią gustu: pod slotem stoi tytuł wydrukowany
 * w bundlowanym PDF-ie, którego nie da się przesunąć. Protokół ma go na 78,04 pt,
 * zgoda i RODO na 57,39 pt.
 */
class DocumentLogoPlacementTest {

    private val a4Height = 841.92f
    private val eps = 0.01f

    private fun topOf(box: DocumentLogoPlacement.Box) = a4Height - box.y - box.height
    private fun bottomOf(box: DocumentLogoPlacement.Box) = a4Height - box.y

    @Test
    fun `szeroki logotyp wypelnia szerokosc slotu`() {
        val box = DocumentLogoPlacement.fit(2000, 250, a4Height, Slot.PROTOCOL)

        assertEquals(Slot.PROTOCOL.maxWidth, box.width, eps)
        assertEquals(200f / 8, box.height, eps)
        assertEquals(DocumentLogoPlacement.LEFT, box.x, eps)
    }

    /**
     * Logo mieszczące się w dawnych 36 pt nie może drgnąć — inaczej podwyższenie slotu
     * przestawiłoby znak na dokumentach, które wyglądają dobrze.
     */
    @Test
    fun `logo do 36 pt zostaje wysrodkowane na kotwicy`() {
        listOf(Slot.PROTOCOL, Slot.CONSENT).forEach { slot ->
            val box = DocumentLogoPlacement.fit(2000, 250, a4Height, slot)
            assertEquals(
                DocumentLogoPlacement.ANCHOR_CENTER_Y - box.height / 2, topOf(box), eps,
                "logotyp ${box.width}×${box.height} w slocie $slot zjechał z kotwicy"
            )
        }
    }

    /**
     * Sygnet 1:1 bierze całą wysokość slotu. Górna krawędź to `max(minTop, środek kotwicy
     * − h/2)`: dopóki logo mieści się pod kotwicą, zostaje na niej wyśrodkowane, a wyższe
     * zatrzymuje się na granicy marginesu drukarki i rośnie w dół.
     */
    @Test
    fun `sygnet kwadratowy dostaje pelna wysokosc slotu i rosnie w dol`() {
        listOf(Slot.PROTOCOL, Slot.CONSENT).forEach { slot ->
            val box = DocumentLogoPlacement.fit(1000, 1000, a4Height, slot)
            assertEquals(slot.maxHeight, box.height, eps, "wysokość sygnetu w slocie $slot")
            assertEquals(
                maxOf(slot.minTop, DocumentLogoPlacement.ANCHOR_CENTER_Y - box.height / 2),
                topOf(box), eps, "górna krawędź sygnetu w slocie $slot"
            )
        }
        // Protokół ma pod slotem 22 pt więcej miejsca niż zgoda — i ma je wykorzystać.
        assertTrue(Slot.PROTOCOL.maxHeight > Slot.CONSENT.maxHeight)
    }

    /** Tytuł protokołu zaczyna się na 78,04 pt, a ramka USŁUGODAWCA na 439,20 pt. */
    @Test
    fun `logo nie wchodzi na tytul protokolu ani na ramke uslugodawcy`() {
        listOf(2000 to 250, 1000 to 1000, 300 to 2000).forEach { (w, h) ->
            val box = DocumentLogoPlacement.fit(w, h, a4Height, Slot.PROTOCOL)
            assertTrue(topOf(box) >= 12f, "top ${topOf(box)} dla ${w}x$h wchodzi w margines drukarki")
            assertTrue(bottomOf(box) <= 78.04f, "bottom ${bottomOf(box)} dla ${w}x$h wchodzi na tytuł protokołu")
            assertTrue(box.x + box.width <= 439.2f, "logo wchodzi na ramkę USŁUGODAWCA")
        }
    }

    /** Tytuł zgody i oświadczenia RODO zaczyna się już na 57,39 pt — slot musi być niższy. */
    @Test
    fun `logo nie wchodzi na tytul zgody`() {
        listOf(2000 to 250, 1000 to 1000, 300 to 2000).forEach { (w, h) ->
            val box = DocumentLogoPlacement.fit(w, h, a4Height, Slot.CONSENT)
            assertTrue(topOf(box) >= 12f, "top ${topOf(box)} dla ${w}x$h wchodzi w margines drukarki")
            assertTrue(bottomOf(box) <= 57.39f, "bottom ${bottomOf(box)} dla ${w}x$h wchodzi na tytuł zgody")
        }
    }

    /** Domyślny slot jest ciaśniejszy: nowe wywołanie nie może przypadkiem wejść na tytuł zgody. */
    @Test
    fun `domyslny slot to zgoda`() {
        val explicit = DocumentLogoPlacement.fit(1000, 1000, a4Height, Slot.CONSENT)
        val default = DocumentLogoPlacement.fit(1000, 1000, a4Height)
        assertEquals(explicit, default)
    }
}
