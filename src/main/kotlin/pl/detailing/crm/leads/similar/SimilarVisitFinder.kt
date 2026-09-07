package pl.detailing.crm.leads.similar

import pl.detailing.crm.service.taxonomy.ServiceFamily
import pl.detailing.crm.service.taxonomy.ServiceScope

/**
 * Ranga dopasowania — porządek zadany przez właściciela produktu, wprost:
 *
 *   1. ten sam model  + TA SAMA usługa
 *   2. ta sama klasa  + TA SAMA usługa
 *   3. ten sam model  + PODOBNA usługa
 *   4. ta sama klasa  + PODOBNA usługa
 *   5. ten sam model  + INNA usługa
 *
 * Wszystko inne ODPADA — w szczególności „ta sama klasa + inna usługa": SUV
 * z myciem nie jest punktem odniesienia dla oklejenia innego SUV-a.
 *
 * Usługa dominuje nad autem (ranga 2 bije rangę 3): pytanie handlowca brzmi
 * „ile bierzemy za taką robotę", a auto tylko kalibruje rozmiar tej roboty.
 * Klasa = sam segment WIELKOŚCI, bez półki rynkowej — decyzja właściciela:
 * SUV VW kosztuje przy tej samej folii tyle, co SUV Porsche, bo pracę wyznacza
 * powierzchnia, nie logo.
 *
 * Kolejność deklaracji JEST kolejnością rang. [MODEL_HISTORY] stoi poza kratą:
 * to tryb „nie znamy usługi" (intencji nie dało się odczytać), gdzie jedyną
 * uczciwą podpowiedzią jest historia dokładnie tego auta — bez twierdzenia,
 * że robota jest „ta sama" albo „inna".
 */
enum class MatchTier {
    SAME_MODEL_SAME_SERVICE,
    SAME_SEGMENT_SAME_SERVICE,
    SAME_MODEL_SIMILAR_SERVICE,
    SAME_SEGMENT_SIMILAR_SERVICE,
    SAME_MODEL_OTHER_SERVICE,
    MODEL_HISTORY
}

/**
 * Krata dopasowania: (oś auta) × (oś usługi) → ranga albo odrzucenie.
 *
 * CZYSTA FUNKCJA, żadnych zależności — bo to jest serce funkcji i musi dać się
 * przetestować jednostkowo zdanie po zdaniu. Wołający dostarcza fakty (stempel
 * zlecenia, sygnatury pozycji, intencję leada), tu zapada wyłącznie werdykt.
 *
 * ═══ Oś usługi liczy POKRYCIE ZAKRESU, nie najlepszą pojedynczą pozycję ═══
 *
 * Wcześniej o randze zlecenia decydowała jedna, najlepiej trafiona pozycja. To
 * dawało odpowiedzi bezużyteczne jako punkt odniesienia dla ceny: zlecenie na
 * 18 819 zł (folia PPF na całe auto, przyciemnianie lamp, komora silnika i przy
 * okazji pakiet czyszczenia wnętrza) wygrywało z klientem pytającym o mycie,
 * lekką korektę i odświeżenie wnętrza — bo jedna pozycja z siedmiu się zgadzała.
 * W drugą stronę było tak samo: mycie za 270 zł wchodziło jako podpowiedź do
 * zapytania o przygotowanie auta do sprzedaży.
 *
 * Dlatego liczymy dwie proporcje:
 *
 *   POKRYCIE   ile z tego, o co pyta klient, zlecenie faktycznie zawiera
 *   SKUPIENIE  ile z tego zlecenia to ta sama robota, a ile rzeczy obok
 *
 * Handlowiec pyta „ile wzięliśmy za taką robotę" — a kwota zlecenia opisuje
 * CAŁE zlecenie. Zlecenie, które robi połowę tego, o co pyta klient, albo w
 * którym ta robota jest dodatkiem do czegoś większego, na to pytanie nie
 * odpowiada, tylko wprowadza w błąd. Lepiej nie pokazać nic.
 */
object SimilarVisitMatcher {

    /** Oś usługi dla JEDNEJ pozycji zlecenia względem intencji leada. */
    private enum class ServiceAxis { SAME, SIMILAR, DIFFERENT }

    /**
     * Ile z zapytania pokrywa zlecenie i ile z tego zlecenia to ta robota.
     * Obie liczby z zakresu 0..1; [exact] mówi, czy pokrycie idzie po pozycjach
     * cennika (dowód tożsamości), czy tylko po rodzinie roboty.
     */
    private data class Overlap(val coverage: Double, val focus: Double, val exact: Boolean)

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

