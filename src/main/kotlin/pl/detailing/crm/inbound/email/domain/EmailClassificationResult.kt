package pl.detailing.crm.inbound.email.domain

sealed class EmailClassificationResult {

    data class LeadDetected(
        val extractedName: String?,
        val summary: String,
        val vehicleMake: String?,
        val vehicleModel: String?,
        val vehicleYear: Int?,
        val requestedServices: List<String>
    ) : EmailClassificationResult()

    data object NotALead : EmailClassificationResult()
}
