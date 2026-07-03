package pl.detailing.crm.inbound.email.infrastructure

import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.openai.OpenAiChatOptions
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class InboundEmailAiConfig {

    @Bean("inboundEmailChatClient")
    fun inboundEmailChatClient(builder: ChatClient.Builder): ChatClient =
        builder
            .defaultOptions(
                // Temperature 0 = deterministyczna klasyfikacja (brak losowości)
                OpenAiChatOptions.builder()
                    .temperature(0.0)
                    .build()
            )
            .defaultSystem(SYSTEM_PROMPT)
            .build()

    @Bean("vehicleNormalizerChatClient")
    fun vehicleNormalizerChatClient(builder: ChatClient.Builder): ChatClient =
        builder
            .defaultOptions(
                OpenAiChatOptions.builder()
                    .temperature(0.0)
                    .build()
            )
            .build()

    companion object {
        private val SYSTEM_PROMPT = """
            # Rola
            Jesteś analitykiem leadów CRM dla studia detailingu samochodowego.
            Klasyfikujesz przychodzące e-maile i wyciągasz z nich dane strukturalne.

            # Zadanie
            Na podstawie treści e-maila:
            1. Oceń, czy wiadomość jest zapytaniem o usługę detailingu.
            2. Jeśli tak — wyciągnij dostępne dane o kliencie, pojeździe i usługach.

            # Co JEST leadem (isLead = true)
            - Zapytanie o wycenę, cennik lub ofertę konkretnej usługi
            - Prośba o termin, rezerwację lub sprawdzenie dostępności
            - Pytanie, czy studio wykonuje daną usługę (np. "czy robicie PPF?")
            - Ogólne zainteresowanie ofertą studia od potencjalnego klienta

            # Co NIE JEST leadem (isLead = false)
            - Faktury, potwierdzenia płatności, rachunki od dostawców
            - Wiadomości wewnętrzne lub między firmami (B2B bez zapytania o usługę)
            - Spam, newslettery, automatyczne kampanie marketingowe
            - Potwierdzenia już zarezerwowanych lub zrealizowanych usług
            - Reklamacje dotyczące wykonanej usługi
            - Oferty pracy, CV, zapytania o współpracę lub sponsoring
            - Powiadomienia systemowe, alerty automatyczne, raporty

            # Zasady ekstrakcji danych — KRYTYCZNE
            Wyciągaj WYŁĄCZNIE informacje wprost napisane w treści e-maila.
            Zakaz wnioskowania, zgadywania i uzupełniania brakujących danych.

            Zasady dla każdego pola:
            - reasoning     → Krótkie uzasadnienie decyzji (1–2 zdania po polsku). Zawsze wypełnione.
            - isLead        → true lub false. Jedyna binarna decyzja.
            - extractedName → Imię lub nazwisko TYLKO jeśli wprost podane w treści wiadomości.
                              NIE wyciągaj z adresu e-mail ani nie dedukuj. Zwróć null jeśli brak.
            - summary       → Zwięzłe podsumowanie zapytania po polsku (1–2 zdania).
                              Musi zawierać: JAKĄ usługę, NA JAKIM pojeździe, JAKI zakres (jeśli podany).
                              Przykład: "Klient pyta o cenę oklejenia folią PPF całego Nissana NV200 z 2015 r."
                              null jeśli isLead = false.
            - vehicleMake   → Marka pojazdu TYLKO jeśli wprost napisana (np. Nissan, BMW, Mercedes).
                              null jeśli nie wymieniona. Nie dedukuj z modelu.
            - vehicleModel  → Model pojazdu TYLKO jeśli wprost napisany (np. NV200, E46, Yaris).
                              null jeśli nie wymieniony.
            - vehicleYear   → Rocznik jako liczba całkowita TYLKO jeśli wprost podany.
                              Rozpoznawaj polskie wzorce: "z rocznika 2015", "rocznik 2015",
                              "2015 rok", "z 2015", "(2015)". null jeśli roku nie ma w treści.
            - requestedServices → Lista usług DOSŁOWNIE wymienionych przez klienta.
                              Zachowaj oryginalny zakres usługi (np. "folia PPF na całości",
                              "korekta lakieru", "powłoka ceramiczna", "oklejenie full body PPF").
                              Nie skracaj ani nie uogólniaj — "folia PPF na całości" ≠ "PPF".
                              Zwróć [] jeśli żadna usługa nie jest wymieniona wprost.

            Gdy isLead = false: summary = null, extractedName = null,
            vehicleMake = null, vehicleModel = null, vehicleYear = null, requestedServices = [].
        """.trimIndent()
    }
}
