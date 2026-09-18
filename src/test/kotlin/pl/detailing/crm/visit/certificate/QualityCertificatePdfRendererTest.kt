package pl.detailing.crm.visit.certificate

import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Certyfikat powstaje w kodzie, nie z szablonu w bazie — te testy są jedynym miejscem,
 * które łapie, że dokument w ogóle się rysuje. Wywalenie się generatora widać dopiero
 * u klienta, więc sprawdzamy kolejno: polskie znaki, długie listy (łamanie stron) i
 * treść, której na certyfikacie być nie może.
 */
class QualityCertificatePdfRendererTest {

    private val renderer = QualityCertificatePdfRenderer()

    private fun data(
        services: List<String> = listOf("Korekta lakieru jednoetapowa"),
        used: List<CertificateItem> = listOf(CertificateItem("ADBL Glass Cleaner", "500 ml, na szyby")),
        recommended: List<CertificateItem> = listOf(CertificateItem("Kosa Shampoo", "co dwa tygodnie")),
        careNote: String? = null
    ) = QualityCertificateData(
        providerName = "Studio Detailingu Żółć Sp. z o.o.",
        visitNumber = "WIZ/2026/00123",
        vehicle = "Škoda Superb, WX 12345",
        customerName = "Zażółć Gęślą Jaźń",
        completedOn = "18.09.2026",
        thankYou = listOf("Dziękujemy, że powierzyli nam Państwo samochód Škoda Superb."),
        services = services,
        usedProducts = used,
        productDeclaration = ("Zaświadczamy, że wszystkie wymienione poniżej prace wykonaliśmy "
            + "z użyciem wyłącznie oryginalnych materiałów wskazanych producentów.")
            .takeIf { used.isNotEmpty() },
        recommendedProducts = recommended,
        careRules = listOf("Myj pojazd metodą dwóch wiader, szamponem o neutralnym pH."),
        careNote = careNote,
        contactLine = "+48 123 456 789   ·   kontakt@studio.pl   ·   studio.pl",
        issuedByName = "Michał Ćwikliński",
        issuedOn = "18.09.2026",
        logoPng = null,
        signaturePng = null
    )

    private fun textOf(bytes: ByteArray): String =
        Loader.loadPDF(bytes).use { PDFTextStripper().getText(it) }

    private fun pagesOf(bytes: ByteArray): Int =
        Loader.loadPDF(bytes).use { it.numberOfPages }

    @Test
    fun `rysuje certyfikat z polskimi znakami`() {
        val text = textOf(renderer.render(data()))

        assertTrue(text.contains("CERTYFIKAT JAKOŚCI"), "brak tytułu w: $text")
        assertTrue(text.contains("Zażółć Gęślą Jaźń"), "zgubione polskie znaki w nazwisku klienta")
        assertTrue(text.contains("Korekta lakieru jednoetapowa"), "brak wybranej usługi")
        assertTrue(text.contains("Studio Detailingu Żółć"), "bez logo nazwa studia musi wejść w jego miejsce")
        assertTrue(text.contains("ADBL Glass Cleaner"), "brak użytego produktu")
        assertTrue(text.contains("Kosa Shampoo"), "brak zalecenia")
        assertTrue(text.contains("Michał Ćwikliński"), "brak podpisu wystawiającego")
        assertTrue(
            text.contains("ZA JAKOŚĆ ODPOWIADA OSOBIŚCIE"),
            "imienna odpowiedzialność to sedno bloku podpisu: $text"
        )
    }

    @Test
    fun `nie pokazuje sekcji zaleceń, gdy nic nie polecono`() {
        val text = textOf(renderer.render(data(recommended = emptyList())))

        assertTrue(
            !text.contains("ZALECANE DO DALSZEJ PIELĘGNACJI"),
            "pusta rubryka zaleceń wygląda jak niedokończony dokument: $text"
        )
        assertTrue(text.contains("UŻYTE MATERIAŁY"), "sekcja użytych materiałów powinna zostać")
    }

