package pl.detailing.crm.email.visitready

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import pl.detailing.crm.customer.infrastructure.CustomerRepository
import pl.detailing.crm.email.provider.EmailProvider
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.VisitId
import pl.detailing.crm.visit.infrastructure.VisitRepository

@Service
class SendVisitReadyForPickupEmailHandler(
    private val visitRepository: VisitRepository,
    private val customerRepository: CustomerRepository,
    private val emailProvider: EmailProvider
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    suspend fun handle(command: SendVisitReadyForPickupEmailCommand): Unit = withContext(Dispatchers.IO) {
        val visitEntity = visitRepository.findById(command.visitId.value).orElse(null)
        if (visitEntity == null) {
            logger.warn("SendVisitReadyForPickupEmail: visit not found [visitId={}]", command.visitId)
            return@withContext
        }

        val customerEntity = customerRepository.findByIdAndStudioId(
            visitEntity.customerId,
            visitEntity.studioId
        )
        if (customerEntity == null) {
            logger.warn("SendVisitReadyForPickupEmail: customer not found [customerId={}]", visitEntity.customerId)
            return@withContext
        }

        val recipientEmail = customerEntity.email
        if (recipientEmail.isNullOrBlank()) {
            return@withContext
        }

        val customerName = listOfNotNull(customerEntity.firstName, customerEntity.lastName)
            .joinToString(" ")
            .ifBlank { "Kliencie" }

        val vehicleName = "${visitEntity.brandSnapshot} ${visitEntity.modelSnapshot}"

        // Pobieramy listę usług z encji wizyty
        val services = visitEntity.serviceItems.map { it.serviceName }

        val body = buildBody(
            customerName = customerName,
            vehicleName = vehicleName,
            visitNumber = visitEntity.visitNumber,
            licensePlate = visitEntity.licensePlateSnapshot,
            services = services,
            paymentMethod = command.paymentMethod,
            warrantyInfo = command.warrantyInfo
        )

        val result = emailProvider.send(
            to = recipientEmail,
            subject = "Twój pojazd jest gotowy do odbioru! – $vehicleName",
            bodyText = body,
            attachments = emptyList()
        )

        if (result.success) {
            logger.info("SendVisitReadyForPickupEmail: email sent [to={} visitId={}]", recipientEmail, command.visitId)
        }
    }

    private fun buildBody(
        customerName: String,
        vehicleName: String,
        visitNumber: String,
        licensePlate: String?,
        services: List<String>,
        paymentMethod: String,
        warrantyInfo: String
    ): String {
        val plateInfo = if (!licensePlate.isNullOrBlank()) " ($licensePlate)" else ""

        // Formatowanie listy usług w punktach
        val servicesList = if (services.isNotEmpty()) {
            "\nWykonane usługi:\n" + services.joinToString("\n") { " • $it" }
        } else ""

        return """
            Szanowny/a $customerName,

            Mamy dobre wiadomości! Prace nad Twoim pojazdem $vehicleName$plateInfo zostały zakończone.
            Auto jest już gotowe i czeka na odbiór w naszym studiu.

            Numer wizyty: $visitNumber
            $servicesList

            Płatność:
            $paymentMethod

            Gwarancja:
            $warrantyInfo

            Zapraszamy po odbiór w godzinach otwarcia naszego serwisu. Do zobaczenia!

            Pozdrawiamy,
            Zespół DetailBoost
        """.trimIndent()
    }
}

data class SendVisitReadyForPickupEmailCommand(
    val visitId: VisitId,
    val studioId: StudioId,
    val paymentMethod: String = "Płatność kartą lub gotówką przy odbiorze.",
    val warrantyInfo: String = "Na wykonane usługi udzielamy standardowej gwarancji jakości zgodnie z regulaminem studia."
)