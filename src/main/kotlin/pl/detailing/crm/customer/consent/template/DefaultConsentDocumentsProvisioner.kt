package pl.detailing.crm.customer.consent.template

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.customer.consent.infrastructure.ConsentDefinitionEntity
import pl.detailing.crm.customer.consent.infrastructure.ConsentDefinitionRepository
import pl.detailing.crm.customer.consent.infrastructure.ConsentTemplateEntity
import pl.detailing.crm.customer.consent.infrastructure.ConsentTemplateRepository
import pl.detailing.crm.customer.consent.infrastructure.S3ConsentStorageService
import pl.detailing.crm.shared.ConsentDefinitionId
import pl.detailing.crm.shared.ConsentTemplateId
import pl.detailing.crm.shared.MarketingChannel
import pl.detailing.crm.shared.ProtocolStage
import pl.detailing.crm.shared.StudioId
import java.time.Instant
import java.util.UUID

/**
 * Seeds the bundled consent documents for a studio that has none of its own.
 *
 * Dwa dokumenty, obydwa na etapie przyjęcia pojazdu, obydwa zbierane raz na klienta:
 *
 *  - **Oświadczenie RODO** — obowiązkowe. Bez informacji o przetwarzaniu danych
 *    studio nie ma na czym oprzeć obsługi klienta, więc dokument nie jest wyborem.
 *  - **Zgody marketingowe** — dobrowolne. Bez nich wolno obsłużyć klienta, nie wolno
 *    tylko wysyłać mu ofert.
 *
 * Żaden z nich nie niesie logo ani danych konkretnego studia — dane administratora
 * wchodzą w pola formularza dopiero przy generowaniu dokumentu do podpisu, z ustawień
 * firmy danego studia.
 *
 * Zasada nadrzędna: **nigdy nie nadpisujemy dokumentu, który studio ma własny.**
 * Gdy studio ma aktywną zgodę tego rodzaju albo systemową kiedyś wyłączyło —
 * wywołanie jest no-opem.
 */
