package pl.detailing.crm.product.adapter.web

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.web.client.RestTemplate
import java.time.Duration

/**
 * Klient HTTP dla sieciowych źródeł danych o kodzie. Osobny bean z KRÓTKIMI limitami
 * czasu: rozpoznanie kodu dzieje się, gdy człowiek stoi z telefonem nad opakowaniem —
 * lepiej oddać „nie znaleziono" po paru sekundach niż trzymać go w oczekiwaniu.
 *
 * Fabryka ustawiana wprost (zamiast RestTemplateBuilder), bo API buildera zmieniało
 * sygnatury między wersjami Boota — tu zależy nam tylko na dwóch timeoutach.
 */
@Configuration
class WebLookupConfig {

    @Bean("webLookupRestTemplate")
    fun webLookupRestTemplate(
        @Value("\${crm.products.web.timeout-ms:5000}") timeoutMs: Long
    ): RestTemplate {
        val factory = SimpleClientHttpRequestFactory().apply {
            setConnectTimeout(Duration.ofMillis(timeoutMs))
            setReadTimeout(Duration.ofMillis(timeoutMs))
        }
        return RestTemplate(factory)
    }
}
