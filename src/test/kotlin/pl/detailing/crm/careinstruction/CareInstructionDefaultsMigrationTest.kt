package pl.detailing.crm.careinstruction

import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pl.detailing.crm.careinstruction.infrastructure.CareInstructionEntity
import pl.detailing.crm.careinstruction.infrastructure.CareInstructionRepository
import pl.detailing.crm.careinstruction.infrastructure.ServiceCareInstructionEntity
import pl.detailing.crm.careinstruction.infrastructure.ServiceCareInstructionRepository
import pl.detailing.crm.service.infrastructure.ServiceEntity
import pl.detailing.crm.service.infrastructure.ServiceRepository
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.studio.settings.StudioSettingsRepository
import java.io.File
import java.util.Optional
import java.util.UUID

/**
 * Domyślne instrukcje żyją w dwóch miejscach: w [DefaultCareInstructionProvisioner.DEFAULTS]
 * (nowe studia) i w migracji V166 (studia, które słownik już miały). Rozjazd znaczyłby,
 * że dwa studia drukują klientom inną treść pod tym samym tytułem.
 */
class CareInstructionDefaultsMigrationTest {

    private val migration = File("src/main/resources/db/migration")
        .listFiles { f -> f.name.startsWith("V166__") }!!
        .single()
        .readText()

    private fun sql(text: String) = "'" + text.replace("'", "''") + "'"

    @Test
    fun `V166 wstawia dokładnie te instrukcje, które zasiewa provisioner`() {
        assertEquals(3, DefaultCareInstructionProvisioner.DEFAULTS.size)
        DefaultCareInstructionProvisioner.DEFAULTS.forEach { default ->
            assertTrue(migration.contains(sql(default.title)), "brak tytułu w V166: ${default.title}")
            assertTrue(migration.contains(sql(default.content)), "inna treść w V166: ${default.title}")
            default.serviceKeywords.forEach { keyword ->
                assertTrue(migration.contains(sql("%$keyword%")), "brak wzorca usługi $keyword w V166")
            }
        }
    }

    @Test
    fun `V166 usuwa tylko nieruszone dawne wpisy - po tytule i treści naraz`() {
        assertTrue(migration.contains("ci.title = old_defaults.title"))
        assertTrue(migration.contains("ci.content = old_defaults.content"))
    }

    // ── Zasiew dla nowego studia ─────────────────────────────────────────────

    private val studio = StudioId(UUID.randomUUID())
    private val saved = mutableListOf<CareInstructionEntity>()
    private val links = mutableListOf<ServiceCareInstructionEntity>()

    private fun service(name: String): ServiceEntity = mockk {
        every { id } returns UUID.randomUUID()
        every { this@mockk.name } returns name
    }

    private val services = listOf(
        service("Powłoka ceramiczna 3 lata"),
        service("Folia PPF - pakiet przód"),
        service("Pranie tapicerki"),
        service("Czyszczenie i impregnacja skóry"),
        service("Mycie podstawowe"),
        service("Przyciemnianie szyb folią"),
    )

    private val provisioner = DefaultCareInstructionProvisioner(
        repository = mockk<CareInstructionRepository> {
            every { countByStudioId(any()) } returns 0L
            every { save(any()) } answers { firstArg<CareInstructionEntity>().also { saved += it } }
        },
        studioSettingsRepository = mockk<StudioSettingsRepository> {
            every { findById(any()) } returns Optional.empty()
            every { save(any()) } answers { firstArg() }
        },
        linkRepository = mockk<ServiceCareInstructionRepository> {
            every { save(any()) } answers { firstArg<ServiceCareInstructionEntity>().also { links += it } }
        },
        serviceRepository = mockk<ServiceRepository> {
            every { findActiveByStudioId(studio.value) } returns services
        },
    )

    private fun linkedServiceNames(title: String): Set<String> {
        val instruction = saved.single { it.title == title }
        val ids = links.filter { it.careInstructionId == instruction.id }.map { it.serviceId }.toSet()
        return services.filter { it.id in ids }.map { it.name }.toSet()
    }

    @Test
    fun `nowe studio - trzy instrukcje, żadna nie jest zaznaczana przy każdym certyfikacie`() {
        assertTrue(provisioner.ensureDefaults(studio))
        assertEquals(DefaultCareInstructionProvisioner.DEFAULTS.map { it.title }, saved.map { it.title })
        saved.forEach { assertFalse(it.isDefaultSelected, "${it.title} zaznaczana zawsze") }
    }

    @Test
    fun `nowe studio - instrukcje przypinają się do usług po nazwie`() {
        provisioner.ensureDefaults(studio)
        assertEquals(setOf("Powłoka ceramiczna 3 lata"), linkedServiceNames("Myjnia bezdotykowa a powłoka ceramiczna"))
        // „Przyciemnianie szyb folią" to też folia, ale nie PPF: porada o lancy nie ma tam sensu.
        assertEquals(setOf("Folia PPF - pakiet przód"), linkedServiceNames("Folia PPF na myjni bezdotykowej"))
        assertEquals(
            setOf("Pranie tapicerki", "Czyszczenie i impregnacja skóry"),
            linkedServiceNames("Kosmetyki do wnętrza, których unikać")
        )
        assertTrue(links.none { link -> services.single { it.id == link.serviceId }.name == "Mycie podstawowe" })
    }
}
