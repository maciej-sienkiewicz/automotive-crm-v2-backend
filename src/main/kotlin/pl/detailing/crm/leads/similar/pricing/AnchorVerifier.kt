package pl.detailing.crm.leads.similar.pricing

import com.fasterxml.jackson.annotation.JsonProperty
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.openai.OpenAiChatOptions
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Service

@Configuration
class AnchorVerifierAiConfig {

    /**
     * INNA rodzina modelu niż ekstraktor potrzeby (gpt-4.1-mini) — celowo: weryfikator
     * ma być krytykiem ZEWNĘTRZNYM, nie echem tych samych skłonności. Ocena binarna
     * ma być powtarzalna, stąd temperatura 0.
     */
    @Bean("anchorVerifierChatClient")
    fun anchorVerifierChatClient(
        builder: ChatClient.Builder,
        @Value("\${crm.ai.similar-visits.verifier-model:gpt-4o-mini}") model: String
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

/** Jeden kandydat w skompresowanym formacie dla weryfikatora. */
data class VerifierCandidate(
    /** Numer 1..n — model odpowiada TYM numerem w polu candidateId. */
    val candidateId: Int,
    /** Auto zlecenia, np. "BMW Seria 5 (E)". */
    val vehicle: String,
    /** Osie dominującej roboty, np. "APPLY_FILM/FULL_BODY". */
    val axes: String,
    /** SUROWE nazwy pasujących pozycji — to na nich weryfikator łapie błędy osi. */
    val serviceNames: List<String>,
    /** Kwota odniesienia w groszach. */
    val referenceGross: Long,
    /** Rok-miesiąc realizacji, np. "2025-03", albo null. */
    val happened: String?
)

/** Werdykt weryfikatora dla jednego kandydata. */
data class VerifierVerdict(
    val sameOperation: Boolean,
    val samePart: Boolean,
    val sameScale: Boolean,
    val priceComparable: Boolean,
    /** ≤140 znaków, na kartę compa: dlaczego ta kwota jest uczciwym odniesieniem. */
    val whyItFits: String?,
    /** ≤140 znaków, na kartę compa: co jawnie różni tę realizację od zapytania. */
    val whatDiffers: String?
) {
    /** 4/4 → utrzymany DIRECT; 3/4 z priceComparable → ADJUSTED; reszta wypada. */
    val passes: Boolean
        get() = priceComparable && listOf(sameOperation, samePart, sameScale).count { it } >= 2
}

/**
 * Weryfikator kotwic (L4) — jedyne wywołanie LLM na ścieżce leada, ADAPTACYJNE:
 * uruchamiane tylko, gdy bramki przepuściły ≥2 kandydatów o rozrzucie cen ponad
 * [GateThresholds.verifierSpreadTrigger]. Przy jednym ocalałym albo zgodnych cenach
 * nie ma czego rozsądzać i nie ma wywołania.
 *
 * Dostaje ZNORMALIZOWANĄ potrzebę (nie surowy mail) i kandydatów w stałym, krótkim
 * formacie — nigdy pełnych wykazów zlecenia: zlecenie z siedmioma pozycjami wygląda
 * „bogaciej" niż jednopozycyjne i sędzia podbijałby je z samej długości, czyli
 * dokładnie te, które bramka skupienia ma odrzucać. Surowe NAZWY pasujących pozycji
 * jednak widzi — bo to na nich łapie błędy klasyfikacji osi, których czysta funkcja
 * nie ma prawa zobaczyć.
 *
 * Cztery pytania BINARNE zamiast skali 0–100: subiektywne skale dają wysoką wariancję
 * i niską zgodność między przebiegami; werdykt składa KOD z booleanów.
 *
 * candidateId w KAŻDYM obiekcie odpowiedzi usuwa problem atrybucji werdyktu
 * (model gubiący numerację listy) projektem — bez ~200 linii kodu naprawczego.
 *
 * ASYMETRIA STRATY względem weryfikatora Instagrama: tam brak dowodu znaczył „bez
 * naruszenia"; tu fałszywa kotwica kosztuje więcej niż brakująca, więc negatyw
 * odrzuca compa również bez cytatu — leniwe „nie" jest bezpieczniejsze niż leniwe „tak".
 *
 * JEDEN przebieg. Drugi (ze zamianą kolejności) dojdzie wyłącznie, jeśli pomiar
 * na golden secie pokaże niestabilność — pozycyjny bias na 4 niezależnych pytaniach
 * binarnych per kandydat jest jakościowo słabszy niż na rankingu.
 */
@Service
class AnchorVerifier(
    @Qualifier("anchorVerifierChatClient") private val chatClient: ChatClient
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * @return werdykt per candidateId albo null przy awarii modelu. Null NIE jest
     *   pustą mapą: awaria weryfikatora daje abstencję (fałszywa kotwica kosztuje
     *   więcej niż brakująca), pusta mapa znaczy „model odpowiedział i nikogo nie ocenił".
     */
    fun verify(needSummary: String, candidates: List<VerifierCandidate>): Map<Int, VerifierVerdict>? {
        if (candidates.isEmpty()) return emptyMap()

        val listing = candidates.joinToString("\n") { c ->
            "#${c.candidateId} | ${c.vehicle} | ${c.axes} | " +
                "pozycje: ${c.serviceNames.joinToString(", ")} | " +
                "${formatZl(c.referenceGross)} | ${c.happened ?: "data nieznana"}"
        }

        return try {
            val answers = chatClient.prompt()
                .system(SYSTEM_PROMPT)
                .user(
                    """
POTRZEBA KLIENTA (znormalizowana przez system, nie surowy mail)
$needSummary

KANDYDACI NA KOTWICĘ CENOWĄ
$listing
""".trim()
                )
                .call()
                .entity(RawVerdicts::class.java)
                ?.results
                .orEmpty()

            answers
                .mapNotNull { raw ->
                    val id = raw.candidateId ?: return@mapNotNull null
                    if (candidates.none { it.candidateId == id }) return@mapNotNull null
                    id to VerifierVerdict(
                        sameOperation = raw.sameOperation ?: false,
                        samePart = raw.samePart ?: false,
                        sameScale = raw.sameScale ?: false,
                        priceComparable = raw.priceComparable ?: false,
                        whyItFits = raw.whyItFits?.trim()?.take(140)?.takeIf { it.isNotEmpty() },
                        whatDiffers = raw.whatDiffers?.trim()?.take(140)?.takeIf { it.isNotEmpty() }
                    )
                }
                .toMap()
        } catch (e: Exception) {
            log.warn("[SIMILAR_VISITS] Weryfikator kotwic nie powiódł się: {}", e.message)
            null
        }
    }

    internal data class RawVerdicts(
        @JsonProperty("results") val results: List<RawVerdict>? = null
    )

    internal data class RawVerdict(
        @JsonProperty("candidateId") val candidateId: Int? = null,
        @JsonProperty("reasoning") val reasoning: String? = null,
        @JsonProperty("sameOperation") val sameOperation: Boolean? = null,
        @JsonProperty("samePart") val samePart: Boolean? = null,
        @JsonProperty("sameScale") val sameScale: Boolean? = null,
        @JsonProperty("priceComparable") val priceComparable: Boolean? = null,
        @JsonProperty("evidence") val evidence: String? = null,
        @JsonProperty("whyItFits") val whyItFits: String? = null,
        @JsonProperty("whatDiffers") val whatDiffers: String? = null
    )

    private fun formatZl(gross: Long): String = "%d zł".format(gross / 100)

    companion object {
        internal val SYSTEM_PROMPT = """
Jesteś surowym rzeczoznawcą w studiu detailingu. Oceniasz, czy HISTORYCZNE zlecenia
nadają się jako KOTWICA CENOWA dla zapytania klienta — czyli czy właściciel może
uczciwie powiedzieć „za taką robotę braliśmy tyle".

Dla KAŻDEGO kandydata z listy odpowiedz na cztery pytania TAK/NIE:
  sameOperation    czy to jest to samo RZEMIOSŁO (naprawa ≠ czyszczenie,
                   folia ≠ powłoka, korekta ≠ mycie)?
  samePart         czy to jest ta sama CZĘŚĆ AUTA albo część wymienna cenowo
                   (fotel i boczek drzwi przy naprawie tapicerki — tak;
                   próg bagażnika i całe nadwozie przy folii — nie)?
  sameScale        czy to jest ta sama SKALA roboty (całe auto ≠ jeden element,
                   nawet przy tej samej folii)?
  priceComparable  czy kwota tego zlecenia uczciwie odpowiada na pytanie
                   „ile bierzemy za robotę, o którą pyta klient"?

ZASADY:
- Oceniasz po SUROWYCH NAZWACH pozycji, nie po etykietach osi — etykiety bywają
  błędne i Twoim zadaniem jest je wyłapać.
- reasoning piszesz PRZED werdyktami — najpierw analiza, potem odpowiedzi.
- Fałszywa kotwica kosztuje więcej niż brakująca. W razie wątpliwości odpowiadaj NIE.
- whyItFits (≤140 znaków, po polsku): dlaczego ta kwota jest uczciwym odniesieniem —
  to zdanie zobaczy właściciel studia na karcie.
- whatDiffers (≤140 znaków, po polsku): co JAWNIE różni tę realizację od zapytania.
- W polu candidateId przepisz numer kandydata z listy. KAŻDY kandydat dostaje
  osobny obiekt odpowiedzi.

ODPOWIEDŹ: { results: [ { candidateId, reasoning, sameOperation, samePart,
sameScale, priceComparable, evidence, whyItFits, whatDiffers } ] }
""".trim()
    }
}
