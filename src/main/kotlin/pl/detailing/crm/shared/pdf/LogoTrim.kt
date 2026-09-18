package pl.detailing.crm.shared.pdf

import org.slf4j.LoggerFactory
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/**
 * Przycina NIEWIDOCZNE obrzeże logo: przezroczyste albo białe, czyli takie, które na białej
 * kartce i tak nie istnieje.
 *
 * Slot w nagłówku skaluje CAŁY obraz („contain"), więc margines wtopiony w plik zjada
 * wysokość — znak firmowy siada w rogu kartki wielkości znaczka, chociaż miejsca jest
 * pod dostatkiem. Tak właśnie wyglądało logo na pierwszej fakturze: w slocie 188 × 36 pt
 * sam znak miał 26 × 20 pt, reszta to było puste pole z pliku.
 *
 * ## Dwa rodzaje tła, dwa zachowania
 *
 * Tło przezroczyste albo białe jak kartka jest NICZYM: na białym dokumencie nie widać
 * różnicy między nim a pustym miejscem, więc przycinamy je do zera.
 *
 * Tło w kolorze — czarna płyta, granatowy kafelek — widać i jest częścią znaku, ale
 * zapas dookoła niego bywa ogromny i to on zjada slot. Taki nadmiar też przycinamy,
 * zostawiając [VISIBLE_PLATE_PADDING_RATIO] szerokości krótszego boku tuszu jako margines.
 * Bez tego marginesu logotyp BELLISSIMOTO (biały napis na czarnej płycie 595 × 336 px)
 * schodził do 437 × 160 px, czyli do samego tuszu: napis dotykał krawędzi płyty, tarcza
 * traciła łuk u góry i szpic u dołu, a faktura wyglądała, jakby logo obcięto nożyczkami.
 *
 * Margines bierzemy z ORYGINAŁU — nie dorysowujemy pikseli. Gdy znak dochodzi w pliku do
 * samej krawędzi, wychodzi tyle zapasu, ile było, bo to już krawędź grafiki, a nie cięcie.
 *
 * Liczymy to po pikselach, bo alternatywą jest kazanie właścicielowi warsztatu poprawić
 * eksport w programie graficznym — a plik raz wgrany wraca na każdy dokument.
 *
 * Reszta ostrożności: przycinamy tylko wtedy, gdy wszystkie cztery narożniki mają to samo
 * tło (inaczej nie wiadomo, co jest marginesem), i tylko gdy jest co przycinać. Przy
 * jakiejkolwiek wątpliwości wracają oryginalne bajty — gorzej obciąć logo niż zostawić
 * je za małe.
 */
object LogoTrim {

    private val logger = LoggerFactory.getLogger(LogoTrim::class.java)

    /** Poniżej tej alfy piksel jest tłem, a nie tuszem (ta sama granica co w CompanyLogoProcessor). */
    private const val ALPHA_VISIBLE = 8

    /** Tolerancja na kanał — kompresja stratna rozjaśnia i przyciemnia jednolite tło o kilka poziomów. */
    private const val CHANNEL_TOLERANCE = 6

    /** Poniżej tego zysku nie ma po co przekodowywać obrazu. */
    private const val MIN_GAIN = 0.02

    /**
     * Od tej jasności każdego kanału tło jest bielą kartki: na białym dokumencie nie widać
     * różnicy między nim a niczym, więc wolno je wyrzucić co do piksela.
     */
    private const val PAPER_WHITE_MIN = 245

    /**
     * Margines zostawiany na widocznej płycie, liczony od krótszego boku tuszu. 6% daje
     * około 3 pt przy logo wypełniającym slot nagłówka — widać, że jest, i nie zjada miejsca.
     * Proporcja, a nie stała liczba pikseli: wariant drukowy ma do 2000 px dłuższego boku,
     * więc „kilka pikseli" znaczy co innego w każdym pliku.
     */
    private const val VISIBLE_PLATE_PADDING_RATIO = 0.06

    /** Dolna granica marginesu: przy małym logo 6% schodzi do zera i znowu tniemy równo z tuszem. */
    private const val MIN_VISIBLE_PLATE_PADDING_PX = 3

    fun trim(bytes: ByteArray): ByteArray = runCatching { trimOrNull(bytes) ?: bytes }
        .onFailure { logger.warn("Nie udało się przyciąć marginesów logo: ${it.message}") }
        .getOrDefault(bytes)

