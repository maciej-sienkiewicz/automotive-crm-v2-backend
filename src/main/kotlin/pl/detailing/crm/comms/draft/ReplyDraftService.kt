package pl.detailing.crm.comms.draft

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.embedding.EmbeddingModel
import org.springframework.ai.openai.OpenAiChatOptions
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Service
import pl.detailing.crm.comms.domain.CommDirection
import pl.detailing.crm.comms.domain.EmailTextCleaner
import pl.detailing.crm.comms.infrastructure.CommMessageEntity
import pl.detailing.crm.comms.infrastructure.CommMessageRepository
import pl.detailing.crm.comms.infrastructure.CommThreadEntity
import pl.detailing.crm.comms.infrastructure.CommThreadRepository
import pl.detailing.crm.leads.infrastructure.LeadRepository
import pl.detailing.crm.leads.infrastructure.LeadServiceItemRepository
import pl.detailing.crm.leads.infrastructure.LeadServiceItemStatus
import pl.detailing.crm.shared.NotFoundException
import pl.detailing.crm.shared.UnprocessableEntityException
import pl.detailing.crm.shared.ValidationException
import pl.detailing.crm.studio.infrastructure.StudioRepository
import java.time.Instant
import java.util.UUID

@Configuration
class ReplyDraftAiConfig {

    /**
     * Szkic ma być powtarzalny: ten sam mail z tą samą wyceną i tymi samymi przykładami
     * ma dać ten sam tekst, bo inaczej „wygeneruj jeszcze raz" staje się loterią, a porównanie
     * trybów (mój styl / propozycja) — porównaniem dwóch losowań. Stąd temperatura 0 i stały
     * seed. Model domyślnie mocniejszy niż przy klasyfikacjach: tu pisze tekst, który trafi
     * do klienta, trzymając naraz styl studia i twarde reguły o kwotach.
     */
    @Bean("replyDraftChatClient")
    fun replyDraftChatClient(
        builder: ChatClient.Builder,
        @Value("\${crm.ai.reply-draft.model:gpt-4.1}") model: String,
        @Value("\${crm.ai.reply-draft.seed:20260925}") seed: Int
    ): ChatClient =
        builder
            .defaultOptions(
                OpenAiChatOptions.builder()
                    .model(model)
                    .temperature(0.0)
                    .seed(seed)
                    .build()
            )
            .build()
}

/** Kształt odpowiedzi modelu (structured output). */
data class ReplyDraftLlmResponse(val reply: String = "")

data class DraftReplyCommand(
    val studioId: UUID,
    val threadId: UUID,
    val senderFullName: String,
    /** Flaga trybu: true = w stylu wysłanych wiadomości studia, false = propozycja AI. */
    val useSentStyle: Boolean,
    /** Stopkę dokleja wysyłka — szkic nie może jej wtedy powtarzać. */
    val signatureAppended: Boolean
)

data class ReplyDraftExampleRef(
    val threadId: UUID,
    val subject: String?,
    val sentAt: Instant,
    /** 1 − odległość kosinusowa pytań; do podpowiedzi „na podstawie…", nie do decyzji. */
    val similarity: Double
)

data class ReplyDraftResult(
    val bodyText: String,
    val useSentStyle: Boolean,
    /** Czy szkic faktycznie powstał na przykładach studia (false także przy braku materiału). */
    val styleApplied: Boolean,
    val examples: List<ReplyDraftExampleRef>,
    /** Znaczniki do uzupełnienia, np. „[proponowany termin]". */
    val placeholders: List<String>,
    /** Kwoty ze szkicu, których nie ma w wycenie leada — do sprawdzenia przed wysłaniem. */
    val unverifiedAmounts: List<String>,
    /** Wyjaśnienie dla użytkownika, gdy tryb nie dał się zastosować tak, jak wybrał. */
    val notice: String?
)

/**
 * Szkic odpowiedzi na maila klienta, generowany na żądanie (kliknięcie w kompozytorze).
 *
 * Dwa tryby, sterowane flagą [DraftReplyCommand.useSentStyle]:
 *  - styl studia: RAG po parach „pytanie → nasza odpowiedź" z poczty studia
 *    ([ReplyExampleStore]); model dostaje odpowiedzi na najbardziej podobne pytania
 *    jako przykłady few-shot i pisze tak, jak pisze studio,
 *  - propozycja: domyślne wskazówki dobrej odpowiedzi handlowej, bez przykładów.
 *
 * W obu trybach treść pochodzi wyłącznie z bieżącej rozmowy i wyceny leada, a kwoty
 * tylko z wyceny — w zapisie brutto dokładnie takim, jaki ustalił człowiek.
 */
