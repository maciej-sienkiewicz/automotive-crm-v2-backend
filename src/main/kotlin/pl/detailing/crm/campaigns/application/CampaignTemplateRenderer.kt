package pl.detailing.crm.campaigns.application

import org.springframework.stereotype.Component
import pl.detailing.crm.campaigns.infrastructure.AudienceRow
import pl.detailing.crm.studio.settings.StudioSettingsRepository
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Substitutes campaign placeholders with per-recipient data.
 *
 * Supported placeholders (documented in the wizard UI):
 * {{imie}}, {{nazwisko}}, {{studio}}, {{telefon_studia}}, {{www}},
 * {{marka}}, {{model}}, {{ostatnia_usluga}}, {{data_ostatniej_wizyty}}, {{dni_od_wizyty}}
 */
@Component
class CampaignTemplateRenderer(
    private val studioSettingsRepository: StudioSettingsRepository
) {
    private val dateFormat = DateTimeFormatter.ofPattern("dd.MM.yyyy")
    private val warsaw: ZoneId = ZoneId.of("Europe/Warsaw")

    data class StudioContext(val name: String, val phone: String, val website: String)

    fun studioContext(studioId: UUID): StudioContext {
        val settings = studioSettingsRepository.findById(studioId).orElse(null)
        return StudioContext(
            name = settings?.name ?: "",
            phone = settings?.phone ?: "",
            website = settings?.website ?: ""
        )
    }

    fun render(template: String, row: AudienceRow, studio: StudioContext): String {
        val lastVisitLocal: LocalDate? = row.lastVisitDate?.atZone(warsaw)?.toLocalDate()
        val daysSince = row.lastVisitDate?.let { ChronoUnit.DAYS.between(it, Instant.now()) }

        return template
            .replace("{{imie}}", row.firstName.orEmpty())
            .replace("{{nazwisko}}", row.lastName.orEmpty())
            .replace("{{studio}}", studio.name)
            .replace("{{telefon_studia}}", studio.phone)
            .replace("{{www}}", studio.website)
            .replace("{{marka}}", row.vehicleBrand.orEmpty())
            .replace("{{model}}", row.vehicleModel.orEmpty())
            .replace("{{ostatnia_usluga}}", row.lastServiceName.orEmpty())
            .replace("{{data_ostatniej_wizyty}}", lastVisitLocal?.format(dateFormat).orEmpty())
            .replace("{{dni_od_wizyty}}", daysSince?.toString().orEmpty())
            // Zbitki podwójnych spacji po pustych podstawieniach
            .replace(Regex(" {2,}"), " ")
            .trim()
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
