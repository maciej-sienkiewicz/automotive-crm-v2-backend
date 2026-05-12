package pl.detailing.crm.gus.api

import pl.detailing.crm.gus.domain.CompanyInfo
import pl.detailing.crm.gus.domain.EntityType

data class CompanyInfoResponse(
    val nip: String,
    val regon: String,
    val name: String,
    val shortName: String?,
    val legalForm: String?,
    val address: CompanyAddressResponse,
    val phone: String?,
    val email: String?,
    val website: String?,
    val krsNumber: String?,
    val activityStartDate: String?,
    val activityEndDate: String?,
    val activitySuspendedDate: String?,
    val entityType: String,
    val isActive: Boolean
)

data class CompanyAddressResponse(
    val street: String?,
    val buildingNumber: String?,
    val apartmentNumber: String?,
    val city: String?,
    val postalCode: String?,
    val country: String?
)

fun CompanyInfo.toResponse() = CompanyInfoResponse(
    nip                   = nip,
    regon                 = regon,
    name                  = name,
    shortName             = shortName,
    legalForm             = legalForm,
    address               = CompanyAddressResponse(
        street          = address.street,
        buildingNumber  = address.buildingNumber,
        apartmentNumber = address.apartmentNumber,
        city            = address.city,
        postalCode      = address.postalCode,
        country         = address.country
    ),
    phone                 = phone,
    email                 = email,
    website               = website,
    krsNumber             = krsNumber,
    activityStartDate     = activityStartDate?.toString(),
    activityEndDate       = activityEndDate?.toString(),
    activitySuspendedDate = activitySuspendedDate?.toString(),
    entityType            = when (entityType) {
        EntityType.LEGAL_PERSON      -> "legal_person"
        EntityType.NATURAL_PERSON    -> "natural_person"
        EntityType.LOCAL_UNIT_LEGAL  -> "local_unit_legal"
        EntityType.LOCAL_UNIT_NATURAL -> "local_unit_natural"
    },
    isActive              = isActive
)
