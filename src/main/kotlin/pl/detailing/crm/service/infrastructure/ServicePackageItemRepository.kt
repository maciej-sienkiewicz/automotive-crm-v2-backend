package pl.detailing.crm.service.infrastructure

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface ServicePackageItemRepository : JpaRepository<ServicePackageItemEntity, UUID> {

    @Query("SELECT i FROM ServicePackageItemEntity i WHERE i.packageId = :packageId ORDER BY i.position")
    fun findByPackageId(@Param("packageId") packageId: UUID): List<ServicePackageItemEntity>

    @Query("SELECT i FROM ServicePackageItemEntity i WHERE i.packageId IN :packageIds ORDER BY i.packageId, i.position")
    fun findByPackageIdIn(@Param("packageIds") packageIds: List<UUID>): List<ServicePackageItemEntity>

    @Query("SELECT i FROM ServicePackageItemEntity i WHERE i.serviceId = :serviceId AND i.studioId = :studioId")
    fun findByServiceIdAndStudioId(
        @Param("serviceId") serviceId: UUID,
        @Param("studioId") studioId: UUID
    ): List<ServicePackageItemEntity>

    @Modifying
    @Query("DELETE FROM ServicePackageItemEntity i WHERE i.packageId = :packageId")
    fun deleteByPackageId(@Param("packageId") packageId: UUID)
}
