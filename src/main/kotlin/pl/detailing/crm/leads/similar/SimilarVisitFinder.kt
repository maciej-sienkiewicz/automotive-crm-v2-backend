package pl.detailing.crm.leads.similar

import org.slf4j.LoggerFactory
import org.springframework.ai.document.Document
import org.springframework.ai.vectorstore.SearchRequest
import org.springframework.ai.vectorstore.VectorStore
import org.springframework.ai.vectorstore.filter.Filter
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Service
import pl.detailing.crm.shared.StudioId
import java.util.UUID

/**
 * Zlecenie-kandydat: identyfikator plus to, na którym kroku kaskady zostało znalezione.
 *
 * [tier] nie jest ozdobnikiem — decyduje o kolejności PRZED oceną modelu. Zlecenie na
 * tym samym modelu jest lepszą odpowiedzią na „ile za Panamerę" niż semantycznie
 * bliskie zlecenie na innym aucie, nawet jeśli tekst pasuje lepiej.
 */
data class VisitCandidate(
    val visitId: UUID,
    val tier: MatchTier,
    val description: String
)

/**
 * Jak blisko trafiliśmy w pojazd. Kolejność deklaracji JEST kolejnością pierwszeństwa.
 */
enum class MatchTier {
    /** Ten sam model — „mieliśmy dokładnie taką Panamerę". */
    SAME_MODEL,

    /** Ta sama marka, inny model — wciąż ten sam warsztat i ta sama rozmowa o cenie. */
    SAME_BRAND,

    /** Ten sam segment i klasa rynkowa — inna marka, porównywalna praca i półka cenowa. */
    SAME_CLASS,

    /** Cokolwiek podobnego w studiu — ostatnia deska, gdy historia jest uboga. */
    ANY
}

/**
 * Szuka w historii studia zleceń podobnych do zapytania z leada.
 *
 * KASKADA ZAMIAST JEDNEGO WYSZUKIWANIA. Czysta bliskość semantyczna odpowiadałaby na
 * pytanie „które zlecenie brzmi podobnie", a pytanie brzmi „co robiliśmy dla TAKIEGO
 * auta". To nie to samo: opis mycia Golfa potrafi być tekstowo bliższy zapytaniu
 * o mycie niż oklejenie Panamery, choć przy wycenie Panamery jest bezużyteczny.
 * Dlatego zakres zawężamy metadanymi — najpierw ten sam model, potem marka, potem
 * segment i klasa rynkowa — a podobieństwo tekstu rozstrzyga dopiero WEWNĄTRZ kroku.
 *
 * Kaskada schodzi niżej tylko wtedy, gdy krok wyżej nie zebrał dość materiału: studio,
 * które robiło trzy Panamery, ma zobaczyć te trzy, a nie utonąć w SUV-ach.
 *
 * FILTR STUDIA JEST WYMUSZONY W TYM KODZIE, nie zostawiony wywołującemu. W bazie
 * wektorowej leżą zlecenia wszystkich studiów, a metadana `studio_id` to jedyna
 * bariera między nimi — cudza cena na ekranie handlowca to nie usterka, tylko wyciek.
 */
