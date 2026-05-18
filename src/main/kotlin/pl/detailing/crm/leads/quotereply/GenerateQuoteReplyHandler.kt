package pl.detailing.crm.leads.quotereply

import com.fasterxml.jackson.annotation.JsonProperty
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.client.ChatClient
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Service
import pl.detailing.crm.leads.estimation.infrastructure.LeadEstimationRepository
import pl.detailing.crm.leads.infrastructure.LeadRepository
import pl.detailing.crm.leads.userquote.infrastructure.LeadUserQuoteRepository
import pl.detailing.crm.shared.EntityNotFoundException
import pl.detailing.crm.shared.ForbiddenException
import pl.detailing.crm.shared.ValidationException
import pl.detailing.crm.studio.settings.StudioSettingsRepository

@Service
class GenerateQuoteReplyHandler(
    private val leadRepository: LeadRepository,
    private val estimationRepository: LeadEstimationRepository,
    private val userQuoteRepository: LeadUserQuoteRepository,
    private val studioSettingsRepository: StudioSettingsRepository,
    @Qualifier("quoteReplyChatClient") private val chatClient: ChatClient
) {
    private val log = LoggerFactory.getLogger(javaClass)

    suspend fun handle(command: GenerateQuoteReplyCommand): GenerateQuoteReplyResult =
        withContext(Dispatchers.IO) {
            val leadEntity = leadRepository.findById(command.leadId.value).orElse(null)
                ?: throw EntityNotFoundException("Lead ${command.leadId} nie został znaleziony")

            if (leadEntity.studioId != command.studioId.value) {
                throw ForbiddenException("Lead nie należy do tego studia")
            }

            val customerMessage = leadEntity.initialMessage?.takeIf { it.isNotBlank() }
                ?: throw ValidationException("Lead nie zawiera wiadomości od klienta, na którą można odpowiedzieć")

            val serviceLines: List<ServiceLine> = resolveServiceLines(command)

            if (serviceLines.isEmpty()) {
                throw ValidationException("Brak wyceny dla tego leadu — dodaj wycenę użytkownika lub uruchom estymację AI")
            }

            val studioSettings = studioSettingsRepository.findById(command.studioId.value).orElse(null)
            val signatureData = SignatureData(
                userName = command.userName,
                studioName = studioSettings?.name,
                phone = studioSettings?.phone
            )

            val userPrompt = buildUserPrompt(customerMessage, serviceLines, signatureData)

            log.info("[QUOTE_REPLY] Generating reply for leadId={}, services={}", command.leadId, serviceLines.size)

            val response = chatClient.prompt()
                .user(userPrompt)
                .call()
                .entity(QuoteReplyLlmResponse::class.java)
                ?: throw IllegalStateException("AI returned empty response")

            GenerateQuoteReplyResult(
                title = response.title.trim(),
                reply = response.reply.trim()
            )
        }

    private fun resolveServiceLines(command: GenerateQuoteReplyCommand): List<ServiceLine> {
        val userQuote = userQuoteRepository.findByLeadId(command.leadId.value)
        if (userQuote != null && userQuote.items.isNotEmpty()) {
            return userQuote.items.map { item ->
                ServiceLine(
                    name = item.serviceName,
                    priceGrossGrosze = item.priceGross,
                    vatRate = item.vatRate
                )
            }
        }

        val estimation = estimationRepository.findByLeadId(command.leadId.value)
        if (estimation != null && estimation.items.isNotEmpty()) {
            return estimation.items.map { item ->
                ServiceLine(
                    name = item.serviceName,
                    priceGrossGrosze = item.priceGross,
                    vatRate = item.vatRate
                )
            }
        }

        return emptyList()
    }

    private fun buildUserPrompt(
        customerMessage: String,
        services: List<ServiceLine>,
        signature: SignatureData
    ): String = buildString {
        appendLine("Treść wiadomości od klienta:")
        appendLine("---")
        appendLine(customerMessage.take(3000))
        appendLine("---")
        appendLine()
        appendLine("Twoja propozycja usług wraz z cenami:")
        services.forEach { line ->
            val priceFormatted = formatGrosze(line.priceGrossGrosze)
            appendLine("- ${line.name}: $priceFormatted zł brutto")
        }
        appendLine()
        appendLine("Dane podpisu:")
        appendLine("Imię i nazwisko: ${signature.userName}")
        if (!signature.studioName.isNullOrBlank()) appendLine("Firma: ${signature.studioName}")
        if (!signature.phone.isNullOrBlank()) appendLine("Telefon: ${signature.phone}")
    }

    private fun formatGrosze(grosze: Long): String {
        val pln = grosze / 100.0
        return if (pln == pln.toLong().toDouble()) {
            pln.toLong().toString()
        } else {
            String.format("%.2f", pln)
        }
    }

    private data class ServiceLine(
        val name: String,
        val priceGrossGrosze: Long,
        val vatRate: Int
    )

    private data class SignatureData(
        val userName: String,
        val studioName: String?,
        val phone: String?
    )

    private data class QuoteReplyLlmResponse(
        @JsonProperty("title") val title: String,
        @JsonProperty("reply") val reply: String
    )
}
