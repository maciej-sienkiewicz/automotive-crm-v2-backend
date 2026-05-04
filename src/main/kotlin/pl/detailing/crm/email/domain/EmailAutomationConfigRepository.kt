package pl.detailing.crm.email.domain

import pl.detailing.crm.shared.StudioId

interface EmailAutomationConfigRepository {

    fun findByStudioId(studioId: StudioId): EmailAutomationConfig?

    fun save(config: EmailAutomationConfig): EmailAutomationConfig
}
