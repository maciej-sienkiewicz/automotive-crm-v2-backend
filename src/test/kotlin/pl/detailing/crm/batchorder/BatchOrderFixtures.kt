package pl.detailing.crm.batchorder

import pl.detailing.crm.batchorder.infrastructure.BatchContractorEntity
import pl.detailing.crm.batchorder.infrastructure.BatchOrderEntryEntity
import pl.detailing.crm.batchorder.infrastructure.ServiceItemEmbeddable
import pl.detailing.crm.batchorder.infrastructure.SettlementStamp
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

internal val STUDIO: UUID = UUID.fromString("00000000-0000-0000-0000-00000000000a")

internal fun contractor(name: String, id: UUID = UUID.randomUUID()) = BatchContractorEntity(
    id = id,
    studioId = STUDIO,
    name = name,
    taxId = null,
    address = null,
    contactPersonName = null,
    email = null,
    phone = null,
    notes = null,
    createdAt = Instant.parse("2026-01-01T00:00:00Z"),
    updatedAt = Instant.parse("2026-01-01T00:00:00Z")
)

/** Usługa za 1900,00 zł wpisana od strony brutto — netto 154472 gr, z którego 190000 gr nie wychodzi. */
internal fun grossTypedService(name: String = "Powłoka ceramiczna") =
    ServiceItemEmbeddable(name = name, netAmountCents = 154_472, grossAmountCents = 190_000, vatRate = 23)

internal fun entry(
    contractorId: UUID,
    date: String,
    closed: Boolean = false,
    createdAt: String = "${date}T10:00:00Z",
    services: List<ServiceItemEmbeddable> = listOf(grossTypedService()),
    id: UUID = UUID.randomUUID()
) = BatchOrderEntryEntity(
    id = id,
    studioId = STUDIO,
    contractorId = contractorId,
    serviceDate = LocalDate.parse(date),
    vehicleMake = "Skoda",
    vehicleModel = "Octavia",
    vehicleLicensePlate = "WX 12345",
    vehicleVin = "TMBJJ7NE0J0123456",
    services = services.toMutableList(),
    notes = null,
    isClosed = closed,
    closeHistoryId = if (closed) UUID.randomUUID() else null,
    createdAt = Instant.parse(createdAt),
    updatedAt = Instant.parse(createdAt)
)

internal fun stamp(contractorId: UUID, from: String, to: String, closedAt: String) = SettlementStamp(
    contractorId = contractorId,
    fromDate = LocalDate.parse(from),
    toDate = LocalDate.parse(to),
    closedAt = Instant.parse(closedAt)
)
