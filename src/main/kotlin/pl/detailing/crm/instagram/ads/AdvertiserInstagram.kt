package pl.detailing.crm.instagram.ads

import java.net.InetAddress

/**
 * Nazwa profilu na Instagramie doklejona do reklamodawcy znalezionego w Bibliotece reklam.
 *
 * Meta tego nie udostępnia: `ads_archive` zna wyłącznie `page_id` i `page_name`,
 * a panel „Informacje o reklamodawcy”, w którym nazwa IG widnieje, stoi za
 * zabezpieczeniem antybotowym. Zostaje droga naokoło, za to legalna i darmowa:
 * reklama niesie DOMENĘ reklamodawcy (`ad_creative_link_captions`), a firma sama
 * publikuje na swojej stronie link do Instagrama — zwykle w stopce.
 *
 * Skuteczność jest z natury cząstkowa i taka ma być: około połowa reklamodawców
 * kieruje ruch na `fb.me` albo do systemu rezerwacji, gdzie nie ma czego szukać.
 * Brak nazwy IG jest normalnym wynikiem, nie błędem.
 */
internal object AdvertiserInstagram {

    /**
     * Hosty, które nie są stroną firmy, tylko pośrednikiem: formularz kontaktowy
     * Meta, system rezerwacji, skracarka. Pobranie ich nic nie da, a kosztuje
     * tyle samo co pobranie prawdziwej strony.
     */
    private val INTERMEDIARIES = setOf(
        "fb.me", "fb.com", "facebook.com", "m.me", "messenger.com",
        "instagram.com", "threads.net", "wa.me", "whatsapp.com",
        "booksy.com", "moment.pl", "versum.pl", "calendly.com",
        "bit.ly", "tinyurl.com", "linktr.ee", "linktree.com", "taplink.cc",
        "goo.gl", "l.facebook.com", "lm.facebook.com",
        "youtube.com", "youtu.be", "tiktok.com", "allegro.pl", "olx.pl",
        "google.com", "maps.app.goo.gl", "g.page"
    )

    /**
     * Pierwszy człon adresu instagram.com, który nie jest nazwą profilu.
     * Bez tego `instagram.com/p/C3x…` zostałoby wzięte za konto „p”.
     */
    private val RESERVED = setOf(
        "p", "reel", "reels", "stories", "tv", "explore", "accounts", "direct",
        "about", "developer", "legal", "privacy", "terms", "directory",
        "challenge", "oauth", "web", "graphql", "api", "help", "session",
        "emails", "push", "ajax", "static", "your_activity"
    )

    /** Dozwolone znaki nazwy profilu wg Instagrama: litery, cyfry, kropka, podkreślenie. */
    private val LINK = Regex("""instagram\.com/+([A-Za-z0-9._]{1,30})""", RegexOption.IGNORE_CASE)

    /**
     * Cokolwiek, co niesie adres — pełny URL, sam host, podpis reklamy — sprowadzone
     * do gołego hosta. `ad_creative_link_captions` bywa jednym i drugim: raz
     * „folia-samochodowa.pl”, raz „https://blachexpertgaraze.pl/”.
     */
    fun hostOf(raw: String?): String? {
        val trimmed = raw?.trim()?.lowercase()?.takeIf { it.isNotBlank() } ?: return null

        val host = trimmed
            .substringAfter("://", trimmed)
            .substringBefore('/')
            .substringBefore('?')
            .substringBefore('#')
            .substringAfter('@')          // odcina ewentualne dane logowania
            .substringBefore(':')         // i port
            .removePrefix("www.")
            .trim('.')

        // Host bez kropki to nie jest domena, tylko fragment tekstu z podpisu.
        return host.takeIf { it.contains('.') && it.none(Char::isWhitespace) }
    }

