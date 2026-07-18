package pl.detailing.crm.costs

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface SupplierAutoRuleRepository : JpaRepository<SupplierAutoRuleEntity, UUID> {

    fun findByStudioId(studioId: UUID): List<SupplierAutoRuleEntity>

    fun findByStudioIdAndSellerNip(studioId: UUID, sellerNip: String): SupplierAutoRuleEntity?

    fun findByIdAndStudioId(id: UUID, studioId: UUID): SupplierAutoRuleEntity?

    @Modifying
    @Query("DELETE FROM SupplierAutoRuleEntity r WHERE r.id = :id AND r.studioId = :studioId")
    fun deleteByIdAndStudioId(
        @Param("id") id: UUID,
        @Param("studioId") studioId: UUID
    ): Int
}
