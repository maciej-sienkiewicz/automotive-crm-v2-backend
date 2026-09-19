package pl.detailing.crm.product.application

import pl.detailing.crm.product.ProductListItem
import pl.detailing.crm.product.infrastructure.ProductUsageRow
import java.util.UUID

/**
 * Scalenie wierszy agregatu użycia z pozycjami listy produktów.
 *
 * Osobno od serwisu, bo to jedyny krok, w którym da się pomylić dwie rzeczy, a obie
 * widać dopiero na ekranie: produkt bez ani jednego użycia musi dostać ZERO (a nie
 * brak wartości, który tabela pokazałaby jako pustkę), a `COUNT` przychodzi jako
 * `Long` i zawężenie do `Int` ma być świadome, nie przypadkowe.
 */
object ProductUsageMerge {

    fun apply(items: List<ProductListItem>, rows: Collection<ProductUsageRow>): List<ProductListItem> {
        if (items.isEmpty()) return items
        val byProduct = rows.associateBy { it.productId }

        return items.map { item ->
            val row = byProduct[runCatching { UUID.fromString(item.id) }.getOrNull()]
            // Brak wiersza = produkt nigdy nie poszedł do wizyty. Zostaje zero z DTO,
            // bo „0 użyć" to odpowiedź, a brak wartości byłby jej unikiem.
                ?: return@map item
            item.copy(
                usageCount = row.visitCount.toInt(),
                lastUsedAt = row.lastUsedAt
            )
        }
    }
}
