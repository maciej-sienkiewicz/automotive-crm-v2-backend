package pl.detailing.crm.vehicle

import com.fasterxml.jackson.annotation.JsonProperty
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.client.ChatClient
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component

/**
 * Jedyne źródło prawdy dla kanonizacji marki i modelu pojazdu względem katalogu
 * (marki_modele_final.json). Zastępuje rozproszoną wcześniej logikę normalizacji.
 *
 * Strategia: deterministic-first, LLM-fallback.
 *  1. Dopasowanie w kodzie (dokładne / po tokenach) — darmowe, pokrywa większość przypadków.
 *  2. Dopiero gdy zawiedzie, wywołanie LLM z krótką listą dozwolonych wartości.
 * Dzięki temu za dopasowanie płacimy tylko przy mowie potocznej / literówkach
 * (np. "g-wagon" → "Klasa G"), zachowując precyzyjność przy rozsądnym koszcie.
 */
@Component
class VehicleCatalogMatcher(
    private val vehicleMetadataService: VehicleMetadataService,
    @Qualifier("vehicleMatchingChatClient") private val chatClient: ChatClient
) {
    private val log = LoggerFactory.getLogger(javaClass)

    data class Match(val brand: String?, val model: String?)

    /**
     * Kanonizuje surową markę i model do wartości z katalogu.
     * Model dopasowujemy tylko jeśli udało się rozpoznać markę (modele są per-marka).
     */
    suspend fun resolve(rawBrand: String?, rawModel: String?): Match {
        val cleanBrand = rawBrand?.takeIf { it.isNotBlank() }
        val brand = cleanBrand?.let { matchBrand(it) }
        var model = if (brand != null) {
            rawModel?.takeIf { it.isNotBlank() }?.let { matchModel(brand, it) }
        } else null

        // Potoczne, jednowyrazowe określenia (np. "g-wagon") często kodują MODEL, nie markę,
        // i ekstrakcja zapisuje je jako markę. Jeśli markę rozpoznaliśmy ROZMYTO (surowy token
        // nie był kanoniczną marką), a modelu wciąż brak — spróbuj dopasować ten sam token jako
        // model w obrębie marki. Dla poprawnie podanej marki (dopasowanie dokładne) tego nie robimy.
        if (brand != null && model == null && cleanBrand != null && !brand.equals(cleanBrand, ignoreCase = true)) {
            model = matchModel(brand, cleanBrand)
        }
        return Match(brand, model)
    }

    /**
     * Kanonizuje markę: dokładne dopasowanie (case-insensitive) → fallback LLM z pełną listą marek.
     * Lista marek jest krótka (~177 pozycji, ~1 KB), więc fallback jest tani.
     */
    suspend fun matchBrand(rawBrand: String): String? {
        val brands = vehicleMetadataService.getBrands()

        brands.firstOrNull { it.equals(rawBrand, ignoreCase = true) }?.let { return it }

        val matched = askLlm(rawBrand, brands, "marek")
            ?.let { llm -> brands.firstOrNull { it.equals(llm, ignoreCase = true) } }

        if (matched == null) {
            log.info("[VEHICLE_MATCHER] Nie dopasowano marki rawBrand='{}'", rawBrand)
        } else if (!matched.equals(rawBrand, ignoreCase = true)) {
            log.debug("[VEHICLE_MATCHER] Marka '{}' → '{}'", rawBrand, matched)
        }
        return matched
    }

    /**
     * Kanonizuje model w obrębie danej (już kanonicznej) marki:
     * dokładne dopasowanie → dopasowanie po tokenach → fallback LLM z listą modeli marki.
     */
    suspend fun matchModel(canonicalBrand: String, rawModel: String): String? {
        val models = vehicleMetadataService.getModelsForBrand(canonicalBrand)
        if (models.isEmpty()) return null

        models.firstOrNull { it.equals(rawModel, ignoreCase = true) }?.let { return it }
        models.firstOrNull { tokenNormalize(it) == tokenNormalize(rawModel) }?.let { return it }

        val matched = askLlm(rawModel, models, "modeli marki $canonicalBrand")
            ?.let { llm -> models.firstOrNull { it.equals(llm, ignoreCase = true) } }

        if (matched == null) {
            log.info("[VEHICLE_MATCHER] Nie dopasowano modelu rawModel='{}' dla marki='{}'", rawModel, canonicalBrand)
        } else if (!matched.equals(rawModel, ignoreCase = true)) {
            log.debug("[VEHICLE_MATCHER] Model '{}' → '{}' (marka '{}')", rawModel, matched, canonicalBrand)
        }
        return matched
    }

    /**
     * Wywołanie LLM z zamkniętą listą dozwolonych wartości. Zwraca surową wartość z LLM
     * (walidacja przynależności do listy odbywa się u wywołującego). Błąd LLM => null.
     */
    private suspend fun askLlm(raw: String, allowedValues: List<String>, label: String): String? =
        withContext(Dispatchers.IO) {
            val prompt = """
                Dozwolone wartości ($label):
                ${allowedValues.joinToString(", ")}

                Klient napisał: "$raw"

                Zwróć DOKŁADNĄ wartość z listy powyżej, która najlepiej odpowiada temu, co napisał klient,
                lub null, jeśli żadna nie pasuje.
            """.trimIndent()

            try {
                chatClient.prompt()
                    .user(prompt)
                    .call()
                    .entity(MatchResponse::class.java)
                    ?.matchedValue
                    ?.takeIf { it.isNotBlank() }
            } catch (e: Exception) {
                log.warn("[VEHICLE_MATCHER] Wywołanie LLM nie powiodło się dla '{}' ({}): {}", raw, label, e.message)
                null
            }
        }

    /**
     * Usuwa znaki niealfanumeryczne i sprowadza do małych liter na potrzeby porównania modeli.
     * "3-Series" i "3 Series" → "3series".
     */
    private fun tokenNormalize(s: String): String =
        s.lowercase().replace(Regex("[^a-z0-9]"), "")

    internal data class MatchResponse(
        @JsonProperty("matchedValue")
        val matchedValue: String?
    )
}
