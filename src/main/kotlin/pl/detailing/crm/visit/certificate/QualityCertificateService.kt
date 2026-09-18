package pl.detailing.crm.visit.certificate

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.customer.infrastructure.CustomerRepository
import pl.detailing.crm.product.infrastructure.ProductEntity
import pl.detailing.crm.product.infrastructure.ProductRepository
import pl.detailing.crm.product.infrastructure.VisitProductRepository
import pl.detailing.crm.shared.EntityNotFoundException
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import pl.detailing.crm.shared.ValidationException
import pl.detailing.crm.shared.VisitStatus
import pl.detailing.crm.studio.logo.CompanyLogoService
import pl.detailing.crm.studio.settings.StudioSettingsRepository
import pl.detailing.crm.user.signature.UserSignatureService
import pl.detailing.crm.visit.infrastructure.VisitRepository
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * Certyfikat jakości — dokument dla klienta po zakończonej wizycie.
 *
 * Nie jest zapisywany: powstaje na żądanie z tego, co pracownik zaznaczył w oknie, i
 * od razu leci do pobrania. Nie ma stanu do przechowywania, bo wybór usług i zaleceń
 * bywa inny przy każdym wydruku (inny klient, inna rozmowa), a same dane źródłowe
 * (usługi wizyty, użyte produkty) i tak żyją w wizycie.
 */
