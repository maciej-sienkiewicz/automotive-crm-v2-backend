package pl.detailing.crm.instagram.ads.discovery

/**
 * Katalog fraz odkrywania — zamknięty, ustalony przez administratora aplikacji.
 *
 * Wcześniej frazy wpisywało studio. To był błąd na trzech poziomach:
 *
 *  1. **Budżet.** Frazy są kluczem WSPÓLNEGO cache, a każda unikalna fraza to
 *     osobne pobranie z Meta z limitu 180 wywołań/godz. na CAŁĄ instalację.
 *     Przy dowolnym tekście liczba unikalnych fraz rosła z liczbą studiów;
 *     przy katalogu jest z góry znana i nie przekroczy [ALL].
 *  2. **Jakość.** „detailing" i „detailing samochodowy" to dwa wpisy w cache i dwa
 *     pobrania, a niemal te same reklamy. Literówka to fraza, która nigdy nic nie zwróci.
 *  3. **Porównywalność.** Gdy każdy śledzi co innego, wyniki dwóch studiów nie mówią
 *     o tym samym rynku. Wspólny katalog czyni z tego jedną, porównywalną miarę.
 *
 * Studio może z katalogu tylko ODZNACZAĆ pozycje (myjnia bezdotykowa nie interesuje
 * studia PPF), nie dopisywać własnych.
 *
 * **Identyfikatory są trwałe.** Trafiają do bazy jako lista wykluczeń studia, więc
 * zmiana `id` osieroci czyjeś ustawienia. Tekst frazy wolno poprawić, `id` — nie.
 *
 * Frazy to hasła, jakich reklamodawca używa w TREŚCI reklamy — `search_terms`
 * przeszukuje treść, nie nazwy stron ani kategorie.
 */
object AdDiscoveryCatalog {

    /** Grupa służy wyłącznie ułożeniu listy na ekranie ustawień. */
    enum class Group(val label: String) {
        POWLOKI("Powłoki i ochrona lakieru"),
        FOLIE("Folie i oklejanie"),
        LAKIER("Korekta i renowacja lakieru"),
        WNETRZE("Wnętrze"),
        MYCIE("Mycie i pielęgnacja"),
        KOLA("Koła i podwozie"),
        OGOLNE("Ogólne hasła branży")
    }

    data class Phrase(val id: String, val text: String, val group: Group)

