package pl.detailing.crm.inbound.email.domain

sealed class EmailClassificationResult {

    data class LeadDetected(
        val extractedName: String?,
        val summary: String
    ) : EmailClassificationResult()

    data object NotALead : EmailClassificationResult()
}
