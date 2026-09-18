package pl.detailing.crm.ksef.revenue.pdf

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/**
 * Kod QR na wizualizację faktury.
 *
 * Od lutego 2026 wizualizacja faktury ustrukturyzowanej udostępniana poza KSeF musi
 * nieść kod QR prowadzący do weryfikacji dokumentu u Ministerstwa Finansów — bez niego
 * odbiorca nie ma jak sprawdzić, czy PDF odpowiada fakturze w systemie.
 *
 * Korekcja błędów M (15%): kod jest drukowany i bywa skanowany z papieru pod kątem,
 * a adres weryfikacyjny jest krótki, więc wyższy poziom nic nie kosztuje w rozmiarze.
 */
@Component
class QrCodeImageFactory {

    private val logger = LoggerFactory.getLogger(javaClass)

    companion object {
        /** Bok bitmapy w pikselach. Na wydruku kod ma ~64 pt, więc 512 px to ~570 DPI. */
        private const val SIZE_PX = 512
        private const val QUIET_ZONE_MODULES = 1
    }

    /** PNG z kodem QR albo null, gdy treści nie da się zakodować. */
    fun png(content: String): ByteArray? = runCatching {
        val matrix = QRCodeWriter().encode(
            content,
            BarcodeFormat.QR_CODE,
            SIZE_PX,
            SIZE_PX,
            mapOf(
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
                EncodeHintType.MARGIN to QUIET_ZONE_MODULES,
                EncodeHintType.CHARACTER_SET to "UTF-8"
            )
        )
        val image = BufferedImage(matrix.width, matrix.height, BufferedImage.TYPE_INT_RGB)
        val black = Color.BLACK.rgb
        val white = Color.WHITE.rgb
        for (x in 0 until matrix.width) {
            for (y in 0 until matrix.height) {
                image.setRGB(x, y, if (matrix.get(x, y)) black else white)
            }
        }
        ByteArrayOutputStream().use { out ->
            ImageIO.write(image, "png", out)
            out.toByteArray()
        }
    }.onFailure {
        logger.warn("Nie udało się wygenerować kodu QR dla faktury: ${it.message}")
    }.getOrNull()
}