@Service
class QualityCertificateService(
    private val visitRepository: VisitRepository,
    private val customerRepository: CustomerRepository,
    private val studioSettingsRepository: StudioSettingsRepository,
    private val visitProductRepository: VisitProductRepository,
    private val productRepository: ProductRepository,
    private val companyLogoService: CompanyLogoService,
    private val userSignatureService: UserSignatureService,
    private val renderer: QualityCertificatePdfRenderer
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    companion object {
        private val WARSAW = ZoneId.of("Europe/Warsaw")
        private val DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy")
    }

    @Transactional(readOnly = true)
    fun generate(
        studioId: StudioId,
        userId: UserId,
        userFullName: String,
        visitId: UUID,
        request: GenerateQualityCertificateRequest
    ): QualityCertificateFile {
        val visit = visitRepository.findById(visitId).orElse(null)
            ?.takeIf { it.studioId == studioId.value }
            ?: throw EntityNotFoundException("Wizyta nie została znaleziona")

        // Certyfikat mówi „usługa wykonana, pojazd wydany" — przed wydaniem byłby
        // obietnicą, a nie potwierdzeniem.
        if (visit.status != VisitStatus.COMPLETED) {
            throw ValidationException("Certyfikat jakości wystawiamy dopiero po zakończeniu wizyty")
        }

        val selectedServices = request.serviceIds.mapNotNull { runCatching { UUID.fromString(it) }.getOrNull() }.toSet()
        val services = visit.serviceItems
            .filter { it.id in selectedServices }
            .map { it.serviceName.trim() }
            .filter { it.isNotBlank() }

        val usedProducts = resolveUsedProducts(studioId, visitId, request.productLinkIds) +
            resolveManualProducts(studioId, request.extraProducts)
        val recommended = resolveManualProducts(studioId, request.recommendations)

        val settings = studioSettingsRepository.findById(studioId.value).orElse(null)
        val customer = customerRepository.findByIdAndStudioId(visit.customerId, studioId.value)
        val customerName = listOfNotNull(customer?.firstName, customer?.lastName)
            .joinToString(" ") { it.trim() }
            .trim()
            .ifBlank { customer?.companyName?.trim().orEmpty() }

        val vehicle = listOf(visit.brandSnapshot, visit.modelSnapshot)
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .let { base ->
                val plate = visit.licensePlateSnapshot?.trim().orEmpty()
                if (plate.isBlank()) base else "$base · $plate"
            }

        val completedAt = visit.pickupDate ?: visit.actualCompletionDate ?: visit.scheduledDate

        val data = QualityCertificateData(
            providerName = settings?.name?.trim().orEmpty(),
            visitNumber = visit.visitNumber,
            vehicle = vehicle,
            customerName = customerName,
            completedOn = formatDate(completedAt),
            thankYou = thankYouParagraphs(customerName, vehicle),
            services = services,
            usedProducts = usedProducts,
            recommendedProducts = recommended,
            issuedByName = userFullName.trim(),
            issuedOn = LocalDate.now(WARSAW).format(DATE),
            logoPng = loadLogo(studioId),
            signaturePng = userSignatureService.downloadBytes(studioId, userId)
        )

        val bytes = renderer.render(data)
        logger.info(
            "Quality certificate generated: visit={} services={} products={} recommendations={} bytes={}",
            visit.visitNumber, services.size, usedProducts.size, recommended.size, bytes.size
        )
        return QualityCertificateFile(bytes, "certyfikat-jakosci-${asciiSlug(visit.visitNumber)}.pdf")
    }

    /**
     * Użyte produkty wybrane w oknie. Filtrujemy po identyfikatorze POWIĄZANIA, nie
     * produktu — ten sam preparat może być dopięty do wizyty dwa razy z różnymi notatkami,
     * a pracownik ma prawo pokazać klientowi tylko jedno z tych wystąpień.
     */
    private fun resolveUsedProducts(
        studioId: StudioId,
        visitId: UUID,
        linkIds: List<String>
    ): List<CertificateItem> {
        if (linkIds.isEmpty()) return emptyList()
        val selected = linkIds.mapNotNull { runCatching { UUID.fromString(it) }.getOrNull() }.toSet()
        if (selected.isEmpty()) return emptyList()

        val links = visitProductRepository.findByVisit(studioId.value, visitId).filter { it.id in selected }
        if (links.isEmpty()) return emptyList()

        val products = productRepository.findAllById(links.map { it.productId }).associateBy { it.id }
        return links.map { link ->
            val product = products[link.productId]
            CertificateItem(
                title = product?.let { displayName(it) } ?: "Produkt usunięty z katalogu",
                note = listOfNotNull(
                    product?.let { packageLabel(it) },
                    link.note?.trim()?.takeIf { it.isNotBlank() }
                ).joinToString(" · ").takeIf { it.isNotBlank() }
            )
        }
    }

    /**
     * Pozycje wpisane ręcznie w oknie — zalecenia i dopisane produkty użyte.
     *
     * Wskazanie na katalog daje nazwę i opakowanie prosto z karty produktu; wpis z ręki
     * przechodzi taki, jaki jest — pracujemy i polecamy też rzeczy spoza swojego katalogu.
     */
    private fun resolveManualProducts(
        studioId: StudioId,
        requested: List<CertificateProductRequest>
    ): List<CertificateItem> = requested.mapNotNull { entry ->
        val note = entry.note?.trim()?.takeIf { it.isNotBlank() }
        val product = entry.productId
            ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?.let { productRepository.findById(it).orElse(null) }
            // Cudzy wpis prywatny nie ma prawa pokazać nawet nazwy — ten sam warunek
            // widoczności co w katalogu produktów.
            ?.takeIf { it.ownerStudioId == null || it.ownerStudioId == studioId.value }

        when {
            product != null -> CertificateItem(
                title = displayName(product),
                note = listOfNotNull(packageLabel(product), note).joinToString(" · ").takeIf { it.isNotBlank() }
            )
            !entry.name.isNullOrBlank() -> CertificateItem(entry.name.trim(), note)
            else -> null
        }
    }

    /** „ADBL Glass Cleaner", a nie „ADBL ADBL Glass Cleaner" — marka tylko, gdy nie jest już w nazwie. */
    private fun displayName(product: ProductEntity): String {
        val name = product.name.trim()
        val brand = product.brand.trim()
        return when {
            brand.isBlank() -> name
            name.startsWith(brand, ignoreCase = true) -> name
            else -> "$brand $name"
        }
    }

    private fun packageLabel(product: ProductEntity): String? = runCatching {
        "${product.packageSizeValue.stripTrailingZeros().toPlainString()} ${product.packageSizeUnit.displayName}"
    }.getOrNull()

    private fun thankYouParagraphs(customerName: String, vehicle: String): List<String> {
        val greeting = if (customerName.isBlank()) {
            "Dziękujemy za zaufanie i wybranie naszego studia."
        } else {
            "$customerName — dziękujemy za zaufanie i wybranie naszego studia."
        }
        val vehiclePart = if (vehicle.isBlank()) "Państwa pojazdem" else "pojazdem $vehicle"
        return listOf(
            "$greeting Doceniamy, że jakość pracy jest dla Państwa równie ważna jak dla nas — " +
                "to ona decyduje o tym, jak długo efekt utrzyma się na lakierze i we wnętrzu.",
            "Poniżej znajduje się zestawienie prac wykonanych nad $vehiclePart, preparatów, " +
                "których do nich użyliśmy, oraz produktów, które polecamy do dalszej pielęgnacji. " +
                "W razie pytań o którykolwiek z nich — jesteśmy do dyspozycji."
        )
    }

    private fun loadLogo(studioId: StudioId): ByteArray? = runCatching {
        companyLogoService.loadDocumentLogo(studioId.value)?.printPng
    }.onFailure {
        logger.warn("Nie udało się wczytać logo studia na certyfikat: ${it.message}")
    }.getOrNull()

    private fun formatDate(instant: Instant): String =
        DATE.format(instant.atZone(WARSAW).toLocalDate())

    /**
     * Nazwa pliku idzie w nagłówku HTTP, więc musi być czysto ASCII — numer wizyty
     * potrafi nieść znaki, których nagłówek nie przeniesie bez kodowania.
     */
    private fun asciiSlug(value: String): String =
        value.lowercase()
            .map { ch -> if (ch.isLetterOrDigit() && ch.code < 128) ch else '-' }
            .joinToString("")
            .trim('-')
            .ifBlank { "wizyta" }
}
