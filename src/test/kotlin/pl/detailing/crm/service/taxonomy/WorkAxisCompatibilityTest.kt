package pl.detailing.crm.service.taxonomy

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Macierze zgodności osi — każda komórka jest zdaniem po polsku i osobnym testem.
 *
 * To jest przewaga jawnej tablicy nad progiem na liczbach: dzisiejszy MIN_COVERAGE
 * był równie arbitralny, ale nie dało się napisać testu „czyszczenie nie dopasowuje
 * się do naprawy", bo próg nie zna pojęcia rzemiosła.
 */
class WorkAxisCompatibilityTest {

    // ── Operacje ─────────────────────────────────────────────────────────────

    /** Sedno przypadku 2: ekstraktor i chemia to nie igła i barwnik. */
    @Test
    fun `czyszczenie i naprawa to dwa rzemiosla`() {
        assertFalse(WorkAxisCompatibility.operationsComparable(ServiceOperation.REPAIR, ServiceOperation.CLEAN))
        assertFalse(WorkAxisCompatibility.operationsComparable(ServiceOperation.CLEAN, ServiceOperation.REPAIR))
    }

    @Test
    fun `ozonowanie i czyszczenie to jedna rozmowa o cenie`() {
        assertTrue(WorkAxisCompatibility.operationsComparable(ServiceOperation.CLEAN, ServiceOperation.SANITIZE))
    }

    @Test
    fun `folia i powloka to dwa rzemiosla`() {
        assertFalse(WorkAxisCompatibility.operationsComparable(ServiceOperation.APPLY_FILM, ServiceOperation.PROTECT))
    }

    @Test
    fun `korekta i mycie to dwa rzemiosla`() {
        assertFalse(WorkAxisCompatibility.operationsComparable(ServiceOperation.CORRECT, ServiceOperation.CLEAN))
    }

    /** Brak danych nie jest konfliktem — o odrzuceniu decyduje bramka, która dane MA. */
    @Test
    fun `unknown po ktorejkolwiek stronie pomija bramke operacji`() {
        assertTrue(WorkAxisCompatibility.operationsComparable(ServiceOperation.UNKNOWN, ServiceOperation.REPAIR))
        assertTrue(WorkAxisCompatibility.operationsComparable(ServiceOperation.REPAIR, ServiceOperation.UNKNOWN))
    }

    // ── Części ───────────────────────────────────────────────────────────────

    /** Tapicerka to jedno rzemiosło: fotel, boczek i podsufitka są wymienne cenowo. */
    @Test
    fun `naprawa fotela i boczka drzwi sa porownywalne`() {
        assertTrue(
            WorkAxisCompatibility.partsComparable(ServiceOperation.REPAIR, ServicePart.SEAT, ServicePart.DOOR_PANEL)
        )
        assertTrue(
            WorkAxisCompatibility.partsComparable(ServiceOperation.REPAIR, ServicePart.SEAT, ServicePart.HEADLINER)
        )
    }

    /** Sedno przypadku 1: próg bagażnika to nie całe nadwozie — 250 zł vs 18 000 zł. */
    @Test
    fun `folia na elemencie i na calym nadwoziu to osobne swiaty`() {
        assertFalse(
            WorkAxisCompatibility.partsComparable(ServiceOperation.APPLY_FILM, ServicePart.FULL_BODY, ServicePart.TRIM_PIECE)
        )
        assertFalse(
            WorkAxisCompatibility.partsComparable(ServiceOperation.APPLY_FILM, ServicePart.FULL_BODY, ServicePart.BODY_FRONT)
        )
    }

    @Test
    fun `drobne elementy folii sa miedzy soba porownywalne`() {
        assertTrue(
            WorkAxisCompatibility.partsComparable(ServiceOperation.APPLY_FILM, ServicePart.TRIM_PIECE, ServicePart.LAMPS)
        )
    }

    /**
     * Grupa wymienności należy do OPERACJI: przy naprawie fotel ~ boczek, ale przy
     * folii nie ma grupy kabinowej — panel nadwozia to panel nadwozia.
     */
    @Test
    fun `grupy czesci nie przeciekaja miedzy operacjami`() {
        assertFalse(
            WorkAxisCompatibility.partsComparable(ServiceOperation.APPLY_FILM, ServicePart.SEAT, ServicePart.DOOR_PANEL)
        )
    }

    @Test
    fun `unknown po ktorejkolwiek stronie pomija bramke czesci`() {
        assertTrue(
            WorkAxisCompatibility.partsComparable(ServiceOperation.REPAIR, ServicePart.UNKNOWN, ServicePart.SEAT)
        )
        assertTrue(
            WorkAxisCompatibility.partsComparable(ServiceOperation.REPAIR, ServicePart.SEAT, ServicePart.UNKNOWN)
        )
    }
}
