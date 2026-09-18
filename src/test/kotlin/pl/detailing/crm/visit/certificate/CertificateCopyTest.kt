package pl.detailing.crm.visit.certificate

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pl.detailing.crm.careinstruction.DefaultCareInstructionProvisioner

/**
 * Wartownik treści, którą studio wręcza klientowi.
 *
 * Certyfikat ma JEDEN wariant akapitu wstępnego i jedną deklarację autentyczności.
 * Obie są w kodzie, nie w ustawieniach, bo dokument o zmiennym tonie przestaje być
 * podpisem firmy pod jakością. Ten test pilnuje dwóch rzeczy naraz: że zakazane zwroty
 * nie wrócą tylnymi drzwiami przy kolejnej edycji i że interpunkcja zostaje zwyczajna.
 *
 * Myślnik em i kropka środkowa są dziś czytane jako ślad tekstu pisanego maszynowo.
 * Na dokumencie, którego cała wartość polega na tym, że stoi za nim człowiek
 * z nazwiskiem, to jest sygnał dokładnie odwrotny do zamierzonego.
 */
class CertificateCopyTest {

    private val authored: List<String> =
        QualityCertificateService.openingParagraphs("Skoda Superb") +
            QualityCertificateService.PRODUCT_DECLARATION +
            DefaultCareInstructionProvisioner.DEFAULTS.flatMap { (title, content) -> listOf(title, content) }

    @Test
    fun `treść autorska nie niesie znaków czytanych jako maszynowe`() {
        authored.forEach { text ->
            assertTrue(!text.contains('\u2014'), "myślnik em w: $text")
            assertTrue(!text.contains('\u2013'), "półpauza w: $text")
            assertTrue(!text.contains('\u00b7'), "kropka środkowa w: $text")
            assertTrue(!text.contains('\u2022'), "punktor w: $text")
            assertTrue(!text.contains('\u2026'), "wielokropek w: $text")
        }
    }

    @Test
    fun `treść autorska nie zawiera zwrotów osłabiających zaufanie`() {
        val banned = listOf(
            "nie widać już",           // defensywne przypomnienie o braku weryfikacji
            "kopia tej informacji",    // biurokratyczne
            "preparat",                // ton apteczny zamiast rzemieślniczego
            "podstawa roszcze",        // zamienia prezent w instrument prawny
            "prosimy przechowywać",
            "wyższy poziom",           // puste superlatywy
            "doskonałoś",
            "niezapomnian"
        )
        authored.forEach { text ->
            val lower = text.lowercase()
            banned.forEach { phrase ->
                assertTrue(!lower.contains(phrase), "zakazany zwrot \u201e$phrase\u201d w: $text")
            }
        }
    }

    @Test
    fun `akapit wstępny niesie deklarację oryginalności i nazwę pojazdu`() {
        val opening = QualityCertificateService.openingParagraphs("Porsche 911").single()

        assertTrue(opening.contains("Porsche 911"), "brak pojazdu w powitaniu: $opening")
        assertTrue(opening.contains("powierzyli nam Państwo"), "brak podziękowania za zaufanie")
        assertTrue(opening.contains("bez zamienników"), "deklaracja oryginalności musi paść już we wstępie")
        // Data wydania stoi w tabeli nagłówkowej; w akapicie byłaby zbędnym balastem.
        assertTrue(!opening.contains("20"), "akapit nie powinien nieść daty: $opening")
    }

    @Test
    fun `bez marki i modelu powitanie nadal się klei`() {
        val opening = QualityCertificateService.openingParagraphs("  ").single()
        assertTrue(opening.contains("powierzyli nam Państwo swój samochód"), opening)
    }

    /** „Zaświadczamy" to akt certyfikujący, nie ozdobnik: pada raz w całym dokumencie. */
    @Test
    fun `kancelaryzm pada dokładnie raz`() {
        val occurrences = authored.sumOf { text ->
            Regex("zaświadczamy", RegexOption.IGNORE_CASE).findAll(text).count()
        }
        assertTrue(occurrences == 1, "oczekiwano jednego „Zaświadczamy”, było $occurrences")
    }
}
