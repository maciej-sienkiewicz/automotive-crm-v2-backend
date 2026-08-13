package pl.detailing.crm.campaigns.application

import org.springframework.stereotype.Component
import pl.detailing.crm.campaigns.infrastructure.AudienceRow
import pl.detailing.crm.communication.template.MessageTemplateRenderer
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * Builds the per-recipient placeholder values for a campaign and hands them to the
 * shared [MessageTemplateRenderer].
 *
 * Supported placeholders (documented in the wizard UI):
 * {{imie}}, {{nazwisko}}, {{marka}}, {{model}},
 * {{ostatnia_usluga}}, {{data_ostatniej_wizyty}}, {{dni_od_wizyty}}
 *
 * The studio's own name, phone and website are not placeholders — the studio knows
 * them when it writes the campaign, so it types them straight into the text.
 */
@Component
class CampaignTemplateRenderer(
    private val renderer: MessageTemplateRenderer
) {
    private val dateFormat = DateTimeFormatter.ofPattern("dd.MM.yyyy")
    private val warsaw: ZoneId = ZoneId.of("Europe/Warsaw")

    fun render(template: String, row: AudienceRow): String {
        val lastVisitLocal: LocalDate? = row.lastVisitDate?.atZone(warsaw)?.toLocalDate()
        val daysSince = row.lastVisitDate?.let { ChronoUnit.DAYS.between(it, Instant.now()) }

        return renderer.render(
            template,
            mapOf(
                "imie" to row.firstName.orEmpty(),
                "nazwisko" to row.lastName.orEmpty(),
                "marka" to row.vehicleBrand.orEmpty(),
                "model" to row.vehicleModel.orEmpty(),
                "ostatnia_usluga" to row.lastServiceName.orEmpty(),
                "data_ostatniej_wizyty" to lastVisitLocal?.format(dateFormat).orEmpty(),
                "dni_od_wizyty" to daysSince?.toString().orEmpty()
            )
        )
    }
}

/**
 * GSM-7 vs UCS-2 segment calculation, identical rules to what SMSAPI bills:
 * GSM-7: 160 chars single / 153 per part when concatenated
 * UCS-2 (Polish diacritics): 70 single / 67 per part
 */
object SmsSegmentCalculator {

    private val GSM7 = (
        "@£\$¥èéùìòÇ\nØø\rÅåΔ_ΦΓΛΩΠΨΣΘΞÆæßÉ !\"#¤%&'()*+,-./0123456789:;<=>?" +
            "¡ABCDEFGHIJKLMNOPQRSTUVWXYZÄÖÑÜ§¿abcdefghijklmnopqrstuvwxyzäöñüà" +
            "^{}\\[~]|€"
        ).toSet()

    fun isGsm7(text: String): Boolean = text.all { it in GSM7 }

    fun segments(text: String): Int {
        if (text.isEmpty()) return 0
        return if (isGsm7(text)) {
            if (text.length <= 160) 1 else (text.length + 152) / 153
        } else {
            if (text.length <= 70) 1 else (text.length + 66) / 67
        }
    }
}
