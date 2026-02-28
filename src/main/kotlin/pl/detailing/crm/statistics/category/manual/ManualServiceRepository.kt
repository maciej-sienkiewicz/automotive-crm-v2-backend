package pl.detailing.crm.statistics.category.manual

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface ManualServiceRepository : JpaRepository<ManualServiceEntity, UUID> {

    fun findByStudioIdAndServiceNameIn(
        studioId: UUID,
        serviceNames: Collection<String>
    ): List<ManualServiceEntity>

    fun findByIdAndStudioId(id: UUID, studioId: UUID): ManualServiceEntity?
}
