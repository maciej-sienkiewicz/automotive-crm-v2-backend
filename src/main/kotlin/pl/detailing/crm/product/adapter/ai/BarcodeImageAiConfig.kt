package pl.detailing.crm.product.adapter.ai

import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.openai.OpenAiChatOptions
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Model wizyjny do odczytu CYFR kodu ze zdjęcia — zapas, gdy dekoder w przeglądarce nie
 * odczyta kadru. Wzorzec 1:1 z odczytem VIN ze zdjęcia
 * (`batchorder/vin/VinExtractionAiConfig`). Temperatura 0 — odczyt, nie twórczość.
 *
 * To jedyne miejsce, gdzie model patrzy na obraz. Rozpoznaniem PRODUKTU po kodzie zajmuje
 * się `adapter/web` — model pytany z pamięci nie potrafi zmapować EAN-u na produkt.
 */
@Configuration
class BarcodeImageAiConfig {

    @Bean("barcodeImageChatClient")
    fun barcodeImageChatClient(
        builder: ChatClient.Builder,
        @Value("\${crm.ai.product-lookup.image-model:gpt-4.1}") model: String
    ): ChatClient =
        builder.defaultOptions(
            OpenAiChatOptions.builder().model(model).temperature(0.0).build()
        ).build()
}
