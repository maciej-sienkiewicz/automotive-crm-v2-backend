package pl.detailing.crm.finance.payment

/**
 * Wynik grupowej zmiany statusu płatności — ten sam kształt dla kosztów i przychodów,
 * bo pasek zaznaczenia na obu zakładkach melduje dokładnie to samo.
 *
 * Rozdzielenie [updated] i [unchanged] nie jest pedanterią: użytkownik zaznaczający
 * „wszystko" zwykle trafia też w dokumenty, które już mają docelowy status. To nie jest
 * błąd i nie może wyglądać jak błąd, ale komunikat „oznaczono 12" przy dwunastu
 * zaznaczonych, z których zmieniły się trzy, byłby nieprawdą.
 */
data class BulkPaymentStatusResponse(
    val updated: Int,
    val unchanged: Int,
    val skipped: List<BulkPaymentStatusSkip>
)

data class BulkPaymentStatusSkip(
    val id: String,
    /** Zdanie po polsku, gotowe do pokazania — dlaczego akurat ten dokument został pominięty. */
    val reason: String
)
