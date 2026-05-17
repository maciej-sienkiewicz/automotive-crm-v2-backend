package pl.detailing.crm.leads.quotereply

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

@Service
class GenerateQuoteReplyHandler(
    private val leadRepository: LeadRepository,
    private val estimationRepository: LeadEstimationRepository,
    private val userQuoteRepository: LeadUserQuoteRepository,
    @Qualifier("quoteReplyChatClient") private val chatClient: ChatClient
) {
    private val log = LoggerFactory.getLogger(javaClass)

    suspend fun handle(command: GenerateQuoteReplyCommand): GenerateQuoteReplyResult =
        withContext(Dispatchers.IO) {
            val leadEntity = leadRepository.findById(command.leadId.value).orElse(null)
                ?: throw EntityNotFoundException("Lead ${command.leadId} not found")

            if (leadEntity.studioId != command.studioId.value) {
                throw ForbiddenException("Lead does not belong to this studio")
            }

            val customerMessage = leadEntity.initialMessage?.takeIf { it.isNotBlank() }
                ?: throw ValidationException("Lead has no customer message to respond to")

            val serviceLines: List<ServiceLine> = resolveServiceLines(command)

            if (serviceLines.isEmpty()) {
                throw ValidationException("No quote available for this lead — add a user quote or trigger AI estimation first")
            }

            val userPrompt = buildUserPrompt(customerMessage, serviceLines)

            log.info("[QUOTE_REPLY] Generating reply for leadId={}, services={}", command.leadId, serviceLines.size)

            val reply = chatClient.prompt()
                .user(userPrompt)
                .call()
                .content()
                ?: throw IllegalStateException("AI returned empty response")

            GenerateQuoteReplyResult(reply = reply.trim())
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

    private fun buildUserPrompt(customerMessage: String, services: List<ServiceLine>): String = buildString {
        appendLine("Treść wiadomości od klienta:")
        appendLine("---")
        appendLine(customerMessage.take(3000))
        appendLine("---")
        appendLine()
        appendLine("Twoja propozycja usług wraz z cenami:")
        services.forEach { line ->
            val priceFormatted = formatGrosze(line.priceGrossGrosze)
            appendLine("- **${line.name}**: $priceFormatted zł brutto")
        }
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
}
