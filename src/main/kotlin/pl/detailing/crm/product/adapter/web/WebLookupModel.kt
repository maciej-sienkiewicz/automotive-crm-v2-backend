package pl.detailing.crm.product.adapter.web

/**
 * Karta produktu złożona z danych ZNALEZIONYCH W SIECI.
 *
 * Osobny typ od `ProductSpec`, bo tu wszystko jest tekstem „jak zastane" — dopiero
 * [WebProductProvider] zamienia to na domenę.
 */
data class WebProductCard(
    val brand: String?,
    val name: String?,
    val packageSizeValue: String?,
    val packageSizeUnit: String?,
    val description: String?,
    /** Adres źródła — ląduje w `source_payload` do audytu. */
    val sourceUrl: String?,
    val confidence: Double
)
