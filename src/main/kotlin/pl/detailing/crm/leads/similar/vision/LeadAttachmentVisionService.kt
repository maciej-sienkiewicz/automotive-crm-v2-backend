package pl.detailing.crm.leads.similar.vision

import com.fasterxml.jackson.annotation.JsonProperty
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.content.Media
import org.springframework.ai.openai.OpenAiChatOptions
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.io.ByteArrayResource
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.util.MimeType
import pl.detailing.crm.comms.infrastructure.CommAttachmentRepository
import pl.detailing.crm.leads.attachment.LeadAttachmentRepository
import java.security.MessageDigest
import java.util.UUID

@Configuration
class LeadVisionAiConfig {

    /** Odczyt faktu ze zdjęcia, nie twórczość — temperatura 0. Wzorzec: VinExtractionAiConfig. */
    @Bean("leadVisionChatClient")
    fun leadVisionChatClient(
        builder: ChatClient.Builder,
        @Value("\${crm.ai.lead-vision.model:gpt-4.1-mini}") model: String
    ): ChatClient =
        builder
            .defaultOptions(
                OpenAiChatOptions.builder()
                    .model(model)
                    .temperature(0.0)
                    .build()
            )
            .build()
}

/**
 * Odczyt załączników leada (L2) — kopia produkcyjnego wzorca multimodalnego
 * z [pl.detailing.crm.batchorder.vin.VinExtractionService]: `Media` z bajtów,
 * temperatura 0, awaria = null. To NIE jest nowa integracja.
 *
 * Po co: w zapytaniu „proszę o wycenę naprawy tapicerki fotela, zdjęcia
 * w załączniku" cała informacja o SKALI roboty (przetarcie boczka vs rozdarcie
 * vs wymiana skóry — trzy różne ceny) jest wyłącznie na zdjęciach, a ekstraktor
 * potrzeby dostawał sam tekst. Przy wejściu niedookreślonym model fabrykuje
 * brakujący kontekst — i dokładnie tak powstała „Naprawa tapicerki DRZWI".
 *
 * Model dostaje ZDJĘCIE BEZ TREŚCI MAILA — pytamy o to, co widać, nie o to,
 * co klient napisał; opis wraca do ekstraktora jako CYTOWANE DANE, nie instrukcja.
 * Nigdy nie zwraca kwot ani nazw pozycji cennika.
 *
 * Raz na PLIK w obrębie studia (klucz: studio + SHA-256 bajtów) — ponowne otwarcia
 * leada i ten sam plik w drugim mailu kosztują zero.
 */