@Service
class DefaultConsentDocumentsProvisioner(
    private val consentDefinitionRepository: ConsentDefinitionRepository,
    private val consentTemplateRepository: ConsentTemplateRepository,
    private val s3ConsentStorageService: S3ConsentStorageService
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    companion object {
        const val MARKETING_CONSENT_NAME = "Zgody marketingowe"
        const val MARKETING_CONSENT_DESCRIPTION =
            "Systemowy dokument zgód marketingowych (SMS, e-mail, kontakt telefoniczny). " +
                "Zbierany jednorazowo przy przyjęciu pojazdu."
        const val MARKETING_CONSENT_RESOURCE = "/templates/zgody_marketingowe_default.pdf"

        const val RODO_CONSENT_NAME = "Oświadczenie RODO"
        const val RODO_CONSENT_DESCRIPTION =
            "Systemowe oświadczenie o zapoznaniu się z klauzulą informacyjną RODO. " +
                "Zbierane jednorazowo przy przyjęciu pojazdu."
        const val RODO_CONSENT_RESOURCE = "/templates/oswiadczenie_rodo_default.pdf"

        /**
         * Kolejność wyświetlania. Protokół przyjęcia ma 0, więc dokumenty zgód stają
         * za nim; RODO przed marketingiem, bo obowiązkowe idzie przed dobrowolnym.
         */
        const val RODO_DISPLAY_ORDER = 5
        const val MARKETING_DISPLAY_ORDER = 10

        /** Synthetic author for system-provisioned rows. */
        val SYSTEM_USER_ID: UUID = UUID(0L, 0L)
    }

    /** Opis jednego dokumentu systemowego — jedyne, czym różnią się oba przebiegi. */
    private data class BundledConsent(
        val name: String,
        val description: String,
        val resource: String,
        val displayOrder: Int,
        val isMandatory: Boolean,
        val marketingChannels: Set<MarketingChannel>
    )

    private val marketing = BundledConsent(
        name = MARKETING_CONSENT_NAME,
        description = MARKETING_CONSENT_DESCRIPTION,
        resource = MARKETING_CONSENT_RESOURCE,
        displayOrder = MARKETING_DISPLAY_ORDER,
        isMandatory = false,
        marketingChannels = setOf(MarketingChannel.EMAIL, MarketingChannel.SMS)
    )

    private val rodo = BundledConsent(
        name = RODO_CONSENT_NAME,
        description = RODO_CONSENT_DESCRIPTION,
        resource = RODO_CONSENT_RESOURCE,
        displayOrder = RODO_DISPLAY_ORDER,
        isMandatory = true,
        marketingChannels = emptySet()
    )

    /**
     * Ensure the studio has the marketing-consent document assigned to CHECK_IN.
     * Returns true when provisioning took place.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun ensureDefaultMarketingConsent(studioId: StudioId): Boolean = ensure(studioId, marketing)

    /**
     * Ensure the studio has the RODO statement assigned to CHECK_IN.
     * Returns true when provisioning took place.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun ensureDefaultRodoConsent(studioId: StudioId): Boolean = ensure(studioId, rodo)

    private fun ensure(studioId: StudioId, spec: BundledConsent): Boolean {
        val existing = consentDefinitionRepository.findAllByStudioId(studioId.value)

        // Dokument systemowy już tu był — także wtedy, gdy studio go wyłączyło.
        // Wyłączenie jest decyzją studia i nie wracamy do niej przy każdym starcie.
        if (existing.any { it.name == spec.name }) return false

        // Studio ma własną zgodę obejmującą te same kanały marketingowe — kanały są
        // unikalne w obrębie studia, więc systemowa i tak zostałaby odrzucona.
        if (spec.marketingChannels.isNotEmpty() &&
            existing.any { it.isActive && it.marketingChannels.any { channel -> channel in spec.marketingChannels } }
        ) return false

        val definitionId = ConsentDefinitionId.random().value
        val now = Instant.now()

        consentDefinitionRepository.save(
            ConsentDefinitionEntity(
                id = definitionId,
                studioId = studioId.value,
                name = spec.name,
                description = spec.description,
                stage = ProtocolStage.CHECK_IN,
                marketingChannels = spec.marketingChannels.toMutableSet(),
                displayOrder = spec.displayOrder,
                isMandatory = spec.isMandatory,
                isActive = true,
                createdBy = SYSTEM_USER_ID,
                updatedBy = SYSTEM_USER_ID,
                createdAt = now,
                updatedAt = now
            )
        )

        // Plik ląduje w S3 zanim powstanie wiersz wersji: szablon bez pliku byłby
        // dokumentem, którego nie da się ani wyświetlić, ani podpisać.
        val s3Key = s3ConsentStorageService.buildS3Key(studioId.value, definitionId, 1)
        s3ConsentStorageService.uploadBytes(s3Key, loadBundledBytes(spec.resource))

        consentTemplateRepository.save(
            ConsentTemplateEntity(
                id = ConsentTemplateId.random().value,
                studioId = studioId.value,
                definitionId = definitionId,
                version = 1,
                s3Key = s3Key,
                isActive = true,
                requiresResign = false,
                createdBy = SYSTEM_USER_ID,
                createdAt = now
            )
        )

        logger.info(
            "Default consent provisioning: studio={} — '{}' seeded (definitionId={})",
            studioId, spec.name, definitionId
        )
        return true
    }

    /**
     * Wgrywa aktualną treść dokumentów systemowych do studia, które dostało je
     * wcześniej — kopia studia leży w S3 i sama się nie zmieni, więc bez tego
     * poprawki w treści (pola na dane administratora, układ) nigdy by nie dotarły.
     *
     * Rusza wyłącznie dla nietkniętej wersji systemowej: aktywny szablon musi być
     * wersją 1 założoną przez konto systemowe. Studio, które opublikowało własną
     * wersję dokumentu, zostaje przy swojej.
     *
     * Zwraca liczbę odświeżonych dokumentów.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun refreshBundledDocuments(studioId: StudioId): Int {
        val definitions = consentDefinitionRepository.findAllByStudioId(studioId.value)
        var refreshed = 0

        for (spec in listOf(rodo, marketing)) {
            val definition = definitions
                .firstOrNull { it.name == spec.name && it.createdBy == SYSTEM_USER_ID }
                ?: continue

            val activeTemplate = consentTemplateRepository
                .findActiveByDefinitionIdAndStudioId(definition.id, studioId.value)
                ?: continue

            if (activeTemplate.version != 1 || activeTemplate.createdBy != SYSTEM_USER_ID) continue

            s3ConsentStorageService.uploadBytes(activeTemplate.s3Key, loadBundledBytes(spec.resource))
            refreshed++
            logger.info(
                "Default consent refresh: studio={} — '{}' re-uploaded (key={})",
                studioId, spec.name, activeTemplate.s3Key
            )
        }
        return refreshed
    }

    /** Reads a bundled consent document from the classpath. */
    fun loadBundledBytes(resource: String): ByteArray {
        val stream = javaClass.getResourceAsStream(resource)
            ?: throw IllegalStateException("Bundled consent document missing from classpath: $resource")
        return stream.use { it.readBytes() }
    }
}