    /**
     * @param leadBrandKey / leadModelKey — auto leada (lower/trim), null gdy nieznane
     * @param leadSegment — segment wielkości auta leada, null/UNKNOWN gdy nieznany
     */
    fun grade(
        candidate: VisitIndexStateEntity,
        signatures: List<VisitServiceSignatureEntity>,
        intent: LeadServiceIntent,
        leadBrandKey: String?,
        leadModelKey: String?,
        leadSegment: String?
    ): MatchTier? {
        val sameModel = leadBrandKey != null && leadModelKey != null &&
            candidate.brandKey == leadBrandKey && candidate.modelKey == leadModelKey
        val sameSegment = !leadSegment.isNullOrBlank() && leadSegment != UNKNOWN &&
            candidate.sizeSegment == leadSegment

        if (!sameModel && !sameSegment) return null

        // Intencji nie znamy — pokazujemy wyłącznie historię DOKŁADNIE tego auta,
        // pod uczciwą etykietą. Segmentowe zlecenia bez znanej usługi to już nie
        // podpowiedź, tylko szum.
        if (intent.status == ServiceIntentStatus.NO_SERVICE) {
            return if (sameModel) MatchTier.MODEL_HISTORY else null
        }

        val overlap = overlap(signatures, intent)
        val enough = overlap.coverage >= MIN_COVERAGE && overlap.focus >= MIN_FOCUS

        return when {
            enough && overlap.exact && sameModel -> MatchTier.SAME_MODEL_SAME_SERVICE
            enough && overlap.exact -> MatchTier.SAME_SEGMENT_SAME_SERVICE
            enough && sameModel -> MatchTier.SAME_MODEL_SIMILAR_SERVICE
            enough -> MatchTier.SAME_SEGMENT_SIMILAR_SERVICE
            // Historia DOKŁADNIE tego auta broni się sama, nawet przy innej robocie.
            sameModel -> MatchTier.SAME_MODEL_OTHER_SERVICE
            // ta sama klasa + inna usługa — poza listą właściciela, odpada
            else -> null
        }
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
    private fun overlap(signatures: List<VisitServiceSignatureEntity>, intent: LeadServiceIntent): Overlap {
        // Zlecenie bez sygnatur — nie wiemy, co w nim było. Brak wiedzy nie jest dopasowaniem.
        if (signatures.isEmpty()) return Overlap(coverage = 0.0, focus = 0.0, exact = false)

        val axes = signatures.map { it to serviceAxis(it, intent) }
        val matching = axes.count { (_, axis) -> axis != ServiceAxis.DIFFERENT }
        val focus = matching.toDouble() / signatures.size

        val visitKeys = signatures.map { it.nameKey }.toSet()
        val visitFamilies = signatures
            .map { ServiceFamily.from(it.family) }
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
            exact = maxOf(keyCoverage, identityCoverage) >= MIN_COVERAGE
        )
    }

    /** Pusty mianownik to brak wymagania, a nie pokrycie zerowe ani pełne. */
    private fun ratio(covered: Int, asked: Int): Double = if (asked == 0) 0.0 else covered.toDouble() / asked

    /**
     * TA SAMA usługa wymaga DOWODU tożsamości, nie braku dowodu różnicy:
     *  - pozycja zlecenia to dokładnie ta pozycja cennika, którą wskazała intencja, albo
     *  - rodzina się zgadza I zakres się zgadza (oba znane).
     *
     * PODOBNA = ta sama rodzina, ale bez dowodu tożsamości — zakres nieznany albo
     * jawnie różny („przód" vs „całe auto": ta sama robota, inna skala i inna cena).
     */
    private fun serviceAxis(signature: VisitServiceSignatureEntity, intent: LeadServiceIntent): ServiceAxis {
        if (signature.nameKey in intent.matchedNameKeys) return ServiceAxis.SAME

        val family = ServiceFamily.from(signature.family)
        // UNKNOWN i OTHER nie tworzą wspólnoty: dwie nieodgadnione nazwy nie stają
        // się przez to tą samą robotą.
        val familyMatch = family != ServiceFamily.UNKNOWN && family != ServiceFamily.OTHER &&
            family in intent.families
        if (!familyMatch) return ServiceAxis.DIFFERENT

        val visitScope = ServiceScope.from(signature.scope)
        val sameScope = visitScope != ServiceScope.UNKNOWN && intent.scope != ServiceScope.UNKNOWN &&
            visitScope == intent.scope
        return if (sameScope) ServiceAxis.SAME else ServiceAxis.SIMILAR
    }

    private const val UNKNOWN = "UNKNOWN"
}
