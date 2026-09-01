package pl.detailing.crm.studio.reset

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Pilnuje, żeby "Wyczyść konto" naprawdę czyściło WSZYSTKO.
 *
 * Największym ryzykiem tej funkcji nie jest błąd w tym, co już usuwa, tylko encja
 * dopisana za pół roku, o której purge nie wie — konto "wyczyszczone", a dane klientów
 * dalej leżą w nowej tabeli. Ten test zamienia to przeoczenie w czerwony build, tym
 * samym mechanizmem co [pl.detailing.crm.shared.NoEnumCheckConstraintsTest]: skanuje
 * źródła zamiast wymagać bazy.
 *
 * Kontrakt: każda klasa @Entity musi albo występować w źródle StudioDataPurger /
 * StudioResetFinalizer (jest czyszczona lub resetowana), albo być świadomie wpisana
 * do [preserved] z uzasadnieniem. Nie ma trzeciej drogi.
 */
class StudioResetCoverageTest {

    private val mainSources = File("src/main/kotlin")

    /**
     * Encje, które reset ŚWIADOMIE zostawia. Dopisanie tu nowej pozycji to decyzja
     * produktowa — uzasadnij ją tak jak poniżej, nie "bo test nie przechodził".
     */
    private val preserved: Map<String, String> = mapOf(
        // Rozliczenia studia z platformą — plan, dodatki i historia płatności to relacja
        // studio ↔ operator, nie dane operacyjne studia.
        "StudioEntity" to "konto/tenant istnieje dalej",
        "StudioSubscriptionPlanEntity" to "aktywny plan przeżywa reset",
        "StudioAddOnEntity" to "wykupione dodatki przeżywają reset",
        "PendingPlanChangeEntity" to "zaplanowana zmiana planu to sprawa billingowa",
        "SubscriptionPaymentLogEntity" to "historia płatności wobec platformy",
        "PaymentOrderEntity" to "zamówienia płatności wobec platformy",
        // Zapłacone środki.
        "SmsCreditBalanceEntity" to "saldo SMS to pieniądze klienta",
        "SmsCreditTransactionEntity" to "historia kredytów SMS to pieniądze klienta",
        // Rejestr zdarzeń — reset zostawia w nim własny wpis CRITICAL.
        "AuditLogEntity" to "dziennik audytu jest rejestrem, przetrwa reset",
        // Telemetria platformy: operacyjna diagnostyka operatora, niewidoczna dla studia.
        "MetricEventEntity" to "telemetria platformy",
        "UserSessionEntity" to "telemetria platformy",
        "ErrorEventEntity" to "telemetria platformy",
        "ErrorGroupEntity" to "telemetria platformy",
        "ErrorGroupImpactEntity" to "telemetria platformy",
        "StudioDailySnapshotEntity" to "telemetria platformy",
        "StudioApiDailyEntity" to "telemetria platformy",
        "PlatformDailySnapshotEntity" to "dane globalne całej platformy",
        "ApiEndpointEntity" to "dane globalne całej platformy",
        "ApiEndpointDailyEntity" to "dane globalne całej platformy",
        // Globalne katalogi współdzielone między studiami.
        "PlanEntity" to "globalny katalog planów",
        "FeatureEntity" to "globalny katalog funkcji",
        "AddOnEntity" to "globalny katalog dodatków",
        "SmsCreditPackageEntity" to "globalny cennik pakietów SMS",
        "VehicleSegmentEntity" to "globalne dane referencyjne segmentów pojazdów",
        // Infrastruktura samego resetu i kont demo.
        "DemoAccountEntity" to "rejestr kont demo prowadzi DemoCleanupJob",
        "StudioResetJobEntity" to "przebieg resetu musi przeżyć własne wykonanie"
    )

    @Test
    fun `kazda encja jest sklasyfikowana - czyszczona, resetowana albo swiadomie zachowana`() {
        val allEntities = scanEntityClassNames()
        check(allEntities.size > 100) {
            "Skan encji znalazł tylko ${allEntities.size} klas — parser się rozjechał ze stylem kodu"
        }

        val purgerSource = resetModuleSource()
        val unclassified = allEntities
            // \b po obu stronach: "ManualServiceEntity" nie może zaliczyć "ServiceEntity".
            .filterNot { Regex("""\b${Regex.escape(it)}\b""").containsMatchIn(purgerSource) }
            .filterNot { preserved.containsKey(it) }
            .sorted()

        assertTrue(
            unclassified.isEmpty(),
            """
            Encje niesklasyfikowane przez "Wyczyść konto": ${unclassified.joinToString()}

            Każda nowa encja musi trafić do StudioDataPurger (dane studia do usunięcia),
            do StudioResetFinalizer (stan resetowany do domyślnych) albo — z uzasadnieniem —
            do listy `preserved` w tym teście. Inaczej reset zostawi dane, które obiecał usunąć.
            """.trimIndent()
        )
    }

    @Test
    fun `lista preserved nie zawiera nieistniejacych encji`() {
        val allEntities = scanEntityClassNames()
        val ghosts = preserved.keys.filterNot { allEntities.contains(it) }.sorted()

        assertTrue(
            ghosts.isEmpty(),
            "Lista `preserved` wymienia encje, których nie ma w kodzie (zmiana nazwy?): " +
                ghosts.joinToString()
        )
    }

    private fun resetModuleSource(): String {
        val purger = File(mainSources, "pl/detailing/crm/studio/reset/StudioDataPurger.kt")
        val finalizer = File(mainSources, "pl/detailing/crm/studio/reset/StudioResetFinalizer.kt")
        check(purger.exists() && finalizer.exists()) { "Nie znaleziono źródeł modułu studio/reset" }
        return purger.readText() + finalizer.readText()
    }

    private fun scanEntityClassNames(): Set<String> =
        mainSources.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                ENTITY_CLASS_REGEX.findAll(file.readText()).map { it.groupValues[1] }
            }
            .toSet()

    companion object {
        /**
         * `@Entity` i pierwsza deklaracja klasy po niej. Dystans do 4000 znaków obejmuje
         * @Table z długimi listami indeksów i KDoc między adnotacją a klasą (np.
         * AuditLogEntity) — styl całego repo.
         */
        private val ENTITY_CLASS_REGEX = Regex("""@Entity\b[\s\S]{0,4000}?\bclass\s+(\w+)""")
    }
}
