package pl.detailing.crm.comms.signature

import net.coobird.thumbnailator.Thumbnails
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import pl.detailing.crm.shared.ValidationException
import pl.detailing.crm.studio.logo.CompanyLogoProcessor
import pl.detailing.crm.studio.logo.LogoSourceFormat
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

enum class MailSignatureImageKind { PHOTO, LOGO }

/** Obrazek gotowy do publicznego adresu: zawsze re-enkodowany, nigdy bajty od klienta. */
class ProcessedSignatureImage(
    val bytes: ByteArray,
    val extension: String,
    val contentType: String,
    val width: Int,
    val height: Int
)

/**
 * Zdjęcie i logo do stopki. Trafiają pod publiczny adres, który pobiera klient poczty
 * odbiorcy, więc obowiązują te same zasady co przy logo studia (plik pochodzi od
 * klienta SaaS-a): format po sygnaturze bajtów, wymiary sprawdzone przed dekodowaniem,
 * każdy raster zapisany od nowa — pod publicznym adresem nie może wylądować nic, czego
 * sami nie wygenerowaliśmy (EXIF z GPS-em, poliglota HTML/obraz, SVG ze skryptem).
 *
 * Rozmiary docelowe wynikają z motywów: zdjęcie ma w stopce do 110 px, logo do 120 px
 * szerokości — trzykrotny zapas pokrywa ekrany retina, a więcej to tylko wolniejsze
 * otwieranie każdej wysłanej wiadomości.
 */
