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
            - summary       → 1–2 zdaniowe podsumowanie zapytania po polsku. null jeśli isLead = false.
            - vehicleMake   → Marka pojazdu (np. BMW, Mercedes, Toyota) TYLKO jeśli wprost napisana.
                              null jeśli nie wymieniona. Nie dedukuj z modelu ani rocznika.
            - vehicleModel  → Model pojazdu (np. E46, A-Klasa, Yaris) TYLKO jeśli wprost napisany.
                              null jeśli nie wymieniony.
            - vehicleYear   → Rocznik jako liczba całkowita (np. 2019) TYLKO jeśli wprost podany.
                              null jeśli nie ma go w treści.
            - requestedServices → Lista usług WYMIENIONYCH przez klienta (np. ["polerowanie", "PPF"]).
                                  Zwróć [] jeśli żadna usługa nie jest wymieniona wprost.

            Gdy isLead = false: summary = null, extractedName = null,
            vehicleMake = null, vehicleModel = null, vehicleYear = null, requestedServices = [].
        """.trimIndent()
    }
}
