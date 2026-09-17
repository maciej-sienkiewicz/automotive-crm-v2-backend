package pl.detailing.crm.product.adapter.gs1

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import pl.detailing.crm.product.domain.Gtin
import pl.detailing.crm.product.domain.ProductSource
import pl.detailing.crm.product.port.ProductDataProvider
import pl.detailing.crm.product.port.ProductLookupResult

/**
 * Krok 3 łańcucha: rejestr GS1 ("Verified by GS1" / GEPIR).
 *
 * WŁĄCZANY FLAGĄ. „Verified by GS1" wymaga umowy licencyjnej, której środowisko może
 * nie mieć — `gs1.enabled=false` jest wtedy POPRAWNYM stanem produkcyjnym: moduł
 * działa, traci jedynie ten krok, a produkty o niskiej pewności lądują w formularzu do
 * ręcznego uzupełnienia. Brak umowy jest konfiguracją, nie blokadą wdrożenia — dokładnie
 * jak `-PksefStub` po stronie budowania.
 *
 * Sam wywołanie HTTP zostaje celowo niezaimplementowane do czasu domknięcia umowy:
 * kształt odpowiedzi GS1 zależy od wybranego planu (Verified vs GEPIR) i wpisywanie go
 * teraz na ślepo byłoby zgadywaniem. Gdy `enabled=true` a wywołanie nie jest gotowe,
 * zwracamy null (zachowujemy się jak „nie znaleziono"), nigdy nie udając danych.
 */
@Component
class Gs1ProductProvider(
    @Value("\${gs1.enabled:false}") private val gs1Enabled: Boolean,
    @Value("\${gs1.api.base-url:}") private val baseUrl: String,
    @Value("\${gs1.api.key:}") private val apiKey: String
) : ProductDataProvider {

    private val log = LoggerFactory.getLogger(javaClass)

    override val source = ProductSource.GS1

    override val enabled: Boolean
        get() = gs1Enabled && baseUrl.isNotBlank() && apiKey.isNotBlank()

    override suspend fun findByGtin(gtin: Gtin): ProductLookupResult? {
        if (!enabled) return null
        // TODO(GS1): podłączyć realne wywołanie po domknięciu umowy licencyjnej.
        // Do tego czasu włączona flaga bez gotowego klienta zachowuje się jak brak danych,
        // nigdy jak zmyślona karta.
        log.info("[PRODUCT_GS1] Wywołanie GS1 nie jest jeszcze podłączone — traktuję jak brak danych [gtin={}]", gtin)
        return null
    }
}
