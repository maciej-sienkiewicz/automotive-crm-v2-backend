package pl.detailing.crm.shared.image

import com.drew.imaging.ImageMetadataReader
import com.drew.metadata.exif.ExifIFD0Directory
import java.awt.RenderingHints
import java.awt.geom.AffineTransform
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream

/**
 * Orientacja zdjęcia z EXIF - telefon zapisuje piksele tak, jak leży matryca, i dopisuje
 * znacznik „obróć o 90°". Przeglądarka go respektuje, a ImageIO, Thumbnailator (skalujący
 * gotowy BufferedImage) i PDFBox nie. Każdy obraz liczony na serwerze z takiego zdjęcia -
 * miniatura, zdjęcie z zaznaczonym uszkodzeniem, obraz w PDF - trzeba najpierw postawić
 * pionowo, inaczej wychodzi bokiem, podczas gdy oryginał w przeglądarce stoi prosto.
 *
 * Wartości 1-8 wg specyfikacji EXIF (TIFF 6.0, tag 0x0112): jak przekształcić zapisane
 * piksele, żeby dostać obraz do wyświetlenia.
 */
object ExifOrientation {

    const val UPRIGHT = 1

    /** Znacznik orientacji z bajtów pliku; brak, błąd albo wartość spoza 1-8 = [UPRIGHT]. */
    fun read(imageBytes: ByteArray): Int = try {
        ImageMetadataReader.readMetadata(ByteArrayInputStream(imageBytes))
            .getFirstDirectoryOfType(ExifIFD0Directory::class.java)
            ?.takeIf { it.containsTag(ExifIFD0Directory.TAG_ORIENTATION) }
            ?.getInt(ExifIFD0Directory.TAG_ORIENTATION)
            ?.takeIf { it in 1..8 }
            ?: UPRIGHT
    } catch (e: Exception) {
        UPRIGHT
    }

    /**
     * Piksele obrócone/odbite tak, jak zdjęcie pokazuje przeglądarka. Wyłącznie obrót
     * i odbicie - bez przycinania i skalowania. Najbliższy sąsiad zamiast interpolacji:
     * przy obrotach o wielokrotność 90° każdy piksel trafia dokładnie w piksel, więc
     * operacja jest bezstratna.
     */
    fun upright(image: BufferedImage, orientation: Int): BufferedImage {
        if (orientation <= UPRIGHT || orientation > 8) return image

        val w = image.width.toDouble()
        val h = image.height.toDouble()
        val swapDimensions = orientation >= 5

        val transform = AffineTransform()
        when (orientation) {
            2 -> { transform.translate(w, 0.0); transform.scale(-1.0, 1.0) }                   // odbicie w poziomie
            3 -> { transform.translate(w, h); transform.rotate(Math.PI) }                       // 180°
            4 -> { transform.translate(0.0, h); transform.scale(1.0, -1.0) }                    // odbicie w pionie
            5 -> { transform.rotate(Math.PI / 2); transform.scale(1.0, -1.0) }                  // transpozycja
            6 -> { transform.translate(h, 0.0); transform.rotate(Math.PI / 2) }                 // 90° w prawo
            7 -> { transform.translate(h, w); transform.rotate(-Math.PI / 2); transform.scale(1.0, -1.0) } // transwersja
            8 -> { transform.translate(0.0, w); transform.rotate(-Math.PI / 2) }                // 90° w lewo
        }

        val output = BufferedImage(
            if (swapDimensions) image.height else image.width,
            if (swapDimensions) image.width else image.height,
            BufferedImage.TYPE_INT_RGB,
        )
        val graphics = output.createGraphics()
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR)
            graphics.drawImage(image, transform, null)
        } finally {
            graphics.dispose()
        }
        return output
    }
}
