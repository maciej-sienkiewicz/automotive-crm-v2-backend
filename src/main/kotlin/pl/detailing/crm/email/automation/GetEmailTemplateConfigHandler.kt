package pl.detailing.crm.email.automation

import org.springframework.stereotype.Service
import pl.detailing.crm.email.domain.EmailAutomationConfig
import pl.detailing.crm.email.domain.EmailAutomationConfigRepository
import pl.detailing.crm.shared.StudioId

@Service
class GetEmailTemplateConfigHandler(
    private val configRepository: EmailAutomationConfigRepository
) {
    fun handle(studioId: StudioId): EmailAutomationConfig =
        configRepository.findByStudioId(studioId) ?: EmailAutomationConfig.defaultFor(studioId)
}
