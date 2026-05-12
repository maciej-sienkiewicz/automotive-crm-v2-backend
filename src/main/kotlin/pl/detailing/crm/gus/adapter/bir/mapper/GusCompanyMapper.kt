package pl.detailing.crm.gus.adapter.bir.mapper

import pl.detailing.crm.gus.adapter.bir.parser.GusFullReportEntry
import pl.detailing.crm.gus.adapter.bir.parser.GusSearchEntry
import pl.detailing.crm.gus.domain.CompanyAddress
import pl.detailing.crm.gus.domain.CompanyInfo
import pl.detailing.crm.gus.domain.EntityType
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Mapuje surowe DTOs parsera GUS na domenowy model [CompanyInfo].
 *
 * Dane ze szczegółowego raportu ([GusFullReportEntry]) mają pierwszeństwo
 * nad danymi z wyników wyszukiwania ([GusSearchEntry]) – są pełniejsze.
 * Gdy pełny raport jest niedostępny, fallback na dane z wyszukiwania.
 */
object GusCompanyMapper {
    private val DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    fun toCompanyInfo(search: GusSearchEntry, report: GusFullReportEntry?): CompanyInfo {
        val address = if (report != null) {
            CompanyAddress(
                street          = report.street,
                buildingNumber  = report.buildingNumber,
                apartmentNumber = report.apartmentNumber,
                city            = report.city,
                postalCode      = report.postalCode,
                country         = "PL"
            )
        } else {
            CompanyAddress(
                street          = search.street,
                buildingNumber  = search.buildingNumber,
                apartmentNumber = null,
                city            = search.city,
                postalCode      = search.postalCode,
                country         = "PL"
            )
        }

        val endDateStr   = report?.activityEndDate   ?: search.activityEndDate
        val startDateStr = report?.activityStartDate

        return CompanyInfo(
            nip                   = search.nip,
            regon                 = search.regon,
            name                  = report?.name?.takeIf { it.isNotBlank() } ?: search.name,
            shortName             = report?.shortName,
            legalForm             = report?.legalFormName,
            address               = address,
            phone                 = report?.phone,
            email                 = report?.email,
            website               = report?.website,
            krsNumber             = report?.krsNumber,
            activityStartDate     = startDateStr?.toLocalDate(),
            activityEndDate       = endDateStr?.toLocalDate(),
            activitySuspendedDate = report?.activitySuspendedDate?.toLocalDate(),
            entityType            = mapEntityType(search.entityType),
            isActive              = endDateStr.isNullOrBlank()
        )
    }

    private fun mapEntityType(typ: String): EntityType = when (typ.uppercase()) {
        "P"  -> EntityType.LEGAL_PERSON
        "F"  -> EntityType.NATURAL_PERSON
        "LP" -> EntityType.LOCAL_UNIT_LEGAL
        "LF" -> EntityType.LOCAL_UNIT_NATURAL
        else -> EntityType.LEGAL_PERSON
    }

    private fun String.toLocalDate(): LocalDate? = try {
        LocalDate.parse(this, DATE_FORMAT)
    } catch (ignored: Exception) {
        null
    }
}
