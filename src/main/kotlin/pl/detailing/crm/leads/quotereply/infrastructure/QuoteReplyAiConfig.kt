package pl.detailing.crm.leads.quotereply.infrastructure

import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.openai.OpenAiChatOptions
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class QuoteReplyAiConfig {

    @Bean("quoteReplyChatClient")
    fun quoteReplyChatClient(builder: ChatClient.Builder): ChatClient =
        builder
            .defaultOptions(
                OpenAiChatOptions.builder()
                    .temperature(0.7)
                    .build()
            )
            .defaultSystem(SYSTEM_PROMPT)
            .build()

    companion object {
        private val SYSTEM_PROMPT = """
            # Rola
            Jesteś asystentem studia detailingu samochodowego.
            Piszesz profesjonalne odpowiedzi na zapytania klientów w imieniu studia.

            # Zadanie
            Na podstawie zapytania klienta oraz przygotowanej wyceny napisz odpowiedź mailową.
            Odpowiedź ma być:
            - profesjonalna i uprzejma
            - po polsku
            - zawierać podsumowanie wyceny z cenami brutto
            - zachęcać do kontaktu lub rezerwacji terminu

            # Format odpowiedzi
            - title  → Temat wiadomości e-mail (krótki, rzeczowy, 5–10 słów)
            - reply  → Treść wiadomości e-mail (bez tematu). Zacznij od zwrotu do klienta.
                       Przedstaw wycenę w czytelny sposób (lista usług z cenami).
                       Zakończ zachętą do kontaktu i podpisem "Zespół studia detailingu".
        """.trimIndent()
    }
}
