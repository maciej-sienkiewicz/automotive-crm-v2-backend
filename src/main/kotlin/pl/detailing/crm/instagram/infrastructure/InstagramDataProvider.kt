package pl.detailing.crm.instagram.infrastructure

/**
 * Wspólny interfejs dostawców danych Instagramowych (SPI).
 *
 * Implementacje:
 * - [InstagramLooterClient]  – RapidAPI "Instagram Looter" (domyślny)
 * - [IgScraper5Client]       – RapidAPI "ig-scraper5" (legacy)
 *
 * Wybór aktywnego dostawcy: property `instagram.provider` (LOOTER | IG_SCRAPER5),
 * patrz [InstagramProviderConfig].
 */
interface InstagramDataProvider {

    val providerName: String

    /**
     * Pobiera szczegóły profilu (metryki, bio, flagi konta).
     * @return null gdy dostawca nie zwrócił danych profilu (np. konto nie istnieje)
     * @throws InstagramProviderException przy błędzie HTTP lub przekroczeniu budżetu wywołań
     */
    fun fetchUserDetails(username: String): RawInstagramUserDetails?

    /**
     * Pobiera jedną stronę postów profilu, od najnowszych.
     * Stronicowanie i decyzja "czy pobierać dalej" należą do warstwy sync –
     * dzięki temu limity wywołań są kontrolowane w jednym miejscu.
     *
     * @param instagramUserId natywne ID konta – wymagane przez niektórych dostawców;
     *        zapisywane w [InstagramProfileEntity.instagramUserId] po pierwszym
     *        pobraniu szczegółów profilu
     * @throws InstagramProviderException przy błędzie HTTP lub przekroczeniu budżetu wywołań
     */
    fun fetchPostsPage(username: String, instagramUserId: String?, cursor: String?): InstagramPostsPage
}

data class InstagramPostsPage(
    val posts: List<RawInstagramPost>,
    val nextCursor: String?,
    val hasMore: Boolean
)

/**
 * Błąd warstwy pozyskiwania danych – niezależny od konkretnego dostawcy.
 * `statusCode == null` oznacza błąd lokalny (np. wyczerpany dzienny budżet wywołań).
 */
class InstagramProviderException(
    val username: String,
    val statusCode: Int?,
    message: String,
    cause: Throwable? = null
) : RuntimeException(message, cause)
