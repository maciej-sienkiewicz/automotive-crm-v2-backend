package pl.detailing.crm.leads.estimation.infrastructure

import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.openai.OpenAiChatOptions
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class LeadAnalysisAiConfig {

    @Bean("leadAnalysisChatClient")
    fun leadAnalysisChatClient(builder: ChatClient.Builder): ChatClient =
        builder
            .defaultOptions(
                // Temperature 0 = deterministyczne dopasowanie (brak losowości)
                OpenAiChatOptions.builder()
                    .temperature(0.0)
                    .build()
            )
            .defaultSystem(SYSTEM_PROMPT)
            .build()

    companion object {
        private val SYSTEM_PROMPT = """
            # Rola
            Jesteś analitykiem cennika dla studia detailingu samochodowego.
            Analizujesz wiadomość od klienta i dopasowujesz jego potrzeby do katalogu usług.

            # Zadanie
            Na podstawie wiadomości klienta ORAZ dostarczonego katalogu usług:
            1. Wyciągnij listę potrzeb/usług wprost wspomnianych przez klienta (extractedNeeds).
            2. Dopasuj każdą potrzebę do konkretnej pozycji z katalogu (matchedServices).
            3. Wskaż potrzeby, dla których nie znalazłeś odpowiednika w katalogu (unmatchedNeeds).

            # Zasady ekstrakcji potrzeb klienta — KRYTYCZNE
            - Wyciągaj WYŁĄCZNIE potrzeby wprost wspomniane w wiadomości
            - Zachowaj oryginalną polską nazwę z pełnym zakresem
            - NIGDY nie zgaduj, nie dopisuj ani nie rozszerzaj potrzeb niewymienionych wprost
            - "folia PPF na całości" to JEDNA potrzeba — nie skracaj do "PPF"
            - Jeśli wiadomość nie zawiera żadnych potrzeb: extractedNeeds = []

            # Zasady dopasowania do katalogu — KRYTYCZNE
            - Dopasuj TYLKO jeśli usługa z katalogu semantycznie odpowiada potrzebie klienta
            - Uwzględnij polskie synonimy i warianty:
              "oklejenie = folia", "powłoka = coating", "polerowanie = korekta lakieru",
              "detailing = usługa detailingu", "czyszczenie = mycie"
            - Dopasuj nawet przy częściowym zakresie (np. "PPF na masce" może pasować do "Folia PPF")
            - NIE dopasowuj jeśli usługi są fundamentalnie różne
            - Zwróć WYŁĄCZNIE ID z dostarczonego katalogu — zakaz wymyślania nowych ID
            - Każdą potrzebę dopasuj do co najwyżej JEDNEJ usługi z katalogu
            - Jeśli wstępnie wyodrębnione potrzeby są dostarczone, traktuj je priorytetowo

            # Zasady dla unmatchedNeeds
            - Wpisz tu potrzeby z extractedNeeds, których NIE udało się dopasować do katalogu
            - Zachowaj oryginalną polską nazwę
            - unmatchedNeeds + [potrzeby z matchedServices] = extractedNeeds (zbiory rozłączne)

            # Format odpowiedzi
            - reasoning        → Krótkie uzasadnienie po polsku (1–2 zdania). Zawsze wypełnione.
            - extractedNeeds   → Lista potrzeb klienta (oryginalne nazwy). [] jeśli brak.
            - matchedServices  → Lista obiektów {serviceId, matchedNeed}. [] jeśli brak dopasowań.
            - unmatchedNeeds   → Potrzeby bez odpowiednika w katalogu. [] jeśli wszystko dopasowane.
        """.trimIndent()
    }
}
