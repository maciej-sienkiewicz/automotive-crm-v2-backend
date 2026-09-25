package pl.detailing.crm.batchorder.report

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import pl.detailing.crm.batchorder.infrastructure.BatchOrderEntryEntity
import java.time.LocalDate

/** Usługa w wierszu zestawienia — kwoty przepisane z wpisu, nigdy nie przeliczane. */
data class ReportServiceLine(
    val name: String,
    val netAmountCents: Long,
    val grossAmountCents: Long,
    val vatRate: Int
)

/**
 * Jeden wiersz zestawienia PDF. Ten sam kształt służy żywemu raportowi (z wpisów)
 * i dokumentowi z historii (ze snapshotu), więc oba rysuje jeden kod i wychodzą
 * identyczne.
 */
data class ReportRow(
    val entryId: String?,
    val serviceDate: LocalDate,
    val vehicleMake: String?,
    val vehicleModel: String?,
    val vehicleLicensePlate: String?,
    val vehicleVin: String?,
    val services: List<ReportServiceLine>,
    val notes: String?
) {
    @get:JsonIgnore
    val netAmountCents: Long get() = services.sumOf { it.netAmountCents }

    // Brutto wiersza to suma brutto zapisanych przy usługach, jak w BatchOrderEntryEntity.
    // Odtworzenie go z netta zgubiłoby grosz na cenach wpisanych od strony brutto.
    @get:JsonIgnore
    val grossAmountCents: Long get() = services.sumOf { it.grossAmountCents }
}

fun BatchOrderEntryEntity.toReportRow() = ReportRow(
    entryId = id.toString(),
    serviceDate = serviceDate,
    vehicleMake = vehicleMake,
    vehicleModel = vehicleModel,
    vehicleLicensePlate = vehicleLicensePlate,
    vehicleVin = vehicleVin,
    services = services.map { ReportServiceLine(it.name, it.netAmountCents, it.grossAmountCents, it.vatRate) },
    notes = notes
)

/**
 * Pozycje rozliczenia zamrożone w chwili rozliczenia (batch_order_close_history.snapshot_json).
 *
 * Wcześniej PDF z historii składano z żywych wpisów po close_history_id — korekta wpisu
 * albo ponowne rozliczenie w trybie ALL zmieniały dokument, który kontrahent już dostał
 * mailem. Snapshot jest niezmienny: zapisywany raz, w tej samej transakcji co rekord
 * historii, i tylko czytany.
 *
 * Serializacja jawna (jak VisitDamageMapStore), a nieznane pola są ignorowane: pole
 * dodane tu kiedyś nie może sprawić, że starego rozliczenia nie da się otworzyć.
 */
data class SettlementSnapshot(
    val version: Int = CURRENT_VERSION,
    val entries: List<ReportRow>
) {
    fun toJson(): String = mapper.writeValueAsString(this)

    companion object {
        const val CURRENT_VERSION = 1

        private val mapper = jacksonObjectMapper()
            .registerModule(JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)

        fun of(entries: List<BatchOrderEntryEntity>) = SettlementSnapshot(entries = entries.map { it.toReportRow() })

        fun fromJson(json: String): SettlementSnapshot = mapper.readValue(json)
    }
}
