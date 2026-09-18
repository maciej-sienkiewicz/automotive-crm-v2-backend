package pl.detailing.crm.visit.certificate

/**
 * Wybór pracownika z okna „Certyfikat jakości".
 *
 * Nic tu nie jest domyślne po stronie serwera: na certyfikat trafia dokładnie to, co
 * zaznaczono w oknie. Kontekst biznesowy jest taki, że nie każda wykonana usługa i nie
 * każdy zużyty preparat mają się znaleźć w dokumencie dla klienta.
 */
data class GenerateQualityCertificateRequest(
    /** Identyfikatory pozycji usługowych wizyty (`VisitServiceItemEntity.id`). */
    val serviceIds: List<String> = emptyList(),
    /** Identyfikatory POWIĄZAŃ produkt↔wizyta (`VisitProductEntity.id`), nie produktów. */
    val productLinkIds: List<String> = emptyList(),
    /**
     * Produkty dopisane do „użytych" ręcznie w oknie certyfikatu — nie ma ich wśród
     * powiązań wizyty. Preparat bywa zużyty bez odnotowania w karcie wizyty, a klient
     * i tak ma prawo wiedzieć, czym pracowaliśmy.
     */
    val extraProducts: List<CertificateProductRequest> = emptyList(),
    /** Zalecenia — zawsze dodawane ręcznie, nigdy nie wynikają z danych wizyty. */
    val recommendations: List<CertificateProductRequest> = emptyList(),
    /**
     * Instrukcje pielęgnacyjne wybrane w oknie — identyfikatory ze słownika studia.
     * Okno zaznacza je wstępnie (domyślne + przypięte do zaznaczonych usług), ale
     * o tym, co trafi na dokument, decyduje ten wykaz, a nie serwer.
     */
    val careInstructionIds: List<String> = emptyList(),
    /**
     * Uwagi dopisane z ręki przy tym jednym certyfikacie — rzeczy, których nie da się
     * skonfigurować, bo dotyczą wyłącznie tej wizyty.
     */
    val careNote: String? = null
)

/**
 * Pozycja produktowa wpisana w oknie. Albo wskazanie na katalog (`productId`), albo sama
 * nazwa z ręki — polecamy i zużywamy też rzeczy, których nie mamy u siebie w katalogu.
 */
data class CertificateProductRequest(
    val productId: String? = null,
    val name: String? = null,
    val note: String? = null
)

/** Gotowy dokument: bajty PDF-a i nazwa pliku do pobrania. */
data class QualityCertificateFile(
    val bytes: ByteArray,
    val fileName: String
)

/** Jedna pozycja listy na certyfikacie: nazwa i opcjonalny dopisek pod nią. */
data class CertificateItem(
    val title: String,
    val note: String?
)

/** Wszystko, co rysuje [QualityCertificatePdfRenderer] — bez zależności od encji i JPA. */
data class QualityCertificateData(
    val providerName: String,
    val visitNumber: String,
    val vehicle: String,
    val customerName: String,
    val completedOn: String,
    /** Akapity otwierające — osobne pozycje, bo między nimi jest światło. */
    val thankYou: List<String>,
    val services: List<String>,
    val usedProducts: List<CertificateItem>,
    /**
     * Deklaracja autentyczności — sedno tego dokumentu. Imienny wykaz marek związany
     * podpisem wykonawcy jest sygnałem, którego nie da się podrobić ani zastąpić
     * przymiotnikiem; sam wykaz, bez deklaracji, jest tylko listą. `null`, gdy nie
     * wskazano żadnego materiału: nie ma wtedy czego poświadczać.
     */
    val productDeclaration: String?,
    val recommendedProducts: List<CertificateItem>,
    /** Instrukcje wybrane ze słownika studia — treści gotowe do wydruku. */
    val careRules: List<String>,
    /** Zalecenia szczegółowe tej realizacji, wpisane przez pracownika. */
    val careNote: String?,
    /** Telefon · e-mail · strona studia. Stopka dokumentu, który klient zatrzymuje. */
    val contactLine: String?,
    val issuedByName: String,
    val issuedOn: String,
    val logoPng: ByteArray?,
    val signaturePng: ByteArray?
)
