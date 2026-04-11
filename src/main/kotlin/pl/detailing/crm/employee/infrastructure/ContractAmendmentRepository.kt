package pl.detailing.crm.employee.infrastructure

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface ContractAmendmentRepository : JpaRepository<ContractAmendmentEntity, UUID> {

    fun findByContractIdOrderByEffectiveFromDesc(contractId: UUID): List<ContractAmendmentEntity>

    fun findFirstByContractIdOrderByEffectiveFromDesc(contractId: UUID): ContractAmendmentEntity?
}
