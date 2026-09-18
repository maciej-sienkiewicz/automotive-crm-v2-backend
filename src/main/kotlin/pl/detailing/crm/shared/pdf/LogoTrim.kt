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
 * ## Dlaczego tylko przezroczyste i białe
 *
 * Pierwsza wersja przycinała dowolne jednolite tło wzięte z narożników — i ścięła czarną
 * płytę logotypu BELLISSIMOTO (595 × 336 px) do 437 × 160 px, czyli do samego tuszu. Skan
 * działał poprawnie, tyle że założenie było błędne: czarny prostokąt nie jest marginesem,
 * jest częścią znaku. Po przycięciu napis dotykał krawędzi płyty i dokument wyglądał,
 * jakby logo obcięto nożyczkami.
 *
 * Piksel wolno wyrzucić tylko wtedy, gdy jego brak niczego nie zmienia na wydruku: gdy jest
 * przezroczysty albo praktycznie biały jak kartka. Każde inne tło — czarne, granatowe,
 * kolorowe — jest grafiką i zostaje.
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
     * różnicy między nim a niczym, więc wolno je wyrzucić.
     */
    private const val PAPER_WHITE_MIN = 245

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
        // Tło widać na wydruku -> jest grafiką, nie marginesem (patrz KDoc: BELLISSIMOTO).
        if (!isInvisibleOnPaper(background)) return null

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

        val newW = right - left + 1
        val newH = bottom - top + 1
        val gain = 1.0 - (newW.toDouble() * newH) / (w.toDouble() * h)
        if (gain < MIN_GAIN) return null

        // Kopia, nie getSubimage: podobraz dzieli raster z oryginałem, a zapis takiego
        // rastra bywa zależny od kodeka.
        val cropped = BufferedImage(newW, newH, BufferedImage.TYPE_INT_ARGB)
        cropped.setRGB(0, 0, newW, newH, pixels, top * w + left, w)

        val out = ByteArrayOutputStream()
        if (!ImageIO.write(cropped, "png", out)) return null
        logger.debug("Logo przycięte z {}×{} do {}×{} px", w, h, newW, newH)
        return out.toByteArray()
    }

    private fun alpha(argb: Int): Int = (argb ushr 24) and 0xFF

    /** Tło, którego na białej kartce nie widać: przezroczyste albo białe. */
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
