package pl.detailing.crm.config

import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.DependsOn
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.subscription.entitlement.FeatureKey
import pl.detailing.crm.subscription.entitlement.domain.AddOnKey
import pl.detailing.crm.subscription.entitlement.domain.PlanKey
import pl.detailing.crm.subscription.entitlement.infrastructure.*

/**
 * Synchronises the feature/plan/add-on catalog with the code-defined offer on startup.
 *
 * Unlike a plain insert-if-missing seeder, this component is the single source of truth
 * for the sales catalog: names, descriptions, prices, availability and feature mappings
 * are updated in place on every deployment, so a price change in code reaches the DB
 * without manual SQL. Rows whose keys are no longer part of the offer are removed
 * by [migrateLegacyKeys] before JPA ever tries to map them.
 *
 * Offer (gross, PLN / month):
 *   Plans:
 *     BASIC  99,00  — calendar & reservations, customer/vehicle DB with visit history
 *     FULL  299,00  — everything (all modules included)
 *   Add-on modules (only purchasable on top of BASIC):
 *     AI_LEAD_ASSISTANT     49,00
 *     INSTAGRAM_MONITORING  32,00
 *     CLIENT_COMMUNICATION  49,00
 *     MARKETING_CAMPAIGNS   29,00
 *     E_SIGNATURES          29,00
 *     FINANCE_MODULE        49,00
 *     STATISTICS_MODULE     19,00
 *
 * BASIC + all modules = 355,00 PLN, so the FULL bundle (299,00) is deliberately
 * cheaper than a self-assembled package — à la carte modules carry a premium.
 */
