package pl.detailing.crm.customer.notes

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface CustomerNoteRepository : JpaRepository<CustomerNoteEntity, UUID> {
    fun findByCustomerIdAndStudioIdOrderByCreatedAtDesc(customerId: UUID, studioId: UUID): List<CustomerNoteEntity>
    fun findByIdAndStudioId(id: UUID, studioId: UUID): CustomerNoteEntity?
}
