package pl.detailing.crm.customer.infrastructure

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface CustomerDocumentRepository : JpaRepository<CustomerDocumentEntity, UUID> {

    @Query("SELECT d FROM CustomerDocumentEntity d WHERE d.customerId = :customerId AND d.studioId = :studioId ORDER BY d.uploadedAt DESC")
    fun findByCustomerIdAndStudioId(
        @Param("customerId") customerId: UUID,
        @Param("studioId") studioId: UUID
    ): List<CustomerDocumentEntity>

    @Query("SELECT d FROM CustomerDocumentEntity d WHERE d.id = :id AND d.studioId = :studioId")
    fun findByIdAndStudioId(
        @Param("id") id: UUID,
        @Param("studioId") studioId: UUID
    ): CustomerDocumentEntity?
}
