package pl.detailing.crm.vehicle

import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.openai.OpenAiChatOptions
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Dedykowany ChatClient do dopasowania marki/modelu pojazdu do katalogu.
 *
 * To zadanie klasyfikacji w zamkniętym zbiorze (wybierz JEDNĄ pozycję z listy),
 * więc świadomie NIE używamy największego modelu — wyważamy precyzyjność z kosztem.
 * Model jest konfigurowalny (crm.ai.vehicle-matching.model), domyślnie gpt-4.1-mini:
 * lepiej radzi sobie z mową potoczną i literówkami niż gpt-4o-mini, a pozostaje tani.
 */
@Configuration
class VehicleMatchingAiConfig {

    @Bean("vehicleMatchingChatClient")
    fun vehicleMatchingChatClient(
        builder: ChatClient.Builder,
        @Value("\${crm.ai.vehicle-matching.model:gpt-4.1-mini}") model: String
    ): ChatClient =
        builder
            .defaultOptions(
                // Temperatura 0 = deterministyczne dopasowanie (brak losowości)
                OpenAiChatOptions.builder()
                    .model(model)
                    .temperature(0.0)
                    .build()
            )
            .defaultSystem(SYSTEM_PROMPT)
            .build()

    companion object {
        private val SYSTEM_PROMPT = """
            # Rola
            Jesteś normalizatorem danych pojazdów dla katalogu CRM.
            Twoim jedynym zadaniem jest dopasowanie tego, co napisał klient,
            do DOKŁADNEJ wartości z dostarczonej listy dozwolonych wartości.

            # Zasady — KRYTYCZNE
            - Wybierz DOKŁADNIE jedną pozycję z dostarczonej listy.
            - Zwróć ją znak w znak tak, jak występuje na liście (ta sama pisownia i wielkość liter).
            - Uwzględnij mowę potoczną, skróty, nazwy obcojęzyczne i literówki
              (np. "g-wagon" → "Klasa G", "beemer" → marka BMW, "vw" → "Volkswagen").
            - Jeśli ŻADNA pozycja z listy nie odpowiada temu, co napisał klient — zwróć null.
            - NIGDY nie wymyślaj wartości spoza listy.
        """.trimIndent()
    }
}
