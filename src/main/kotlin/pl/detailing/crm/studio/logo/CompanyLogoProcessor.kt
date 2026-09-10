package pl.detailing.crm.studio.logo

import net.coobird.thumbnailator.Thumbnails
import org.apache.batik.transcoder.SVGAbstractTranscoder
import org.apache.batik.transcoder.TranscoderInput
import org.apache.batik.transcoder.TranscoderOutput
import org.apache.batik.transcoder.image.ImageTranscoder
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.DocumentType
import org.jsoup.nodes.Element
import org.jsoup.nodes.Entities
import org.jsoup.nodes.XmlDeclaration
import org.jsoup.parser.Parser
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import pl.detailing.crm.shared.ValidationException
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/** Format wgranego pliku rozpoznany po zawartości, nie po rozszerzeniu ani nagłówku HTTP. */
enum class LogoSourceFormat(val contentType: String) {
    PNG("image/png"),
    JPEG("image/jpeg"),
    WEBP("image/webp"),
    SVG("image/svg+xml")
}

/**
 * Wynik przetworzenia jednego wgranego pliku na warianty używane przez system.
 *
 * @property appPng    mały PNG z przezroczystością do menu bocznego i ustawień
 * @property printPng  PNG w rozdzielczości do druku A4 — stemplowany w PDF przez PDFBox
 * @property vectorSvg oczyszczony SVG, gdy użytkownik wgrał wektor (do szablonów HTML)
 */
class ProcessedLogo(
    val sourceFormat: LogoSourceFormat,
    val appPng: ByteArray,
    val printPng: ByteArray,
    val vectorSvg: ByteArray?,
    val printWidth: Int,
    val printHeight: Int,
    /**
     * Czy logo zniknęłoby na ciemnym pasku menu: ma przezroczyste tło i ciemny „tusz".
     * Logo z własnym tłem (czarnym, białym, dowolnym) nie potrzebuje podkładki.
     */
    val needsLightPlate: Boolean
) {
    val aspectRatio: Double get() = printWidth.toDouble() / printHeight
}

/**
 * Przetwarzanie logo studia — czysto w pamięci, bez natywnych zależności (obraz
 * produkcyjny to alpine z samym JRE, bez ImageMagicka i librsvg).
 *
 * Właściciel warsztatu wgrywa jeden plik; stąd wychodzą warianty do dwóch skrajnie
 * różnych kontekstów: 36-pikselowy kafelek w menu i nagłówek dokumentu drukowanego
 * w 300–600 DPI. Bezpieczeństwo (SaaS wielodostępny — plik pochodzi od klienta):
 *  - format rozpoznajemy po sygnaturze bajtów, nagłówek Content-Type z przeglądarki
 *    jest tylko wskazówką,
 *  - wymiary sprawdzamy PRZED dekodowaniem (mały plik potrafi rozpakować się do
 *    gigabajtów — decompression bomb),
 *  - każdy raster jest re-enkodowany (gubi metadane EXIF i ładunki ukryte w pliku),
 *  - SVG to XML z wykonywalnym JavaScriptem i odwołaniami do zasobów zewnętrznych;
 *    przechodzi przez [sanitizeSvg], zanim trafi do S3 albo do Batika.
 */
@Service
class CompanyLogoProcessor {

