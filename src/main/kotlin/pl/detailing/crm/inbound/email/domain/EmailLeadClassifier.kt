package pl.detailing.crm.inbound.email.domain

interface EmailLeadClassifier {
    suspend fun classify(from: String, subject: String?, body: String): EmailClassificationResult
}
