package pl.detailing.crm.studio.logo

/**
 * Gdzie na stronie A4 ląduje logo studia — wspólnie dla systemowych protokołów
 * (przyjęcia, wydania) i zgód (marketingowe, RODO), bo wszystkie szablony rezerwują
 * tę samą kotwicę: „188.16 × 16.38 pt, x = 29.30 pt od lewej krawędzi, top 24.23 pt"
 * (komentarze w plikach .html w resources/templates — odwzorowanie bundlowanych PDF-ów).
 *
 * Kotwica opisuje oryginalny, bardzo płaski logotyp. Logo klientów miewa dowolne
 * proporcje (sygnet 1:1, logotyp 8:1), więc slot jest od niej wyższy. Logo skalujemy
 * „contain": nigdy nie deformujemy, nigdy nie powiększamy ponad slot, wyrównujemy
 * do lewej.
 *
 * ## Dlaczego dwa sloty
 *
 * Wysokość slotu ogranicza pierwsza treść wydrukowana w bundlowanym PDF-ie — tej nie
 * da się przesunąć, bo dokument jest gotowym plikiem, a nie układem, który się przelicza:
 *
 * | dokument                  | pierwsza treść w lewej kolumnie | slot        |
 * |---------------------------|--------------------------------|-------------|
 * | protokół przyjęcia/wydania| tytuł na 78,04 pt              | 56 pt       |
 * | zgody marketingowe, RODO  | tytuł na 57,39 pt              | 38 pt       |
 *
 * Wcześniej oba miały 36 pt — wysokość dobraną pod ciaśniejszy z dokumentów. Na protokole
 * zostawiało to 28 pt pustego pasma nad tytułem, a znak firmowy o proporcjach zbliżonych
 * do kwadratu siadał w rogu kartki wielkości znaczka.
 */
object DocumentLogoPlacement {

    /** Wszystko w pt (1/72"), mierzone jak w CSS — od lewej/górnej krawędzi strony. */
    const val LEFT = 29.30f
    const val ANCHOR_TOP = 24.23f
    const val ANCHOR_HEIGHT = 16.38f
    const val ANCHOR_CENTER_Y = ANCHOR_TOP + ANCHOR_HEIGHT / 2

    /**
     * Slot na logo w rodzinie dokumentów.
     *
     * [minTop] to granica, poniżej której logo nie może się wspiąć: 12–14 pt to około
     * 5 mm od krawędzi kartki, czyli tyle, ile zwykła drukarka laserowa i tak obcina.
     */
    enum class Slot(val maxWidth: Float, val maxHeight: Float, val minTop: Float) {
        /** Protokół przyjęcia i wydania pojazdu: tytuł zaczyna się na 78,04 pt. */
        PROTOCOL(200f, 56f, 14.42f),

        /** Zgody marketingowe i oświadczenie RODO: tytuł zaczyna się na 57,39 pt. */
        CONSENT(200f, 38f, 12f)
    }

    /** Prostokąt w przestrzeni użytkownika PDF (początek układu w lewym DOLNYM rogu). */
    data class Box(val x: Float, val y: Float, val width: Float, val height: Float)

    /**
     * @param imageWidth  szerokość rastra w px
     * @param imageHeight wysokość rastra w px
     * @param pageHeight  wysokość strony w pt (A4 = 841.92) — do odwrócenia osi Y
     * @param slot        rodzina dokumentu; domyślnie ciaśniejsza, żeby nowe wywołanie
     *                    nie mogło przypadkiem wejść na tytuł
     */
    fun fit(
        imageWidth: Int,
        imageHeight: Int,
        pageHeight: Float,
        slot: Slot = Slot.CONSENT
    ): Box {
        require(imageWidth > 0 && imageHeight > 0) { "Logo image has no pixels" }
        val scale = minOf(slot.maxWidth / imageWidth, slot.maxHeight / imageHeight)
        val width = imageWidth * scale
        val height = imageHeight * scale
        // Logo mieszczące się w dawnych 36 pt zostaje dokładnie tam, gdzie stało:
        // wyśrodkowane na kotwicy z szablonów. Wyższe nie ma jak pójść w górę — margines
        // niedrukowalny — więc rośnie w dół, w pasmo, które i tak jest puste.
        val topFromPageTop = maxOf(slot.minTop, ANCHOR_CENTER_Y - height / 2)
        return Box(
            x = LEFT,
            y = pageHeight - topFromPageTop - height,
            width = width,
            height = height
        )
    }
}
