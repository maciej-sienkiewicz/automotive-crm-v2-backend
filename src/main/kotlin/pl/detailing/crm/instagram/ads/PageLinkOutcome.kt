package pl.detailing.crm.instagram.ads

/**
 * Wynik próby wskazania, zmiany albo odpięcia strony na Facebooku.
 *
 * Trzy stany, bo użytkownik widzi trzy różne rzeczy: strona została powiązana od ręki,
 * prośba poszła do administratora, albo żądanie odrzucono. Bez tego rozróżnienia
 * „zapisano" i „zgłoszono do wprowadzenia" wyglądałyby na ekranie tak samo, a to dwie
 * zupełnie różne obietnice.
 */
sealed interface PageLinkOutcome {

    /** Zapisane od ręki — pierwsze wskazanie strony dla profilu, który jej nie miał. */
    data class Linked(val pageId: String) : PageLinkOutcome

    /** Prośba o zmianę albo odpięcie poszła mailem do administratora; baza bez zmian. */
    data object RequestSent : PageLinkOutcome

    /** Brak uprawnień, błędny numer albo numer identyczny z obecnym. */
    data object Rejected : PageLinkOutcome
}
