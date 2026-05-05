package pl.detailing.crm.smscredits.infrastructure

import org.springframework.stereotype.Component
import pl.detailing.crm.smscredits.domain.SmsCreditPackage
import pl.detailing.crm.smscredits.domain.SmsCreditPackageRepository
import java.util.UUID

@Component
class SmsCreditPackageRepositoryAdapter(
    private val jpa: SmsCreditPackageJpaRepository
) : SmsCreditPackageRepository {

    override fun findAllActive(): List<SmsCreditPackage> =
        jpa.findAllByIsActiveTrueOrderBySortOrderAsc().map { it.toDomain() }

    override fun findById(id: UUID): SmsCreditPackage? =
        jpa.findById(id).orElse(null)?.toDomain()

    override fun save(pkg: SmsCreditPackage): SmsCreditPackage =
        jpa.save(SmsCreditPackageEntity.fromDomain(pkg)).toDomain()

    override fun existsAny(): Boolean =
        jpa.existsByIsActiveTrue()
}
