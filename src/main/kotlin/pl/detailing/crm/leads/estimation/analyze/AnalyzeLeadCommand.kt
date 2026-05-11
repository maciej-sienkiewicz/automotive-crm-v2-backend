package pl.detailing.crm.leads.estimation.analyze

import pl.detailing.crm.shared.LeadId
import pl.detailing.crm.shared.StudioId

data class AnalyzeLeadCommand(
    val leadId: LeadId,
    val studioId: StudioId,
    val preExtractedNeeds: List<String> = emptyList(),
    val preExtractedVehicleMake: String? = null,
    val preExtractedVehicleModel: String? = null,
    val overrideSummary: String? = null
)
