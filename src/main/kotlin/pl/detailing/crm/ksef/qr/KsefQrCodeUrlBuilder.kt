package pl.detailing.crm.ksef.qr

import org.springframework.stereotype.Component
import pl.detailing.crm.ksef.config.KsefProperties
import java.security.MessageDigest
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Base64

/**
 * Buduje adres weryfikacyjny KSeF („KOD I") — ten sam, który KSeF nakazuje umieszczać
 * na wizualizacji faktury w postaci kodu QR. Zeskanowanie prowadzi do strony MF
 * potwierdzającej, że dokument o takim skrócie istnieje w KSeF i nie został podmieniony.
 *
 * Format (dokumentacja KSeF 2.0, „Kody QR"):
 *
 *     {qrBaseUrl}/invoice/{NIP sprzedawcy}/{dd-MM-yyyy}/{SHA-256 XML w Base64URL}
 *
 * Skrót to SHA-256 *oryginalnego* (nieszyfrowanego) XML faktury zakodowany
 * Base64URL bez dopełnienia. KSeF w API posługuje się tym samym skrótem w Base64
 * standardowym (`invoiceHash` w metadanych i przy wysyłce), więc wartość zapisaną
 * przy wysyłce wystarczy przekodować — nie trzeba przechowywać XML-a, co ma
 * znaczenie dla faktur EXTERNAL, których treści nie pobieramy.
 *
 * Sam URL budujemy lokalnie zamiast sięgać po `QrCodeService` z SDK: format jest
 * stabilną częścią kontraktu MF, a lokalna implementacja działa też dla faktur
 * pobranych z KSeF (mamy skrót, nie mamy XML-a) i pozwala pokryć go testem.
 */
@Component
class KsefQrCodeUrlBuilder(private val properties: KsefProperties) {

    companion object {
        private val DATE_FORMAT = DateTimeFormatter.ofPattern("dd-MM-yyyy")
        private const val SHA256_LENGTH_BYTES = 32
    }

    /**
     * Adres weryfikacyjny faktury albo null, gdy brakuje któregokolwiek składnika
     * (NIP sprzedawcy lub skrót dokumentu) — lepiej nie pokazać kodu QR niż pokazać
     * taki, który po zeskanowaniu prowadzi donikąd.
     */
    fun buildInvoiceVerificationUrl(sellerNip: String?, issueDate: LocalDate?, invoiceHash: String?): String? {
        val nip = sellerNip?.filter { it.isDigit() }?.takeIf { it.isNotBlank() } ?: return null
        val date = issueDate ?: return null
        val hash = toBase64Url(invoiceHash) ?: return null
        return "${properties.resolvedQrBaseUrl().trimEnd('/')}/invoice/$nip/${date.format(DATE_FORMAT)}/$hash"
    }

    /** SHA-256 XML-a faktury w Base64URL — dla dokumentów, dla których skrótu nie zapisano. */
    fun hashInvoiceXml(invoiceXml: String): String =
        Base64.getUrlEncoder().withoutPadding()
            .encodeToString(MessageDigest.getInstance("SHA-256").digest(invoiceXml.toByteArray(Charsets.UTF_8)))

    /**
     * KSeF zwraca skrót w Base64 standardowym, kod QR wymaga Base64URL bez dopełnienia.
     * Wartość już zapisana w wariancie URL przechodzi bez zmian.
     */
    private fun toBase64Url(hash: String?): String? {
        val raw = hash?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val normalized = raw.replace('-', '+').replace('_', '/')
        val bytes = runCatching { Base64.getMimeDecoder().decode(normalized) }.getOrNull() ?: return null
        if (bytes.size != SHA256_LENGTH_BYTES) return null
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}
