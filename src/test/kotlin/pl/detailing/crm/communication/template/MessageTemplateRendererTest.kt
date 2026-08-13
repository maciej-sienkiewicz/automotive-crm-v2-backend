package pl.detailing.crm.communication.template

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import pl.detailing.crm.shared.ValidationException

class MessageTemplateRendererTest {

    private val renderer = MessageTemplateRenderer()

    @Test
    fun `substitutes every declared placeholder`() {
        val result = renderer.render(
            "Cześć {{imie}} {{nazwisko}}, termin: {{data}} {{godzina}}",
            mapOf("imie" to "Jan", "nazwisko" to "Kowalski", "data" to "02.04.2026", "godzina" to "14:30")
        )

        assertThat(result).isEqualTo("Cześć Jan Kowalski, termin: 02.04.2026 14:30")
    }

    @Test
    fun `refuses to deliver a template it cannot fully resolve`() {
        assertThatThrownBy {
            renderer.render("Cześć {{imie}}, zapraszamy do {{studio}}", mapOf("imie" to "Jan"))
        }
            .isInstanceOf(UnresolvedPlaceholderException::class.java)
            .hasMessageContaining("{{studio}}")
    }

    @Test
    fun `empty substitutions do not leave double spaces or trailing blanks`() {
        val result = renderer.render(
            "Pojazd {{pojazd}} {{rejestracja}} jest gotowy. ",
            mapOf("pojazd" to "BMW X5", "rejestracja" to "")
        )

        assertThat(result).isEqualTo("Pojazd BMW X5 jest gotowy.")
    }

    @Test
    fun `tolerates whitespace inside the braces`() {
        val result = renderer.render("Cześć {{ imie }}", mapOf("imie" to "Jan"))

        assertThat(result).isEqualTo("Cześć Jan")
    }

    @Test
    fun `a value containing braces is not re-scanned as a placeholder`() {
        val result = renderer.render("Uwaga: {{uslugi}}", mapOf("uslugi" to "korekta {{2 etapy}}"))

        assertThat(result).isEqualTo("Uwaga: korekta {{2 etapy}}")
    }

    @Test
    fun `validate rejects a placeholder the message cannot fill`() {
        assertThatThrownBy {
            MessageTemplateKind.SMS_PRE_VISIT.validate(
                "Cześć {{imie}}, zapraszamy do {{studio}}",
                renderer,
                "Przypomnienie przed wizytą"
            )
        }
            .isInstanceOf(ValidationException::class.java)
            .hasMessageContaining("{{studio}}")
            .hasMessageContaining("Przypomnienie przed wizytą")
    }

    @Test
    fun `validate accepts a template using only declared placeholders`() {
        MessageTemplateKind.SMS_PRE_VISIT.validate(
            "Cześć {{imie}} {{nazwisko}}, do zobaczenia {{data}} o {{godzina}}",
            renderer,
            "Przypomnienie przed wizytą"
        )
    }

    @Test
    fun `no message kind offers a placeholder for something the studio already knows`() {
        val forbidden = setOf("studio", "firma", "telefon_studia", "www", "adres")

        MessageTemplateKind.entries.forEach { kind ->
            assertThat(kind.allowedPlaceholders)
                .describedAs("%s must not expose studio-owned data as a placeholder", kind)
                .doesNotContainAnyElementsOf(forbidden)
        }
    }
}