    val ALL: List<Phrase> = listOf(
        // ── Powłoki i ochrona lakieru ────────────────────────────────────────
        Phrase("powloka-ceramiczna", "powłoka ceramiczna", Group.POWLOKI),
        Phrase("ceramika-samochodowa", "ceramika samochodowa", Group.POWLOKI),
        Phrase("powloka-kwarcowa", "powłoka kwarcowa", Group.POWLOKI),
        Phrase("powloka-grafenowa", "powłoka grafenowa", Group.POWLOKI),
        Phrase("powloka-hydrofobowa", "powłoka hydrofobowa", Group.POWLOKI),
        Phrase("zabezpieczenie-lakieru", "zabezpieczenie lakieru", Group.POWLOKI),
        Phrase("ochrona-lakieru", "ochrona lakieru", Group.POWLOKI),

        // ── Folie i oklejanie ────────────────────────────────────────────────
        Phrase("folia-ppf", "folia ppf", Group.FOLIE),
        Phrase("folia-ochronna-lakier", "folia ochronna na lakier", Group.FOLIE),
        Phrase("bezbarwna-folia", "bezbarwna folia ochronna", Group.FOLIE),
        Phrase("zmiana-koloru", "zmiana koloru auta", Group.FOLIE),
        Phrase("oklejanie-samochodu", "oklejanie samochodu", Group.FOLIE),
        Phrase("car-wrapping", "car wrapping", Group.FOLIE),
        Phrase("folia-na-auto", "folia na auto", Group.FOLIE),
        Phrase("przyciemnianie-szyb", "przyciemnianie szyb", Group.FOLIE),
        Phrase("folia-przyciemniajaca", "folia przyciemniająca", Group.FOLIE),
        Phrase("przyciemnianie-lamp", "przyciemnianie lamp", Group.FOLIE),

        // ── Korekta i renowacja lakieru ──────────────────────────────────────
        Phrase("korekta-lakieru", "korekta lakieru", Group.LAKIER),
        Phrase("polerowanie-lakieru", "polerowanie lakieru", Group.LAKIER),
        Phrase("usuwanie-rys", "usuwanie rys z lakieru", Group.LAKIER),
        Phrase("renowacja-lakieru", "renowacja lakieru", Group.LAKIER),
        Phrase("dekontaminacja-lakieru", "dekontaminacja lakieru", Group.LAKIER),
        Phrase("polerowanie-reflektorow", "polerowanie reflektorów", Group.LAKIER),
        Phrase("renowacja-reflektorow", "renowacja reflektorów", Group.LAKIER),

        // ── Wnętrze ──────────────────────────────────────────────────────────
        Phrase("detailing-wnetrza", "detailing wnętrza", Group.WNETRZE),
        Phrase("pranie-tapicerki", "pranie tapicerki", Group.WNETRZE),
        Phrase("czyszczenie-tapicerki", "czyszczenie tapicerki samochodowej", Group.WNETRZE),
        Phrase("renowacja-skory", "renowacja skóry w samochodzie", Group.WNETRZE),
        Phrase("czyszczenie-podsufitki", "czyszczenie podsufitki", Group.WNETRZE),
        Phrase("ozonowanie", "ozonowanie samochodu", Group.WNETRZE),
        Phrase("odgrzybianie-klimatyzacji", "odgrzybianie klimatyzacji", Group.WNETRZE),
        Phrase("czyszczenie-klimatyzacji", "czyszczenie klimatyzacji samochodowej", Group.WNETRZE),

        // ── Mycie i pielęgnacja ──────────────────────────────────────────────
        Phrase("mycie-detailingowe", "mycie detailingowe", Group.MYCIE),
        Phrase("myjnia-reczna", "myjnia ręczna", Group.MYCIE),
        Phrase("mycie-reczne", "mycie ręczne samochodu", Group.MYCIE),
        Phrase("myjnia-bezdotykowa", "myjnia bezdotykowa", Group.MYCIE),
        Phrase("pielegnacja-samochodu", "pielęgnacja samochodu", Group.MYCIE),

        // ── Koła i podwozie ──────────────────────────────────────────────────
        Phrase("czyszczenie-felg", "czyszczenie felg", Group.KOLA),
        Phrase("zabezpieczenie-felg", "zabezpieczenie felg", Group.KOLA),
        Phrase("konserwacja-podwozia", "konserwacja podwozia", Group.KOLA),

        // ── Ogólne hasła branży ──────────────────────────────────────────────
        Phrase("detailing-samochodowy", "detailing samochodowy", Group.OGOLNE),
        Phrase("studio-detailingu", "studio detailingu", Group.OGOLNE),
        Phrase("auto-detailing", "auto detailing", Group.OGOLNE),
        Phrase("kosmetyka-samochodowa", "kosmetyka samochodowa", Group.OGOLNE),
        Phrase("auto-spa", "auto spa", Group.OGOLNE)
    )

    private val byId: Map<String, Phrase> = ALL.associateBy { it.id }

    /** Tekst frazy po identyfikatorze; null dla identyfikatora spoza katalogu. */
    fun textOf(id: String): String? = byId[id]?.text

    /** Czy taki identyfikator w ogóle istnieje — do odsiewu wykluczeń z dawnych wersji katalogu. */
    fun exists(id: String): Boolean = id in byId

    /**
     * Frazy, które studio faktycznie śledzi: cały katalog bez odznaczonych pozycji.
     *
     * Wykluczenie WSZYSTKIEGO daje pustkę, a nie po cichu cały katalog — kto odznaczył
     * wszystko, ma zobaczyć pustą tabelę, a nie wyniki, których nie zamawiał.
     */
    fun phrasesExcept(excludedIds: Collection<String>): List<String> {
        val excluded = excludedIds.toSet()
        return ALL.filterNot { it.id in excluded }.map { it.text }
    }
}