    /**
     * Domena firmy wybrana z podpisów wszystkich jej reklam.
     *
     * Reklamodawca prowadzi ruch w różne miejsca — raz na sklep, raz na formularz
     * na `fb.me`, raz na Booksy. Bierzemy tę domenę, która powtarza się najczęściej
     * po odsianiu pośredników: to jest strona firmy, a nie jednorazowa kampania.
     */
    fun primaryHost(captions: Collection<String?>): String? {
        val hosts = captions.mapNotNull(::hostOf).filterNot(::isIntermediary)
        if (hosts.isEmpty()) return null
        return hosts.groupingBy { it }.eachCount()
            .entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { hosts.indexOf(it.key) })
            .first().key
    }

    /**
     * Nazwa profilu wzięta WPROST z podpisów reklam, bez pobierania czegokolwiek.
     *
     * `instagram.com` jest na liście pośredników i słusznie — nie ma sensu pobierać
     * Instagrama, żeby szukać na nim linku do Instagrama. Tyle że reklama, która
     * kieruje na `instagram.com/nazwa_profilu`, niesie odpowiedź w samym adresie:
     * odsiewając ją jako pośrednika wyrzucaliśmy najkrótszą drogę do celu i szli
     * okrężną przez stronę firmy, której taki reklamodawca często nie ma.
     *
     * Bierzemy nazwę powtarzającą się najczęściej — z tego samego powodu co
     * w [handleFrom]: pojedyncza kampania potrafi kierować na cudzy profil.
     */
    fun handleFromCaptions(captions: Collection<String?>): String? =
        captions.asSequence()
            .filterNotNull()
            .flatMap { LINK.findAll(it).map { m -> m.groupValues[1].trim('.', '_') } }
            .filter { it.length >= 2 && it.lowercase() !in RESERVED }
            .toList()
            .takeIf { it.isNotEmpty() }
            ?.let { hits ->
                hits.groupingBy { it }.eachCount()
                    .entries
                    .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { hits.indexOf(it.key) })
                    .first().key
            }

    /** Pobieranie pośrednika to zmarnowane trzy sekundy — firmy tam nie ma. */
    fun isIntermediary(host: String): Boolean =
        host in INTERMEDIARIES || INTERMEDIARIES.any { host.endsWith(".$it") }

    /**
     * Nazwa profilu z HTML-a strony firmy.
     *
     * Bierzemy nazwę POWTARZAJĄCĄ SIĘ najczęściej, a nie pierwszą napotkaną:
     * na stronie z blogiem pierwszy link do Instagrama bywa cudzy (zdjęcie klienta,
     * partner, realizacja), za to własny profil wisi w stopce obecnej na każdej
     * podstronie i w nagłówku — więc występuje wielokrotnie.
     */
    fun handleFrom(html: String): String? =
        LINK.findAll(html)
            .map { it.groupValues[1].trim('.', '_') }
            .filter { it.length >= 2 && it.lowercase() !in RESERVED }
            .toList()
            .takeIf { it.isNotEmpty() }
            ?.let { hits ->
                hits.groupingBy { it }.eachCount()
                    .entries
                    .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { hits.indexOf(it.key) })
                    .first().key
            }

    /**
     * Adres, pod który serwerowi nie wolno pukać w imieniu cudzej reklamy.
     *
     * Domena bierze się z pola, które wypełnia reklamodawca, więc traktujemy ją jak
     * dane od obcego. Pętla zwrotna, sieci prywatne i link-local (w tym
     * `169.254.169.254`, spod którego chmury wydają poświadczenia) są poza zasięgiem.
     */
    fun isPrivateAddress(address: InetAddress): Boolean =
        address.isLoopbackAddress || address.isSiteLocalAddress || address.isLinkLocalAddress ||
            address.isAnyLocalAddress || address.isMulticastAddress

    /**
     * Ta sama firma? Porównujemy hosty, nie całe adresy: w bio Instagrama stoi
     * „https://www.carslab.pl/?utm_source=ig”, a na reklamie samo „carslab.pl”.
     */
    fun sameHost(domain: String, externalUrl: String?): Boolean {
        val other = hostOf(externalUrl) ?: return false
        return other == domain
    }
}
