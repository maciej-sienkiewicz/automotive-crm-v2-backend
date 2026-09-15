package pl.detailing.crm.leads.similar

import pl.detailing.crm.service.taxonomy.ServiceFamily
import pl.detailing.crm.service.taxonomy.ServiceScope
import java.util.UUID

/**
 * Ranga dopasowania — porządek zadany przez właściciela produktu:
 *
 *   1. ten sam model  + TA SAMA usługa
 *   2. ta sama klasa  + TA SAMA usługa
 *   3. ten sam model  + PODOBNA usługa
 *   4. ta sama klasa  + PODOBNA usługa
 *
 * Wszystko inne ODPADA. Dwie rangi z pierwotnej listy zostały skasowane ŚWIADOMIE
 * (decyzja z przebudowy — docs/similar-visits-redesign.md §1.3):
 *
 *  - SAME_MODEL_OTHER_SERVICE wykonywała się DOKŁADNIE wtedy, gdy progi pokrycia
 *    nie przeszły — jedno ramię `when` anulowało całą bramkę i to ono podsuwało
 *    „Usunięcie rys" za 400 zł do zapytania o PPF za 18 450 zł. Uzasadnienie
 *    „historia dokładnie tego auta broni się sama" zawierało błąd kategorialny:
 *    lead ma ten sam MODEL, nie to samo AUTO.
 *  - MODEL_HISTORY odwracała logikę niewiedzy: „nie wiemy, o co pyta" dawało pełną
 *    listę cen, a „wiemy i tego nie sprzedajemy" — pustkę. Historia modelu żyje
 *    teraz w OSOBNEJ sekcji odpowiedzi (vehicleHistory), jawnie bez roli cenowej.
 *
 * Usługa dominuje nad autem (ranga 2 bije rangę 3): pytanie handlowca brzmi
 * „ile bierzemy za taką robotę", a auto tylko kalibruje rozmiar tej roboty.
 */
enum class MatchTier {
    SAME_MODEL_SAME_SERVICE,
    SAME_SEGMENT_SAME_SERVICE,
    SAME_MODEL_SIMILAR_SERVICE,
    SAME_SEGMENT_SIMILAR_SERVICE
}

/**
 * Wynik kraty dla jednego kandydata — ranga plus metryki, które do tej pory
 * ginęły wraz z ramką stosu. Metryki zasilają dziennik decyzji (lead_match_decisions)
 * i bramki ekonomiczne w AnchorGate.
 *
 * @property tier ranga albo null (odpada)
 * @property coverage pokrycie zapytania przez zlecenie (0..1, liczone po licznościach — jak dotąd)
 * @property focus ile zlecenia to ta robota (0..1, po licznościach; bramka KWOTOWA żyje w AnchorGate)
 * @property matchingSignatureIds sygnatury, które odpowiadają zapytaniu (oś != DIFFERENT) —
 *   po nich AnchorGate sumuje pieniądze pozycji
 */
data class LatticeResult(
    val tier: MatchTier?,
    val coverage: Double,
    val focus: Double,
    val matchingSignatureIds: Set<UUID>
)

/**
 * Krata dopasowania: (oś auta) × (oś usługi) → ranga albo odrzucenie.
 *
 * CZYSTA FUNKCJA, żadnych zależności — bo to jest serce funkcji i musi dać się
 * przetestować jednostkowo zdanie po zdaniu. Wołający dostarcza fakty (stempel
 * zlecenia, sygnatury pozycji, intencję leada), tu zapada wyłącznie werdykt.
 *
 * ═══ Oś usługi liczy POKRYCIE ZAKRESU, nie najlepszą pojedynczą pozycję ═══
 *
 * Liczymy dwie proporcje:
 *
 *   POKRYCIE   ile z tego, o co pyta klient, zlecenie faktycznie zawiera
 *   SKUPIENIE  ile z tego zlecenia to ta sama robota, a ile rzeczy obok
 *
 * Handlowiec pyta „ile wzięliśmy za taką robotę" — a kwota zlecenia opisuje
 * CAŁE zlecenie. Zlecenie, które robi połowę tego, o co pyta klient, albo w
 * którym ta robota jest dodatkiem do czegoś większego, na to pytanie nie
 * odpowiada, tylko wprowadza w błąd. Lepiej nie pokazać nic.
 *
 * Krata rozstrzyga TOŻSAMOŚĆ RODZAJU roboty. SKALĘ (850 zł vs 18 450 zł przy tej
 * samej rodzinie) i RZEMIOSŁO (czyszczenie vs naprawa w tej samej rodzinie)
 * rozstrzygają bramki w [pricing.AnchorGate] — krata dostaje ich werdykt gotowy,
 * jako zbiór [axisDisqualified], i traktuje takie pozycje jak DIFFERENT.
 */
object SimilarVisitMatcher {

