package pl.detailing.crm.protocol.template

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import pl.detailing.crm.protocol.infrastructure.ProtocolFieldMappingRepository
import pl.detailing.crm.protocol.infrastructure.ProtocolRuleRepository
import pl.detailing.crm.protocol.infrastructure.ProtocolTemplateDefaultRevisionRepository
import pl.detailing.crm.protocol.infrastructure.ProtocolTemplateEntity
import pl.detailing.crm.protocol.infrastructure.ProtocolTemplateRepository
import pl.detailing.crm.protocol.infrastructure.S3ProtocolStorageService
import pl.detailing.crm.shared.StudioId
import java.util.UUID

/**
 * After an account reset had purged S3 behind the freshly seeded default protocols,
 * every studio row pointed at a key that no longer existed and each check-in ended in
 * NoSuchKey. The healer puts the bundled files back for system templates only.
 */
class DefaultProtocolTemplateProvisionerHealTest {

    private val templates: ProtocolTemplateRepository = mockk()
    private val storage: S3ProtocolStorageService = mockk(relaxed = true)
    private val provisioner = DefaultProtocolTemplateProvisioner(
        templates, mockk<ProtocolRuleRepository>(), mockk<ProtocolFieldMappingRepository>(), storage,
        mockk<ProtocolTemplateDefaultRevisionRepository>()
    )
    private val studio = StudioId(UUID.randomUUID())

    private fun template(
        name: String,
        isDefault: Boolean,
        createdBy: UUID,
        active: Boolean = true,
        key: String = "${studio.value}/protocols/templates/${UUID.randomUUID()}.pdf"
    ): ProtocolTemplateEntity = mockk {
        every { id } returns UUID.randomUUID()
        every { this@mockk.name } returns name
        every { this@mockk.isDefault } returns isDefault
        every { this@mockk.createdBy } returns createdBy
        every { isActive } returns active
        every { s3Key } returns key
    }

    private val checkInKey = "${studio.value}/protocols/templates/in.pdf"
    private val checkOutKey = "${studio.value}/protocols/templates/out.pdf"

    @Test
    fun `restores the bundled check-in and check-out files when their objects are gone`() {
        val checkIn = template(DefaultProtocolTemplateProvisioner.DEFAULT_TEMPLATE_NAME, true, DefaultProtocolTemplateProvisioner.SYSTEM_USER_ID, key = checkInKey)
        val checkOut = template(DefaultProtocolTemplateProvisioner.CHECK_OUT_TEMPLATE_NAME, false, DefaultProtocolTemplateProvisioner.SYSTEM_USER_ID, key = checkOutKey)
        every { templates.findAllByStudioId(studio.value) } returns listOf(checkIn, checkOut)
        every { storage.objectExists(any()) } returns false

        val restored = provisioner.healMissingSystemTemplateFiles(studio)

        assertEquals(2, restored)
        verify(exactly = 1) { storage.uploadBytes(checkInKey, any(), "application/pdf") }
        verify(exactly = 1) { storage.uploadBytes(checkOutKey, any(), "application/pdf") }
    }

    @Test
    fun `a present file is left alone`() {
        val checkIn = template(DefaultProtocolTemplateProvisioner.DEFAULT_TEMPLATE_NAME, true, DefaultProtocolTemplateProvisioner.SYSTEM_USER_ID, key = checkInKey)
        every { templates.findAllByStudioId(studio.value) } returns listOf(checkIn)
        every { storage.objectExists(checkInKey) } returns true

        assertEquals(0, provisioner.healMissingSystemTemplateFiles(studio))
        verify(exactly = 0) { storage.uploadBytes(any(), any(), any()) }
    }

    @Test
    fun `a studio-uploaded template is never overwritten with the bundled file`() {
        val custom = template("Mój własny protokół", false, UUID.randomUUID())
        every { templates.findAllByStudioId(studio.value) } returns listOf(custom)
        every { storage.objectExists(any()) } returns false

        assertEquals(0, provisioner.healMissingSystemTemplateFiles(studio))
        verify(exactly = 0) { storage.objectExists(any()) }
        verify(exactly = 0) { storage.uploadBytes(any(), any(), any()) }
    }

    @Test
    fun `an inactive default template is not restored`() {
        val disabled = template(DefaultProtocolTemplateProvisioner.DEFAULT_TEMPLATE_NAME, true, DefaultProtocolTemplateProvisioner.SYSTEM_USER_ID, active = false)
        every { templates.findAllByStudioId(studio.value) } returns listOf(disabled)
        every { storage.objectExists(any()) } returns false

        assertEquals(0, provisioner.healMissingSystemTemplateFiles(studio))
        verify(exactly = 0) { storage.uploadBytes(any(), any(), any()) }
    }
}
