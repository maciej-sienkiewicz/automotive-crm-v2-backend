package pl.detailing.crm.email.provider

data class EmailAttachment(
    val fileName: String,
    val content: ByteArray,
    val contentType: String
)
