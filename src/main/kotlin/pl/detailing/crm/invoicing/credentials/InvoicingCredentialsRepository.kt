package pl.detailing.crm.invoicing.credentials

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import java.util.UUID

interface InvoicingCredentialsRepository : JpaRepository<InvoicingCredentialsEntity, UUID> {

    fun findByStudioId(studioId: UUID): InvoicingCredentialsEntity?

    @Modifying
    @Query("DELETE FROM InvoicingCredentialsEntity e WHERE e.studioId = :studioId")
    fun deleteByStudioId(studioId: UUID)
}
