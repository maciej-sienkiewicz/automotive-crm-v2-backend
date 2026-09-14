package pl.detailing.crm.instagram.ads.discovery

/**
 * Reklamy odfiltrowane po obszarze → tabela reklamodawców.
 *
 * Jeden wiersz to jedna firma (strona na Facebooku): nazwa, ile jej aktywnych
 * reklam trafia w rejon, jaki mają łączny zasięg w Polsce i skąd je podejrzeć.
 *
 * Czysta funkcja, bez bazy — grupowanie i sumowanie to sedno tej funkcji, więc
 * ma dać się sprawdzić testem bez stawiania kontekstu Springa.
 */
object AreaAdvertiserSummary {

    fun summarize(
        ads: List<DiscoveredAd>,
        requestedCities: List<String>,
        mode: AreaMatchMode
    ): List<AdvertiserRow> =
        ads.asSequence()
            .filter { it.active }
            .filter { AreaLocationMatcher.matches(it.locations, requestedCities, mode) }
            .groupBy { it.pageId }
            .map { (pageId, group) -> toRow(pageId, group) }
            // Najpierw najwięksi obecni w tym rejonie: więcej reklam, przy remisie większy zasięg.
            .sortedWith(
                compareByDescending<AdvertiserRow> { it.activeAds }
                    .thenByDescending { it.reach ?: -1 }
                    .thenBy { it.companyName.lowercase() }
            )

    private fun toRow(pageId: String, group: List<DiscoveredAd>): AdvertiserRow {
        val reachValues = group.mapNotNull { it.reach }
        return AdvertiserRow(
            pageId = pageId,
            // Nazwa strony bywa pusta w części odpowiedzi Meta — bierzemy pierwszą niepustą.
            companyName = group.firstNotNullOfOrNull { it.pageName?.trim()?.takeIf { n -> n.isNotBlank() } }
                ?: pageId,
            activeAds = group.size,
            reach = reachValues.takeIf { it.isNotEmpty() }?.sum(),
            adLibraryUrl = MetaAdLibraryUrl.forPage(pageId),
            sampleSnapshotUrl = group.firstNotNullOfOrNull { it.snapshotUrl?.takeIf { url -> url.isNotBlank() } }
        )
    }
}

/** Adresy do Biblioteki reklam Meta — jedno miejsce, żeby format żył w jednym punkcie. */
object MetaAdLibraryUrl {

    /**
     * Strona reklamodawcy w Bibliotece reklam, zawężona do AKTYWNYCH reklam w
     * Polsce — dokładnie to, co pokazuje tabela, otwarte w oryginale u Meta.
     */
    fun forPage(pageId: String): String =
        "https://www.facebook.com/ads/library/" +
            "?active_status=active&ad_type=all&country=PL&view_all_page_id=$pageId"
}
