package pl.detailing.crm.shared

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Invalid Data — `limit=0`, `page=-5`, `limit=MAX_INT` used to 500 (÷0, negative subList, overflow). */
class PaginationTest {

    private val items = (1..25).toList()

    @Test
    fun `limit zero and negative page are coerced instead of exploding`() {
        val page = Pagination.normalizePage(-5)
        val limit = Pagination.normalizeLimit(0)
        assertEquals(1, page)
        assertEquals(1, limit)
        assertEquals(listOf(1), Pagination.slice(items, page, limit))
        assertEquals(25, Pagination.totalPages(25, limit))
    }

    @Test
    fun `limit is capped so a single request cannot dump the whole table`() {
        assertEquals(Pagination.DEFAULT_MAX_LIMIT, Pagination.normalizeLimit(Int.MAX_VALUE))
        assertEquals(50, Pagination.normalizeLimit(Int.MAX_VALUE, max = 50))
    }

    @Test
    fun `page beyond the end is an empty list, huge page does not overflow`() {
        assertEquals(emptyList<Int>(), Pagination.slice(items, page = 4, limit = 10))
        assertEquals(emptyList<Int>(), Pagination.slice(items, page = Int.MAX_VALUE, limit = Pagination.DEFAULT_MAX_LIMIT))
    }

    @Test
    fun `regular paging still works`() {
        assertEquals((11..20).toList(), Pagination.slice(items, page = 2, limit = 10))
        assertEquals(3, Pagination.totalPages(25, 10))
    }
}
