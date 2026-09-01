package pl.detailing.crm.studio.reset

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import pl.detailing.crm.customer.consent.template.DefaultMarketingConsentProvisioner
import pl.detailing.crm.protocol.template.DefaultProtocolTemplateProvisioner
import pl.detailing.crm.role.permission.PermissionSnapshotCache
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import pl.detailing.crm.studio.settings.StudioSettingsEntity
import pl.detailing.crm.studio.settings.StudioSettingsRepository
import java.time.Instant

/**
 * Końcówka resetu konta: przywrócenie ustawień domyślnych, ponowne zasianie danych
 * startowych i unieważnienie pamięci podręcznych.
 */
@Component
class StudioResetFinalizer(
    private val studioSettingsRepository: StudioSettingsRepository,
    private val defaultProtocolTemplateProvisioner: DefaultProtocolTemplateProvisioner,
    private val defaultMarketingConsentProvisioner: DefaultMarketingConsentProvisioner,
    private val permissionSnapshotCache: PermissionSnapshotCache
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    fun steps(): List<StudioResetStep> = listOf(
        StudioResetStep("Ustawienia domyślne") { ctx -> resetSettings(ctx) },
        StudioResetStep("Dane startowe") { ctx -> seedDefaults(ctx) },
        StudioResetStep("Pamięci podręczne") { ctx -> evictCaches(ctx) }
    )

    /**
     * Zastępuje wiersz `studio_settings` świeżym — wartości domyślne pochodzą wprost
     * z deklaracji [StudioSettingsEntity], czyli z tego samego źródła, które definiuje
     * stan nowo założonego konta. Dane firmy (nazwa, NIP, adres...) domyślnie przeżywają
     * reset — ich utrata byłaby zaskoczeniem; użytkownik może je świadomie wyczyścić
     * przełącznikiem "usuń też dane firmy". Logo znika zawsze, bo pliki studia w S3
     * są czyszczone bezwarunkowo.
     */
    private fun resetSettings(ctx: StudioResetContext) {
        val existing = studioSettingsRepository.findById(ctx.studioId).orElse(null)
        val fresh = StudioSettingsEntity(studioId = ctx.studioId, updatedAt = Instant.now())

        if (!ctx.wipeCompanyData && existing != null) {
            fresh.name = existing.name
            fresh.taxId = existing.taxId
            fresh.regon = existing.regon
            fresh.street = existing.street
            fresh.postalCode = existing.postalCode
            fresh.city = existing.city
            fresh.phone = existing.phone
            fresh.email = existing.email
            fresh.website = existing.website
            fresh.bankAccount = existing.bankAccount
        }

        studioSettingsRepository.save(fresh)
    }

    /**
     * Te same idempotentne seedery, które zasilają konto przy rejestracji
     * (patrz [pl.detailing.crm.subscription.SubscriptionService.createStudio]) —
     * dzięki temu "po resecie" i "po rejestracji" to z definicji ten sam stan.
     */
    private fun seedDefaults(ctx: StudioResetContext) {
        val studioId = StudioId(ctx.studioId)
        defaultProtocolTemplateProvisioner.ensureDefaultCheckInTemplate(studioId)
        defaultProtocolTemplateProvisioner.ensureDefaultCheckOutTemplate(studioId)
        defaultMarketingConsentProvisioner.ensureDefaultMarketingConsent(studioId)
    }

    private fun evictCaches(ctx: StudioResetContext) {
        try {
            permissionSnapshotCache.evictStudio(StudioId(ctx.studioId))
            permissionSnapshotCache.evictUser(UserId(ctx.keepUserId), StudioId(ctx.studioId))
        } catch (e: Exception) {
            // Snapshoty uprawnień mają TTL — nieudana inwalidacja opóźnia spójność,
            // ale nie może zawalić całego resetu.
            logger.warn("Permission cache eviction failed for studio {}: {}", ctx.studioId, e.message)
        }
    }
}
