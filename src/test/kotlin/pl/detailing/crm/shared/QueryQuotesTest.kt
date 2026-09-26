package pl.detailing.crm.shared

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider
import org.springframework.core.type.filter.AssignableTypeFilter
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.Repository
import org.springframework.data.repository.query.SpelQueryContext

/**
 * Każde `@Query` musi przejść przez parser cudzysłowów Spring Data — tak samo jak przy
 * starcie aplikacji.
 *
 * Zgłoszenie z produkcji: po wdrożeniu kontekst nie wstał, bo zapytanie natywne
 * `FinancialDocumentRepository.findPaidIncomeForReport` miało komentarz SQL
 * `-- … raport „tylko paragony"`:
 *
 *     QueryCreationException: … The string <…> starts a quoted range at 327,
 *     but never ends it.
 *
 * SpelQueryContext.QuotationMap nie zna komentarzy SQL: `'` i `"` w linii `--` liczy
 * jak otwarcie literału, a polski cudzysłów zamykający (`"` zamiast `”`) zostaje
 * nieparzysty. Testy jednostkowe repozytoriów jadą na mockach, więc zapytania nie
 * parsuje nikt poza startem kontekstu — dlatego ten test czyta adnotacje refleksją,
 * bez bazy, i zamienia martwe wdrożenie w czerwony build.
 *
 * Uzasadnienia zapytań piszemy w KDoc metody, nie jako `--` wewnątrz SQL.
 */
class QueryQuotesTest {

    private val repositories: List<Class<*>> by lazy {
        object : ClassPathScanningCandidateComponentProvider(false) {
            override fun isCandidateComponent(beanDefinition: AnnotatedBeanDefinition) =
                beanDefinition.metadata.isInterface
        }
            .apply { addIncludeFilter(AssignableTypeFilter(Repository::class.java)) }
            .findCandidateComponents("pl.detailing.crm")
            .mapNotNull { it.beanClassName }
            .map { Class.forName(it, false, javaClass.classLoader) }
    }

    private val context = SpelQueryContext.of({ index, _ -> "__\$synthetic\$__$index" }, { _, expression -> expression })

    private fun parse(query: String) = context.parse(query)

    @Test
    fun `parser odtwarza awarie z produkcji`() {
        // Bez tego test niżej mógłby przechodzić dlatego, że parser niczego nie sprawdza.
        val incident = """
            SELECT * FROM financial_documents d
            WHERE d.direction = 'INCOME'
              -- Korekta idzie z typem dokumentu, który koryguje: raport „tylko paragony"
              AND d.status = 'PAID'
        """
        assertThrows<IllegalArgumentException> { parse(incident) }
    }

    @Test
    fun `zadne zapytanie nie ma niedomknietego cudzyslowu`() {
        assertTrue(repositories.size > 20, "Skan nie znalazł repozytoriów: ${repositories.size}")

        val broken = repositories.flatMap { repository ->
            repository.declaredMethods.flatMap { method ->
                val query = method.getAnnotation(Query::class.java) ?: return@flatMap emptyList()
                listOf(query.value, query.countQuery).filter { it.isNotBlank() }.mapNotNull { sql ->
                    runCatching { parse(sql) }.exceptionOrNull()
                        ?.let { "${repository.simpleName}.${method.name}: ${it.message?.take(200)}" }
                }
            }
        }

        assertTrue(broken.isEmpty(), "Zapytania wywrócą start aplikacji:\n" + broken.joinToString("\n"))
    }
}
