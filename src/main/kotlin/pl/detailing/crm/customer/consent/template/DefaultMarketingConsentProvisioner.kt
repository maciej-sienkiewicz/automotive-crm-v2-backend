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
 * Seeds the bundled marketing-consent document for a studio that has none.
 *
 * Studio bez własnej zgody marketingowej nie zbiera żadnej — a bez niej nie wolno
 * wysłać ani SMS-a, ani maila z ofertą. Dlatego każde studio dostaje systemowy
 * dokument (resources/templates/zgody_marketingowe_default.pdf) podpięty do etapu
 * przyjęcia pojazdu, opcjonalny: klient podpisuje go raz, przy pierwszej wizycie.
 *
 * Dokument nie niesie logo ani danych żadnego studia — miejsca na dane
 * administratora zostają w nim jako nawiasy kwadratowe do uzupełnienia przez
 * studio (nowa wersja dokumentu w ustawieniach), tak jak systemowy protokół
 * przyjęcia nie niesie cudzej marki.
 *
 * Zasada nadrzędna: **nigdy nie nadpisujemy zgody, którą studio ma własną.**
 * Jeżeli istnieje aktywna zgoda obejmująca którykolwiek kanał marketingowy albo
 * ta systemowa została kiedyś dodana i wyłączona — wywołanie jest no-opem.
 */
@Service
class DefaultMarketingConsentProvisioner(
    private val consentDefinitionRepository: ConsentDefinitionRepository,
    private val consentTemplateRepository: ConsentTemplateRepository,
    private val s3ConsentStorageService: S3ConsentStorageService
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    companion object {
        const val DEFAULT_CONSENT_NAME = "Zgody marketingowe"
        const val DEFAULT_CONSENT_DESCRIPTION =
            "Systemowy dokument zgód marketingowych (SMS, e-mail, kontakt telefoniczny). " +
                "Zbierany jednorazowo przy przyjęciu pojazdu."
        const val DEFAULT_CONSENT_RESOURCE = "/templates/zgody_marketingowe_default.pdf"

        /**
         * Kolejność wyświetlania — protokół przyjęcia ma 0, więc zgoda staje za nim:
         * klient najpierw podpisuje dokument wizyty, potem to, co dotyczy marketingu.
         */
        const val DEFAULT_CONSENT_DISPLAY_ORDER = 10

        /** Synthetic author for system-provisioned rows. */
        val SYSTEM_USER_ID: UUID = UUID(0L, 0L)
    }

    /**
     * Ensure the studio has a marketing-consent document assigned to CHECK_IN.
     * Returns true when provisioning took place, false when the studio already had
     * its own consent (or had deactivated the system one).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun ensureDefaultMarketingConsent(studioId: StudioId): Boolean {
        val existing = consentDefinitionRepository.findAllByStudioId(studioId.value)

        // Studio ma własną zgodę marketingową — kanały są unikalne w obrębie studia,
        // więc dołożenie systemowej i tak zostałoby odrzucone przez tę samą regułę.
        if (existing.any { it.isActive && it.marketingChannels.isNotEmpty() }) return false

        // Systemowa zgoda już tu była i studio ją wyłączyło — to jest decyzja studia.
        if (existing.any { it.name == DEFAULT_CONSENT_NAME }) return false

        val definitionId = ConsentDefinitionId.random().value
        val now = Instant.now()

        consentDefinitionRepository.save(
            ConsentDefinitionEntity(
                id = definitionId,
                studioId = studioId.value,
                name = DEFAULT_CONSENT_NAME,
                description = DEFAULT_CONSENT_DESCRIPTION,
                stage = ProtocolStage.CHECK_IN,
                marketingChannels = mutableSetOf(MarketingChannel.EMAIL, MarketingChannel.SMS),
                displayOrder = DEFAULT_CONSENT_DISPLAY_ORDER,
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
        s3ConsentStorageService.uploadBytes(s3Key, loadBundledConsentBytes())

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
            "Default marketing consent provisioning: studio={} — seeded (definitionId={})",
            studioId, definitionId
        )
        return true
    }

    /**
     * Wgrywa aktualną treść systemowego dokumentu do studia, które dostało go
     * wcześniej — plik studia leży w S3 i sam się nie zmieni, więc bez tego
     * poprawki w dokumencie (dane administratora, układ) nigdy by nie dotarły.
     *
     * Rusza wyłącznie dla nietkniętej wersji systemowej: aktywny szablon musi być
     * wersją 1 założoną przez konto systemowe. Studio, które opublikowało własną
     * wersję dokumentu, zostaje przy swojej.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun refreshBundledConsentDocument(studioId: StudioId): Boolean {
        val definition = consentDefinitionRepository.findAllByStudioId(studioId.value)
            .firstOrNull { it.name == DEFAULT_CONSENT_NAME && it.createdBy == SYSTEM_USER_ID }
            ?: return false

        val activeTemplate = consentTemplateRepository
            .findActiveByDefinitionIdAndStudioId(definition.id, studioId.value)
            ?: return false

        if (activeTemplate.version != 1 || activeTemplate.createdBy != SYSTEM_USER_ID) return false

        s3ConsentStorageService.uploadBytes(activeTemplate.s3Key, loadBundledConsentBytes())
        logger.info(
            "Default marketing consent refresh: studio={} — bundled document re-uploaded (key={})",
            studioId, activeTemplate.s3Key
        )
        return true
    }

    /** Reads the bundled consent document from the classpath. */
    fun loadBundledConsentBytes(): ByteArray {
        val resource = javaClass.getResourceAsStream(DEFAULT_CONSENT_RESOURCE)
            ?: throw IllegalStateException(
                "Bundled default marketing consent missing from classpath: $DEFAULT_CONSENT_RESOURCE"
            )
        return resource.use { it.readBytes() }
    }
}
