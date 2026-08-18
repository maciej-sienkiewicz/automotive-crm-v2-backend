package pl.detailing.crm.mailbox.infrastructure

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import pl.detailing.crm.mailbox.domain.MailAccountStatus
import pl.detailing.crm.mailbox.domain.MailProviderType
import java.util.UUID

@Repository
interface MailAccountRepository : JpaRepository<MailAccountEntity, UUID> {
    fun findByStudioId(studioId: UUID): List<MailAccountEntity>
    fun findByIdAndStudioId(id: UUID, studioId: UUID): MailAccountEntity?
    fun findByStudioIdAndEmailAddress(studioId: UUID, emailAddress: String): MailAccountEntity?
    fun findByStatusAndProviderType(
        status: MailAccountStatus,
        providerType: MailProviderType
    ): List<MailAccountEntity>
}
