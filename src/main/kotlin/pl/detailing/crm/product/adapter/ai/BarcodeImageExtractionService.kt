package pl.detailing.crm.product.adapter.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.content.Media
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.core.io.ByteArrayResource
import org.springframework.stereotype.Service
import org.springframework.util.MimeType
import pl.detailing.crm.product.domain.Gtin

/**
 * Odczyt kodu kreskowego ZE ZDJĘCIA modelem wizyjnym — zapas dla przeglądarek/kadrów,
 * których dekoder po stronie klienta nie odczyta. Dokładnie ten model, co odczyt VIN
 * ze zdjęcia ([pl.detailing.crm.batchorder.vin.VinExtractionService]): telefon robi
 * zdjęcie, my prosimy model o SAME CYFRY, a wynik przepuszczamy przez walidację sumy
 * kontrolnej GTIN. Model nie „rozpoznaje produktu" — czyta wydrukowane cyfry; jego
 * pomyłka jest łapana przez sumę kontrolną, więc do sesji nie wpadnie zmyślony kod.
 *
 * Na zdjęciu bywa etykieta z nazwą produktu, ale do modelu NIE trafia żaden kontekst
 * studia ani wizyty — tylko obraz i polecenie odczytu cyfr.
 */
@Service
class BarcodeImageExtractionService(
    @Qualifier("barcodeImageChatClient") private val chatClient: ChatClient
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** Zwraca znormalizowany GTIN-14 albo null, gdy na zdjęciu nie ma czytelnego kodu. */
    suspend fun extractGtin(imageBytes: ByteArray, contentType: String): Gtin? =
        withContext(Dispatchers.IO) {
            try {
                val media = Media(MimeType.valueOf(contentType), ByteArrayResource(imageBytes))
                log.info("[BARCODE_IMAGE] request bytes={} contentType={}\n--- USER ---\n{}", imageBytes.size, contentType, USER_PROMPT)

                val raw = chatClient.prompt()
                    .user { spec ->
                        spec.text(USER_PROMPT)
                        spec.media(media)
                    }
                    .call()
                    .content()
                    ?.trim()
                log.info("[BARCODE_IMAGE] response raw='{}'", raw)
                if (raw.isNullOrBlank()) return@withContext null

                // Model ma zwrócić same cyfry; na wszelki wypadek wycinamy wszystko inne.
                // Kolejno próbujemy każdy ciąg cyfr o długości kodu — zdjęcie może zawierać
                // też inne liczby (pojemność, numer partii) i tylko GTIN przejdzie sumę
                // kontrolną.
                val candidates = Regex("\\d{8,14}").findAll(raw).map { it.value }.toList()
                val gtin = candidates.firstNotNullOfOrNull { Gtin.parseOrNull(it) }
                if (gtin == null) {
                    log.info("[BARCODE_IMAGE] no_valid_gtin candidates={} raw='{}'", candidates, raw)
                }
                gtin
            } catch (e: Exception) {
                log.warn("[BARCODE_IMAGE] failed: {}", e.toString(), e)
                null
            }
        }

    companion object {
        private const val USER_PROMPT = """
Na zdjęciu jest opakowanie produktu z kodem kreskowym (EAN-13, EAN-8, UPC lub podobnym).
Odczytaj CYFRY wydrukowane pod kreskami kodu.

Zasady:
- Zwróć TYLKO ciąg cyfr kodu (zwykle 13 cyfr), bez spacji, myślników, słów ani wyjaśnień.
- Jeśli na zdjęciu jest kilka kodów, zwróć ten najbardziej wyraźny, w jednej linii.
- Jeśli cyfry są nieczytelne albo kodu nie ma, zwróć pustą odpowiedź. NIE zgaduj cyfr.
"""
    }
}