@Service
class ReplyDraftService(
    @Qualifier("replyDraftChatClient") private val chatClient: ChatClient,
    private val embeddingModel: EmbeddingModel,
    private val store: ReplyExampleStore,
    private val indexer: ReplyExampleIndexer,
    private val threadRepository: CommThreadRepository,
    private val messageRepository: CommMessageRepository,
    private val leadRepository: LeadRepository,
    private val leadServiceItemRepository: LeadServiceItemRepository,
    private val studioRepository: StudioRepository,
    private val textCleaner: EmailTextCleaner,
    @Value("\${crm.ai.reply-draft.enabled:true}") private val enabled: Boolean,
    @Value("\${crm.ai.reply-draft.examples:4}") private val exampleCount: Int,
    @Value("\${crm.ai.reply-draft.bootstrap-batch:150}") private val bootstrapBatch: Int
) {
    private val log = LoggerFactory.getLogger(javaClass)

    suspend fun draft(command: DraftReplyCommand): ReplyDraftResult = withContext(Dispatchers.IO) {
        if (!enabled) throw UnprocessableEntityException("Szkice odpowiedzi są chwilowo wyłączone.")

        val thread = threadRepository.findByIdAndStudioId(command.threadId, command.studioId)
            ?: throw NotFoundException("Nie znaleziono rozmowy")
        val messages = messageRepository.findByThreadIdOrderBySentAtAsc(thread.id)
        val lastInbound = messages.lastOrNull { it.direction == CommDirection.INBOUND }
            ?: throw ValidationException("W tej rozmowie nie ma jeszcze wiadomości od klienta — nie ma na co odpowiadać")

        val conversation = conversation(messages)
        if (conversation.none { it.fromCustomer }) {
            throw ValidationException("Wiadomość klienta jest pusta — nie ma na co odpowiadać")
        }

        val (examples, notice) = if (command.useSentStyle) {
            styleExamples(command.studioId, thread, lastInbound)
        } else {
            emptyList<StoredReplyExample>() to null
        }

        val lead = leadContext(command.studioId, thread)
        val input = ReplyDraftPromptInput(
            studioName = studioRepository.findById(command.studioId).map { it.name }.orElse("studio detailingu"),
            senderFirstName = command.senderFullName.trim().substringBefore(' ').takeIf { it.isNotBlank() },
            signatureAppended = command.signatureAppended,
            conversation = conversation,
            lead = lead,
            examples = examples.map { DraftStyleExample(it.inquiryText, it.replyText) }
        )

        val reply = try {
            chatClient.prompt()
                .system(ReplyDraftPrompt.system(input))
                .user(ReplyDraftPrompt.user(input))
                .call()
                .entity(ReplyDraftLlmResponse::class.java)
                ?.reply
        } catch (e: Exception) {
            log.warn("[REPLY_DRAFT] Wywołanie LLM nie powiodło się dla wątku {}: {}", thread.id, e.message)
            throw UnprocessableEntityException("Szkic jest chwilowo niedostępny. Spróbuj za chwilę.")
        }
        val body = reply?.let(::tidy)?.takeIf { it.isNotBlank() }
            ?: throw UnprocessableEntityException("Asystent nie zwrócił szkicu. Spróbuj ponownie.")

        log.info(
            "[REPLY_DRAFT] Szkic dla wątku {}: styl studia={}, przykładów={}, pozycji wyceny={}",
            thread.id, command.useSentStyle, examples.size, lead?.lines?.size ?: 0
        )

        ReplyDraftResult(
            bodyText = body,
            useSentStyle = command.useSentStyle,
            styleApplied = examples.isNotEmpty(),
            examples = examples.map {
                ReplyDraftExampleRef(it.threadId, it.threadSubject, it.sentAt, (1.0 - it.distance).coerceIn(0.0, 1.0))
            },
            placeholders = DraftAmountChecker.placeholders(body),
            unverifiedAmounts = DraftAmountChecker.unverifiedAmounts(body, lead?.allowedAmounts().orEmpty()),
            notice = notice
        )
    }

    /**
     * Przykłady do trybu „w moim stylu". Zanim szukamy, dociągamy zaległe wysłane
     * wiadomości tego studia — przy pierwszym użyciu większą porcją, potem małą, żeby
     * odpowiedź wysłana pięć minut temu już się liczyła.
     */
    private fun styleExamples(
        studioId: UUID,
        thread: CommThreadEntity,
        lastInbound: CommMessageEntity
    ): Pair<List<StoredReplyExample>, String?> {
        val indexed = store.countEligible(studioId)
        runCatching { indexer.indexPending(studioId, if (indexed == 0L) bootstrapBatch else FRESH_BATCH) }
            .onFailure { log.warn("[REPLY_DRAFT] Dociągnięcie przykładów studia {} nie powiodło się: {}", studioId, it.message) }

        val inquiry = ReplyExamplePairing.prepareInquiry(cleanText(lastInbound))
            ?: cleanText(lastInbound)?.take(ReplyExamplePairing.MAX_INQUIRY_LENGTH)
            ?: return emptyList<StoredReplyExample>() to NO_STYLE_MATERIAL

        val examples = runCatching {
            val vector = embeddingModel.embed(ReplyExamplePairing.embeddingInput(thread.subject, inquiry))
            // Z zapasem, bo szablon wysłany dwudziestu klientom zająłby wszystkie miejsca
            // tym samym tekstem — a przykłady mają pokazać styl, nie jedną odpowiedź.
            distinctReplies(store.nearest(studioId, thread.id, vector, exampleCount * 3)).take(exampleCount)
        }.getOrElse {
            log.warn("[REPLY_DRAFT] Wyszukiwanie przykładów dla studia {} nie powiodło się: {}", studioId, it.message)
            return emptyList<StoredReplyExample>() to STYLE_UNAVAILABLE
        }
        return if (examples.isEmpty()) examples to NO_STYLE_MATERIAL else examples to null
    }

    private fun conversation(messages: List<CommMessageEntity>): List<DraftConversationTurn> {
        val recent = messages.takeLast(MAX_TURNS)
        return recent.mapIndexedNotNull { index, message ->
            val limit = if (index == recent.lastIndex) MAX_LAST_TURN_LENGTH else MAX_TURN_LENGTH
            val text = cleanText(message)?.take(limit)?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapIndexedNotNull null
            DraftConversationTurn(
                fromCustomer = message.direction == CommDirection.INBOUND,
                sentAt = message.sentAt,
                text = text
            )
        }
    }

    /**
     * Wycena leada: tylko pozycje ZAAKCEPTOWANE przez człowieka. Sugestia AI, której
     * nikt nie zatwierdził, nie jest ceną podaną klientowi. Kwota idzie tak, jak ją
     * zapisano (price_gross) — bez żadnego przeliczania.
     */
    private fun leadContext(studioId: UUID, thread: CommThreadEntity): DraftLeadContext? {
        val lead = thread.leadId?.let { leadRepository.findByIdAndStudioId(it, studioId) }
        val lines = lead?.let {
            leadServiceItemRepository.findByLeadIdOrderByCreatedAtAsc(it.id)
                .filter { item -> item.status == LeadServiceItemStatus.ACCEPTED }
                .map { item -> DraftQuoteLine(item.name, item.quantity, item.priceGross, item.note) }
        }.orEmpty()
        val customerName = lead?.customerName?.takeIf { it.isNotBlank() } ?: thread.participantName?.takeIf { it.isNotBlank() }
        val vehicle = lead?.let { listOfNotNull(it.vehicleBrand, it.vehicleModel).joinToString(" ").trim() }
            ?.takeIf { it.isNotEmpty() }
        if (customerName == null && vehicle == null && lines.isEmpty()) return null
        return DraftLeadContext(customerName, vehicle, lines)
    }

    private fun cleanText(message: CommMessageEntity): String? =
        message.bodyTextClean?.takeIf { it.isNotBlank() }
            ?: textCleaner.clean(message.bodyHtmlSafe, message.bodyText).takeIf { it.isNotBlank() }

    companion object {
        private const val MAX_TURNS = 10
        private const val MAX_TURN_LENGTH = 1500
        private const val MAX_LAST_TURN_LENGTH = 4000
        private const val FRESH_BATCH = 25

        const val NO_STYLE_MATERIAL =
            "Nie znaleźliśmy jeszcze wysłanych odpowiedzi, z których można przejąć Twój styl — to jest propozycja asystenta."
        const val STYLE_UNAVAILABLE =
            "Nie udało się sięgnąć po Twoje wysłane odpowiedzi — to jest propozycja asystenta."

        private val CODE_FENCE = Regex("^```\\w*\\s*|\\s*```$")
        private val SUBJECT_LINE = Regex("^(temat|subject)\\s*:[^\\n]*\\n+", RegexOption.IGNORE_CASE)

        /** Model bywa usłużny: blok kodu albo linia „Temat:" nie mają trafić do treści maila. */
        internal fun tidy(text: String): String =
            text.trim()
                .replace(CODE_FENCE, "")
                .replace(SUBJECT_LINE, "")
                .replace("\r\n", "\n")
                .replace(Regex("\n{3,}"), "\n\n")
                .trim()

        /** Tę samą odpowiedź (szablon) liczymy raz — zostaje najbliższe jej wystąpienie. */
        internal fun distinctReplies(examples: List<StoredReplyExample>): List<StoredReplyExample> =
            examples.distinctBy { it.replyText.lowercase().replace(Regex("\\s+"), " ").take(400) }
    }
}
