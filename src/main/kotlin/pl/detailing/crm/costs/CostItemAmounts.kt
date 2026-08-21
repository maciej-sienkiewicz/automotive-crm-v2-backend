package pl.detailing.crm.costs

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Uzupełnianie brakującej strony kwoty pozycji kosztowej (netto ⇄ brutto).
 *
 * Faktury kosztowe z KSeF bywają niekompletne: część sprzedawców wypełnia tylko
 * wartość brutto (P_11A), część tylko netto (P_11). Encja [pl.detailing.crm.ksef
 * .infrastructure.KsefInvoiceItemEntity] odwzorowuje XML wiernie — z nullami — więc
 * to warstwa API musi domknąć kwotę, zanim pozycje trafią do podsumowań.
 *
 * Bez tego suma netto pomijała pozycje z pustym P_11, a suma brutto je liczyła:
 * różnica brutto−netto rosła daleko ponad realny VAT (na sierpniu 2026 było to
 * 23 716 zł kosztów bez netto przy 175 pozycjach).
 */
object CostItemAmounts {

    /**
     * Stawki VAT ze schematu FA(2) w polu P_12, sprowadzone do ułamka.
     *
     * "zw" (zwolniony), "np" (nie podlega), "oo" (odwrotne obciążenie) i "0"/"0 KR"
     * dają netto == brutto — pozycja nie niesie VAT-u.
     */
    private val RATES: Map<String, BigDecimal> = mapOf(
        "23"   to BigDecimal("0.23"),
        "22"   to BigDecimal("0.22"),
        "8"    to BigDecimal("0.08"),
        "7"    to BigDecimal("0.07"),
        "5"    to BigDecimal("0.05"),
        "4"    to BigDecimal("0.04"),
        "3"    to BigDecimal("0.03"),
        "0"    to BigDecimal.ZERO,
        "0 kr" to BigDecimal.ZERO,
        "0 wdt" to BigDecimal.ZERO,
        "0 exp" to BigDecimal.ZERO,
        "zw"   to BigDecimal.ZERO,
        "np"   to BigDecimal.ZERO,
        "oo"   to BigDecimal.ZERO
    )

    /**
     * Stawka jako ułamek (0.23 dla "23"), albo null gdy kodu nie da się rozpoznać —
     * wtedy niczego nie zgadujemy i kwota zostaje pusta.
     */
    fun rateOf(vatRate: String?): BigDecimal? {
        val normalized = vatRate?.trim()?.replace(Regex("\\s+"), " ")?.removeSuffix("%")?.trim()
            ?.lowercase()
            ?: return null
        if (normalized.isEmpty()) return null
        return RATES[normalized] ?: normalized.toBigDecimalOrNull()?.takeIf { it >= BigDecimal.ZERO }
            ?.movePointLeft(2)
    }

    /**
     * Netto pozycji w groszach — z bazy, a gdy go brak, wyliczone „w stu"
     * z brutto i stawki.
     */
    fun netValueOf(netValue: Long?, grossValue: Long?, vatRate: String?): Long? {
        if (netValue != null) return netValue
        val gross = grossValue ?: return null
        val rate  = rateOf(vatRate) ?: return null
        return BigDecimal.valueOf(gross)
            .divide(BigDecimal.ONE.add(rate), 0, RoundingMode.HALF_UP)
            .toLong()
    }

    /** Brutto pozycji w groszach — z bazy, a gdy go brak, doliczone do netto wg stawki. */
    fun grossValueOf(netValue: Long?, grossValue: Long?, vatRate: String?): Long? {
        if (grossValue != null) return grossValue
        val net  = netValue ?: return null
        val rate = rateOf(vatRate) ?: return null
        return BigDecimal.valueOf(net)
            .multiply(BigDecimal.ONE.add(rate))
            .setScale(0, RoundingMode.HALF_UP)
            .toLong()
    }
}
