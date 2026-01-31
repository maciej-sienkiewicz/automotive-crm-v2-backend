package pl.detailing.crm.customer.update

import java.time.Instant

data class UpdateNotesResult(
    val notes: String,
    val updatedAt: Instant
)
