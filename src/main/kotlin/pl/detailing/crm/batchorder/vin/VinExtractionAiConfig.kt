package pl.detailing.crm.batchorder.vin

import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.openai.OpenAiChatOptions
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class VinExtractionAiConfig {

    @Bean("vinExtractionChatClient")
    fun vinExtractionChatClient(
        builder: ChatClient.Builder,
        @Value("\${crm.ai.vin-extraction.model:gpt-4o}") model: String
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
