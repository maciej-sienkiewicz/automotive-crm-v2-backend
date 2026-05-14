package pl.detailing.crm.config

import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.DependsOn
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.subscription.entitlement.FeatureKey
import pl.detailing.crm.subscription.entitlement.domain.AddOnKey
import pl.detailing.crm.subscription.entitlement.domain.PlanKey
import pl.detailing.crm.subscription.entitlement.infrastructure.*

/**
 * Seeds the feature/plan/add-on catalog on startup if the tables are empty.
 *
 * This is idempotent — it only inserts rows that do not already exist,
 * identified by their enum keys. Safe to run on every deployment.
 *
 * Prices (gross, PLN):
 *   - BASIC:          99 PLN/month
 *   - EVERYTHING:    199 PLN/month
 *   - SMS_EMAIL:      49 PLN/month  (available)
 *   - FINANCE:        null           (coming soon — price TBD)
 *   - EMPLOYEES:      null           (coming soon — price TBD)
 */
@Component
@DependsOn("databaseInitializer")
class EntitlementDataSeeder(
    private val featureRepo: FeatureJpaRepository,
    private val planRepo: PlanJpaRepository,
    private val addOnRepo: AddOnJpaRepository
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @PostConstruct
    @Transactional
    fun seed() {
        val features = seedFeatures()
        seedPlans(features)
        seedAddOns(features)
        logger.info("Entitlement catalog seeded successfully")
    }

    // ─────────────────────────────────────────────────────────────────────────

    private fun seedFeatures(): Map<FeatureKey, FeatureEntity> {
        val catalog = mapOf(
            FeatureKey.CALENDAR  to "Kalendarz wizyt i rezerwacji",
            FeatureKey.VISITS    to "Zarządzanie wizytami serwisowymi",
            FeatureKey.CUSTOMERS to "Baza klientów",
            FeatureKey.VEHICLES  to "Rejestr pojazdów",
            FeatureKey.DOCUMENTS to "Dokumenty i protokoły",
            FeatureKey.GALLERY   to "Galeria zdjęć",
            FeatureKey.FINANCE   to "Moduł finansowy (kasy fiskalne, dokumenty finansowe)",
            FeatureKey.EMPLOYEES to "Moduł pracowniczy (umowy, płace, grafiki)",
            FeatureKey.SMS_EMAIL to "Automatyczne SMS-y i e-maile do klientów"
        )

        return catalog.map { (key, description) ->
            val existing = featureRepo.findByKey(key)
            if (existing != null) {
                key to existing
            } else {
                key to featureRepo.save(
                    FeatureEntity(key = key, name = key.displayName, description = description)
                )
            }
        }.toMap()
    }

    private fun seedPlans(features: Map<FeatureKey, FeatureEntity>) {
        val basicFeatures = setOf(
            FeatureKey.CALENDAR, FeatureKey.VISITS, FeatureKey.CUSTOMERS,
            FeatureKey.VEHICLES, FeatureKey.DOCUMENTS, FeatureKey.GALLERY
        )

        upsertPlan(
            key = PlanKey.BASIC,
            name = "Podstawowy",
            description = "Kompletny pakiet do codziennej obsługi warsztatu",
            monthlyPriceCents = 9900L,
            displayOrder = 1,
            featureEntities = basicFeatures.map { features.getValue(it) }.toMutableSet()
        )

        upsertPlan(
            key = PlanKey.EVERYTHING,
            name = "Wszystko",
            description = "Pełny dostęp do wszystkich modułów platformy",
            monthlyPriceCents = 19900L,
            displayOrder = 2,
            featureEntities = features.values.toMutableSet()
        )
    }

    private fun seedAddOns(features: Map<FeatureKey, FeatureEntity>) {
        upsertAddOn(
            key = AddOnKey.SMS_EMAIL_MODULE,
            name = "SMS i E-maile",
            description = "Automatyczne powiadomienia SMS i e-mail do klientów",
            monthlyPriceCents = 4900L,
            isAvailable = true,
            featureEntities = mutableSetOf(features.getValue(FeatureKey.SMS_EMAIL))
        )

        upsertAddOn(
            key = AddOnKey.FINANCE_MODULE,
            name = "Moduł Finansów",
            description = "Kasy fiskalne, faktury, dokumenty finansowe, KSeF — wkrótce",
            monthlyPriceCents = null,
            isAvailable = false,
            featureEntities = mutableSetOf(features.getValue(FeatureKey.FINANCE))
        )

        upsertAddOn(
            key = AddOnKey.EMPLOYEES_MODULE,
            name = "Moduł Pracowników",
            description = "Umowy, płace, grafiki pracy, urlopy — wkrótce",
            monthlyPriceCents = null,
            isAvailable = false,
            featureEntities = mutableSetOf(features.getValue(FeatureKey.EMPLOYEES))
        )
    }

    // ─── Upsert helpers ───────────────────────────────────────────────────────

    private fun upsertPlan(
        key: PlanKey,
        name: String,
        description: String,
        monthlyPriceCents: Long,
        displayOrder: Int,
        featureEntities: MutableSet<FeatureEntity>
    ) {
        val existing = planRepo.findByKey(key)
        if (existing == null) {
            planRepo.save(
                PlanEntity(
                    key = key,
                    name = name,
                    description = description,
                    monthlyPriceGrossCents = monthlyPriceCents,
                    displayOrder = displayOrder,
                    features = featureEntities
                )
            )
        }
    }

    private fun upsertAddOn(
        key: AddOnKey,
        name: String,
        description: String,
        monthlyPriceCents: Long?,
        isAvailable: Boolean,
        featureEntities: MutableSet<FeatureEntity>
    ) {
        val existing = addOnRepo.findByKey(key)
        if (existing == null) {
            addOnRepo.save(
                AddOnEntity(
                    key = key,
                    name = name,
                    description = description,
                    monthlyPriceGrossCents = monthlyPriceCents,
                    isAvailable = isAvailable,
                    features = featureEntities
                )
            )
        }
    }
}
