package pl.detailing.crm.leads.quotereply

import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.openai.OpenAiChatOptions
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Dedykowany ChatClient do WYODRĘBNIENIA charakteru pisma z zaakceptowanych przykładów ofert.
 *
 * To zadanie analityczne (nie kreatywne): temperatura 0 dla powtarzalności.
 * Model konfigurowalny (crm.ai.quote-style.model), domyślnie gpt-4.1-mini —
 * dobrze wychwytuje subtelne konwencje stylu przy niskim koszcie. Wynik jest
 * cache'owany per studio, więc analiza uruchamia się tylko przy zmianie przykładów.
 */
@Configuration
class QuoteStyleAiConfig {

    @Bean("quoteStyleChatClient")
    fun quoteStyleChatClient(
        builder: ChatClient.Builder,
        @Value("\${crm.ai.quote-style.model:gpt-4.1-mini}") model: String
    ): ChatClient =
        builder
            .defaultOptions(
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
            Jesteś analitykiem stylu komunikacji. Dostajesz kilka zaakceptowanych przez studio
            odpowiedzi ofertowych i masz wyodrębnić z nich CHARAKTER PISMA — czyli powtarzalne,
            świadome wybory stylistyczne autora.

            # Zadanie
            Zwróć zwięzły zestaw KONKRETNYCH, wykonalnych wytycznych, które pozwolą napisać nową
            wiadomość w identycznym stylu. Opisuj JAK autor pisze, nie CO konkretnie napisał
            (ignoruj nazwy klientów, konkretne usługi, kwoty — to zmienne).

            # Na co zwrócić uwagę (jeśli występuje jakiś wzorzec)
            - Zwroty grzecznościowe: powitanie i pożegnanie, formy Pan/Pani vs na "ty", podpis.
            - Ton i rejestr: formalny/bezpośredni, ekspercki/kumpelski, zwięzły/rozbudowany.
            - Prezentacja cen: czy ceny podawane są wprost ("cena"), czy jako "inwestycja";
              czy cena stoi przy każdej usłudze (np. w nawiasie), format kwot i waluty.
            - Interpunkcja i formatowanie: myślniki vs kropki vs przecinki, listy, akapity,
              emoji, wielkość liter, długość zdań.
            - Nazewnictwo usług: dosłownie z cennika czy opisowo.
            - Struktura wiadomości: kolejność i obecność sekcji.

            # Zasady
            - Wypisz TYLKO wzorce faktycznie obecne w przykładach. Nie zgaduj, nie dopisuj "dobrych praktyk".
            - Jeśli przykłady są niespójne w danym aspekcie — pomiń go albo opisz wariant dominujący.
            - Pisz po polsku, w trybie rozkazującym, jako lista krótkich reguł.

            # Format wyjściowy
            Zwróć obiekt JSON z jednym polem:
            - "styleGuide" -> lista wytycznych stylu jako zwykły tekst (myślniki na początku linii).
              Jeśli z przykładów nie da się wyciągnąć żadnego wyraźnego wzorca — zwróć pusty string.
        """.trimIndent()
    }
}
