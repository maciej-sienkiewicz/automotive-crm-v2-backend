package pl.detailing.crm.instagram.ads.discovery

import pl.detailing.crm.instagram.ads.AdvertiserInstagram
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

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
     * @param knownSince rejestr reklamodawców: strona → najwcześniejszy start kampanii,
     *   jaki KIEDYKOLWIEK u niej widzieliśmy (także takiej, której w cache już nie ma).
     *   Bez tego nie da się odróżnić debiutanta od firmy, która podmieniła kreację.
     * @param ackedThrough dzień, do którego studio odznaczyło nowości („widziałem
     *   wszystko, co ruszyło do tego dnia włącznie"). Null = nigdy nie odznaczano.
     * @param today dzień, względem którego liczy się okno nowości — parametr, żeby
     *   granicę okna dało się sprawdzić testem bez zegara.
     */
    fun summarize(
        ads: List<DiscoveredAd>,
        requestedCities: List<String>,
        mode: AreaMatchMode,
        blockedPageIds: Set<String> = emptySet(),
        knownSince: Map<String, LocalDate> = emptyMap(),
        ackedThrough: LocalDate? = null,
        today: LocalDate = AreaNovelty.today()
    ): List<AdvertiserRow> =
        ads.asSequence()
            .filter { it.active }
            .filterNot { it.pageId in blockedPageIds }
            .filter { AreaLocationMatcher.matches(it.locations, requestedCities, mode) }
            .groupBy { it.pageId }
            .map { (pageId, group) -> toRow(pageId, group, knownSince[pageId], ackedThrough, today) }
            // Nowości na górze — na 14 dni. Tabela odpowiada na „kto tu jest", ale
            // człowiek wraca do niej z pytaniem „co się zmieniło"; nowa firma z jedną
            // reklamą stałaby inaczej na piątej stronie i nikt by jej nie zobaczył.
            // Pod nowościami stary porządek: najwięksi obecni w tym rejonie —
            // więcej reklam, przy remisie większy zasięg.
            .sortedWith(
                compareByDescending<AdvertiserRow> { it.newAdvertiser }
                    .thenByDescending { it.newCampaigns > 0 }
                    .thenByDescending { it.activeAds }
                    .thenByDescending { it.reach ?: -1 }
                    .thenBy { it.companyName.lowercase() }
            )

    private fun toRow(
        pageId: String,
        group: List<DiscoveredAd>,
        knownSince: LocalDate?,
        ackedThrough: LocalDate?,
        today: LocalDate
    ): AdvertiserRow {
        val reachValues = group.mapNotNull { it.reach }
        val newStarts = group.map { it.deliveryStart }.filter { AreaNovelty.isNew(it, today, ackedThrough) }
        // Debiut liczymy od NAJWCZEŚNIEJSZEGO startu, jaki znamy: z rejestru albo —
        // gdy rejestru dla tej strony jeszcze nie ma — z tego, co widać w cache.
        val earliestKnown = listOfNotNull(knownSince, group.minOf { it.deliveryStart }).min()
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
            domain = AdvertiserInstagram.primaryHost(group.map { it.linkCaption }),
            // Reklama kierująca wprost na profil niesie nazwę w samym adresie —
            // wtedy nie ma po co szukać jej okrężnie na stronie firmy.
            instagram = AdvertiserInstagram.handleFromCaptions(group.map { it.linkCaption }),
            newCampaigns = newStarts.size,
            newAdvertiser = AreaNovelty.isNew(earliestKnown, today, ackedThrough),
            latestCampaignStart = newStarts.maxOrNull()
        )
    }
}

/**
 * Okno, w którym kampania albo firma jest „nowa".
 *
 * Liczone od DNIA STARTU EMISJI podanego przez Meta, nie od chwili, w której nasz
 * crawler ją zobaczył. Dwa powody:
 *   - fraza pobrana po raz pierwszy (nowa w katalogu, nowe wdrożenie) zwróciłaby
 *     setki „nowych" kampanii trwających od miesięcy — a nowość ma opisywać rynek,
 *     nie nasz harmonogram pobierania;
 *   - ta sama kampania świeci wtedy tyle samo dla każdego studia i gaśnie sama,
 *     bez stanu per użytkownik i bez klikania „widziałem".
 *
 * Czternaście dni: studio zagląda tu raz w tygodniu (tak jak do tygodniowego
 * podsumowania), więc siedem dni gubiłoby kampanię, która ruszyła dzień po
 * wizycie, a trzydzieści zamieniłoby w ruchliwym rejonie pół tabeli w nowości —
 * i wtedy odznaka przestałaby cokolwiek wyróżniać. Dwa tygodnie to dwie wizyty:
 * pierwsza zauważa, druga jeszcze widzi.
 *
 * Okno jest GÓRNĄ granicą, nie jedyną: odznaczenie („Odznacz nowe") gasi pigułki
 * wcześniej. Samo wygaszanie po czasie odpowiada na upływ dni, a nie na to, że
 * ktoś już te firmy przejrzał — a odznaka oglądana po raz dziesiąty przestaje być
 * widziana i przegapia się przy niej tę jedną nową.
 */
object AreaNovelty {
    const val WINDOW_DAYS = 14L

    private val warsaw = ZoneId.of("Europe/Warsaw")

    fun today(): LocalDate = LocalDate.now(warsaw)

    /**
     * Start 0…13 dni temu = nowe; równo 14 dni temu już nie. Start w przyszłości
     * też liczy się jako nowy.
     *
     * [ackedThrough] odcina dodatkowo wszystko, co ruszyło do dnia odznaczenia
     * WŁĄCZNIE — odznaczenie znaczy „widziałem to, co teraz pokazujecie", więc
     * kampania z dzisiejszym startem gaśnie razem z resztą, a jutrzejsza wraca.
     */
    fun isNew(start: LocalDate, today: LocalDate, ackedThrough: LocalDate? = null): Boolean {
        if (ackedThrough != null && !start.isAfter(ackedThrough)) return false
        return ChronoUnit.DAYS.between(start, today) < WINDOW_DAYS
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
