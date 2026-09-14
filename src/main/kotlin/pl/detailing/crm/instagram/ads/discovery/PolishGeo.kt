package pl.detailing.crm.instagram.ads.discovery

import java.text.Normalizer

/**
 * Minimalny słownik geografii Polski na potrzeby dopasowania „szerszego obszaru".
 *
 * Problem: Meta w `target_locations` podaje nazwę obszaru, ale nie mówi, że
 * Poznań leży w Wielkopolsce — a przy trybie [AreaMatchMode.INCLUDE_BROADER]
 * reklama targetowana na całe województwo albo na Polskę POWINNA trafić w
 * zapytanie o Poznań, bo faktycznie tam dociera. Bez własnego słownika miasto →
 * województwo nie da się tego rozstrzygnąć, a współrzędnych Meta zwykle nie
 * udostępnia.
 *
 * Świadomie NIE jest to pełny wykaz gmin (byłoby ich ~2500). To słownik
 * największych miast i miejscowości, które faktycznie pojawiają się w
 * zapytaniach. Skutek braku wpisu jest łagodny i nigdy fałszywie dodatni:
 * miejscowość spoza słownika po prostu nie łapie dopasowania po województwie —
 * dopasowanie po nazwie miasta i po całym kraju działa dla niej dalej. Słownik
 * rozszerza się przez dopisanie wiersza, bez zmian w logice.
 *
 * Nazwy porównujemy po [normalize]: bez ogonków, małymi literami, interpunkcja
 * na spacje — bo Meta bywa niekonsekwentna („Wielkopolskie" vs „Greater Poland
 * Voivodeship"), a nazwy wpisują ludzie.
 */
object PolishGeo {

    /** Bez ogonków, małymi, interpunkcja → spacja. „Suchy Las" i „SUCHY-LAS" to jedno. */
    fun normalize(value: String): String =
        Normalizer.normalize(value.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .replace('ł', 'l')
            .map { if (it.isLetterOrDigit()) it else ' ' }
            .joinToString("")
            .replace(Regex(" +"), " ")
            .trim()

    /** Znormalizowane tokeny wskazujące na całą Polskę jako obszar targetowania. */
    private val COUNTRY_TOKENS = setOf("polska", "poland", "pl", "rzeczpospolita polska")

    /**
     * Województwo → jego znormalizowane nazwy (polska, angielska Meta, potoczna).
     * Klucz jest kanoniczny i sam też należy do zbioru aliasów.
     */
    private val VOIVODESHIP_ALIASES: Map<String, Set<String>> = mapOf(
        "dolnoslaskie" to setOf("dolnoslaskie", "lower silesian", "lower silesia"),
        "kujawsko pomorskie" to setOf("kujawsko pomorskie", "kuyavian pomeranian"),
        "lubelskie" to setOf("lubelskie", "lublin"),
        "lubuskie" to setOf("lubuskie", "lubusz"),
        "lodzkie" to setOf("lodzkie", "lodz"),
        "malopolskie" to setOf("malopolskie", "lesser poland"),
        "mazowieckie" to setOf("mazowieckie", "masovian", "mazovia"),
        "opolskie" to setOf("opolskie", "opole"),
        "podkarpackie" to setOf("podkarpackie", "subcarpathian"),
        "podlaskie" to setOf("podlaskie", "podlachian"),
        "pomorskie" to setOf("pomorskie", "pomeranian"),
        "slaskie" to setOf("slaskie", "silesian", "silesia"),
        "swietokrzyskie" to setOf("swietokrzyskie", "holy cross", "swietokrzyskie"),
        "warminsko mazurskie" to setOf("warminsko mazurskie", "warmian masurian"),
        "wielkopolskie" to setOf("wielkopolskie", "greater poland", "wielkopolska"),
        "zachodniopomorskie" to setOf("zachodniopomorskie", "west pomeranian")
    )

    /** Odwrócony indeks: dowolny alias (znormalizowany) → kanoniczne województwo. */
    private val ALIAS_TO_VOIVODESHIP: Map<String, String> =
        VOIVODESHIP_ALIASES.flatMap { (canon, aliases) -> aliases.map { it to canon } }.toMap()

    /**
     * Miasto/miejscowość (znormalizowana) → województwo. Największe miasta plus
     * miejscowości z realnych zapytań (np. podpoznańskie Skórzewo, Suchy Las).
     */
    private val CITY_TO_VOIVODESHIP: Map<String, String> = mapOf(
        "wielkopolskie" to listOf("Poznań", "Skórzewo", "Suchy Las", "Luboń", "Swarzędz",
            "Gniezno", "Kalisz", "Konin", "Leszno", "Piła", "Ostrów Wielkopolski", "Komorniki", "Tarnowo Podgórne"),
        "mazowieckie" to listOf("Warszawa", "Radom", "Płock", "Siedlce", "Pruszków", "Piaseczno", "Legionowo"),
        "malopolskie" to listOf("Kraków", "Tarnów", "Nowy Sącz", "Wieliczka", "Oświęcim"),
        "dolnoslaskie" to listOf("Wrocław", "Wałbrzych", "Legnica", "Jelenia Góra", "Lubin"),
        "pomorskie" to listOf("Gdańsk", "Gdynia", "Sopot", "Słupsk", "Tczew", "Rumia"),
        "zachodniopomorskie" to listOf("Szczecin", "Koszalin", "Stargard", "Świnoujście"),
        "slaskie" to listOf("Katowice", "Częstochowa", "Gliwice", "Zabrze", "Bytom", "Sosnowiec",
            "Bielsko-Biała", "Rybnik", "Tychy", "Dąbrowa Górnicza", "Chorzów"),
        "lodzkie" to listOf("Łódź", "Piotrków Trybunalski", "Pabianice", "Tomaszów Mazowiecki"),
        "lubelskie" to listOf("Lublin", "Zamość", "Chełm", "Puławy"),
        "podlaskie" to listOf("Białystok", "Suwałki", "Łomża"),
        "podkarpackie" to listOf("Rzeszów", "Przemyśl", "Stalowa Wola", "Mielec"),
        "swietokrzyskie" to listOf("Kielce", "Ostrowiec Świętokrzyski"),
        "warminsko mazurskie" to listOf("Olsztyn", "Elbląg", "Ełk"),
        "kujawsko pomorskie" to listOf("Bydgoszcz", "Toruń", "Włocławek", "Grudziądz", "Inowrocław"),
        "opolskie" to listOf("Opole", "Kędzierzyn-Koźle", "Nysa"),
        "lubuskie" to listOf("Zielona Góra", "Gorzów Wielkopolski")
    ).flatMap { (voivodeship, cities) -> cities.map { normalize(it) to voivodeship } }
        .toMap()

    /** Czy ta nazwa obszaru oznacza całą Polskę. */
    fun isCountry(name: String): Boolean = normalize(name) in COUNTRY_TOKENS

    /** Województwo, w którym leży miejscowość — null, gdy poza słownikiem. */
    fun voivodeshipOf(cityNormalized: String): String? = CITY_TO_VOIVODESHIP[cityNormalized]

    /** Kanoniczne województwo dla nazwy obszaru z Meta — null, gdy to nie województwo. */
    fun voivodeshipFromName(name: String): String? = ALIAS_TO_VOIVODESHIP[normalize(name)]
}
