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
import pl.detailing.crm.studio.settings.StudioSettingsEntity
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

        /**
         * Oświadczenie o zgodności preparatów — powód, dla którego ten dokument w ogóle
         * istnieje. Klient płaci między innymi za to, CZYM się przy jego aucie pracuje,
         * a tego po zakończonej usłudze nie da się z auta odczytać. Podpisane zdanie
         * zamienia wykaz w zobowiązanie.
         *
         * Treść stała, nie do edycji w oknie: oświadczenie, które każdy formułuje
         * po swojemu, przestaje cokolwiek znaczyć.
         */
        private const val PRODUCT_DECLARATION =
            "Oświadczamy, że przy tej realizacji pracowaliśmy wyłącznie preparatami " +
                "wymienionymi powyżej — w postaci oryginalnej, bez zamienników i bez " +
                "rozcieńczeń innych niż przewidziane przez producenta."

        /**
         * Zasady pielęgnacji prawdziwe przy KAŻDEJ realizacji.
         *
         * Celowo nie ma tu terminów utwardzania powłok ani zakazu mycia przez pierwsze dni:
         * to zależy od tego, co zrobiono, a nieprawdziwa instrukcja na dokumencie
         * z podpisem jest gorsza niż jej brak. Takie rzeczy wpisuje pracownik w polu
         * „zalecenia szczegółowe".
         */
        private val CARE_RULES = listOf(
            "Myj pojazd metodą dwóch wiader, szamponem o neutralnym pH. Myjnie automatyczne " +
                "ze szczotkami zostawiają na lakierze siatkę rys.",
            "Osuszaj miękką mikrofibrą lub sprężonym powietrzem. Woda pozostawiona do " +
                "odparowania zostawia osad z kamienia.",
            "Odchody ptaków, owady i żywicę usuwaj możliwie szybko. Zaschnięte wytrawiają " +
                "lakier i ślad po nich zostaje na stałe.",
            "Unikaj preparatów silnie alkalicznych i kwaśnych poza zastosowaniem, do którego " +
                "są przeznaczone. Skracają żywotność zabezpieczeń."
        )
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
            thankYou = openingParagraphs(vehicle),
            services = services,
            usedProducts = usedProducts,
            productDeclaration = PRODUCT_DECLARATION.takeIf { usedProducts.isNotEmpty() },
            recommendedProducts = recommended,
            careRules = CARE_RULES,
            careNote = request.careNote?.trim()?.takeIf { it.isNotBlank() },
            contactLine = contactLine(settings),
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

    /**
     * Akapity otwierające.
     *
     * Świadomie bez podziękowań w rodzaju „cieszymy się, że nas Państwo wybrali":
     * dokument, który zaczyna się od komplementu, czyta się jak ulotka, a ma być
     * dowodem. Drugi akapit mówi wprost, PO CO klient go dostaje — to jedyne zdanie,
     * które uzasadnia całą resztę kartki.
     */
    private fun openingParagraphs(vehicle: String): List<String> {
        val vehiclePart = if (vehicle.isBlank()) "pojazdu" else "pojazdu $vehicle"
        return listOf(
            "Dziękujemy za powierzenie nam $vehiclePart. Poniżej opisujemy, co przy nim " +
                "wykonaliśmy, jakimi preparatami pracowaliśmy i co robić, żeby uzyskany efekt " +
                "utrzymał się jak najdłużej.",
            "Zakres prac i wykaz preparatów wystawiamy na piśmie, bo po zakończonej usłudze " +
                "nie widać już, czym została wykonana. To jest Państwa kopia tej informacji."
        )
    }

    /** Dane kontaktowe studia w stopce — certyfikat zostaje u klienta na dłużej niż faktura. */
    private fun contactLine(settings: StudioSettingsEntity?): String? = listOfNotNull(
        settings?.phone?.trim()?.takeIf { it.isNotBlank() },
        settings?.email?.trim()?.takeIf { it.isNotBlank() },
        settings?.website?.trim()?.takeIf { it.isNotBlank() }
    ).joinToString("   ·   ").takeIf { it.isNotBlank() }

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
