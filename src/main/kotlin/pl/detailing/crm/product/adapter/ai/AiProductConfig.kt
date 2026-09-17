package pl.detailing.crm.product.adapter.ai

import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.openai.OpenAiChatOptions
import org.springframework.ai.openai.api.OpenAiApi.ChatCompletionRequest.WebSearchOptions
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
            OpenAiChatOptions.builder().model(model).temperature(0.3).build()
        ).build()

    @Bean("productVerifierChatClient")
    fun productVerifierChatClient(
        builder: ChatClient.Builder,
        @Value("\${crm.ai.product-lookup.verifier-model:gpt-4.1-mini}") model: String
    ): ChatClient =
        builder.defaultOptions(
            OpenAiChatOptions.builder().model(model).temperature(0.3).build()
        ).build()

    /**
     * WYSZUKIWANIE W SIECI ([productWebSearchChatClient]) — model z włączonym
     * `web_search_options`, czyli JEDYNY tutaj, który naprawdę wychodzi do internetu.
     *
     * Po co, skoro jest już [productLookupChatClient]: tamten odpowiada wyłącznie z wag i
     * dla realnego EAN-u oddaje pustkę, bo tablicy kod → produkt w wagach nie ma. Ten
     * najpierw SZUKA, a potem odpowiada z tego, co znalazł.
     *
     * Dwa szczegóły, które łatwo przeoczyć:
     *  - `user_location` ustawiamy na kraj studia (domyślnie PL). Oferty tego samego kodu
     *    są lokalne — bez tego wyniki potrafią przyjść z zupełnie innego rynku.
     *  - NIE ustawiamy tu `temperature`. Modele z rodziny `*-search-preview` odrzucają ten
     *    parametr błędem 400; determinizm i tak bierze się z promptu, nie z temperatury.
     */
    @Bean("productWebSearchChatClient")
    fun productWebSearchChatClient(
        builder: ChatClient.Builder,
        @Value("\${crm.products.web.openai-search.model:gpt-4o-mini-search-preview}") model: String,
        @Value("\${crm.products.web.openai-search.context-size:MEDIUM}") contextSize: String,
        @Value("\${crm.products.web.openai-search.country:PL}") country: String
    ): ChatClient {
        val size = runCatching { WebSearchOptions.SearchContextSize.valueOf(contextSize.uppercase()) }
            .getOrDefault(WebSearchOptions.SearchContextSize.MEDIUM)
        val location = WebSearchOptions.UserLocation(
            "approximate",
            WebSearchOptions.UserLocation.Approximate(null, country, null, null)
        )
        return builder.defaultOptions(
            OpenAiChatOptions.builder()
                .model(model)
                .webSearchOptions(WebSearchOptions(size, location))
                .build()
        ).build()
    }

    /**
     * ODCZYT KODU ZE ZDJĘCIA ([barcodeImageChatClient]) — model wizyjny czyta cyfry
     * wydrukowane pod kreskami. To zapas na przeglądarki i kadry, których dekoder w
     * przeglądarce nie odczyta; wzorzec 1:1 z odczytem VIN ze zdjęcia
     * (`batchorder/vin/VinExtractionAiConfig`). Temperatura 0 — odczyt, nie twórczość.
     */
    @Bean("barcodeImageChatClient")
    fun barcodeImageChatClient(
        builder: ChatClient.Builder,
        @Value("\${crm.ai.product-lookup.image-model:gpt-4.1}") model: String
    ): ChatClient =
        builder.defaultOptions(
            OpenAiChatOptions.builder().model(model).temperature(0.0).build()
        ).build()
}
