package pl.detailing.crm.ksef.domain

/**
 * Forma płatności zgodna ze schematem FA(2) KSeF – pole <FormaPlatnosci>.
 *
 * Wartości wg specyfikacji MF (plik FA_v2-0E.xsd):
 *   1 = gotówka
 *   2 = karta
 *   3 = czek (nieużywany w FA_KOR)
 *   4 = bon / voucher
 *   5 = kredyt
 *   6 = przelew  ← najczęstszy w B2B
 *   7 = mobilna
 *
 * @property ksefCode wartość liczbowa z XML faktury KSeF (<FormaPlatnosci>)
 * @property displayName czytelna etykieta do prezentacji w UI / raportach
 */
enum class PaymentForm(val ksefCode: String, val displayName: String) {
    GOTOWKA("1",  "Gotówka"),
    KARTA("2",    "Karta"),
    CZEK("3",     "Czek"),
    BON("4",      "Bon / voucher"),
    KREDYT("5",   "Kredyt"),
    PRZELEW("6",  "Przelew"),
    MOBILNA("7",  "Mobilna");

    companion object {
        /**
         * Mapuje kod z XML KSeF na enum.
         * Zwraca null gdy kod jest nieznany lub null – brak pola w dokumencie.
         */
        fun fromKsefCode(code: String?): PaymentForm? =
            code?.trim()?.let { c -> entries.firstOrNull { it.ksefCode == c } }
    }
}
