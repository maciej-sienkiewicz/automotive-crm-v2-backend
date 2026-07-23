package pl.detailing.crm.batchorder.vin

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.model.Media
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.core.io.ByteArrayResource
import org.springframework.stereotype.Service
import org.springframework.util.MimeType

@Service
class VinExtractionService(
    @Qualifier("vinExtractionChatClient") private val chatClient: ChatClient
) {
    private val log = LoggerFactory.getLogger(javaClass)

    suspend fun extractVin(imageBytes: ByteArray, contentType: String): String? =
        withContext(Dispatchers.IO) {
            try {
                val media = Media(MimeType.valueOf(contentType), ByteArrayResource(imageBytes))

                val response = chatClient.prompt()
                    .user { spec ->
                        spec.text(USER_PROMPT)
                        spec.media(media)
                    }
                    .call()
                    .content()
                    ?.trim()
                    ?: return@withContext null

                val vin = response
                    .replace(Regex("[^A-HJ-NPR-Z0-9]"), "")
                    .uppercase()
                    .takeIf { it.length == 17 }

                if (vin == null) {
                    log.info("[VIN_EXTRACT] LLM nie zwrócił prawidłowego VIN. raw='{}'", response)
                }
                vin
            } catch (e: Exception) {
                log.warn("[VIN_EXTRACT] Wywołanie LLM nie powiodło się: {}", e.message)
                null
            }
        }

    companion object {
        private const val USER_PROMPT = """
Wyciągnij numer VIN z tego zdjęcia tabliczki VIN lub dowodu rejestracyjnego pojazdu.

Zasady:
- VIN ma dokładnie 17 znaków (cyfry i wielkie litery, bez I, O, Q)
- Zwróć TYLKO 17-znakowy VIN, bez żadnych dodatkowych słów, spacji ani wyjaśnień
- Jeśli VIN jest nieczytelny lub nie można go znaleźć na zdjęciu, zwróć pustą odpowiedź
"""
    }
}
