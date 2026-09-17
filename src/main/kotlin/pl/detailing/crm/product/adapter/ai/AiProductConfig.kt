package pl.detailing.crm.product.adapter.ai

import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.openai.OpenAiChatOptions
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Dwa klienty modelu dla rozpoznawania produktu — celowo rozdzielone.
 *
 * ODCZYT ([productLookupChatClient]) wypisuje kartę produktu o danym GTIN. Temperatura 0,
 * bo to odczyt faktu, nie twórczość — wzorzec z [LeadVehicleExtractionService].
 *
 * WERYFIKATOR ([productVerifierChatClient]) to KRYTYK ZEWNĘTRZNY, mniejszy/inny model,
 * który dostaje kartę z kroku odczytu i odpowiada tylko „czy jesteś pewny, że te dane
 * naprawdę należą do tego kodu". Rozdzielenie jest istotą punktu 2 wymagania: model
 * potrafi napisać wiarygodną kartę produktu, którego nie zna, więc pytamy o zgodność
 * drugim, niezależnym głosem, zanim uznamy pewność za wystarczającą. Wzorzec z
 * `leads/similar/pricing/AnchorVerifier` (inna rodzina modelu, temperatura 0).
 */
@Configuration
class AiProductConfig {

    @Bean("productLookupChatClient")
    fun productLookupChatClient(
        builder: ChatClient.Builder,
        @Value("\${crm.ai.product-lookup.model:gpt-4.1}") model: String
    ): ChatClient =
        builder.defaultOptions(
            OpenAiChatOptions.builder().model(model).temperature(0.0).build()
        ).build()

    @Bean("productVerifierChatClient")
    fun productVerifierChatClient(
        builder: ChatClient.Builder,
        @Value("\${crm.ai.product-lookup.verifier-model:gpt-4.1-mini}") model: String
    ): ChatClient =
        builder.defaultOptions(
            OpenAiChatOptions.builder().model(model).temperature(0.0).build()
        ).build()
}
