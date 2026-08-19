package pl.detailing.crm.leads.update

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.leads.domain.LeadTag
import pl.detailing.crm.leads.infrastructure.LeadTagEntity
import pl.detailing.crm.leads.infrastructure.LeadTagRepository
import java.util.UUID

/**
 * Tagi leada. Zapis jest zawsze podmianą całego zestawu, nie doklejaniem: użytkownik
 * widzi w oknie listę zaznaczeń i oczekuje, że zapisze się dokładnie to, co widzi.
 */
@Service
class LeadTagService(
    private val tagRepository: LeadTagRepository
) {

    @Transactional
    fun replaceTags(leadId: UUID, tags: List<LeadTag>) {
        tagRepository.deleteByLeadId(leadId)
        if (tags.isEmpty()) return
        tagRepository.saveAll(
            tags.distinct().map { LeadTagEntity(leadId = leadId, tagCode = it.name) }
        )
    }

    fun tagsOf(leadId: UUID): List<LeadTag> =
        tagRepository.findByLeadId(leadId).mapNotNull { LeadTag.fromCode(it.tagCode) }

    /** Tagi wielu leadów naraz — lista leadów pobiera je jednym zapytaniem, nie N. */
    fun tagsOf(leadIds: Collection<UUID>): Map<UUID, List<LeadTag>> {
        if (leadIds.isEmpty()) return emptyMap()
        return tagRepository.findByLeadIdIn(leadIds)
            .groupBy { it.leadId }
            .mapValues { (_, rows) -> rows.mapNotNull { LeadTag.fromCode(it.tagCode) } }
    }
}
