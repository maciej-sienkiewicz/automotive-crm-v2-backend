package pl.detailing.crm.instagram.ads

import java.time.LocalDate

/**
 * Reklama tak, jak zwraca ją `ads_archive`. Tylko pola, które faktycznie
 * pokazujemy — reszty nie pobieramy, żeby nie trzymać danych bez zastosowania.
 *
 * Czego w tym modelu NIE MA, bo Meta tego nie udostępnia dla reklam komercyjnych:
 * budżetu, wydatków, CPM, wyświetleń, kliknięć, konwersji ani grafiki reklamy.
 * Jedynym oknem na kreację jest [snapshotUrl] — strona w bibliotece Meta.
 */
data class RawMetaAd(
    val adArchiveId: String,
    val pageId: String,
    val pageName: String?,
    /** Pierwszy tytuł kreacji — służy do odróżnienia reklam, nie do czytania treści. */
    val title: String?,
    val deliveryStart: LocalDate,
    /** null = emisja trwa. */
    val deliveryStop: LocalDate?,
    /** Konta z całej UE. Do pokazania używamy sumy z rozbicia dla PL. */
    val reachEu: Int?,
    val platforms: List<String>,
    /** Ustawienie reklamodawcy, np. „25-54". */
    val targetAges: String?,
    /** All | Men | Women */
    val targetGender: String?,
    val targetLocations: List<RawAdLocation>,
    val payer: String?,
    val beneficiary: String?,
    /** Rozbicie zasięgu WYŁĄCZNIE dla Polski. */
    val polandBreakdown: List<RawAgeGenderReach>,
    val snapshotUrl: String?
) {
    /** Zasięg w Polsce policzony z rozbicia — pozostałe kraje nas nie interesują. */
    val reachPoland: Int?
        get() = polandBreakdown
            .takeIf { it.isNotEmpty() }
            ?.sumOf { it.male + it.female + it.unknown }
}

data class RawAdLocation(
    val name: String,
    /** country | region | city | zip | neighborhood… — tak jak nazywa to Meta. */
    val type: String,
    val excluded: Boolean
)

data class RawAgeGenderReach(
    /** Stały podział Meta: 13-17, 18-24, 25-34, 35-44, 45-54, 55-64, 65+. */
    val ageRange: String,
    val male: Int,
    val female: Int,
    val unknown: Int
)

/** Błąd wywołania Biblioteki reklam — z kodem, bo po nim rozpoznajemy brak weryfikacji konta. */
class MetaAdsException(
    val statusCode: Int?,
    val errorCode: Int?,
    val errorSubcode: Int?,
    message: String
) : RuntimeException(message)

/**
 * Strona reklamodawcy znaleziona po frazie — kandydat do powiązania z profilem.
 *
 * Liczba reklam i data ostatniego startu są tu po to, żeby człowiek odróżnił
 * właściwe studio od zbieżnej nazwy: „Auto Spa" jest w każdym mieście.
 */
data class MetaPageCandidate(
    val pageId: String,
    val pageName: String,
    val ads: Int,
    val lastStart: java.time.LocalDate?
)
