package pl.detailing.crm.inbound.email.infrastructure

import org.springframework.ai.chat.client.ChatClient
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class InboundEmailAiConfig {

    @Bean("inboundEmailChatClient")
    fun inboundEmailChatClient(builder: ChatClient.Builder): ChatClient =
        builder
            .defaultSystem(
                """
                Jesteś asystentem analizującym e-maile dla firmy świadczącej usługi detailingu samochodowego.
                Klasyfikujesz wiadomości i odpowiadasz WYŁĄCZNIE w formacie JSON – bez żadnego dodatkowego tekstu.
                """.trimIndent()
            )
            .build()
}
