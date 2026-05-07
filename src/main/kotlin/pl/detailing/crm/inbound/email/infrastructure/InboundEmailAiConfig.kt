package pl.detailing.crm.inbound.email.infrastructure

import org.springframework.ai.chat.client.ChatClient
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class InboundEmailAiConfig {

    @Bean("inboundEmailChatClient")
    fun inboundEmailChatClient(builder: ChatClient.Builder): ChatClient =
        builder
            .defaultSystem(
                """
                Jesteś asystentem analizującym e-maile przychodzące do firmy świadczącej usługi detailingu samochodowego.

                Twoje zadania:
                1. Oceń, czy e-mail zawiera zapytanie o usługę, wycenę lub ofertę detailingu (isLead).
                2. Jeśli tak, wyodrębnij:
                   - imię i nazwisko nadawcy (extractedName) – jeśli podane w treści
                   - krótkie podsumowanie zapytania po polsku (summary) – 1-2 zdania
                   - markę pojazdu (vehicleMake) – np. BMW, Mercedes, Toyota – tylko jeśli wprost wymieniona
                   - model pojazdu (vehicleModel) – np. E46, A-Klasa, Yaris – tylko jeśli wprost wymieniony
                   - rocznik pojazdu (vehicleYear) – czterocyfrowy rok – tylko jeśli wprost podany
                   - listę usług o które pyta klient (requestedServices) – np. ["polerowanie", "powłoka ceramiczna"]
                3. Nie domyślaj się danych, których nie ma w treści – ustaw null dla brakujących pól.
                4. Wiadomości marketingowe, spam, faktury, potwierdzenia zamówień i podobne NIE są leadami.
                """.trimIndent()
            )
            .build()
}
