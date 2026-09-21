package pl.detailing.crm.finance.payment

/**
 * Rozstrzyga, co zrobić z każdym dokumentem zaznaczonym do grupowej zmiany statusu
 * płatności — zanim cokolwiek zostanie zapisane.
 *
 * Plan powstaje osobno od zapisu z jednego powodu: pojedyncze handlery rzucają
 * [pl.detailing.crm.shared.ValidationException] przy niedozwolonym przejściu, a wyjątek
 * rzucony w pętli wewnątrz transakcji ustawia ją w rollback-only — złapanie go nie
 * pomaga, bo cała operacja i tak wywraca się na commicie. Dlatego dokumenty, których
 * nie wolno ruszyć, odsiewamy TUTAJ, a do zapisu idą wyłącznie te, które na pewno
 * przejdą. Dzięki temu operacja na 20 fakturach nie przepada przez jedną opłaconą.
 */
object BulkPaymentStatusPlan {

    const val PAID = "PAID"
    const val PENDING = "PENDING"

    /** Dozwolone cele grupowej zmiany: „zapłacone" i „czeka na płatność". */
    val TARGETS = setOf(PAID, PENDING)

    /**
     * Jeden zaznaczony dokument odnaleziony w bazie.
     *
     * @param paidIsFinal dokument modułu finansowego (paragon, dokument „inny") nie wraca
     *        ze statusu PAID — tak stanowi reguła domeny w UpdateDocumentStatusHandler.
     *        Faktura z ledgera KSeF cofa się swobodnie, bo status płatności jest tam
     *        wyłącznie adnotacją księgową, a nie zdarzeniem kasowym.
     */
    data class Item(
        val key: String,
        val currentStatus: String,
        val paidIsFinal: Boolean = false
    )

    data class Skipped(val key: String, val reason: String)

    data class Plan(
        /** Dokumenty do zapisania — mają inny status niż docelowy i wolno je zmienić. */
        val toChange: List<String>,
        /** Miały już docelowy status: nic do roboty, ale i nie ma o czym meldować jako błędzie. */
        val unchanged: List<String>,
        val skipped: List<Skipped>
    )

    const val REASON_NOT_FOUND = "dokument nie istnieje"
    const val REASON_PAID_IS_FINAL = "opłaconego dokumentu nie można cofnąć"

    /**
     * @param requested klucze przysłane przez klienta — te, których nie ma w [found],
     *        wracają jako pominięte, a nie jako błąd całej operacji.
     */
    fun of(requested: List<String>, found: List<Item>, target: String): Plan {
        require(target in TARGETS) { "Nieobsługiwany status docelowy: $target" }

        val byKey = found.associateBy { it.key }
        val toChange = mutableListOf<String>()
        val unchanged = mutableListOf<String>()
        val skipped = mutableListOf<Skipped>()

        for (key in requested.distinct()) {
            val item = byKey[key]
            when {
                item == null -> skipped += Skipped(key, REASON_NOT_FOUND)
                item.currentStatus == target -> unchanged += key
                item.paidIsFinal && item.currentStatus == PAID -> skipped += Skipped(key, REASON_PAID_IS_FINAL)
                else -> toChange += key
            }
        }

        return Plan(toChange = toChange, unchanged = unchanged, skipped = skipped)
    }
}
