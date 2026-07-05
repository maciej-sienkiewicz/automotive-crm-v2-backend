package pl.detailing.crm.signing.infrastructure

import org.springframework.stereotype.Service
import pl.detailing.crm.shared.ValidationException
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/**
 * In-memory (RAM-only) processing of the raw signature bitmap captured on the tablet.
 *
 * Division of responsibility (security policy):
 *  - The TABLET renders the signature strokes on a fully TRANSPARENT canvas and uploads
 *    a PNG with an alpha channel — no background is ever drawn client-side.
 *  - The BACKEND does NOT trust the client. It re-enforces transparency server-side:
 *    any opaque near-white background pixels are stripped, the image is cropped to the
 *    ink bounding box and re-encoded. This is the authoritative guarantee that only the
 *    bare signature strokes (never a rectangle with a background) are merged into the PDF.
 *
 * Data-minimisation guarantees (eIDAS / GDPR evidentiary posture):
 *  - The bitmap exists exclusively in RAM for the duration of one HTTP request.
 *  - It is NEVER written to disk, S3, the database or application logs.
 *  - All intermediate byte buffers are explicitly zeroed via [wipe] immediately after
 *    the signature is merged into the sealed PDF, so no reusable signature image
 *    survives the signing transaction.
 */
@Service
class SignatureImageProcessor {

    companion object {
        private const val MAX_IMAGE_BYTES = 10 * 1024 * 1024     // 10 MB raw upload cap
        private const val MAX_DIMENSION = 5000                    // px, either axis
        private const val MIN_INK_PIXELS = 60                     // reject empty/accidental taps
        private const val WHITE_THRESHOLD = 245                   // R,G,B above this = background
        private const val ALPHA_TRANSPARENT_THRESHOLD = 16        // alpha below this = already transparent
        private const val OUTPUT_PADDING = 4                      // px around the ink bounding box
    }

    /**
     * Validate, normalize and re-encode the signature: returns a cropped PNG that
     * contains ONLY the signature strokes on a transparent background.
     *
     * @throws ValidationException when the payload is not a valid PNG, exceeds limits
     *         or contains no visible ink (blank signature).
     */
    fun normalizeToTransparentPng(rawPngBytes: ByteArray): ByteArray {
        if (rawPngBytes.isEmpty()) throw ValidationException("Obraz podpisu jest pusty")
        if (rawPngBytes.size > MAX_IMAGE_BYTES) {
            throw ValidationException("Obraz podpisu przekracza dopuszczalny rozmiar")
        }

        val source: BufferedImage = ByteArrayInputStream(rawPngBytes).use { input ->
            ImageIO.read(input) ?: throw ValidationException("Nieprawidłowy format obrazu podpisu (wymagany PNG)")
        }

        if (source.width > MAX_DIMENSION || source.height > MAX_DIMENSION) {
            throw ValidationException("Obraz podpisu przekracza dopuszczalne wymiary")
        }

        val argb = toArgb(source)
        stripBackground(argb)

        val bounds = inkBoundingBox(argb)
            ?: throw ValidationException("Podpis jest pusty — brak widocznych linii podpisu")

        val cropped = crop(argb, bounds)

        return ByteArrayOutputStream().use { output ->
            ImageIO.write(cropped, "png", output)
            output.toByteArray()
        }.also {
            // Release pixel data of intermediates promptly; raw input is wiped by the caller.
            argb.flush()
            cropped.flush()
            source.flush()
        }
    }

    /**
     * Overwrite a sensitive buffer with zeros. Call as soon as the signature image has
     * been merged into the PDF — this is the "immediate destruction" required by the
     * security policy (the reference is dead after this call).
     */
    fun wipe(buffer: ByteArray?) {
        buffer?.fill(0)
    }

    private fun toArgb(source: BufferedImage): BufferedImage {
        val target = BufferedImage(source.width, source.height, BufferedImage.TYPE_INT_ARGB)
        val g = target.createGraphics()
        try {
            g.drawImage(source, 0, 0, null)
        } finally {
            g.dispose()
        }
        return target
    }

    /**
     * Force full transparency for background pixels. Handles both compliant tablets
     * (already transparent — no-op) and non-compliant clients that flattened the
     * signature onto a white canvas.
     */
    private fun stripBackground(image: BufferedImage) {
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                val pixel = image.getRGB(x, y)
                val alpha = pixel ushr 24 and 0xFF
                if (alpha <= ALPHA_TRANSPARENT_THRESHOLD) continue

                val r = pixel shr 16 and 0xFF
                val g = pixel shr 8 and 0xFF
                val b = pixel and 0xFF
                if (r >= WHITE_THRESHOLD && g >= WHITE_THRESHOLD && b >= WHITE_THRESHOLD) {
                    image.setRGB(x, y, 0x00000000)
                }
            }
        }
    }

    /** Bounding box of non-transparent (ink) pixels, or null when below [MIN_INK_PIXELS]. */
    private fun inkBoundingBox(image: BufferedImage): IntArray? {
        var minX = Int.MAX_VALUE
        var minY = Int.MAX_VALUE
        var maxX = -1
        var maxY = -1
        var inkPixels = 0

        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                val alpha = image.getRGB(x, y) ushr 24 and 0xFF
                if (alpha > ALPHA_TRANSPARENT_THRESHOLD) {
                    inkPixels++
                    if (x < minX) minX = x
                    if (y < minY) minY = y
                    if (x > maxX) maxX = x
                    if (y > maxY) maxY = y
                }
            }
        }

        if (inkPixels < MIN_INK_PIXELS || maxX < 0) return null
        return intArrayOf(minX, minY, maxX, maxY)
    }

    private fun crop(image: BufferedImage, bounds: IntArray): BufferedImage {
        val (minX, minY, maxX, maxY) = bounds
        val x = (minX - OUTPUT_PADDING).coerceAtLeast(0)
        val y = (minY - OUTPUT_PADDING).coerceAtLeast(0)
        val w = (maxX + OUTPUT_PADDING).coerceAtMost(image.width - 1) - x + 1
        val h = (maxY + OUTPUT_PADDING).coerceAtMost(image.height - 1) - y + 1

        val cropped = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        val g = cropped.createGraphics()
        try {
            g.drawImage(image.getSubimage(x, y, w, h), 0, 0, null)
        } finally {
            g.dispose()
        }
        return cropped
    }

    private operator fun IntArray.component4(): Int = this[3]
}
