package pl.detailing.crm.product.adapter.web

import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.openai.OpenAiChatOptions
import org.springframework.ai.openai.api.OpenAiApi.ChatCompletionRequest.WebSearchOptions
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Klient modelu z włączonym `web_search_options` — jedyny w tym module, który naprawdę
 * wychodzi do internetu.
 *
 * Dlaczego to musi być model WYSZUKUJĄCY: zwykłe wywołanie odpowiada wyłącznie z wag, a
 * tablicy EAN → produkt w wagach nie ma (kod kreskowy to numer nadany przez GS1, nazwa
 * produktu nie jest z niego wyprowadzalna). Zapytany o realny kod model uczciwie oddaje
 * pustkę. Dopiero wyszukiwanie daje mu fakty.
 *
 * Dwa szczegóły, które łatwo przeoczyć:
 *  - `user_location` ustawiamy na kraj studia (domyślnie PL). Oferty tego samego kodu są
 *    lokalne — bez tego wyniki potrafią przyjść z zupełnie innego rynku.
 *  - NIE ustawiamy `temperature`. Modele z rodziny `*-search-preview` odrzucają ten
 *    parametr błędem 400; determinizm bierze się z promptu, nie z temperatury.
 */
@Configuration
class WebLookupConfig {

    @Bean("productWebSearchChatClient")
    fun productWebSearchChatClient(
        builder: ChatClient.Builder,
        @Value("\${crm.products.web.search.model:gpt-4o-mini-search-preview}") model: String,
        @Value("\${crm.products.web.search.context-size:MEDIUM}") contextSize: String,
        @Value("\${crm.products.web.search.country:PL}") country: String
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
}
