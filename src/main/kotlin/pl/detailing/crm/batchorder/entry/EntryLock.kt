package pl.detailing.crm.batchorder.entry

import pl.detailing.crm.shared.ConflictException

/**
 * Co próbowano zrobić z rozliczonym wpisem — od tego zależy tylko treść odmowy.
 */
enum class LockedEntryAction(val message: String) {
    UPDATE("Wpis jest rozliczony. Odblokuj go do korekty, zanim go zmienisz."),
    DELETE("Wpis jest rozliczony. Odblokuj go do korekty, zanim go usuniesz.")
}

/**
 * Rozliczony wpis jest zamknięty. Wcześniej każdy zapis zdejmował mu `isClosed`,
 * więc poprawka literówki w numerze rejestracyjnym cicho cofała rozliczenie i ta sama
 * praca trafiała na następne zestawienie drugi raz. Zmiana rozliczonego wpisu ma być
 * decyzją (POST /entries/{id}/reopen), a nie skutkiem ubocznym zapisu.
 */
fun ensureEntryEditable(isClosed: Boolean, action: LockedEntryAction) {
    if (isClosed) throw ConflictException(action.message)
}
