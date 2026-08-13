package pl.detailing.crm.ksef.revenue.domain

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Cykl życia wysyłki faktury przychodowej do KSeF (source=CRM).
 *
 * PENDING → SENDING → SUBMITTED → ACCEPTED
 *                   ↘ REJECTED (błąd walidacji KSeF — trwały, wymaga poprawy danych)
 *         ↘ QUEUED_RETRY (błąd łączności / niedostępność KSeF — tryb offline24,
 *                         dosyłka schedulerem najpóźniej następnego dnia roboczego)
 *
 * Faktury source=EXTERNAL (pobrane pullem, wystawione poza CRM) są zawsze ACCEPTED —
 * istnieją w KSeF z definicji.
 */
enum class KsefRevenueStatus {
    PENDING,
    SENDING,
    SUBMITTED,
    ACCEPTED,
    REJECTED,
    QUEUED_RETRY;

    /** Statusy, z których wolno ponowić wysyłkę (idempotencja — ACCEPTED/SENDING nigdy). */
    fun isRetryable(): Boolean = this == PENDING || this == QUEUED_RETRY || this == REJECTED
}

/**
 * Wynik detekcji podwójnego fakturowania — para (faktura CRM, faktura EXTERNAL)
 * o tym samym NIP nabywcy, tej samej kwocie brutto i bliskiej dacie wystawienia.
 *
 * System nigdy nie scala ani nie ukrywa takich faktur automatycznie — obie są
 * prawnie wiążące. Decyzję podejmuje użytkownik:
 *   CONFIRMED_DUPLICATE – duplikat potwierdzony; wykluczony ze statystyk,
 *                         nadmiarową fakturę należy skorygować do zera
 *   DISMISSED           – to odrębne transakcje; alert nie wraca
 */
enum class DuplicateStatus {
    NONE,
    SUSPECTED,
    CONFIRMED_DUPLICATE,
    DISMISSED
}

/** Rodzaj faktury w rozumieniu FA(3) <RodzajFaktury>. */
enum class RevenueInvoiceType {
    VAT,
    KOR
}

/**
 * Stawki VAT wspierane przy wystawianiu faktur FA(3).
 *
 * [faCode] — wartość pola P_12 w wierszu faktury.
 * [aggregateIndex] — sufiks pól agregatów podstawy/podatku w sekcji Fa:
 *   23% → P_13_1/P_14_1, 8% → P_13_2/P_14_2, 5% → P_13_3/P_14_3,
 *   0% (krajowe) → P_13_6_1 (bez pola podatku), zw → P_13_7 (bez pola podatku).
 */
enum class VatRate(val faCode: String, val rate: BigDecimal?) {
    RATE_23("23", BigDecimal("0.23")),
    RATE_8("8", BigDecimal("0.08")),
    RATE_5("5", BigDecimal("0.05")),
    RATE_0("0", BigDecimal.ZERO),
    ZW("zw", null);

    /** VAT w groszach od podstawy netto w groszach (zaokrąglenie HALF_UP jak w przepisach). */
    fun vatFromNet(netGrosz: Long): Long = when (rate) {
        null -> 0L
        else -> BigDecimal(netGrosz).multiply(rate).setScale(0, RoundingMode.HALF_UP).toLong()
    }

    /**
     * VAT w groszach liczony od kwoty brutto — wzór z art. 106e ust. 7 ustawy o VAT:
     * VAT = brutto × stawka / (100 + stawka). Używany, gdy użytkownik wpisał cenę
     * brutto (metoda „w stu"): brutto pozostaje dokładnie takie, jak wpisano,
     * a netto = brutto − VAT.
     */
    fun vatFromGross(grossGrosz: Long): Long = when (rate) {
        null -> 0L
        else -> BigDecimal(grossGrosz)
            .multiply(rate.movePointRight(2))
            .divide(BigDecimal(100).add(rate.movePointRight(2)), 0, RoundingMode.HALF_UP)
            .toLong()
    }

    companion object {
        fun fromCode(code: String): VatRate =
            entries.firstOrNull { it.faCode.equals(code.trim(), ignoreCase = true) }
                ?: throw IllegalArgumentException(
                    "Nieobsługiwana stawka VAT: '$code'. Dozwolone: ${entries.joinToString { it.faCode }}"
                )
    }
}

/**
 * Metoda cenowa pozycji — które pole wpisał użytkownik. Kwota wpisana jest źródłem
 * prawdy i nigdy nie jest przeliczana wstecz; druga kwota jest zawsze pochodną:
 * NET   → VAT = netto × stawka, brutto = netto + VAT,
 * GROSS → VAT = brutto × stawka/(100+stawka) (art. 106e ust. 7), netto = brutto − VAT.
 */
enum class PriceMode {
    NET,
    GROSS
}

/** Źródło dokumentu przychodowego w ledgerze. */
enum class RevenueSource {
    /** Wystawiona w CRM i wysyłana do KSeF. */
    CRM,

    /** Pobrana z KSeF (pull SUBJECT1) — wystawiona poza CRM. */
    EXTERNAL
}
