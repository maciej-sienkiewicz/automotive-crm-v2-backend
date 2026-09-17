package pl.detailing.crm.product.adapter.web

/**
 * Karta produktu złożona z danych z SIECI (baza kodów albo wyniki wyszukiwarki).
 *
 * Osobny typ od `ProductSpec`, bo tu wszystko jest tekstem „jak zastane" — dopiero
 * [WebProductProvider] zamienia to na domenę i decyduje o pewności.
 */
data class WebProductCard(
    val brand: String?,
    val name: String?,
    val packageSizeValue: String?,
    val packageSizeUnit: String?,
    val description: String?,
    /** Skąd to wzięliśmy — ląduje w `source_payload` do audytu. */
    val sourceUrl: String?,
    val confidence: Double
)

/** Pojedynczy wynik wyszukiwarki: tytuł + fragment opisu. Bez treści strony. */
data class WebSnippet(
    val title: String,
    val snippet: String,
    val url: String
)
