package pl.detailing.crm.instagram.ads.discovery

import pl.detailing.crm.instagram.ads.AdvertiserInstagram

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

    /**
     * @param blockedPageIds strony wykluczone dla tego studia — globalne wykluczenia
     *   administratora plus własna czarna lista. Odsiewamy je TU, a nie przy pobraniu:
     *   cache reklam jest wspólny dla wszystkich najemców, więc usunięcie z niego
     *   czegokolwiek na życzenie jednego studia zabrałoby to wszystkim.
     */
    fun summarize(
        ads: List<DiscoveredAd>,
        requestedCities: List<String>,
        mode: AreaMatchMode,
        blockedPageIds: Set<String> = emptySet()
    ): List<AdvertiserRow> =
        ads.asSequence()
            .filter { it.active }
            .filterNot { it.pageId in blockedPageIds }
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
            // Link do POJEDYNCZEJ reklamy składamy z jej identyfikatora — patrz
            // MetaAdLibraryUrl.forAd: adres z API niósłby token instalacji.
            sampleSnapshotUrl = group.firstOrNull()?.let { MetaAdLibraryUrl.forAd(it.adArchiveId) },
            // Adresy WSZYSTKICH reklam firmy, nie pojedynczej: jedna kampania potrafi
            // kierować na fb.me, druga na sklep — o domenie decyduje większość.
            domain = AdvertiserInstagram.primaryHost(group.map { it.linkCaption })
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

    /**
     * Pojedyncza reklama w Bibliotece — link PUBLICZNY, składany z samego
     * identyfikatora archiwum.
     *
     * Świadomie NIE używamy `ad_snapshot_url` z API. Meta zwraca je w postaci
     * `…/ads/archive/render_ad/?id=…&access_token=<TOKEN>`, czyli z naszym
     * tokenem instalacji w adresie. Zapisany w bazie i podany przeglądarce
     * klienta byłby to wyciek poświadczenia ważnego dla całego konta — a ten
     * link pokazuje dokładnie to samo i nie niesie niczego tajnego.
     */
    fun forAd(adArchiveId: String): String =
        "https://www.facebook.com/ads/library/?id=$adArchiveId"
}
