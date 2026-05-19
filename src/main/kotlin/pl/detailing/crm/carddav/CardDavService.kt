package pl.detailing.crm.carddav

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import org.springframework.stereotype.Service
import pl.detailing.crm.customer.infrastructure.CustomerEntity
import pl.detailing.crm.shared.EntityNotFoundException
import pl.detailing.crm.shared.ForbiddenException
import java.util.UUID

@Service
class CardDavService(
    private val customerRepository: CardDavCustomerRepository
) {

    fun getContactsForTenant(tenantId: UUID): List<CustomerEntity> =
        customerRepository.findActiveWithPhoneByStudioId(tenantId)

    fun getContactForTenant(tenantId: UUID, customerId: UUID): CustomerEntity {
        return customerRepository.findActiveWithPhoneByIdAndStudioId(customerId, tenantId)
            ?: throw EntityNotFoundException("Contact not found")
    }

    fun assertTenantOwnership(authenticatedStudioId: UUID, requestedTenantId: UUID) {
        if (authenticatedStudioId != requestedTenantId) {
            throw ForbiddenException("Access to tenant $requestedTenantId is not permitted")
        }
    }
}

@Repository
interface CardDavCustomerRepository : JpaRepository<CustomerEntity, UUID> {

    @Query("""
        SELECT c FROM CustomerEntity c
        WHERE c.studioId = :studioId
          AND c.isActive = true
          AND c.phone IS NOT NULL
          AND c.phone <> ''
    """)
    fun findActiveWithPhoneByStudioId(@Param("studioId") studioId: UUID): List<CustomerEntity>

    @Query("""
        SELECT c FROM CustomerEntity c
        WHERE c.id = :id
          AND c.studioId = :studioId
          AND c.isActive = true
          AND c.phone IS NOT NULL
          AND c.phone <> ''
    """)
    fun findActiveWithPhoneByIdAndStudioId(
        @Param("id") id: UUID,
        @Param("studioId") studioId: UUID
    ): CustomerEntity?
}
