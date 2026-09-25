package pl.detailing.crm.comms.draft

import org.slf4j.LoggerFactory
import org.springframework.ai.embedding.EmbeddingModel
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import pl.detailing.crm.comms.domain.CommDirection
import pl.detailing.crm.comms.domain.EmailTextCleaner
import pl.detailing.crm.comms.infrastructure.CommMessageEntity
import pl.detailing.crm.comms.infrastructure.CommMessageRepository
import pl.detailing.crm.comms.infrastructure.CommThreadRepository
import pl.detailing.crm.rolepreview.RolePreviewOutboundGuard
import java.util.UUID

/**
 * Przygotowanie tekstów pary. Czyste funkcje — reguły odrzutu są tu, żeby dało się
 * je przetestować bez bazy i bez modelu.
 */
object ReplyExamplePairing {
    /** Krótsze to „Dziękuję, do zobaczenia" — nic o stylu wyceny ani o ofercie. */
    const val MIN_REPLY_LENGTH = 60
    const val MIN_INQUIRY_LENGTH = 15
    const val MAX_INQUIRY_LENGTH = 2000
    const val MAX_REPLY_LENGTH = 3000

    private val SIGNATURE_DELIMITER = Regex("(?m)^-- ?$")
    private val EXCESS_BLANK_LINES = Regex("\n{3,}")

    /**
     * Odpowiedź bez stopki: stopkę system dokleja sam przy wysyłce, a przykład z nią
     * uczyłby model przepisywać cudzy telefon i adres do treści szkicu.
     */
    fun prepareReply(text: String?): String? =
        normalize(text?.let { SIGNATURE_DELIMITER.split(it, limit = 2).first() }, MAX_REPLY_LENGTH)
            ?.takeIf { it.length >= MIN_REPLY_LENGTH }

    fun prepareInquiry(text: String?): String? =
        normalize(text, MAX_INQUIRY_LENGTH)?.takeIf { it.length >= MIN_INQUIRY_LENGTH }

    /**
     * Wektor liczymy z PYTANIA klienta (z tematem), nie z naszej odpowiedzi: szukamy
     * sytuacji, w której ktoś pytał o to samo, żeby pokazać, jak wtedy odpisaliśmy.
     */
    fun embeddingInput(subject: String?, inquiry: String): String {
        val cleanSubject = subject?.trim()?.takeIf { it.isNotEmpty() }
        return if (cleanSubject != null) "Temat: $cleanSubject\n$inquiry" else inquiry
    }

    private fun normalize(text: String?, maxLength: Int): String? =
        text
            ?.replace("\r\n", "\n")
            ?.replace(EXCESS_BLANK_LINES, "\n\n")
            ?.trim()
            ?.take(maxLength)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
}

/**
 * Utrzymuje zbiór par „pytanie → odpowiedź" w zgodzie ze skrzynką.
 *
 * Uzgadniacz, a nie nasłuch na wysyłkę: odpowiedzi wysłane z telefonu czy webmaila
 * trafiają do CRM-a importem folderu Wysłane, nie przez SendMailHandler — nasłuch
 * zobaczyłby tylko część stylu studia. Jedno zadanie po `comm_messages` widzi
 * wszystkie drogi naraz i jest zarazem mechanizmem pierwszego zapełnienia.
 *
 * Koszt: jedno wywołanie modelu embeddingów na porcję (wsadowo), zero na wiadomości
 * już przejrzane. Piaskownica podglądu roli nie wysyła niczego do dostawcy modelu —
 * jej wiadomości są oznaczane jako odrzuty, żeby nie wracały w każdym przebiegu.
 */