@Component
@DependsOn("databaseInitializer")
class EntitlementDataSeeder(
    private val featureRepo: FeatureJpaRepository,
    private val planRepo: PlanJpaRepository,
    private val addOnRepo: AddOnJpaRepository,
    private val jdbcTemplate: JdbcTemplate
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @PostConstruct
    @Transactional
    fun seed() {
        migrateLegacyKeys()
        val features = syncFeatures()
        syncPlans(features)
        syncAddOns(features)
        logger.info("Entitlement catalog synchronised successfully")
    }

    // ─── Legacy data migration ────────────────────────────────────────────────

    /**
     * Renames or removes rows created under the previous catalog so that
     * @Enumerated(STRING) columns never contain values absent from the enums:
     *   plan  EVERYTHING        → FULL
     *   addon SMS_EMAIL_MODULE  → CLIENT_COMMUNICATION
     *   addon EMPLOYEES_MODULE  → removed (module discontinued; employees ship with base)
     *   feature EMPLOYEES       → removed
     * Runs raw SQL so it works regardless of the current enum definitions.
     */
    private fun migrateLegacyKeys() {
        try {
            jdbcTemplate.update("UPDATE subscription_plans SET plan_key = 'FULL' WHERE plan_key = 'EVERYTHING'")
            jdbcTemplate.update("UPDATE subscription_add_ons SET add_on_key = 'CLIENT_COMMUNICATION' WHERE add_on_key = 'SMS_EMAIL_MODULE'")

            jdbcTemplate.update("""
                DELETE FROM studio_subscription_add_ons WHERE add_on_id IN
                    (SELECT id FROM subscription_add_ons WHERE add_on_key = 'EMPLOYEES_MODULE')
            """.trimIndent())
            jdbcTemplate.update("""
                DELETE FROM subscription_add_on_features WHERE add_on_id IN
                    (SELECT id FROM subscription_add_ons WHERE add_on_key = 'EMPLOYEES_MODULE')
            """.trimIndent())
            jdbcTemplate.update("DELETE FROM subscription_add_ons WHERE add_on_key = 'EMPLOYEES_MODULE'")

            jdbcTemplate.update("""
                DELETE FROM subscription_plan_features WHERE feature_id IN
                    (SELECT id FROM subscription_features WHERE feature_key = 'EMPLOYEES')
            """.trimIndent())
            jdbcTemplate.update("""
                DELETE FROM subscription_add_on_features WHERE feature_id IN
                    (SELECT id FROM subscription_features WHERE feature_key = 'EMPLOYEES')
            """.trimIndent())
            jdbcTemplate.update("DELETE FROM subscription_features WHERE feature_key = 'EMPLOYEES'")

            // Pending downgrades to a plan that no longer exists under its old name.
            jdbcTemplate.update("UPDATE pending_plan_changes SET from_plan_key = 'FULL' WHERE from_plan_key = 'EVERYTHING'")
            jdbcTemplate.update("UPDATE pending_plan_changes SET to_plan_key = 'FULL' WHERE to_plan_key = 'EVERYTHING'")

            // Billing audit log stores plan/add-on keys as strings too.
            jdbcTemplate.update("UPDATE subscription_payment_log SET plan_key = 'FULL' WHERE plan_key = 'EVERYTHING'")
            jdbcTemplate.update("UPDATE subscription_payment_log SET add_on_key = 'CLIENT_COMMUNICATION' WHERE add_on_key = 'SMS_EMAIL_MODULE'")
            jdbcTemplate.update("UPDATE subscription_payment_log SET add_on_key = NULL WHERE add_on_key = 'EMPLOYEES_MODULE'")
        } catch (e: Exception) {
            // Tables may not exist yet on a fresh database (ddl-auto=update creates them later
            // in the JPA bootstrap); nothing to migrate in that case.
            logger.debug("Legacy entitlement key migration skipped: {}", e.message)
        }
    }

    // ─── Catalog sync ─────────────────────────────────────────────────────────

    private fun syncFeatures(): Map<FeatureKey, FeatureEntity> {
        val catalog = mapOf(
            FeatureKey.CALENDAR             to "Kalendarz wizyt i zarządzanie rezerwacjami",
            FeatureKey.VISITS               to "Zarządzanie wizytami z pełną historią",
            FeatureKey.CUSTOMERS            to "Baza danych klientów",
            FeatureKey.VEHICLES             to "Rejestr pojazdów",
            FeatureKey.DOCUMENTS            to "Dokumenty i protokoły",
            FeatureKey.GALLERY              to "Galeria zdjęć",
            FeatureKey.AI_LEADS             to "Asystent AI przy obsłudze leadów",
            FeatureKey.INSTAGRAM_MONITORING to "Monitoring konkurencji na Instagramie",
            FeatureKey.SMS_EMAIL            to "Automatyzacja kontaktu z klientem poprzez SMS oraz e-mail",
            FeatureKey.CAMPAIGNS            to "Kampanie marketingowe SMS oraz e-mail",
            FeatureKey.E_SIGNATURES         to "Podpisy elektroniczne dokumentów",
            FeatureKey.FINANCE              to "Kontrola nad finansami (dokumenty finansowe, kasy)",
            FeatureKey.STATISTICS           to "Statystyki i raporty"
        )

        return catalog.map { (key, description) ->
            val existing = featureRepo.findByKey(key)
            val entity = if (existing != null) {
                existing.name = key.displayName
                existing.description = description
                featureRepo.save(existing)
            } else {
                featureRepo.save(FeatureEntity(key = key, name = key.displayName, description = description))
            }
            key to entity
        }.toMap()
    }

    private fun syncPlans(features: Map<FeatureKey, FeatureEntity>) {
        val basicFeatures = setOf(
            FeatureKey.CALENDAR, FeatureKey.VISITS, FeatureKey.CUSTOMERS,
            FeatureKey.VEHICLES, FeatureKey.DOCUMENTS, FeatureKey.GALLERY
        )

        upsertPlan(
            key = PlanKey.BASIC,
            name = "BASIC",
            description = "Kalendarz i zarządzanie rezerwacjami oraz baza klientów i pojazdów z pełną historią wizyt",
            monthlyPriceCents = 9_900L,
            displayOrder = 1,
            featureEntities = basicFeatures.map { features.getValue(it) }.toSet()
        )

        upsertPlan(
            key = PlanKey.FULL,
            name = "FULL",
            description = "Pełny dostęp do wszystkich modułów platformy w cenie niższej niż suma pojedynczych modułów",
            monthlyPriceCents = 29_900L,
            displayOrder = 2,
            featureEntities = features.values.toSet()
        )
    }

    private fun syncAddOns(features: Map<FeatureKey, FeatureEntity>) {
        data class ModuleSpec(
            val key: AddOnKey,
            val name: String,
            val description: String,
            val priceCents: Long,
            val feature: FeatureKey
        )

        val modules = listOf(
            ModuleSpec(
                AddOnKey.AI_LEAD_ASSISTANT, "Asystent AI przy obsłudze leadów",
                "Automatyczna kwalifikacja i obsługa leadów wspierana przez AI",
                4_900L, FeatureKey.AI_LEADS
            ),
            ModuleSpec(
                AddOnKey.INSTAGRAM_MONITORING, "Monitoring konkurencji na Instagramie",
                "Śledzenie profili konkurencji i analiz trendów na Instagramie",
                3_200L, FeatureKey.INSTAGRAM_MONITORING
            ),
            ModuleSpec(
                AddOnKey.CLIENT_COMMUNICATION, "Automatyzacja kontaktu z klientem",
                "Automatyczne powiadomienia SMS i e-mail: przypomnienia o wizytach, statusy zleceń",
                4_900L, FeatureKey.SMS_EMAIL
            ),
            ModuleSpec(
                AddOnKey.MARKETING_CAMPAIGNS, "Kampanie marketingowe SMS i E-mail",
                "Masowe kampanie SMS i e-mail do bazy klientów z segmentacją",
                2_900L, FeatureKey.CAMPAIGNS
            ),
            ModuleSpec(
                AddOnKey.E_SIGNATURES, "Podpisy elektroniczne",
                "Elektroniczne podpisywanie protokołów i dokumentów na tablecie",
                2_900L, FeatureKey.E_SIGNATURES
            ),
            ModuleSpec(
                AddOnKey.FINANCE_MODULE, "Kontrola nad finansami",
                "Dokumenty finansowe, faktury, kasy fiskalne i KSeF",
                4_900L, FeatureKey.FINANCE
            ),
            ModuleSpec(
                AddOnKey.STATISTICS_MODULE, "Statystyki",
                "Raporty przychodów, statystyki usług i analiza opóźnień",
                1_900L, FeatureKey.STATISTICS
            )
        )

        modules.forEach { spec ->
            upsertAddOn(
                key = spec.key,
                name = spec.name,
                description = spec.description,
                monthlyPriceCents = spec.priceCents,
                isAvailable = true,
                featureEntities = setOf(features.getValue(spec.feature))
            )
        }
    }

    // ─── Upsert helpers ───────────────────────────────────────────────────────

    private fun upsertPlan(
        key: PlanKey,
        name: String,
        description: String,
        monthlyPriceCents: Long,
        displayOrder: Int,
        featureEntities: Set<FeatureEntity>
    ) {
        val existing = planRepo.findByKey(key)
        if (existing != null) {
            existing.name = name
            existing.description = description
            existing.monthlyPriceGrossCents = monthlyPriceCents
            existing.displayOrder = displayOrder
            existing.isActive = true
            existing.features.clear()
            existing.features.addAll(featureEntities)
            planRepo.save(existing)
        } else {
            planRepo.save(
                PlanEntity(
                    key = key,
                    name = name,
                    description = description,
                    monthlyPriceGrossCents = monthlyPriceCents,
                    displayOrder = displayOrder,
                    features = featureEntities.toMutableSet()
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
        featureEntities: Set<FeatureEntity>
    ) {
        val existing = addOnRepo.findByKey(key)
        if (existing != null) {
            existing.name = name
            existing.description = description
            existing.monthlyPriceGrossCents = monthlyPriceCents
            existing.isAvailable = isAvailable
            existing.isActive = true
            existing.features.clear()
            existing.features.addAll(featureEntities)
            addOnRepo.save(existing)
        } else {
            addOnRepo.save(
                AddOnEntity(
                    key = key,
                    name = name,
                    description = description,
                    monthlyPriceGrossCents = monthlyPriceCents,
                    isAvailable = isAvailable,
                    features = featureEntities.toMutableSet()
                )
            )
        }
    }
}