    companion object {
        private val logger = LoggerFactory.getLogger(CompanyLogoProcessor::class.java)

        const val MAX_BYTES = 5L * 1024 * 1024
        /** Poniżej tego logo na A4 (ok. 66 mm szerokości nagłówka) jest już wyraźnie rozmyte. */
        const val MIN_LONGER_EDGE_PX = 300
        const val MAX_DIMENSION_PX = 8000
        const val MAX_PIXELS = 30_000_000L
        /** Dłuższa krawędź wariantu drukowego: 2000 px na 66 mm ≈ 770 DPI — z zapasem na laser. */
        const val PRINT_MAX_EDGE_PX = 2000
        /** Kafelek w menu ma 36 px; 256 px pokrywa ekrany 3× z zapasem na sygnety w 56 px. */
        const val APP_MAX_EDGE_PX = 256

        private const val ALPHA_TRANSPARENT_THRESHOLD = 8
        /** R, G i B powyżej tej wartości = biały margines (papier, ramka z eksportu), nie logo. */
        private const val WHITE_MARGIN_THRESHOLD = 245
        private const val SNIFF_BYTES = 4096
        /** Poniżej tej części przezroczystych pikseli logo „ma własne tło" (zaokrąglone rogi to ułamek procenta). */
        private const val TRANSPARENT_BACKGROUND_MIN_FRACTION = 0.05
        /** Średnia luminancja tuszu, poniżej której logo na przezroczystym tle ginie na #0f172a. */
        private const val DARK_INK_LUMINANCE = 0.5

        private val FORBIDDEN_SVG_ELEMENTS = setOf(
            "script", "foreignobject", "iframe", "object", "embed", "audio", "video",
            "handler", "animate", "animatemotion", "animatetransform", "animatecolor", "set", "discard"
        )
        private val SAFE_DATA_URI = Regex("^data:image/(png|jpe?g|gif|webp);base64,", RegexOption.IGNORE_CASE)
        private val CSS_EXTERNAL_URL = Regex("""url\(\s*(['"]?)\s*(?!#)[^)]*\)""", RegexOption.IGNORE_CASE)
        private val CSS_IMPORT = Regex("""@import[^;]*;?""", RegexOption.IGNORE_CASE)
        private val CSS_EXPRESSION = Regex("""expression\s*\(""", RegexOption.IGNORE_CASE)

        init {
            // Dekoder WebP (TwelveMonkeys) rejestruje się przez ServiceLoader; w fat-jarze
            // Spring Boota bywa widoczny dopiero po jawnym skanie.
            ImageIO.scanForPlugins()
        }
    }

    fun process(bytes: ByteArray): ProcessedLogo {
        if (bytes.isEmpty()) throw ValidationException("Plik logo jest pusty")
        if (bytes.size > MAX_BYTES) throw ValidationException("Logo nie może przekraczać 5 MB")

        val format = detectFormat(bytes)
            ?: throw ValidationException("Nieobsługiwany format logo. Dozwolone: SVG, PNG, WebP, JPEG")

        val vectorSvg: ByteArray?
        val source: BufferedImage
        if (format == LogoSourceFormat.SVG) {
            val sanitized = sanitizeSvg(String(bytes, Charsets.UTF_8))
            vectorSvg = sanitized.toByteArray(Charsets.UTF_8)
            source = rasterizeSvg(vectorSvg)
        } else {
            vectorSvg = null
            source = decodeRaster(bytes)
        }

        val normalized = trimMargins(toArgb(source))
        val longerEdge = maxOf(normalized.width, normalized.height)
        // Wektor rasteryzujemy sami w docelowej rozdzielczości, więc minimum dotyczy tylko rastrów.
        if (format != LogoSourceFormat.SVG && longerEdge < MIN_LONGER_EDGE_PX) {
            throw ValidationException(
                "Logo jest za małe (${normalized.width}×${normalized.height} px). " +
                    "Dłuższy bok musi mieć co najmniej $MIN_LONGER_EDGE_PX px, zalecane 1000 px lub plik SVG"
            )
        }

        val print = scaleToFit(normalized, PRINT_MAX_EDGE_PX)
        val app = scaleToFit(normalized, APP_MAX_EDGE_PX)
        val needsLightPlate = needsLightPlate(app)

        logger.info(
            "Logo processed: format={} source={}x{} print={}x{} app={}x{} vector={}",
            format, source.width, source.height, print.width, print.height, app.width, app.height, vectorSvg != null
        )
        return ProcessedLogo(
            sourceFormat = format,
            appPng = toPng(app),
            printPng = toPng(print),
            vectorSvg = vectorSvg,
            printWidth = print.width,
            printHeight = print.height,
            needsLightPlate = needsLightPlate
        )
    }

