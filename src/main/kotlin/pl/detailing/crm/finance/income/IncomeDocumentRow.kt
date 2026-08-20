package pl.detailing.crm.finance.income

import pl.detailing.crm.finance.domain.PaymentMethod
import pl.detailing.crm.ksef.domain.PaymentForm
import java.math.BigDecimal
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate

/**
 * Wiersz zunifikowanej listy dokumentów przychodowych — wspólny kształt dla faktur
 * z ledgera KSeF i dokumentów z modułu finansowego (paragony, dokumenty „inne").
 *
 * [sourceKind] rozstrzyga, gdzie leży dokument źródłowy, a więc jakie akcje są
 * dla niego dostępne w UI (szczegóły faktury KSeF vs edycja dokumentu finansowego).
 */
data class IncomeDocumentRow(
    val id: String,
    /** KSEF = ledger faktur KSeF, FINANCE = moduł finansowy. */
    val sourceKind: String,
    /** INVOICE | CORRECTION | RECEIPT | OTHER */
    val documentType: String,
    val documentNumber: String,
    val issueDate: LocalDate,
    val counterpartyName: String?,
    val counterpartyNip: String?,
    val totalNet: Long,
    val totalVat: Long,
    val totalGross: Long,
    val currency: String,
    /** PAID | PENDING | OVERDUE */
    val paymentStatus: String,
    /** Czytelna nazwa formy płatności — wspólna dla obu słowników. */
    val paymentLabel: String?,
    /** Status wysyłki do KSeF; null dla dokumentów spoza ledgera KSeF. */
    val ksefStatus: String?,
    val ksefNumber: String?,
    /** CRM | EXTERNAL (KSeF) albo VISIT | MANUAL (moduł finansowy). */
    val origin: String?,
    val duplicateStatus: String,
    val visitId: String?,
    val createdAt: Instant,
    /** Ukryty ręcznie ze statystyk — widoczny tylko przy includeExcluded. */
    val excluded: Boolean
) {
    companion object {
        fun from(row: Array<Any?>) = IncomeDocumentRow(
            id               = row[0] as String,
            sourceKind       = row[1] as String,
            documentType     = row[2] as String,
            documentNumber   = row[3] as? String ?: "",
            issueDate        = toLocalDate(row[4]),
            counterpartyName = row[5] as? String,
            counterpartyNip  = row[6] as? String,
            totalNet         = toLong(row[7]),
            totalVat         = toLong(row[8]),
            totalGross       = toLong(row[9]),
            currency         = row[10] as? String ?: "PLN",
            paymentStatus    = row[11] as? String ?: "PENDING",
            paymentLabel     = paymentLabel(row[12] as? String),
            ksefStatus       = row[13] as? String,
            ksefNumber       = row[14] as? String,
            origin           = row[15] as? String,
            duplicateStatus  = row[16] as? String ?: "NONE",
            visitId          = row[17] as? String,
            createdAt        = toInstant(row[18]),
            excluded         = row[19] == true
        )

        /**
         * Oba źródła używają innych słowników form płatności (PaymentForm w KSeF,
         * PaymentMethod w module finansowym) — sprowadzamy je do jednej etykiety.
         */
        private fun paymentLabel(raw: String?): String? = raw?.takeIf { it.isNotBlank() }?.let { value ->
            runCatching { PaymentForm.valueOf(value).displayName }.getOrNull()
                ?: runCatching { PaymentMethod.valueOf(value).displayName }.getOrNull()
                ?: value
        }

        private fun toLocalDate(value: Any?): LocalDate = when (value) {
            is LocalDate -> value
            is java.sql.Date -> value.toLocalDate()
            else -> LocalDate.now()
        }

        private fun toInstant(value: Any?): Instant = when (value) {
            is Instant -> value
            is Timestamp -> value.toInstant()
            is java.time.OffsetDateTime -> value.toInstant()
            else -> Instant.now()
        }

        private fun toLong(value: Any?): Long = when (value) {
            null -> 0L
            is Long -> value
            is BigDecimal -> value.toLong()
            is Number -> value.toLong()
            else -> 0L
        }
    }
}