    /** Oś usługi dla JEDNEJ pozycji zlecenia względem intencji leada. */
    private enum class ServiceAxis { SAME, SIMILAR, DIFFERENT }

    private data class Overlap(
        val coverage: Double,
        val focus: Double,
        val exact: Boolean,
        val matchingIds: Set<UUID>
    )

    /**
     * Zlecenie musi zawierać PRZYNAJMNIEJ POŁOWĘ tego, o co pyta klient. Próg jest
     * niski celowo: klient wymienia w mailu roboty, których część studio i tak
     * wpisze do jednej pozycji cennika.
     */
    private const val MIN_COVERAGE = 0.5

    /**
     * I przynajmniej jedna trzecia zlecenia musi być tą robotą. Bez tego progu
     * duże zlecenie „przy okazji" zawierające tę usługę wygrywa z całą resztą,
     * bo pokrycie ma pełne — a jego kwota mówi o zupełnie innej robocie.
     */
    private const val MIN_FOCUS = 1.0 / 3.0

    /** Zgodność wsteczna: ranga bez metryk. Odpowiada [evaluate] bez dyskwalifikacji osiowych. */
    fun grade(
        candidate: VisitIndexStateEntity,
        signatures: List<VisitServiceSignatureEntity>,
        intent: LeadServiceIntent,
        leadBrandKey: String?,
        leadModelKey: String?,
        leadSegment: String?
    ): MatchTier? = evaluate(candidate, signatures, intent, leadBrandKey, leadModelKey, leadSegment).tier

    /**
     * @param leadBrandKey / leadModelKey — auto leada (lower/trim), null gdy nieznane
     * @param leadSegment — segment wielkości auta leada, null/UNKNOWN gdy nieznany
     * @param axisDisqualified — sygnatury zdyskwalifikowane przez bramki osi
     *   (operacja/część, [pricing.AnchorGate]); krata traktuje je jak DIFFERENT,
     *   ale zostają w mianowniku skupienia — dyskwalifikacja nie odchudza zlecenia
     */
    fun evaluate(
        candidate: VisitIndexStateEntity,
        signatures: List<VisitServiceSignatureEntity>,
        intent: LeadServiceIntent,
        leadBrandKey: String?,
        leadModelKey: String?,
        leadSegment: String?,
        axisDisqualified: Set<UUID> = emptySet()
    ): LatticeResult {
        val sameModel = leadBrandKey != null && leadModelKey != null &&
            candidate.brandKey == leadBrandKey && candidate.modelKey == leadModelKey
        val sameSegment = !leadSegment.isNullOrBlank() && leadSegment != UNKNOWN &&
            candidate.sizeSegment == leadSegment

        if (!sameModel && !sameSegment) return NO_MATCH

        // Intencji nie znamy — sekcja CENOWA nie ma prawa niczego pokazać.
        // Historia dokładnie tego auta żyje w osobnej sekcji, poza kratą.
        if (intent.status == ServiceIntentStatus.NO_SERVICE) return NO_MATCH

        val overlap = overlap(signatures, intent, axisDisqualified)
        val enough = overlap.coverage >= MIN_COVERAGE && overlap.focus >= MIN_FOCUS

        val tier = when {
            enough && overlap.exact && sameModel -> MatchTier.SAME_MODEL_SAME_SERVICE
            enough && overlap.exact -> MatchTier.SAME_SEGMENT_SAME_SERVICE
            enough && sameModel -> MatchTier.SAME_MODEL_SIMILAR_SERVICE
            enough -> MatchTier.SAME_SEGMENT_SIMILAR_SERVICE
            // Progi nie przeszły → ODPADA. Nie ma rangi-śmietnika, która by je anulowała.
            else -> null
        }
        return LatticeResult(tier, overlap.coverage, overlap.focus, overlap.matchingIds)
    }