@Service
class ReplyExampleIndexer(
    private val store: ReplyExampleStore,
    private val messageRepository: CommMessageRepository,
    private val threadRepository: CommThreadRepository,
    private val textCleaner: EmailTextCleaner,
    private val embeddingModel: EmbeddingModel,
    private val rolePreviewGuard: RolePreviewOutboundGuard,
    @Value("\${crm.ai.reply-draft.enabled:true}") private val enabled: Boolean,
    @Value("\${crm.ai.reply-draft.reconcile-batch:200}") private val batchSize: Int
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(
        fixedDelayString = "\${crm.ai.reply-draft.reconcile-interval-ms:600000}",
        initialDelayString = "\${crm.ai.reply-draft.reconcile-initial-delay-ms:120000}"
    )
    fun reconcile() {
        if (!enabled) return
        runCatching { indexPending(studioId = null, limit = batchSize) }
            .onFailure { log.warn("[REPLY_DRAFT] Uzgadnianie przykładów odpowiedzi nie powiodło się: {}", it.message) }
    }

    /**
     * @param studioId null = wszystkie studia (przebieg okresowy); konkretne studio =
     *   dociągnięcie zaległości tuż przed szkicem, żeby świeżo wysłane odpowiedzi
     *   liczyły się od razu, a nie po najbliższym przebiegu.
     * @return ile par nadających się na przykład przybyło.
     */
    fun indexPending(studioId: UUID?, limit: Int): Int {
        if (!enabled || limit <= 0) return 0
        val ids = store.findPendingOutbound(studioId, limit)
        if (ids.isEmpty()) return 0

        val outbound = messageRepository.findAllById(ids)
        val subjects = threadRepository.findAllById(outbound.map { it.threadId }.distinct())
            .associate { it.id to it.subject }

        val prepared = outbound.map { message -> prepare(message, subjects[message.threadId]) }
        val eligible = prepared.filter { it.example.eligible }

        // Awaria modelu embeddingów nie może zapisać par jako odrzutów — wróciłyby już
        // nigdy. Zapisujemy wtedy tylko prawdziwe odrzuty, a resztę bierze następny przebieg.
        val embeddings: List<FloatArray>? = if (eligible.isEmpty()) emptyList() else runCatching {
            embeddingModel.embed(eligible.map { it.embeddingInput!! })
        }.onFailure {
            log.warn("[REPLY_DRAFT] Embeddingi {} par nie powiodły się: {}", eligible.size, it.message)
        }.getOrNull()

        prepared.filterNot { it.example.eligible }.forEach { store.insert(it.example, null) }
        if (embeddings == null || embeddings.size != eligible.size) return 0
        eligible.zip(embeddings).forEach { (item, vector) -> store.insert(item.example, vector) }

        log.info(
            "[REPLY_DRAFT] Przejrzano {} wysłanych wiadomości (studio={}), przykładów: {}",
            prepared.size, studioId ?: "wszystkie", eligible.size
        )
        return eligible.size
    }

    private class PreparedExample(val example: NewReplyExample, val embeddingInput: String?)

    private fun prepare(outbound: CommMessageEntity, subject: String?): PreparedExample {
        val rejected = NewReplyExample(
            studioId = outbound.studioId,
            threadId = outbound.threadId,
            outboundMessageId = outbound.id,
            inboundMessageId = null,
            eligible = false,
            inquiryText = null,
            replyText = null,
            sentAt = outbound.sentAt
        )
        if (rolePreviewGuard.isSandbox(outbound.studioId)) return PreparedExample(rejected, null)

        val reply = ReplyExamplePairing.prepareReply(cleanText(outbound))
            ?: return PreparedExample(rejected, null)
        val inbound = messageRepository.findFirstByThreadIdAndDirectionAndSentAtBeforeOrderBySentAtDesc(
            outbound.threadId, CommDirection.INBOUND, outbound.sentAt
        ) ?: return PreparedExample(rejected, null)
        val inquiry = ReplyExamplePairing.prepareInquiry(cleanText(inbound))
            ?: return PreparedExample(rejected.copy(inboundMessageId = inbound.id), null)

        return PreparedExample(
            rejected.copy(inboundMessageId = inbound.id, eligible = true, inquiryText = inquiry, replyText = reply),
            ReplyExamplePairing.embeddingInput(subject, inquiry)
        )
    }

    private fun cleanText(message: CommMessageEntity): String? =
        message.bodyTextClean?.takeIf { it.isNotBlank() }
            ?: textCleaner.clean(message.bodyHtmlSafe, message.bodyText).takeIf { it.isNotBlank() }
}