    /**
     * Menu boczne jest ciemne (#0f172a). Czarny logotyp na przezroczystym tle byłby tam
     * niewidoczny, więc dostaje jasną podkładkę. Logo z własnym tłem (jak biały napis
     * na czarnym prostokącie) niesie swój kontrast i podkładka tylko by je zepsuła.
     */
    fun needsLightPlate(image: BufferedImage): Boolean {
        var transparent = 0L
        var inked = 0L
        var luminanceSum = 0.0
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                val argb = image.getRGB(x, y)
                val alpha = (argb ushr 24) and 0xFF
                if (alpha < 250) transparent++
                if (alpha > ALPHA_TRANSPARENT_THRESHOLD) {
                    inked++
                    val r = (argb shr 16) and 0xFF
                    val g = (argb shr 8) and 0xFF
                    val b = argb and 0xFF
                    luminanceSum += (0.2126 * r + 0.7152 * g + 0.0722 * b) / 255.0
                }
            }
        }
        val total = image.width.toLong() * image.height
        if (inked == 0L || transparent < total * TRANSPARENT_BACKGROUND_MIN_FRACTION) return false
        return luminanceSum / inked < DARK_INK_LUMINANCE
    }

    /** Rozpoznanie po sygnaturze bajtów. `null` = nic z listy dozwolonych. */
    fun detectFormat(bytes: ByteArray): LogoSourceFormat? {
        if (bytes.size < 12) return sniffSvg(bytes)
        val b = bytes.map { it.toInt() and 0xFF }
        if (b[0] == 0x89 && b[1] == 0x50 && b[2] == 0x4E && b[3] == 0x47 &&
            b[4] == 0x0D && b[5] == 0x0A && b[6] == 0x1A && b[7] == 0x0A
        ) return LogoSourceFormat.PNG
        if (b[0] == 0xFF && b[1] == 0xD8 && b[2] == 0xFF) return LogoSourceFormat.JPEG
        if (String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" &&
            String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP"
        ) return LogoSourceFormat.WEBP
        return sniffSvg(bytes)
    }

    private fun sniffSvg(bytes: ByteArray): LogoSourceFormat? {
        val head = String(bytes, 0, minOf(bytes.size, SNIFF_BYTES), Charsets.UTF_8)
        if (head.any { it == ' ' }) return null
        return if (head.contains("<svg", ignoreCase = true)) LogoSourceFormat.SVG else null
    }

    /**
     * Zostawia z SVG wyłącznie to, co rysuje: usuwa skrypty, obiekty obce, animacje,
     * atrybuty zdarzeń, odwołania `javascript:` i każde `href`/`url()` poza kotwicą
     * `#id` w tym samym pliku lub obrazkiem `data:image/…`. DOCTYPE i instrukcje
     * przetwarzania znikają (encje XML = billion laughs, `xml-stylesheet` = zasób zewnętrzny).
     *
     * Style zostają — eksporty z Illustratora trzymają wypełnienia w `<style>` i klasach,
     * bez nich logo rysowałoby się na czarno.
     */
    fun sanitizeSvg(svg: String): String {
        val doc = Jsoup.parse(svg.removePrefix("﻿"), "", Parser.xmlParser())
        doc.outputSettings()
            .syntax(Document.OutputSettings.Syntax.xml)
            .escapeMode(Entities.EscapeMode.xhtml)
            .prettyPrint(false)

        doc.childNodes().filter { it is DocumentType || it is XmlDeclaration }.forEach { it.remove() }

        val root = doc.children().firstOrNull()
            ?: throw ValidationException("Plik SVG nie zawiera elementu <svg>")
        if (!root.tagName().equals("svg", ignoreCase = true)) {
            throw ValidationException("Plik SVG musi zaczynać się od elementu <svg>")
        }
        doc.children().drop(1).forEach { it.remove() }

        root.getAllElements().toList().forEach { element ->
            if (element.tagName().lowercase() in FORBIDDEN_SVG_ELEMENTS) {
                element.remove()
                return@forEach
            }
            if (element.tagName().equals("a", ignoreCase = true)) {
                element.unwrap()
                return@forEach
            }
            sanitizeAttributes(element)
            if (element.tagName().equals("style", ignoreCase = true)) {
                // Parser XML trzyma treść <style> jako zwykły tekst (data() jest puste).
                val css = element.data().ifEmpty { element.wholeText() }
                element.text(sanitizeCss(css))
            }
        }

        if (!root.hasAttr("xmlns")) root.attr("xmlns", "http://www.w3.org/2000/svg")
        return """<?xml version="1.0" encoding="UTF-8"?>""" + root.outerHtml()
    }

    private fun sanitizeAttributes(element: Element) {
        element.attributes().asList().forEach { attr ->
            val name = attr.key.lowercase()
            val value = attr.value.trim()
            val isHref = name == "href" || name.endsWith(":href")
            val remove = when {
                name.startsWith("on") -> true
                value.contains("javascript:", ignoreCase = true) -> true
                name == "externalresourcesrequired" -> true
                isHref -> !(value.startsWith("#") || SAFE_DATA_URI.containsMatchIn(value))
                else -> false
            }
            if (remove) {
                element.removeAttr(attr.key)
            } else if (name == "style") {
                element.attr(attr.key, sanitizeCss(value))
            }
        }
    }

    private fun sanitizeCss(css: String): String = css
        .replace(CSS_IMPORT, "")
        .replace(CSS_EXPRESSION, "none(")
        .replace(CSS_EXTERNAL_URL, "none")

    private fun rasterizeSvg(svg: ByteArray): BufferedImage {
        val transcoder = BufferedImageTranscoder()
        // KEY_WIDTH skaluje wektor do docelowej szerokości (bez tego Batik renderuje
        // w rozmiarze z viewBoxa — nawet 100 px), KEY_MAX_HEIGHT domyka pionowe logotypy.
        transcoder.addTranscodingHint(SVGAbstractTranscoder.KEY_WIDTH, PRINT_MAX_EDGE_PX.toFloat())
        transcoder.addTranscodingHint(SVGAbstractTranscoder.KEY_MAX_HEIGHT, PRINT_MAX_EDGE_PX.toFloat())
        transcoder.addTranscodingHint(SVGAbstractTranscoder.KEY_ALLOW_EXTERNAL_RESOURCES, false)
        try {
            transcoder.transcode(TranscoderInput(ByteArrayInputStream(svg)), null)
        } catch (e: Exception) {
            logger.warn("SVG rasterization failed: ${e.message}")
            throw ValidationException("Nie udało się odczytać pliku SVG. Sprawdź, czy plik nie jest uszkodzony")
        }
        return transcoder.image
            ?: throw ValidationException("Nie udało się odczytać pliku SVG. Sprawdź, czy plik nie jest uszkodzony")
    }

    private class BufferedImageTranscoder : ImageTranscoder() {
        var image: BufferedImage? = null
        override fun createImage(width: Int, height: Int): BufferedImage =
            BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)

        override fun writeImage(img: BufferedImage, output: TranscoderOutput?) {
            image = img
        }
    }

    private fun decodeRaster(bytes: ByteArray): BufferedImage {
        ImageIO.createImageInputStream(ByteArrayInputStream(bytes)).use { input ->
            val reader = ImageIO.getImageReaders(input).asSequence().firstOrNull()
                ?: throw ValidationException("Nie udało się odczytać obrazu. Plik jest uszkodzony lub zapisany w nieobsługiwanym wariancie formatu")
            try {
                reader.input = input
                val width = reader.getWidth(0)
                val height = reader.getHeight(0)
                if (width > MAX_DIMENSION_PX || height > MAX_DIMENSION_PX || width.toLong() * height > MAX_PIXELS) {
                    throw ValidationException(
                        "Logo jest za duże (${width}×${height} px). Maksymalnie $MAX_DIMENSION_PX px na bok"
                    )
                }
                return reader.read(0)
                    ?: throw ValidationException("Nie udało się odczytać obrazu. Plik jest uszkodzony")
            } catch (e: ValidationException) {
                throw e
            } catch (e: Exception) {
                logger.warn("Raster decode failed: ${e.message}")
                throw ValidationException("Nie udało się odczytać obrazu. Plik jest uszkodzony lub zapisany w nieobsługiwanym wariancie formatu")
            } finally {
                reader.dispose()
            }
        }
    }

    private fun toArgb(image: BufferedImage): BufferedImage {
        if (image.type == BufferedImage.TYPE_INT_ARGB) return image
        val argb = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB)
        val g = argb.createGraphics()
        try {
            g.drawImage(image, 0, 0, null)
        } finally {
            g.dispose()
        }
        return argb
    }

    /**
     * Ucina marginesy, które nie są logiem: przezroczyste, a w pliku bez przezroczystości
     * (JPEG, nieprzezroczysty PNG) także jednolicie białe. Eksporty logo zwykle mają
     * sporo pustki dookoła, a białą ramkę dokładają narzędzia do eksportu i skany;
     * każdy taki piksel zmniejsza logo w kafelku menu i w slocie nagłówka dokumentu,
     * a biała ramka wokół ciemnego logo wygląda w ciemnym menu jak obwódka.
     *
     * Biel jest marginesem tylko w pliku bez przezroczystości: w logo z przezroczystym
     * tłem białe piksele to zwykle tusz (biały napis), nie papier.
     */
    private fun trimMargins(image: BufferedImage): BufferedImage {
        val opaqueFile = !hasTransparency(image)
        fun isMargin(argb: Int): Boolean {
            val alpha = (argb ushr 24) and 0xFF
            if (alpha <= ALPHA_TRANSPARENT_THRESHOLD) return true
            if (!opaqueFile) return false
            val r = (argb shr 16) and 0xFF
            val g = (argb shr 8) and 0xFF
            val b = argb and 0xFF
            return r >= WHITE_MARGIN_THRESHOLD && g >= WHITE_MARGIN_THRESHOLD && b >= WHITE_MARGIN_THRESHOLD
        }

        var top = image.height
        var bottom = -1
        var left = image.width
        var right = -1
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                if (!isMargin(image.getRGB(x, y))) {
                    if (y < top) top = y
                    if (y > bottom) bottom = y
                    if (x < left) left = x
                    if (x > right) right = x
                }
            }
        }
        if (bottom < 0) throw ValidationException("Logo jest puste: plik jest całkowicie przezroczysty albo biały")
        if (top == 0 && left == 0 && bottom == image.height - 1 && right == image.width - 1) return image
        return image.getSubimage(left, top, right - left + 1, bottom - top + 1)
    }

    private fun hasTransparency(image: BufferedImage): Boolean {
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                if (((image.getRGB(x, y) ushr 24) and 0xFF) < 250) return true
            }
        }
        return false
    }

    /** Zmniejsza, nigdy nie powiększa — rozciągnięty raster to rozmyte logo bez zysku. */
    private fun scaleToFit(image: BufferedImage, maxEdge: Int): BufferedImage {
        if (maxOf(image.width, image.height) <= maxEdge) return image
        return Thumbnails.of(image)
            .size(maxEdge, maxEdge)
            .keepAspectRatio(true)
            .imageType(BufferedImage.TYPE_INT_ARGB)
            .asBufferedImage()
    }

    private fun toPng(image: BufferedImage): ByteArray =
        ByteArrayOutputStream().use { out ->
            if (!ImageIO.write(image, "png", out)) throw IllegalStateException("PNG writer unavailable")
            out.toByteArray()
        }
}
