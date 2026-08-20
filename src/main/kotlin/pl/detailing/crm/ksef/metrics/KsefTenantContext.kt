package pl.detailing.crm.ksef.metrics

import pl.detailing.crm.shared.StudioId

/**
 * Wskazuje, czyje żądanie do KSeF właśnie leci.
 *
 * Limity KSeF są naliczane per kontekst NIP, więc każda metryka ruchu jest warta
 * tyle, ile wiedza, którego studia dotyczy. Sam klient SDK jej nie ma — jego metody
 * przyjmują token dostępu, nie identyfikator najemcy — a token to nieprzezroczysty
 * ciąg, z którego nie odczytamy studia bez trzymania osobnej mapy sekretów.
 *
 * Stąd wątkowy kontekst ustawiany tam, gdzie studio jest jeszcze znane: w usłudze
 * uwierzytelniania, wysyłce faktur i synchronizacji. Cała komunikacja z KSeF jest
 * synchroniczna i jednowątkowa (scheduler albo wątek żądania HTTP), więc ThreadLocal
 * jest tu adekwatny; gdyby kiedyś doszła asynchroniczność, przekazanie studia
 * wprost w sygnaturze będzie jedynym poprawnym wyjściem.
 *
 * Brak kontekstu nie jest błędem — SDK odpytuje KSeF o klucz publiczny przy starcie
 * aplikacji, zanim istnieje jakiekolwiek żądanie. Takie wywołania trafiają pod
 * [SYSTEM], żeby nie ginęły i nie udawały ruchu przypadkowego najemcy.
 */
object KsefTenantContext {

    /** Etykieta ruchu bez najemcy: inicjalizacja klienta, klucz publiczny KSeF. */
    const val SYSTEM = "system"

    private val current = ThreadLocal<String?>()

    /** Identyfikator studia dla bieżącego wątku albo [SYSTEM], gdy ruch jest bezkontekstowy. */
    fun currentStudioTag(): String = current.get() ?: SYSTEM

    /**
     * Wykonuje [block] z ustawionym studiem. Poprzednia wartość jest przywracana,
     * a nie kasowana — zagnieżdżenie (wysyłka faktury woła uwierzytelnienie)
     * nie może zgubić kontekstu zewnętrznego wywołania.
     */
    fun <T> withStudio(studioId: StudioId, block: () -> T): T {
        val previous = current.get()
        current.set(studioId.value.toString())
        try {
            return block()
        } finally {
            current.set(previous)
        }
    }
}
