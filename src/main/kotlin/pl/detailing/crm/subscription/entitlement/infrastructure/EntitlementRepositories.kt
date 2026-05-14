package pl.detailing.crm.subscription.entitlement.infrastructure

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import pl.detailing.crm.subscription.entitlement.FeatureKey
import pl.detailing.crm.subscription.entitlement.domain.AddOnKey
import pl.detailing.crm.subscription.entitlement.domain.PlanKey
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

    @Query("""
        SELECT s FROM StudioSubscriptionPlanEntity s
        LEFT JOIN FETCH s.activeAddOns a
        LEFT JOIN FETCH a.addOn
        WHERE s.studioId = :studioId
    """)
    fun findByStudioIdWithAddOns(studioId: UUID): StudioSubscriptionPlanEntity?
}
