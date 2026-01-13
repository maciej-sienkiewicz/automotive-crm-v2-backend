package pl.detailing.crm.customer.infrastructure

import jakarta.persistence.*
import pl.detailing.crm.customer.domain.CompanyData
import pl.detailing.crm.customer.domain.Customer
import pl.detailing.crm.shared.CustomerId
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import java.time.Instant
import java.util.UUID

@Entity
@Table(
    name = "customers",
    indexes = [
        Index(name = "idx_customers_studio_email", columnList = "studio_id, email", unique = true),
        Index(name = "idx_customers_studio_phone", columnList = "studio_id, phone", unique = true),
        Index(name = "idx_customers_studio_active", columnList = "studio_id, is_active"),
        Index(name = "idx_customers_created_by", columnList = "created_by"),
        Index(name = "idx_customers_updated_by", columnList = "updated_by")
    ]
)
class CustomerEntity(
    @Id
    @Column(name = "id", columnDefinition = "uuid")
    val id: UUID,

    @Column(name = "studio_id", nullable = false, columnDefinition = "uuid")
    val studioId: UUID,

    @Column(name = "first_name", nullable = false, length = 100)
    var firstName: String,

    @Column(name = "last_name", nullable = false, length = 100)
    var lastName: String,

    @Column(name = "email", nullable = false, length = 255)
    var email: String,

    @Column(name = "phone", nullable = false, length = 20)
    var phone: String,

    @Column(name = "home_address_street", length = 200)
    var homeAddressStreet: String?,

    @Column(name = "home_address_city", length = 100)
    var homeAddressCity: String?,

    @Column(name = "home_address_postal_code", length = 20)
    var homeAddressPostalCode: String?,

    @Column(name = "home_address_country", length = 100)
    var homeAddressCountry: String?,

    @Column(name = "company_name", length = 200)
    var companyName: String?,

    @Column(name = "company_nip", length = 20)
    var companyNip: String?,

    @Column(name = "company_regon", length = 20)
    var companyRegon: String?,

    @Column(name = "company_address_street", length = 200)
    var companyAddressStreet: String?,

    @Column(name = "company_address_city", length = 100)
    var companyAddressCity: String?,

    @Column(name = "company_address_postal_code", length = 20)
    var companyAddressPostalCode: String?,

    @Column(name = "company_address_country", length = 100)
    var companyAddressCountry: String?,

    @Column(name = "notes", columnDefinition = "TEXT")
    var notes: String?,

    @Column(name = "is_active", nullable = false)
    var isActive: Boolean = true,

    @Column(name = "created_by", nullable = false, columnDefinition = "uuid")
    val createdBy: UUID,

    @Column(name = "updated_by", nullable = false, columnDefinition = "uuid")
    var updatedBy: UUID,

    @Column(name = "created_at", nullable = false, columnDefinition = "timestamp with time zone")
    val createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false, columnDefinition = "timestamp with time zone")
    var updatedAt: Instant = Instant.now()
) {
    fun toDomain(): Customer = Customer(
        id = CustomerId(id),
        studioId = StudioId(studioId),
        firstName = firstName,
        lastName = lastName,
        email = email,
        phone = phone,
        homeAddress = if (homeAddressStreet != null && homeAddressCity != null && 
                          homeAddressPostalCode != null && homeAddressCountry != null) {
            pl.detailing.crm.customer.domain.HomeAddress(
                street = homeAddressStreet!!,
                city = homeAddressCity!!,
                postalCode = homeAddressPostalCode!!,
                country = homeAddressCountry!!
            )
        } else null,
        companyData = if (companyName != null) {
            CompanyData(
                name = companyName!!,
                nip = companyNip,
                regon = companyRegon,
                address = if (companyAddressStreet != null && companyAddressCity != null &&
                              companyAddressPostalCode != null && companyAddressCountry != null) {
                    pl.detailing.crm.customer.domain.CompanyAddress(
                        street = companyAddressStreet!!,
                        city = companyAddressCity!!,
                        postalCode = companyAddressPostalCode!!,
                        country = companyAddressCountry!!
                    )
                } else null
            )
        } else null,
        notes = notes,
        isActive = isActive,
        createdBy = UserId(createdBy),
        updatedBy = UserId(updatedBy),
        createdAt = createdAt,
        updatedAt = updatedAt
    )

    companion object {
        fun fromDomain(customer: Customer): CustomerEntity = CustomerEntity(
            id = customer.id.value,
            studioId = customer.studioId.value,
            firstName = customer.firstName,
            lastName = customer.lastName,
            email = customer.email,
            phone = customer.phone,
            homeAddressStreet = customer.homeAddress?.street,
            homeAddressCity = customer.homeAddress?.city,
            homeAddressPostalCode = customer.homeAddress?.postalCode,
            homeAddressCountry = customer.homeAddress?.country,
            companyName = customer.companyData?.name,
            companyNip = customer.companyData?.nip,
            companyRegon = customer.companyData?.regon,
            companyAddressStreet = customer.companyData?.address?.street,
            companyAddressCity = customer.companyData?.address?.city,
            companyAddressPostalCode = customer.companyData?.address?.postalCode,
            companyAddressCountry = customer.companyData?.address?.country,
            notes = customer.notes,
            isActive = customer.isActive,
            createdBy = customer.createdBy.value,
            updatedBy = customer.updatedBy.value,
            createdAt = customer.createdAt,
            updatedAt = customer.updatedAt
        )
    }
}