@Service
class SimilarVisitFinder(
    @Qualifier(VisitSimilarityVectorConfig.VISIT_SIMILARITY_VECTOR_STORE)
    private val vectorStore: VectorStore
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun find(
        studioId: StudioId,
        query: String,
        brand: String?,
        model: String?,
        sizeSegment: String?,
        marketTier: String?,
        limit: Int = MAX_CANDIDATES
    ): List<VisitCandidate> {
        val text = query.trim().take(MAX_QUERY_LENGTH)
        if (text.isEmpty()) return emptyList()

        val found = LinkedHashMap<UUID, VisitCandidate>()

        for (step in steps(brand, model, sizeSegment, marketTier)) {
            if (found.size >= limit) break
            val documents = search(text, studioId, step.narrow, limit)
            for (document in documents) {
                val visitId = document.metadata[VisitDocumentFactory.META_VISIT_ID]
                    ?.toString()
                    ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                    ?: continue
                // Pierwsze trafienie wygrywa: zlecenie znalezione jako „ten sam model"
                // nie ma być potem przepisane na słabszy krok kaskady.
                found.putIfAbsent(visitId, VisitCandidate(visitId, step.tier, document.text.orEmpty()))
            }
        }

        log.debug(
            "[SIMILAR_VISITS] Studio {}: {} kandydatów ({})",
            studioId.value, found.size, found.values.groupingBy { it.tier }.eachCount()
        )
        return found.values.take(limit)
    }

    /**
     * Kroki kaskady dostępne dla tego leada. Krok bez danych po prostu nie powstaje —
     * lead bez rozpoznanego auta schodzi od razu do wyszukiwania po samej treści.
     */
    private fun steps(
        brand: String?,
        model: String?,
        sizeSegment: String?,
        marketTier: String?
    ): List<Step> = buildList {
        val cleanBrand = brand?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        val cleanModel = model?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }

        if (cleanBrand != null && cleanModel != null) {
            add(Step(MatchTier.SAME_MODEL) { b, expr ->
                b.and(b.and(expr, b.eq(VisitDocumentFactory.META_BRAND, cleanBrand)),
                    b.eq(VisitDocumentFactory.META_MODEL, cleanModel))
            })
        }
        if (cleanBrand != null) {
            add(Step(MatchTier.SAME_BRAND) { b, expr ->
                b.and(expr, b.eq(VisitDocumentFactory.META_BRAND, cleanBrand))
            })
        }
        if (!sizeSegment.isNullOrBlank() && sizeSegment != UNKNOWN) {
            add(Step(MatchTier.SAME_CLASS) { b, expr ->
                var narrowed = b.and(expr, b.eq(VisitDocumentFactory.META_SIZE_SEGMENT, sizeSegment))
                if (!marketTier.isNullOrBlank() && marketTier != UNKNOWN) {
                    narrowed = b.and(narrowed, b.eq(VisitDocumentFactory.META_MARKET_TIER, marketTier))
                }
                narrowed
            })
        }
        add(Step(MatchTier.ANY) { _, expr -> expr })
    }

    private fun search(
        query: String,
        studioId: StudioId,
        narrow: (FilterExpressionBuilder, FilterExpressionBuilder.Op) -> FilterExpressionBuilder.Op,
        topK: Int
    ): List<Document> {
        val builder = FilterExpressionBuilder()
        // Filtr studia jest PIERWSZY i bezwarunkowy — każdy krok kaskady tylko go zawęża.
        val expression: Filter.Expression = narrow(
            builder,
            builder.eq(VisitDocumentFactory.META_STUDIO_ID, studioId.value.toString())
        ).build()

        return runCatching {
            vectorStore.similaritySearch(
                SearchRequest.builder()
                    .query(query)
                    .topK(topK)
                    .filterExpression(expression)
                    .build()
            ) ?: emptyList()
        }.getOrElse {
            // Brak indeksu albo awaria bazy wektorowej nie może wywrócić podglądu leada:
            // sekcja jest dodatkiem do niego, a nie warunkiem jego otwarcia.
            log.warn("[SIMILAR_VISITS] Wyszukiwanie nie powiodło się: {}", it.message)
            emptyList()
        }
    }

    private class Step(
        val tier: MatchTier,
        val narrow: (FilterExpressionBuilder, FilterExpressionBuilder.Op) -> FilterExpressionBuilder.Op
    )

    companion object {
        private const val UNKNOWN = "UNKNOWN"

        /**
         * Sufit kandydatów. To jest zarazem sufit tego, co pójdzie do przesiewu LLM,
         * więc liczba jest kompromisem między szansą na trafienie a długością promptu.
         */
        const val MAX_CANDIDATES = 20

        private const val MAX_QUERY_LENGTH = 2_000
    }
}
