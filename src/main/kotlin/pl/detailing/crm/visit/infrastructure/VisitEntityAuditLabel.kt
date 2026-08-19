package pl.detailing.crm.visit.infrastructure

import pl.detailing.crm.visit.domain.VisitAuditLabel

/**
 * [VisitAuditLabel] for handlers that hold the entity rather than the domain object, so
 * naming a visit never costs a `toDomain()` round-trip just to read four strings.
 */
val VisitEntity.auditDisplayName: String
    get() = VisitAuditLabel.of(title, brandSnapshot, modelSnapshot, licensePlateSnapshot, visitNumber)
