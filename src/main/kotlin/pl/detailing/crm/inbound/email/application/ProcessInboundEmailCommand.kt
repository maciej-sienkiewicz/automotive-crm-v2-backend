package pl.detailing.crm.inbound.email.application

data class ProcessInboundEmailCommand(
    val alias: String,
    val from: String,
    val subject: String?,
    val body: String
)
