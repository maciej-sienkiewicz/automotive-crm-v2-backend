package pl.detailing.crm.studio.reset

import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import pl.detailing.crm.customer.consent.template.DefaultMarketingConsentProvisioner
import pl.detailing.crm.protocol.template.DefaultProtocolTemplateProvisioner
import pl.detailing.crm.role.permission.PermissionSnapshotCache
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import pl.detailing.crm.studio.settings.StudioSettingsEntity
import pl.detailing.crm.studio.settings.StudioSettingsRepository
import java.util.Optional
import java.util.UUID

class StudioResetFinalizerTest {

    private val studioSettingsRepository = mockk<StudioSettingsRepository>()
    private val protocolProvisioner = mockk<DefaultProtocolTemplateProvisioner>(relaxed = true)
    private val consentProvisioner = mockk<DefaultMarketingConsentProvisioner>(relaxed = true)
    private val permissionSnapshotCache = mockk<PermissionSnapshotCache>()

    private val finalizer = StudioResetFinalizer(
        studioSettingsRepository, protocolProvisioner, consentProvisioner, permissionSnapshotCache
    )

    private val studioId = UUID.randomUUID()
    private val ownerId = UUID.randomUUID()

    private fun context(wipeCompanyData: Boolean = false) =
        StudioResetContext(studioId, ownerId, wipeCompanyData)

    private fun existingSettings() = StudioSettingsEntity(
        studioId = studioId,
        name = "Auto Spa Kraków",
        taxId = "6772334455",
        street = "ul. Detailingowa 7",
        city = "Kraków",
        logoS3Key = "$studioId/logo.png",
        idleTimeoutSeconds = 300,
        visitCardSendByDefault = true,
        leadStagnantOurThresholdHours = 12,
        visitNumberFormat = "VIS-{YYYY}-{SEQ}"
    )

    private fun runStep(name: String, ctx: StudioResetContext) =
        finalizer.steps().single { it.name == name }.execute(ctx)

    @Test
    fun `reset ustawien przywraca wartosci domyslne, ale zachowuje dane firmy`() {
        every { studioSettingsRepository.findById(studioId) } returns Optional.of(existingSettings())
        val saved = slot<StudioSettingsEntity>()
        every { studioSettingsRepository.save(capture(saved)) } answers { firstArg() }

        runStep("Ustawienia domyślne", context(wipeCompanyData = false))

        val fresh = saved.captured
        // Tożsamość firmy przeżywa reset.
        assertEquals("Auto Spa Kraków", fresh.name)
        assertEquals("6772334455", fresh.taxId)
        assertEquals("ul. Detailingowa 7", fresh.street)
        assertEquals("Kraków", fresh.city)
        // Ustawienia behawioralne wracają do wartości z deklaracji encji.
        assertEquals(0, fresh.idleTimeoutSeconds)
        assertEquals(false, fresh.visitCardSendByDefault)
        assertEquals(48, fresh.leadStagnantOurThresholdHours)
        assertNull(fresh.visitNumberFormat)
        // Logo znika zawsze — pliki studia w S3 są czyszczone bezwarunkowo.
        assertNull(fresh.logoS3Key)
    }

    @Test
    fun `wipeCompanyData czysci takze dane firmy`() {
        every { studioSettingsRepository.findById(studioId) } returns Optional.of(existingSettings())
        val saved = slot<StudioSettingsEntity>()
        every { studioSettingsRepository.save(capture(saved)) } answers { firstArg() }

        runStep("Ustawienia domyślne", context(wipeCompanyData = true))

        assertNull(saved.captured.name)
        assertNull(saved.captured.taxId)
        assertNull(saved.captured.street)
        assertNull(saved.captured.city)
    }

    @Test
    fun `brak wiersza ustawien nie wywraca resetu`() {
        every { studioSettingsRepository.findById(studioId) } returns Optional.empty()
        val saved = slot<StudioSettingsEntity>()
        every { studioSettingsRepository.save(capture(saved)) } answers { firstArg() }

        runStep("Ustawienia domyślne", context())

        assertEquals(studioId, saved.captured.studioId)
        assertNull(saved.captured.name)
    }

    @Test
    fun `dane startowe to te same seedery co przy rejestracji`() {
        runStep("Dane startowe", context())

        verify { protocolProvisioner.ensureDefaultCheckInTemplate(StudioId(studioId)) }
        verify { protocolProvisioner.ensureDefaultCheckOutTemplate(StudioId(studioId)) }
        verify { consentProvisioner.ensureDefaultMarketingConsent(StudioId(studioId)) }
    }

    @Test
    fun `czyszczenie cache uprawnien obejmuje cale studio i ocalonego ownera`() {
        every { permissionSnapshotCache.evictStudio(any()) } just Runs
        every { permissionSnapshotCache.evictUser(any(), any()) } just Runs

        runStep("Pamięci podręczne", context())

        verify { permissionSnapshotCache.evictStudio(StudioId(studioId)) }
        verify { permissionSnapshotCache.evictUser(UserId(ownerId), StudioId(studioId)) }
    }

    @Test
    fun `awaria inwalidacji cache nie wywraca resetu - snapshoty maja TTL`() {
        every { permissionSnapshotCache.evictStudio(any()) } throws IllegalStateException("Redis down")

        runStep("Pamięci podręczne", context())
    }
}
