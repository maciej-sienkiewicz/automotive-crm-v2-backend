package pl.detailing.crm.smscredits.infrastructure

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface SmsCreditPackageJpaRepository : JpaRepository<SmsCreditPackageEntity, UUID> {

    fun findAllByIsActiveTrueOrderBySortOrderAsc(): List<SmsCreditPackageEntity>

    fun existsByIsActiveTrue(): Boolean
}
