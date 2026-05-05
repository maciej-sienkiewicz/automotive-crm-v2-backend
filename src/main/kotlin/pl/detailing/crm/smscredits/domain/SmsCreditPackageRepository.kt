package pl.detailing.crm.smscredits.domain

import java.util.UUID

interface SmsCreditPackageRepository {
    fun findAllActive(): List<SmsCreditPackage>
    fun findById(id: UUID): SmsCreditPackage?
    fun save(pkg: SmsCreditPackage): SmsCreditPackage
    fun existsAny(): Boolean
}
