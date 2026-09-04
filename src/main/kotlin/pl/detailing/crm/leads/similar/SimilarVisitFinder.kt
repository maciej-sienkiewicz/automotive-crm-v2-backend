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
 */
object SimilarVisitMatcher {

    /** Oś usługi dla JEDNEJ pozycji zlecenia względem intencji leada. */
    private enum class ServiceAxis { SAME, SIMILAR, DIFFERENT }

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

        val axis = signatures
            .map { serviceAxis(it, intent) }
            .minByOrNull { it.ordinal }  // najlepsza pozycja decyduje o zleceniu
            ?: ServiceAxis.DIFFERENT     // zlecenie bez sygnatur = robota nieznana

        return when {
            axis == ServiceAxis.SAME && sameModel -> MatchTier.SAME_MODEL_SAME_SERVICE
            axis == ServiceAxis.SAME -> MatchTier.SAME_SEGMENT_SAME_SERVICE
            axis == ServiceAxis.SIMILAR && sameModel -> MatchTier.SAME_MODEL_SIMILAR_SERVICE
            axis == ServiceAxis.SIMILAR -> MatchTier.SAME_SEGMENT_SIMILAR_SERVICE
            sameModel -> MatchTier.SAME_MODEL_OTHER_SERVICE
            // ta sama klasa + inna usługa — poza listą właściciela, odpada
            else -> null
        }
    }

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
