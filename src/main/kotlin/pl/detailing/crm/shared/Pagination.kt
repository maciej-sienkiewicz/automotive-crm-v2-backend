package pl.detailing.crm.shared

/**
 * Bounds for client-supplied `page` / `limit` query parameters.
 *
 * The in-memory pagination in several list controllers computed
 * `start = (page - 1) * limit` and `totalPages = (total + limit - 1) / limit` straight
 * from the request: `limit=0` divided by zero, `page=-5` fed a negative index to
 * `subList`, `limit=2147483647` overflowed — each a 500 anyone could trigger — and an
 * unbounded `limit` materialised the whole tenant table in one response.
 */
object Pagination {
    const val DEFAULT_MAX_LIMIT = 200

    fun normalizePage(page: Int): Int = page.coerceAtLeast(1)

    fun normalizeLimit(limit: Int, max: Int = DEFAULT_MAX_LIMIT): Int = limit.coerceIn(1, max)

    /** Safe slice of an in-memory list for 1-based [page] and [limit] (both already normalised). */
    fun <T> slice(items: List<T>, page: Int, limit: Int): List<T> {
        val start = (page.toLong() - 1) * limit
        if (start >= items.size) return emptyList()
        val end = minOf(start + limit, items.size.toLong())
        return items.subList(start.toInt(), end.toInt())
    }

    fun totalPages(totalItems: Int, limit: Int): Int = (totalItems + limit - 1) / limit
}