@Service
class MailSignatureImageProcessor(
    private val formatDetector: CompanyLogoProcessor
) {

    companion object {
        private val logger = LoggerFactory.getLogger(MailSignatureImageProcessor::class.java)

        const val MAX_BYTES = 5L * 1024 * 1024
        const val MAX_DIMENSION_PX = 8000
        const val MAX_PIXELS = 30_000_000L

        const val PHOTO_EDGE_PX = 360
        const val PHOTO_MIN_EDGE_PX = 96
        const val LOGO_MAX_EDGE_PX = 480
        const val LOGO_MIN_EDGE_PX = 48
        private const val JPEG_QUALITY = 0.9f
        private const val ALPHA_TRANSPARENT_THRESHOLD = 8

        init {
            // Dekoder WebP (TwelveMonkeys) rejestruje się przez ServiceLoader; w fat-jarze
            // Spring Boota bywa widoczny dopiero po jawnym skanie.
            ImageIO.scanForPlugins()
        }
    }

    fun process(bytes: ByteArray, kind: MailSignatureImageKind): ProcessedSignatureImage {
        if (bytes.isEmpty()) throw ValidationException("Plik obrazka jest pusty")
        if (bytes.size > MAX_BYTES) throw ValidationException("Obrazek nie może przekraczać 5 MB")
        when (formatDetector.detectFormat(bytes)) {
            LogoSourceFormat.PNG, LogoSourceFormat.JPEG, LogoSourceFormat.WEBP -> Unit
            else -> throw ValidationException("Nieobsługiwany format obrazka. Dozwolone: JPG, PNG, WebP")
        }
        val source = decode(bytes)
        return when (kind) {
            MailSignatureImageKind.PHOTO -> photo(source)
            MailSignatureImageKind.LOGO -> logo(source)
        }.also {
            logger.info("Mail signature image processed: kind={} source={}x{} out={}x{} {}",
                kind, source.width, source.height, it.width, it.height, it.extension)
        }
    }

    /**
     * Kwadrat wycięty ze środka. Kadr ustawia użytkownik w kreatorze, ale serwer i tak
     * tnie do kwadratu: motywy rysują zdjęcie jako koło o stałym boku, a `object-fit`
     * nie działa w Outlooku — prostokąt byłby tam ściśnięty.
     */
    private fun photo(source: BufferedImage): ProcessedSignatureImage {
        val side = minOf(source.width, source.height)
        if (side < PHOTO_MIN_EDGE_PX) {
            throw ValidationException(
                "Zdjęcie jest za małe (${source.width}×${source.height} px). " +
                    "Krótszy bok musi mieć co najmniej $PHOTO_MIN_EDGE_PX px"
            )
        }
        val square = source.getSubimage((source.width - side) / 2, (source.height - side) / 2, side, side)
        val target = minOf(side, PHOTO_EDGE_PX)
        val scaled = Thumbnails.of(flattenOnWhite(square))
            .size(target, target)
            .imageType(BufferedImage.TYPE_INT_RGB)
            .asBufferedImage()
        return ProcessedSignatureImage(toJpeg(scaled), "jpg", "image/jpeg", scaled.width, scaled.height)
    }

    /** PNG, bo logo zwykle ma przezroczyste tło, które w stopce ma zostać przezroczyste. */
    private fun logo(source: BufferedImage): ProcessedSignatureImage {
        val trimmed = trimTransparentMargins(toArgb(source))
        if (maxOf(trimmed.width, trimmed.height) < LOGO_MIN_EDGE_PX) {
            throw ValidationException(
                "Logo jest za małe (${trimmed.width}×${trimmed.height} px). " +
                    "Dłuższy bok musi mieć co najmniej $LOGO_MIN_EDGE_PX px"
            )
        }
        val scaled = if (maxOf(trimmed.width, trimmed.height) <= LOGO_MAX_EDGE_PX) {
            trimmed
        } else {
            Thumbnails.of(trimmed)
                .size(LOGO_MAX_EDGE_PX, LOGO_MAX_EDGE_PX)
                .keepAspectRatio(true)
                .imageType(BufferedImage.TYPE_INT_ARGB)
                .asBufferedImage()
        }
        return ProcessedSignatureImage(toPng(scaled), "png", "image/png", scaled.width, scaled.height)
    }

    private fun decode(bytes: ByteArray): BufferedImage {
        ImageIO.createImageInputStream(ByteArrayInputStream(bytes)).use { input ->
            val reader = ImageIO.getImageReaders(input).asSequence().firstOrNull()
                ?: throw ValidationException("Nie udało się odczytać obrazka. Plik jest uszkodzony")
            try {
                reader.input = input
                val width = reader.getWidth(0)
                val height = reader.getHeight(0)
                // Przed dekodowaniem: mały plik potrafi rozpakować się do gigabajtów.
                if (width > MAX_DIMENSION_PX || height > MAX_DIMENSION_PX || width.toLong() * height > MAX_PIXELS) {
                    throw ValidationException(
                        "Obrazek jest za duży (${width}×${height} px). Maksymalnie $MAX_DIMENSION_PX px na bok"
                    )
                }
                return reader.read(0) ?: throw ValidationException("Nie udało się odczytać obrazka. Plik jest uszkodzony")
            } catch (e: ValidationException) {
                throw e
            } catch (e: Exception) {
                logger.warn("Mail signature image decode failed: {}", e.message)
                throw ValidationException("Nie udało się odczytać obrazka. Plik jest uszkodzony")
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

    /** JPEG nie zna przezroczystości; bez podkładu przezroczyste piksele wyszłyby czarne. */
    private fun flattenOnWhite(image: BufferedImage): BufferedImage {
        val rgb = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_RGB)
        val g = rgb.createGraphics()
        try {
            g.color = Color.WHITE
            g.fillRect(0, 0, image.width, image.height)
            g.drawImage(image, 0, 0, null)
        } finally {
            g.dispose()
        }
        return rgb
    }

    /** Eksporty logo mają zwykle szerokie puste marginesy, które w stopce zmniejszają znak. */
    private fun trimTransparentMargins(image: BufferedImage): BufferedImage {
        var top = image.height
        var bottom = -1
        var left = image.width
        var right = -1
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                val alpha = (image.getRGB(x, y) ushr 24) and 0xFF
                if (alpha > ALPHA_TRANSPARENT_THRESHOLD) {
                    if (y < top) top = y
                    if (y > bottom) bottom = y
                    if (x < left) left = x
                    if (x > right) right = x
                }
            }
        }
        if (bottom < 0) throw ValidationException("Logo jest całkowicie przezroczyste")
        if (top == 0 && left == 0 && bottom == image.height - 1 && right == image.width - 1) return image
        return image.getSubimage(left, top, right - left + 1, bottom - top + 1)
    }

    private fun toPng(image: BufferedImage): ByteArray =
        ByteArrayOutputStream().use { out ->
            if (!ImageIO.write(image, "png", out)) throw IllegalStateException("PNG writer unavailable")
            out.toByteArray()
        }

    private fun toJpeg(image: BufferedImage): ByteArray {
        val writer = ImageIO.getImageWritersByFormatName("jpg").next()
        return try {
            ByteArrayOutputStream().use { out ->
                ImageIO.createImageOutputStream(out).use { stream ->
                    writer.output = stream
                    val params = writer.defaultWriteParam.apply {
                        compressionMode = ImageWriteParam.MODE_EXPLICIT
                        compressionQuality = JPEG_QUALITY
                    }
                    writer.write(null, IIOImage(image, null, null), params)
                }
                out.toByteArray()
            }
        } finally {
            writer.dispose()
        }
    }
}
