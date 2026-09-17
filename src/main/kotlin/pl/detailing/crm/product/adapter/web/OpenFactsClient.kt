package pl.detailing.crm.product.adapter.web

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.web.client.RestTemplate

/**
 * Otwarte bazy kodów kreskowych z rodziny Open Food Facts: Open Food Facts,
 * Open Products Facts (produkty ogólne) i Open Beauty Facts (kosmetyka/chemia).
 *
 * DARMOWE i BEZ KLUCZA — dlatego to pierwszy krok sieciowy: kosztuje jedno zapytanie
 * HTTP i nie wymaga żadnej umowy. Dane są USTRUKTURYZOWANE (nazwa, marka, pojemność),
 * więc nie potrzebują modelu do wydobycia. Pokrycie polskiej chemii detailingowej bywa
 * dziurawe — stąd drugi krok ([BarcodeWebSearchClient]).
 *
 * Odpytujemy kodem w postaci DRUKOWANEJ (EAN-13, bez wiodących zer): te bazy kluczują
 * właśnie tak i „05902806493015" nie trafiłoby w nic.
 */
@Component
class OpenFactsClient(
    private val objectMapper: ObjectMapper,
    private val webLookupRestTemplate: RestTemplate,
    @Value("\${crm.products.web.openfacts.enabled:true}") val enabled: Boolean,
    @Value("\${crm.products.web.openfacts.hosts:world.openfoodfacts.org,world.openbeautyfacts.org,world.openproductsfacts.org}")
    private val hostsRaw: String
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val hosts: List<String> by lazy {
        hostsRaw.split(",").map { it.trim() }.filter { it.isNotBlank() }
    }

    fun lookup(ean: String): WebProductCard? {
        if (!enabled) return null
        for (host in hosts) {
            val url = "https://$host/api/v2/product/$ean.json" +
                "?fields=code,product_name,brands,quantity,product_quantity,product_quantity_unit,generic_name"
            try {
                val body = webLookupRestTemplate.getForObject(url, String::class.java)
                if (body.isNullOrBlank()) continue
                val root = objectMapper.readTree(body)
                // status=1 znaczy „znaleziono"; przy 0 baza zwraca szkielet z pustym produktem.
                if (root.path("status").asInt(0) != 1) {
                    log.info("[PRODUCT_WEB] openfacts_miss host={} ean={}", host, ean)
                    continue
                }
                val p = root.path("product")
                val card = toCard(p, host, ean)
                if (card != null) {
                    log.info("[PRODUCT_WEB] openfacts_hit host={} ean={} card={}", host, ean, card)
                    return card
                }
            } catch (e: Exception) {
                log.warn("[PRODUCT_WEB] openfacts_failed host={} ean={}: {}", host, ean, e.toString())
            }
        }
        return null
    }

    private fun toCard(p: JsonNode, host: String, ean: String): WebProductCard? {
        val name = p.path("product_name").asText(null)?.trim()?.ifBlank { null }
        val brand = p.path("brands").asText(null)?.split(",")?.firstOrNull()?.trim()?.ifBlank { null }
        if (name == null || brand == null) return null

        // Pojemność bywa rozbita (product_quantity + unit) albo scalona w "500 ml".
        val qtyValue = p.path("product_quantity").asText(null)?.trim()?.ifBlank { null }
        val qtyUnit = p.path("product_quantity_unit").asText(null)?.trim()?.ifBlank { null }
        val parsed = if (qtyValue != null) qtyValue to (qtyUnit ?: "")
        else parseQuantity(p.path("quantity").asText(null))

        return WebProductCard(
            brand = brand,
            name = name,
            packageSizeValue = parsed?.first,
            packageSizeUnit = parsed?.second,
            description = p.path("generic_name").asText(null)?.trim()?.ifBlank { null },
            sourceUrl = "https://$host/product/$ean",
            // Baza kodów to dane wprost pod tym kodem, nie wnioskowanie — wysoka pewność,
            // ale wpis i tak wymaga potwierdzenia etykietą (AI_SUGGESTED).
            confidence = 0.95
        )
    }

    /** "500 ml", "1,5 L", "750ml" → ("500","ML"). Zwraca null, gdy nie da się rozdzielić. */
    private fun parseQuantity(raw: String?): Pair<String, String>? {
        val q = raw?.trim()?.ifBlank { null } ?: return null
        val m = Regex("([0-9]+(?:[.,][0-9]+)?)\\s*([a-zA-Z]+)").find(q) ?: return null
        return m.groupValues[1].replace(',', '.') to m.groupValues[2].uppercase()
    }
}