    @Test
    fun `deklaracja autentyczności znika razem z pustym wykazem`() {
        val withProducts = textOf(renderer.render(data()))
        assertTrue(
            withProducts.contains("wyłącznie oryginalnych materiałów"),
            "deklaracja to sedno certyfikatu: $withProducts"
        )

        val without = textOf(renderer.render(data(used = emptyList())))
        assertTrue(
            !without.contains("wyłącznie oryginalnych materiałów"),
            "bez wykazu nie ma czego poświadczać: $without"
        )
    }

    /**
     * Badania nad zaufaniem są w tej sprawie jednoznaczne: autopromocja u firmy
     * kompetentnej obniża wiarygodność, a zdanie przypominające klientowi, że nie ma jak
     * zweryfikować naszej pracy, wzbudza dokładnie ten niepokój, który dokument ma gasić.
     * Ten test pilnuje, żeby takie zwroty nie wróciły tylnymi drzwiami.
     */
    @Test
    fun `dokument nie zawiera zwrotów osłabiających zaufanie`() {
        val text = textOf(renderer.render(data())).lowercase()

        listOf(
            "nie widać już",           // defensywne przypomnienie o braku weryfikacji
            "kopia tej informacji",    // biurokratyczne
            "preparat",                // ton apteczny zamiast rzemieślniczego
            "podstawa roszcze",        // zamienia prezent w instrument prawny
            "prosimy przechowywać",
            "wyższy poziom",           // puste superlatywy
            "doskonałoś"
        ).forEach { banned ->
            assertTrue(!text.contains(banned), "zakazany zwrot „$banned” w dokumencie: $text")
        }
    }

    @Test
    fun `zalecenia szczegółowe pracownika trafiają pod stałe zasady`() {
        val text = textOf(renderer.render(data(careNote = "Pierwsze mycie nie wcześniej niż 7 dni po wizycie.")))

        assertTrue(text.contains("JAK UTRZYMAĆ EFEKT"), "brak sekcji pielęgnacji: $text")
        assertTrue(text.contains("metodą dwóch wiader"), "brak stałych zasad")
        assertTrue(text.contains("Zalecenia dla tej realizacji"), "brak nagłówka zaleceń szczegółowych")
        assertTrue(text.contains("nie wcześniej niż 7 dni"), "zgubiona treść wpisana przez pracownika")
    }

    @Test
    fun `mówi wprost, gdy nie wskazano usług ani produktów`() {
        val text = textOf(renderer.render(data(services = emptyList(), used = emptyList())))

        assertTrue(text.contains("ZAKRES WYKONANYCH PRAC"), "belka sekcji ma zostać")
        assertTrue(text.contains("Nie wskazano wykonanych prac"), "pusta lista musi się tłumaczyć: $text")
    }

    @Test
    fun `długie listy przechodzą na kolejną stronę zamiast wyjeżdżać poza kartkę`() {
        val many = (1..60).map { "Usługa numer $it — pełna nazwa pozycji na liście" }
        val bytes = renderer.render(data(services = many))

        assertTrue(pagesOf(bytes) > 1, "60 pozycji nie mieści się na jednej stronie A4")
        val text = textOf(bytes)
        assertTrue(text.contains("Usługa numer 60"), "ostatnia pozycja zgubiona przy łamaniu stron")
        assertTrue(text.contains("ZA JAKOŚĆ ODPOWIADA OSOBIŚCIE"), "blok podpisu musi przetrwać łamanie stron")
    }

    @Test
    fun `znaki spoza fontu nie wywalają generowania`() {
        val exotic = CertificateItem("Полироль 🚗 wosk", "note")
        val bytes = renderer.render(data(used = listOf(exotic)))

        assertEquals(1, pagesOf(bytes))
        assertTrue(textOf(bytes).contains("wosk"), "czytelna część nazwy ma zostać")
    }

    @Test
    fun `bardzo długie słowo łamie się zamiast wyjechać poza margines`() {
        val url = "https://sklep.example.com/produkt/" + "a".repeat(400)
        val bytes = renderer.render(data(recommended = listOf(CertificateItem("Wosk", url))))

        assertTrue(pagesOf(bytes) >= 1)
        assertTrue(textOf(bytes).contains("https://sklep.example.com"), "początek adresu ma być widoczny")
    }
}