    private fun trimOrNull(bytes: ByteArray): ByteArray? {
        val image = ImageIO.read(ByteArrayInputStream(bytes)) ?: return null
        val w = image.width
        val h = image.height
        if (w < 2 || h < 2) return null

        val pixels = image.getRGB(0, 0, w, h, null, 0, w)
        val corners = listOf(
            pixels[0], pixels[w - 1], pixels[(h - 1) * w], pixels[h * w - 1]
        )
        val background = corners.first()
        // Narożniki różnią się od siebie -> to nie jest obraz z jednolitym marginesem.
        if (corners.any { !sameBackground(it, background) }) return null

        var left = w
        var right = -1
        var top = h
        var bottom = -1
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                if (isBackground(pixels[row + x], background)) continue
                if (x < left) left = x
                if (x > right) right = x
                if (y < top) top = y
                if (y > bottom) bottom = y
            }
        }
        if (right < left || bottom < top) return null

        // Widoczna płyta zostaje z marginesem, żeby znak nie dotykał jej krawędzi; tła, którego
        // i tak nie widać, nie ma sensu zostawiać ani piksela.
        val padding = if (isInvisibleOnPaper(background)) {
            0
        } else {
            maxOf(
                MIN_VISIBLE_PLATE_PADDING_PX,
                Math.round(minOf(right - left + 1, bottom - top + 1) * VISIBLE_PLATE_PADDING_RATIO).toInt()
            )
        }
        // Margines bierzemy z oryginału, więc przycinamy go do granic obrazu — nic nie dorysowujemy.
        val cropLeft = maxOf(0, left - padding)
        val cropTop = maxOf(0, top - padding)
        val cropRight = minOf(w - 1, right + padding)
        val cropBottom = minOf(h - 1, bottom + padding)

        val newW = cropRight - cropLeft + 1
        val newH = cropBottom - cropTop + 1
        val gain = 1.0 - (newW.toDouble() * newH) / (w.toDouble() * h)
        if (gain < MIN_GAIN) return null

        // Kopia, nie getSubimage: podobraz dzieli raster z oryginałem, a zapis takiego
        // rastra bywa zależny od kodeka.
        val cropped = BufferedImage(newW, newH, BufferedImage.TYPE_INT_ARGB)
        cropped.setRGB(0, 0, newW, newH, pixels, cropTop * w + cropLeft, w)

        val out = ByteArrayOutputStream()
        if (!ImageIO.write(cropped, "png", out)) return null
        logger.debug("Logo przycięte z {}×{} do {}×{} px", w, h, newW, newH)
        return out.toByteArray()
    }

    private fun alpha(argb: Int): Int = (argb ushr 24) and 0xFF

    /** Tło, którego na białej kartce nie widać: przezroczyste albo białe — tu margines jest zerowy. */
    private fun isInvisibleOnPaper(background: Int): Boolean {
        if (alpha(background) < ALPHA_VISIBLE) return true
        return (0..2).all { shift -> ((background ushr (shift * 8)) and 0xFF) >= PAPER_WHITE_MIN }
    }

    /**
     * Tło może być przezroczyste albo jednolicie zamalowane (białe, czarne, dowolne).
     * Dwa przezroczyste piksele są tym samym tłem niezależnie od barwy pod spodem —
     * eksporty zapisują pod alfą 0 śmieci.
     */
    private fun sameBackground(argb: Int, background: Int): Boolean {
        val transparent = alpha(argb) < ALPHA_VISIBLE
        val backgroundTransparent = alpha(background) < ALPHA_VISIBLE
        if (transparent || backgroundTransparent) return transparent && backgroundTransparent
        return near(argb, background)
    }

    private fun isBackground(argb: Int, background: Int): Boolean {
        if (alpha(argb) < ALPHA_VISIBLE) return true
        if (alpha(background) < ALPHA_VISIBLE) return false
        return near(argb, background)
    }

    private fun near(argb: Int, background: Int): Boolean =
        (0..2).all { shift ->
            val bits = shift * 8
            val a = (argb ushr bits) and 0xFF
            val b = (background ushr bits) and 0xFF
            Math.abs(a - b) <= CHANNEL_TOLERANCE
        }
}
