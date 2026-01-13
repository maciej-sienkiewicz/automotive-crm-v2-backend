package pl.detailing.crm.customer.infrastructure

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface CustomerRepository : JpaRepository<CustomerEntity, UUID> {

    @Query("SELECT c FROM CustomerEntity c WHERE c.id = :id AND c.studioId = :studioId")
    fun findByIdAndStudioId(
        @Param("id") id: UUID,
        @Param("studioId") studioId: UUID
    ): CustomerEntity?

    @Query("SELECT c FROM CustomerEntity c WHERE c.studioId = :studioId")
    fun findByStudioId(@Param("studioId") studioId: UUID): List<CustomerEntity>

    @Query("SELECT c FROM CustomerEntity c WHERE c.studioId = :studioId AND c.isActive = true")
    fun findActiveByStudioId(@Param("studioId") studioId: UUID): List<CustomerEntity>

    @Query("""
        SELECT COUNT(c) > 0 FROM CustomerEntity c 
        WHERE c.studioId = :studioId 
        AND c.email = :email 
        AND c.isActive = true
    """)
    fun existsActiveByStudioIdAndEmail(
        @Param("studioId") studioId: UUID,
        @Param("email") email: String
    ): Boolean

    @Query("""
        SELECT COUNT(c) > 0 FROM CustomerEntity c 
        WHERE c.studioId = :studioId 
        AND c.phone = :phone 
        AND c.isActive = true
    """)
    fun existsActiveByStudioIdAndPhone(
        @Param("studioId") studioId: UUID,
        @Param("phone") phone: String
    ): Boolean

    @Query("""
        SELECT c FROM CustomerEntity c 
        WHERE c.studioId = :studioId 
        AND c.email = :email 
        AND c.isActive = true
    """)
    fun findActiveByStudioIdAndEmail(
        @Param("studioId") studioId: UUID,
        @Param("email") email: String
    ): CustomerEntity?

    @Query("""
        SELECT c FROM CustomerEntity c 
        WHERE c.studioId = :studioId 
        AND c.phone = :phone 
        AND c.isActive = true
    """)
    fun findActiveByStudioIdAndPhone(
        @Param("studioId") studioId: UUID,
        @Param("phone") phone: String
    ): CustomerEntity?
}