@Service
class LeadAttachmentVisionService(
    @Qualifier("leadVisionChatClient") private val chatClient: ChatClient,
    private val factsRepository: LeadAttachmentFactsRepository,
    private val leadAttachmentRepository: LeadAttachmentRepository,
    private val commAttachmentRepository: CommAttachmentRepository,
    @Value("\${crm.ai.lead-vision.enabled:true}") private val enabled: Boolean,
    @Value("\${crm.ai.lead-vision.model:gpt-4.1-mini}") private val modelName: String,
    @Value("\${crm.ai.lead-vision.max-files:3}") private val maxFiles: Int
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Odczytuje obrazowe załączniki leada i utrwala fakty. Idempotentne i odporne:
     * plik już opisany nie płaci drugi raz, awaria jednego pliku nie blokuje reszty,
     * awaria całości nie wywraca niczego — brak faktów degraduje dopasowanie do
     * trybu NEEDS_INSPECTION, nie do błędu.
     *
     * @return liczba świeżo opisanych plików.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun analyzeLeadAttachments(studioId: UUID, leadId: UUID): Int {
        if (!enabled) return 0

        val images = leadAttachmentRepository.findByLeadIdOrderByReceivedAtAsc(leadId)
            .filter { it.contentType.lowercase().startsWith("image/") }
            .take(maxFiles)
        if (images.isEmpty()) return 0

        var analyzed = 0
        for (attachment in images) {
            val content = commAttachmentRepository.findByIdAndStudioId(attachment.attachmentId, studioId)
                ?: continue
            val sha = sha256(content.content)

            if (factsRepository.findByStudioIdAndContentSha256(studioId, sha) != null) continue

            val answer = ask(content.content, content.contentType) ?: continue
            val entity = LeadAttachmentFactsEntity(
                studioId = studioId,
                contentSha256 = sha,
                leadId = leadId,
                attachmentId = attachment.attachmentId,
                readable = answer.readable ?: false,
                part = answer.part?.trim()?.uppercase()?.take(20),
                operationHint = answer.operationHint?.trim()?.uppercase()?.take(20),
                damageType = answer.damageType?.trim()?.uppercase()?.take(30),
                severity = answer.severity?.trim()?.uppercase()?.take(20),
                spotCount = answer.spotCount,
                summaryPl = answer.summaryPl?.trim()?.take(300),
                model = modelName.take(60),
                promptVersion = PROMPT_VERSION
            )
            try {
                factsRepository.save(entity)
                analyzed++
            } catch (e: DataIntegrityViolationException) {
                // Dwa przebiegi na tym samym pliku — wygrywa pierwszy zapis.
                log.debug("[LEAD_VISION] Fakty dla {} zapisane równolegle", sha.take(12))
            }
        }

        if (analyzed > 0) {
            log.info("[LEAD_VISION] Lead {} — opisano {} zdjęć", leadId, analyzed)
        }
        return analyzed
    }

    private fun ask(imageBytes: ByteArray, contentType: String): RawFacts? =
        try {
            val media = Media(MimeType.valueOf(contentType), ByteArrayResource(imageBytes))
            chatClient.prompt()
                .system(SYSTEM_PROMPT)
                .user { spec ->
                    spec.text(USER_PROMPT)
                    spec.media(media)
                }
                .call()
                .entity(RawFacts::class.java)
        } catch (e: Exception) {
            log.warn("[LEAD_VISION] Odczyt zdjęcia nie powiódł się: {}", e.message)
            null
        }

    internal data class RawFacts(
        @JsonProperty("readable") val readable: Boolean? = null,
        @JsonProperty("part") val part: String? = null,
        @JsonProperty("operationHint") val operationHint: String? = null,
        @JsonProperty("damageType") val damageType: String? = null,
        @JsonProperty("severity") val severity: String? = null,
        @JsonProperty("spotCount") val spotCount: Int? = null,
        @JsonProperty("summaryPl") val summaryPl: String? = null
    )

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
            .take(64)

    companion object {
        const val PROMPT_VERSION = "v1"

        internal val SYSTEM_PROMPT = """
Opisujesz zdjęcie nadesłane przez klienta studia detailingu. Odpowiadasz WYŁĄCZNIE
kodami z poniższych list i jednym zdaniem opisu. Nigdy nie podajesz cen, nazw usług
ani zaleceń — wyłącznie to, co WIDAĆ.

readable       true, gdy zdjęcie czytelnie pokazuje fragment auta; false, gdy jest
               nieostre, za ciemne albo nie pokazuje auta
part           FULL_BODY | BODY_FRONT | BODY_PANEL | TRIM_PIECE | LAMPS | GLASS |
               WHEELS | ENGINE_BAY | CABIN | SEAT | DOOR_PANEL | DASHBOARD |
               HEADLINER | CARPET | UNKNOWN
operationHint  REPAIR gdy widać uszkodzenie substancji (rozdarcie, przetarcie,
               wypalenie), CORRECT gdy widać defekt lakieru, CLEAN gdy widać sam
               brud; UNKNOWN gdy nie sposób rozstrzygnąć
damageType     WEAR | TEAR | BURN | STAIN | SCRATCH | DENT | DELAMINATION | NONE_VISIBLE
severity       LIGHT | MODERATE | HEAVY | UNKNOWN
spotCount      liczba widocznych miejsc uszkodzenia (0, gdy brak)
summaryPl      jedno zdanie po polsku, np. "Przetarcie skóry na bocznym wałku
               fotela kierowcy, ok. 3 cm" — bez zaleceń i bez cen

Nie zgaduj: UNKNOWN jest lepsze niż pewnie brzmiący błąd — na tym opisie ktoś
oprze decyzję, czy robotę w ogóle da się wycenić zdalnie.
""".trim()

        internal const val USER_PROMPT = "Opisz, co widać na zdjęciu."
    }
}
