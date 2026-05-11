package pl.detailing.crm.leads.estimation.domain

interface LeadAnalyzer {
    suspend fun analyze(
        leadMessage: String,
        preExtractedNeeds: List<String>,
        catalogServices: List<CatalogService>,
        preExtractedVehicleMake: String? = null,
        preExtractedVehicleModel: String? = null
    ): LeadAnalysisResult
}

data class CatalogService(
    val id: String,
    val name: String,
    val priceNet: Long,
    val vatRate: Int
)

data class LeadAnalysisResult(
    val extractedNeeds: List<String>,
    val matchedServiceIds: List<String>,
    val unmatchedNeeds: List<String>,
    val vehicleBrand: String? = null,
    val vehicleModel: String? = null,
    val summary: String? = null
)
