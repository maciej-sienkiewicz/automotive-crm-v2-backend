package pl.detailing.crm.finance.income

import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import jakarta.persistence.EntityManager

/**
 * Strażnik składni natywnego zapytania listy dokumentów przychodowych.
 *
 * Hibernate parsuje `:nazwa` jako parametr nazwany, więc postgresowe rzutowanie
 * `wartość::typ` jest po cichu psute (`id::text` → `id:text`) i kończy się
 * błędem składni dopiero w bazie, w czasie działania aplikacji.
 */
class IncomeDocumentsRepositorySqlTest {

    private val repository = IncomeDocumentsRepository(mockk<EntityManager>())

    @Test
    fun `zapytanie nie uzywa skladni rzutowania dwukropkiem`() {
        assertFalse(
            repository.selectColumns.contains("::"),
            "Rzutowania muszą używać CAST(... AS ...) — '::' koliduje z parametrami nazwanymi Hibernate"
        )
    }

    @Test
    fun `oba zrodla zwracaja tyle samo kolumn ile mapuje wiersz`() {
        val branches = repository.selectColumns.split("UNION ALL")
        assertEquals(2, branches.size, "Zapytanie łączy dokładnie dwa źródła")

        // Liczba kolumn w każdej gałęzi musi się zgadzać z mapowaniem IncomeDocumentRow.from
        branches.forEach { branch ->
            val select = branch.substringAfter("SELECT").substringBefore("FROM")
            val columns = splitTopLevel(select).size
            assertEquals(20, columns, "Gałąź UNION musi mieć 20 kolumn zgodnych z IncomeDocumentRow")
        }
    }

    /** Podział po przecinkach spoza nawiasów — CASE/CAST zawierają własne przecinki. */
    private fun splitTopLevel(select: String): List<String> {
        val parts = mutableListOf<String>()
        var depth = 0
        val current = StringBuilder()
        select.forEach { ch ->
            when {
                ch == '(' -> { depth++; current.append(ch) }
                ch == ')' -> { depth--; current.append(ch) }
                ch == ',' && depth == 0 -> { parts += current.toString(); current.clear() }
                else -> current.append(ch)
            }
        }
        if (current.isNotBlank()) parts += current.toString()
        return parts.map { it.trim() }.filter { it.isNotEmpty() }
    }
}
