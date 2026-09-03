package pl.detailing.crm.instagram.ai.infrastructure

import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface InstagramGeneratedPostRepository : JpaRepository<InstagramGeneratedPostEntity, UUID> {

    fun findByStudioIdOrderByCreatedAtDesc(studioId: UUID, pageable: Pageable): List<InstagramGeneratedPostEntity>

    fun findByIdAndStudioId(id: UUID, studioId: UUID): InstagramGeneratedPostEntity?
}
