package pl.detailing.crm.shared.qr

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
 * Kod QR jako PNG, rysowany w całości u nas (ZXing + ImageIO).
 *
 * Nigdy przez zewnętrzne API (Google Charts i podobne): treść kodu to dane finansowe -
 * numer rachunku, kwota, adres weryfikacyjny faktury - a zapytanie o obrazek niesie ją
 * w adresie do cudzego serwera, razem z jego logami.
 *
 * Treść zawsze w UTF-8 (ZXing dopisuje wtedy do kodu znacznik ECI): bez tego polskie
 * litery w nazwie odbiorcy wychodzą w aplikacji banku jako krzaki.
 */
@Component
class QrCodeImageFactory {

    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * Parametry obrazka zależą od tego, gdzie kod będzie skanowany.
     *
     * [INVOICE_PDF] - kod na wizualizacji faktury (od lutego 2026 obowiązkowy przy udostępnianiu
     * poza KSeF): korekcja M, bo bywa skanowany z papieru pod kątem; ramka 1 moduł, bo biały
     * margines daje kartka; 512 px, bo na wydruku ma ~64 pt, czyli ~570 DPI.
     */
    data class Spec(
        val sizePx: Int,
        val errorCorrection: ErrorCorrectionLevel,
        val quietZoneModules: Int,
    ) {
        companion object {
            val INVOICE_PDF = Spec(sizePx = 512, errorCorrection = ErrorCorrectionLevel.M, quietZoneModules = 1)
        }
    }

    /** PNG z kodem QR albo null, gdy treści nie da się zakodować. */
    fun png(content: String, spec: Spec = Spec.INVOICE_PDF): ByteArray? = runCatching {
        val matrix = QRCodeWriter().encode(
            content,
            BarcodeFormat.QR_CODE,
            spec.sizePx,
            spec.sizePx,
            mapOf(
                EncodeHintType.ERROR_CORRECTION to spec.errorCorrection,
                EncodeHintType.MARGIN to spec.quietZoneModules,
                EncodeHintType.CHARACTER_SET to Charsets.UTF_8.name()
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
        // Treści kodu nie logujemy: bywa nią numer rachunku z kwotą.
        logger.warn("Nie udało się wygenerować kodu QR: ${it.message}")
    }.getOrNull()
}
