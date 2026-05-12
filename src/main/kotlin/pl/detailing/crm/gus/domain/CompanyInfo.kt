package pl.detailing.crm.gus.domain

import java.io.Serializable
import java.time.LocalDate

data class CompanyInfo(
    val nip: String,
    val regon: String,
    val name: String,
    val shortName: String?,
    val legalForm: String?,
    val address: CompanyAddress,
    val phone: String?,
    val email: String?,
    val website: String?,
    val krsNumber: String?,
    val activityStartDate: LocalDate?,
    val activityEndDate: LocalDate?,
    val activitySuspendedDate: LocalDate?,
    val entityType: EntityType,
    val isActive: Boolean
) : Serializable

data class CompanyAddress(
    val street: String?,
    val buildingNumber: String?,
    val apartmentNumber: String?,
    val city: String?,
    val postalCode: String?,
    val country: String?
) : Serializable

enum class EntityType {
    LEGAL_PERSON,       // P  – spółka, fundacja, stowarzyszenie itp.
    NATURAL_PERSON,     // F  – CEIDG (działalność gosp. os. fizycznej)
    LOCAL_UNIT_LEGAL,   // LP – jednostka lokalna os. prawnej
    LOCAL_UNIT_NATURAL  // LF – jednostka lokalna os. fizycznej
}
