package pl.detailing.crm.employee.contract

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import pl.detailing.crm.employee.domain.ContractAmendment
import pl.detailing.crm.employee.infrastructure.ContractAmendmentRepository
import pl.detailing.crm.shared.*

@Service
class ListAmendmentsHandler(
    private val amendmentRepository: ContractAmendmentRepository
) {
    suspend fun handle(contractId: EmploymentContractId): List<ContractAmendment> =
        withContext(Dispatchers.IO) {
            amendmentRepository.findByContractIdOrderByEffectiveFromDesc(contractId.value)
                .map { it.toDomain() }
        }
}
