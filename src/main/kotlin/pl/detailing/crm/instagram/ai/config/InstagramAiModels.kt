package pl.detailing.crm.instagram.ai.config

import org.springframework.ai.openai.OpenAiChatOptions
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

/**
 * Model i temperatura OSOBNO dla każdego kroku pętli.
 *
 * Kroki mają sprzeczne wymagania i jeden globalny `spring.ai.openai.chat.options.*` nie
 * może ich pogodzić:
 *   - GENERATOR   — ma być kreatywny (temperatura z konfiguracji globalnej, 0.7) i to on
 *                   jako jedyny naprawdę korzysta z mocniejszego modelu: pisze tekst
 *                   trzymając w głowie kilkanaście reguł naraz.
 *   - WERYFIKATOR — ocena „spełnia / nie spełnia" ma być POWTARZALNA, stąd temperatura 0.0.
 *   - KOREKTOR    — poprawia naruszenie, nie pisze posta od nowa: 0.3.
 *   - KLASYFIKATOR — wybór jednej z czterech etykiet; najtańszy model wystarcza.
 *
 * Domyślnie wszystkie kroki dziedziczą model z `spring.ai.openai.chat.options.model`,
 * więc sama obecność tej klasy niczego nie zmienia w rachunku za OpenAI. Podniesienie
 * jakości generowania to jedna właściwość:
 *
 *     instagram.ai.model.generator=gpt-4.1
 *
 * Opcje żądania nadpisują konfigurację globalną tylko w polach, które ustawiają
 * (Spring AI scala je z domyślnymi) — pusta wartość = zostaje model globalny.
 */
@Component
class InstagramAiModels(
    @Value("\${instagram.ai.model.generator:}") generatorModel: String = "",
    @Value("\${instagram.ai.model.verifier:}") verifierModel: String = "",
    @Value("\${instagram.ai.model.corrector:}") correctorModel: String = "",
    @Value("\${instagram.ai.model.classifier:}") classifierModel: String = ""
) {
    companion object {
        /** Ocena binarna ma być powtarzalna, nie kreatywna. */
        const val VERIFIER_TEMPERATURE = 0.0

        /** Przepisanie jednego zdania wymaga odrobiny swobody językowej — ale tylko odrobiny. */
        const val CORRECTOR_TEMPERATURE = 0.3
    }

    /** null = brak nadpisania, czyli model i temperatura z konfiguracji globalnej. */
    val generator: OpenAiChatOptions? = modelOnly(generatorModel)

    val verifier: OpenAiChatOptions = withTemperature(verifierModel, VERIFIER_TEMPERATURE)

    val corrector: OpenAiChatOptions = withTemperature(correctorModel, CORRECTOR_TEMPERATURE)

    /** null = model globalny; klasyfikacja nie potrzebuje niczego mocniejszego. */
    val classifier: OpenAiChatOptions? = modelOnly(classifierModel)

    private fun modelOnly(name: String): OpenAiChatOptions? =
        name.takeIf { it.isNotBlank() }?.let { OpenAiChatOptions.builder().model(it).build() }

    private fun withTemperature(name: String, temperature: Double): OpenAiChatOptions =
        OpenAiChatOptions.builder()
            .temperature(temperature)
            .apply { if (name.isNotBlank()) model(name) }
            .build()
}
