package pl.detailing.crm.product.adapter.web

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.web.client.RestTemplate
import java.time.Duration

/**
 * Klient HTTP do Responses API OpenAI.
 *
 * Dlaczego własny RestTemplate, a nie Spring AI: Spring AI 1.0.0 zna wyłącznie Chat
 * Completions, a tam wyszukiwanie istniało tylko przez modele `*-search-preview`, które
 * OpenAI wycofało (shutdown 2026-07-23). Hostowane narzędzie `web_search` żyje w Responses
 * API, którego ta wersja Spring AI nie obsługuje — więc wołamy je wprost.
 *
 * Timeout jest DŁUŻSZY niż przy zwykłym wywołaniu: model najpierw realnie szuka w sieci,
 * a dopiero potem odpowiada.
 */
@Configuration
class WebLookupConfig {

    @Bean("openAiResponsesRestTemplate")
    fun openAiResponsesRestTemplate(
        @Value("\${crm.products.web.search.timeout-ms:45000}") timeoutMs: Long
    ): RestTemplate {
        val factory = SimpleClientHttpRequestFactory().apply {
            setConnectTimeout(Duration.ofSeconds(10))
            setReadTimeout(Duration.ofMillis(timeoutMs))
        }
        return RestTemplate(factory)
    }
}