    /**
     * Pokrycie i skupienie dla całego zlecenia.
     *
     * Pokrycie liczymy DWOMA miarami i bierzemy hojniejszą: po pozycjach cennika
     * (dowód tożsamości) i po rodzinach roboty. Studio potrafi sprzedać tę samą
     * robotę pod inną nazwą niż ta, którą wskazał model — rodzina to wyłapuje.
     * Ale tylko pokrycie po pozycjach daje rangę „TA SAMA usługa": rodzina mówi,
     * że to ten sam rodzaj roboty, nie że ta sama robota.
     */
    private fun overlap(
        signatures: List<VisitServiceSignatureEntity>,
        intent: LeadServiceIntent,
        axisDisqualified: Set<UUID>
    ): Overlap {
        // Zlecenie bez sygnatur — nie wiemy, co w nim było. Brak wiedzy nie jest dopasowaniem.
        if (signatures.isEmpty()) return Overlap(coverage = 0.0, focus = 0.0, exact = false, matchingIds = emptySet())

        val axes = signatures.map { it to serviceAxis(it, intent, axisDisqualified) }
        val matching = axes.filter { (_, axis) -> axis != ServiceAxis.DIFFERENT }
        val focus = matching.size.toDouble() / signatures.size

        val visitKeys = matching.map { (signature, _) -> signature.nameKey }.toSet()
        val visitFamilies = matching
            .map { (signature, _) -> ServiceFamily.from(signature.family) }
            .filter { it != ServiceFamily.UNKNOWN && it != ServiceFamily.OTHER }
            .toSet()
        // Rodziny, w których zlecenie zrobiło robotę w TYM SAMYM zakresie co pytanie —
        // to jest dowód tożsamości z rodziny, ten sam, co w [serviceAxis].
        val identityFamilies = axes
            .filter { (_, axis) -> axis == ServiceAxis.SAME }
            .map { (signature, _) -> ServiceFamily.from(signature.family) }
            .toSet()

        val askedKeys = intent.matchedNameKeys
        val askedFamilies = intent.families
        val keyCoverage = ratio(askedKeys.count { it in visitKeys }, askedKeys.size)
        val familyCoverage = ratio(askedFamilies.count { it in visitFamilies }, askedFamilies.size)
        val identityCoverage = ratio(askedFamilies.count { it in identityFamilies }, askedFamilies.size)

        return Overlap(
            coverage = maxOf(keyCoverage, familyCoverage),
            focus = focus,
            // „Ta sama robota" wymaga DOWODU tożsamości na przynajmniej połowie
            // zapytania: albo wprost pozycją cennika, albo rodziną z tym samym
            // zakresem. Sama zgodność rodziny to dopiero „podobna".
            exact = maxOf(keyCoverage, identityCoverage) >= MIN_COVERAGE,
            matchingIds = matching.map { (signature, _) -> signature.id }.toSet()
        )
    }

    /** Pusty mianownik to brak wymagania, a nie pokrycie zerowe ani pełne. */
    private fun ratio(covered: Int, asked: Int): Double = if (asked == 0) 0.0 else covered.toDouble() / asked

    /**
     * TA SAMA usługa wymaga DOWODU tożsamości, nie braku dowodu różnicy:
     *  - pozycja zlecenia to dokładnie ta pozycja cennika, którą wskazała intencja, albo
     *  - rodzina się zgadza I zakres się zgadza (oba znane).
     *
     * PODOBNA = ta sama rodzina, zakres NIEZNANY po którejś stronie.
     *
     * Zakres znany po OBU stronach i jawnie sprzeczny („przód" vs „całe auto") to
     * INNA robota — inna skala i inna cena. Do przebudowy taki konflikt degradował
     * tylko do „podobnej" i to on wpuścił folię na progu bagażnika za 850 zł jako
     * odpowiedź na pytanie o full body za 18 450 zł. Konflikt ZNANYCH zakresów
     * dyskwalifikuje; nieznany zakres jedynie odbiera dowód tożsamości.
     */
    private fun serviceAxis(
        signature: VisitServiceSignatureEntity,
        intent: LeadServiceIntent,
        axisDisqualified: Set<UUID>
    ): ServiceAxis {
        // Werdykt bramek osi (rzemiosło/część z AnchorGate) jest ostateczny:
        // czyszczenie nie staje się naprawą przez wspólną rodzinę INTERIOR.
        if (signature.id in axisDisqualified) return ServiceAxis.DIFFERENT

        val visitScope = ServiceScope.from(signature.scope)
        val scopesKnown = visitScope != ServiceScope.UNKNOWN && intent.scope != ServiceScope.UNKNOWN
        val scopeConflict = scopesKnown && visitScope != intent.scope

        if (signature.nameKey in intent.matchedNameKeys) {
            // Dokładna pozycja cennika, ale zlecenie robiło ją w INNYM zakresie niż
            // pyta klient — cennik potrafi mieć jedną nazwę na obie skale.
            return if (scopeConflict) ServiceAxis.DIFFERENT else ServiceAxis.SAME
        }

        val family = ServiceFamily.from(signature.family)
        // UNKNOWN i OTHER nie tworzą wspólnoty: dwie nieodgadnione nazwy nie stają
        // się przez to tą samą robotą.
        val familyMatch = family != ServiceFamily.UNKNOWN && family != ServiceFamily.OTHER &&
            family in intent.families
        if (!familyMatch) return ServiceAxis.DIFFERENT

        return when {
            scopeConflict -> ServiceAxis.DIFFERENT
            scopesKnown -> ServiceAxis.SAME
            else -> ServiceAxis.SIMILAR
        }
    }

    private val NO_MATCH = LatticeResult(tier = null, coverage = 0.0, focus = 0.0, matchingSignatureIds = emptySet())

    private const val UNKNOWN = "UNKNOWN"
}
