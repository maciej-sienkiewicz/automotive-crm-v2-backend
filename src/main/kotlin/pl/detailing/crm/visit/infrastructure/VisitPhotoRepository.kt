package pl.detailing.crm.visit.infrastructure

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface VisitPhotoRepository : JpaRepository<VisitPhotoEntity, UUID> {

    @Query("""
        SELECT p FROM VisitPhotoEntity p
        WHERE p.id = :photoId AND p.visit.studioId = :studioId
    """)
    fun findByIdAndStudioId(
        @Param("photoId") photoId: UUID,
        @Param("studioId") studioId: UUID
    ): VisitPhotoEntity?
}
