package pl.detailing.crm.product.application

import pl.detailing.crm.product.ProductListItem

/**
 * Kolejność wierszy listy produktów.
 *
 * Wyjęte z kontrolera, bo przestało być jednym `when` po dwóch polach tekstowych:
 * przy liczbach trzeba jeszcze rozstrzygnąć, co robić z remisem i z produktami,
 * których nikt nie użył — a to jest decyzja, nie szczegół implementacji.
 */
object ProductListSort {

    /**
     * @param sortBy name | brand | usage | rating; cokolwiek innego (także null)
     *   daje porządek alfabetyczny, bo katalog bez wskazanego porządku czyta się
     *   jak spis, a nie jak ranking.
     */
    fun apply(items: List<ProductListItem>, sortBy: String?, sortDirection: String): List<ProductListItem> {
        val descending = sortDirection.equals("desc", ignoreCase = true)

        return when (sortBy) {
            "name" -> items.sortedWith(byName(descending))
            "brand" -> items.sortedWith(
                if (descending) compareByDescending<ProductListItem> { it.brand.lowercase() }.then(byName(false))
                else compareBy<ProductListItem> { it.brand.lowercase() }.then(byName(false))
            )
            // Remis rozstrzyga nazwa, nie kolejność z bazy: przy katalogu, w którym
            // połowa produktów ma zero użyć, losowa kolejność wewnątrz grupy sprawia,
            // że lista „skacze" między odświeżeniami i nie da się jej czytać.
            "usage" -> items.sortedWith(
                if (descending) compareByDescending<ProductListItem> { it.usageCount }.then(byName(false))
                else compareBy<ProductListItem> { it.usageCount }.then(byName(false))
            )
            // Produkt bez oceny nie jest „gorszy od jedynki" - jest nieoceniony.
            // Przy malejąco spada na koniec, przy rosnąco też: w obu kierunkach
            // pytamy o oceny, a nie o ich brak.
            "rating" -> items.sortedWith(
                compareBy<ProductListItem> { it.ratingValue == null }
                    .then(
                        if (descending) compareByDescending { it.ratingValue ?: 0 }
                        else compareBy { it.ratingValue ?: 0 }
                    )
                    .then(byName(false))
            )
            else -> items.sortedWith(byName(false))
        }
    }

    /** Alfabetycznie, bez względu na wielkość liter — „Wosk" i „wosk" stoją obok siebie. */
    private fun byName(descending: Boolean): Comparator<ProductListItem> =
        if (descending) compareByDescending { it.name.lowercase() }
        else compareBy { it.name.lowercase() }
}
