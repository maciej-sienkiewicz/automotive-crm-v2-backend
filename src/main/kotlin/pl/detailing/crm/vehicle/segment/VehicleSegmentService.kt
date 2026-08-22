package pl.detailing.crm.vehicle.segment

import com.fasterxml.jackson.annotation.JsonProperty
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.openai.OpenAiChatOptions
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

@Configuration
class VehicleSegmentAiConfig {

    /** Klasyfikacja faktu, nie twórczość — temperatura 0. */
    @Bean("vehicleSegmentChatClient")
    fun vehicleSegmentChatClient(
        builder: ChatClient.Builder,
        @Value("\${crm.ai.vehicle-segment.model:gpt-4o-mini}") model: String
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

/**
 * Przypisuje modelowi auta segment wielkości i klasę rynkową — raz na model,
 * potem już tylko z bazy.
 *
 * Pytanie „czym jest Skoda Superb" ma jedną odpowiedź dla całego świata i nie zmienia
 * się między studiami ani między leadami. Odpytywanie modelu językowego przy każdym
 * zapytaniu byłoby płaceniem w kółko za tę samą odpowiedź i dokładaniem sekund do
 * ścieżki, która i tak jest asynchroniczna tylko dlatego, że LLM jest wolny.
 *
 * Klasyfikacja jest z natury przybliżona (Passat bywa liczony jako D albo E, SUV-y
 * dzielą się na własne podsegmenty), więc traktujemy ją jak oś analityczną, a nie
 * jak dane rejestracyjne. Kolumna `source` zostawia miejsce na ręczną poprawkę,
 * której automat nie ma prawa nadpisać.
 */
@Service
class VehicleSegmentService(
    @Qualifier("vehicleSegmentChatClient") private val chatClient: ChatClient,
    private val repository: VehicleSegmentRepository
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Klasyfikacja dla pary marka/model. Zwraca null, gdy nie ma marki albo model
     * językowy zawiódł — brak klasyfikacji jest dopuszczalny, lead ważniejszy.
     *
     * REQUIRES_NEW, bo wywołanie idzie z listenera po zatwierdzeniu transakcji:
     * nie ma już do czego dołączyć, a zapis wyniku potrzebuje własnej.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun classify(brand: String?, model: String?): VehicleSegmentEntity? {
        val cleanBrand = brand?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val cleanModel = model?.trim()?.takeIf { it.isNotEmpty() }
        val brandKey = cleanBrand.lowercase()
        val modelKey = cleanModel?.lowercase().orEmpty()

        repository.findByBrandKeyAndModelKey(brandKey, modelKey)?.let { return it }

        val answer = ask(cleanBrand, cleanModel) ?: return null
        val entity = VehicleSegmentEntity(
            brandKey = brandKey,
            modelKey = modelKey,
            brand = cleanBrand,
            model = cleanModel,
            sizeSegment = VehicleSizeSegment.from(answer.sizeSegment),
            marketTier = VehicleMarketTier.from(answer.marketTier)
        )

        return try {
            repository.save(entity)
        } catch (e: DataIntegrityViolationException) {
            // Dwa leady z tym samym autem potrafią trafić tu równolegle. Wyścig nie
            // jest błędem: wygrywa ten, który zapisał pierwszy, my czytamy jego wiersz.
            log.debug("[VEHICLE_SEGMENT] {} {} zapisany równolegle — czytam istniejący", cleanBrand, cleanModel.orEmpty())
            repository.findByBrandKeyAndModelKey(brandKey, modelKey)
        }
    }

    private fun ask(brand: String, model: String?): SegmentAnswer? =
        try {
            kotlinx.coroutines.runBlocking {
                withContext(Dispatchers.IO) {
                    chatClient.prompt()
                        .system(SYSTEM_PROMPT)
                        .user("Marka: $brand\nModel: ${model ?: "(nie podano)"}")
                        .call()
                        .entity(SegmentAnswer::class.java)
                }
            }
        } catch (e: Exception) {
            log.warn("[VEHICLE_SEGMENT] Klasyfikacja {} {} nie powiodła się: {}", brand, model.orEmpty(), e.message)
            null
        }

    internal data class SegmentAnswer(
        @JsonProperty("sizeSegment")
        val sizeSegment: String? = null,
        @JsonProperty("marketTier")
        val marketTier: String? = null
    )

    companion object {
        private val SYSTEM_PROMPT = """
Klasyfikujesz samochód do dwóch niezależnych osi. Odpowiadasz wyłącznie kodami
z poniższych list — nic innego nie jest dozwolone.

sizeSegment — segment wielkości wg klasyfikacji europejskiej:
  A     miejskie mini (Fiat 500, Toyota Aygo, Kia Picanto)
  B     małe miejskie (Skoda Fabia, Toyota Yaris, Renault Clio)
  C     kompakty (VW Golf, Toyota Corolla, Ford Focus)
  D     klasa średnia (VW Passat, BMW serii 3, Skoda Superb)
  E     klasa wyższa (Audi A6, BMW serii 5, Mercedes klasy E)
  F     limuzyny reprezentacyjne (Mercedes klasy S, BMW serii 7, Porsche Panamera)
  SUV   SUV-y i crossovery każdej wielkości (Ford Puma, Hyundai Tucson, Volvo XC90)
  VAN   vany i minivany, 5-9 miejsc (VW Sharan, Ford Galaxy)
  SPORT auta sportowe: coupé, kabriolety, superauta (Porsche 911, Mazda MX-5)

marketTier — pozycjonowanie MARKI, niezależne od rozmiaru auta:
  BUDGET     najtańsze w zakupie i serwisie (Dacia, Łada, MG)
  MAINSTREAM złoty środek rynku (Toyota, VW, Skoda, Kia, Ford, Hyundai, Renault)
  PREMIUM    droższe, lepsze materiały i prestiż marki (Mercedes-Benz, BMW, Audi,
             Volvo, Lexus, Alfa Romeo)
  LUXURY     małe serie i rzemiosło (Rolls-Royce, Bentley, Aston Martin, Ferrari,
             Lamborghini)

ZASADY:
- SUV ma pierwszeństwo przed literą: podwyższony crossover to SUV, nawet jeśli
  rozmiarem odpowiada segmentowi C.
- Auto sportowe ma pierwszeństwo przed literą, ale nie przed SUV.
- Gdy model nie został podany, oceń po samej marce: sizeSegment ustaw na typowy dla
  jej oferty, a przy markach o bardzo szerokiej gamie zwróć UNKNOWN.
- Gdy nie masz pewności, zwróć UNKNOWN. Zgadnięcie jest gorsze niż brak: ta wartość
  wchodzi do zestawień, na których ktoś oprze decyzję cenową.
""".trim()
    }
}
