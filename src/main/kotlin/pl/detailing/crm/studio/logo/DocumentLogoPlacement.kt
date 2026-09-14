package pl.detailing.crm.studio.logo

/**
 * Gdzie na stronie A4 ląduje logo studia — wspólnie dla systemowych protokołów
 * (przyjęcia, wydania) i zgód, bo wszystkie szablony rezerwują tę samą kotwicę:
 * „188.16 × 16.38 pt, x = 29.30 pt od lewej krawędzi, top 24.23 pt" (komentarze
 * w plikach .html w resources/templates — odwzorowanie bundlowanych PDF-ów).
 *
 * Kotwica opisuje oryginalny, bardzo płaski logotyp. Logo klientów miewa dowolne
 * proporcje (sygnet 1:1, logotyp 8:1), więc slot jest wyższy niż kotwica: do
 * [MAX_HEIGHT] pt, wyśrodkowany w pionie na jej środku. Mieści się w paśmie
 * nagłówka obu rodzin dokumentów (68.65 pt w protokołach, 48 pt w zgodach — tytuł
 * zaczyna się dopiero pod nim) i nie wchodzi na ramkę USŁUGODAWCA po prawej
 * (x ≥ 439 pt). Logo skalujemy „contain": nigdy nie deformujemy, nigdy nie
 * powiększamy ponad slot, wyrównujemy do lewej.
 */
object DocumentLogoPlacement {

    /** Wszystko w pt (1/72"), mierzone jak w CSS — od lewej/górnej krawędzi strony. */
    const val LEFT = 29.30f
    const val ANCHOR_TOP = 24.23f
    const val ANCHOR_HEIGHT = 16.38f
    const val MAX_WIDTH = 188.16f
    const val MAX_HEIGHT = 36f

    const val ANCHOR_CENTER_Y = ANCHOR_TOP + ANCHOR_HEIGHT / 2
    const val SLOT_TOP = ANCHOR_CENTER_Y - MAX_HEIGHT / 2

    /** Prostokąt w przestrzeni użytkownika PDF (początek układu w lewym DOLNYM rogu). */
    data class Box(val x: Float, val y: Float, val width: Float, val height: Float)

    /**
     * @param imageWidth  szerokość rastra w px
     * @param imageHeight wysokość rastra w px
     * @param pageHeight  wysokość strony w pt (A4 = 841.92) — do odwrócenia osi Y
     */
    fun fit(imageWidth: Int, imageHeight: Int, pageHeight: Float): Box {
        require(imageWidth > 0 && imageHeight > 0) { "Logo image has no pixels" }
        val scale = minOf(MAX_WIDTH / imageWidth, MAX_HEIGHT / imageHeight)
        val width = imageWidth * scale
        val height = imageHeight * scale
        val topFromPageTop = ANCHOR_CENTER_Y - height / 2
        return Box(
            x = LEFT,
            y = pageHeight - topFromPageTop - height,
            width = width,
            height = height
        )
    }
}
