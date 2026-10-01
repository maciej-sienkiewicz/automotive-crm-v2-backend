package pl.detailing.crm.subscription.entitlement.infrastructure

import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import pl.detailing.crm.subscription.entitlement.FeatureKey
import pl.detailing.crm.subscription.entitlement.domain.AddOnKey
import pl.detailing.crm.subscription.entitlement.domain.PlanKey
import java.time.Instant
import java.util.UUID

interface FeatureJpaRepository : JpaRepository<FeatureEntity, UUID> {
    fun findByKey(key: FeatureKey): FeatureEntity?
    fun findAllByKeyIn(keys: Collection<FeatureKey>): List<FeatureEntity>
}

interface PlanJpaRepository : JpaRepository<PlanEntity, UUID> {
    fun findByKey(key: PlanKey): PlanEntity?
    fun findAllByIsActiveTrue(): List<PlanEntity>
}

interface AddOnJpaRepository : JpaRepository<AddOnEntity, UUID> {
    fun findByKey(key: AddOnKey): AddOnEntity?
    fun findAllByIsActiveTrue(): List<AddOnEntity>
    fun findAllByKeyIn(keys: Collection<AddOnKey>): List<AddOnEntity>
}

interface StudioSubscriptionPlanRepository : JpaRepository<StudioSubscriptionPlanEntity, UUID> {
    fun findByStudioId(studioId: UUID): StudioSubscriptionPlanEntity?

    /**
     * Wiersz planu, jeśli go nie ma — rozstrzyga baza (`ON CONFLICT`), nie SELECT przed
     * INSERT-em. Dwa równoległe „pierwsze przypisania" (trial + zakup, duplikat notyfikacji)
     * kończyły się wcześniej `DataIntegrityViolationException` na unikacie studio_id
     * (audyt, D1). `flushAutomatically`: studio zapisane chwilę wcześniej w tej samej
     * transakcji musi już być w bazie, bo klucz obcy z V172 go wymaga.
     */
    @Modifying(flushAutomatically = true)
    @Query(
        value = """
            INSERT INTO studio_subscription_plans (id, studio_id, plan_id, activated_at, created_at, version)
            VALUES (:id, :studioId, :planId, :now, :now, 0)
            ON CONFLICT (studio_id) DO NOTHING
        """,
        nativeQuery = true
    )
    fun insertIfAbsent(
        @Param("id") id: UUID,
        @Param("studioId") studioId: UUID,
        @Param("planId") planId: UUID,
        @Param("now") now: Instant
    ): Int

    @Query("""
        SELECT s FROM StudioSubscriptionPlanEntity s
        LEFT JOIN FETCH s.activeAddOns a
        LEFT JOIN FETCH a.addOn
        WHERE s.studioId = :studioId
    """)
    fun findByStudioIdWithAddOns(studioId: UUID): StudioSubscriptionPlanEntity?
}

/** Moduły z zaplanowanym wyłączeniem, których termin minął — dla joba zmian planu. */
data class DueAddOnCancellation(val studioId: UUID, val addOnKey: AddOnKey)

interface StudioAddOnRepository : JpaRepository<StudioAddOnEntity, UUID> {

    @Query("""
        SELECT new pl.detailing.crm.subscription.entitlement.infrastructure.DueAddOnCancellation(p.studioId, a.addOn.key)
        FROM StudioAddOnEntity a JOIN a.studioSubscriptionPlan p
        WHERE a.cancelAt IS NOT NULL AND a.cancelAt <= :now
        ORDER BY a.cancelAt ASC
    """)
    fun findDueCancellations(@Param("now") now: Instant, pageable: Pageable): List<DueAddOnCancellation>
}
