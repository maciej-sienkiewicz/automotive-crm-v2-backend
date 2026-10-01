package pl.detailing.crm.subscription.entitlement.infrastructure

import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

/**
 * Maps a studio to its currently active plan.
 * One row per studio (UNIQUE on studio_id).
 *
 * Billing lifecycle (active/expired) is still tracked on StudioEntity
 * via subscriptionStatus / subscriptionEndsAt. This table only records
 * which feature-plan the studio is entitled to.
 *
 * Agregat jest MUTOWALNY i wersjonowany. Wcześniej wszystkie pola były `val`, więc zmiana
 * planu budowała nową instancję z tym samym `id` i wołała `save()` → `merge()`, a moduły
 * usuwał `orphanRemoval` z kolekcji wyczyszczonej chwilę wcześniej na innej instancji.
 * Działało wyłącznie dzięki szczegółom implementacji `merge` (audyt, D3). Teraz plan zmienia
 * się NA MIEJSCU ([changePlan]) — ta sama instancja i ta sama kolekcja, którą śledzi Hibernate.
 */
@Entity
@Table(
    name = "studio_subscription_plans",
    uniqueConstraints = [UniqueConstraint(name = "uq_studio_subscription_plans_studio", columnNames = ["studio_id"])]
)
class StudioSubscriptionPlanEntity(

    @Id
    val id: UUID = UUID.randomUUID(),

    @Column(name = "studio_id", nullable = false, updatable = false)
    val studioId: UUID,

    plan: PlanEntity,

    activatedAt: Instant = Instant.now(),

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now()
) {
    // Plan zostaje EAGER: EntitlementService.hasFeature jest wołany z filtra PII poza
    // transakcją i poza OSIV — leniwe ładowanie skończyłoby się LazyInitializationException.
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "plan_id", nullable = false)
    var plan: PlanEntity = plan
        private set

    @Column(name = "activated_at", nullable = false)
    var activatedAt: Instant = activatedAt
        private set

    @OneToMany(
        mappedBy = "studioSubscriptionPlan",
        fetch = FetchType.EAGER,
        cascade = [CascadeType.ALL],
        orphanRemoval = true
    )
    val activeAddOns: MutableSet<StudioAddOnEntity> = mutableSetOf()

    /**
     * Null przed pierwszym zapisem: Spring Data rozpoznaje wtedy nowy wiersz po wersji
     * (persist zamiast merge przy przypisanym z góry id), a Hibernate nadaje 0 przy INSERT.
     * Potem optymistyczna blokada: dwa równoległe zapisy tego samego planu nie nadpiszą się
     * po cichu (audyt, D2). DEFAULT 0 w definicji kolumny — bo wiersze wstawia też
     * natywny SQL (StudioSubscriptionBackfill), który o wersji nie wie.
     */
    @Version
    @Column(name = "version", nullable = false, columnDefinition = "BIGINT NOT NULL DEFAULT 0")
    var version: Long? = null
        private set

    /**
     * Zmiana planu: ta sama instancja, ta sama kolekcja modułów. Moduły znikają ZAWSZE — także
     * przy „zmianie" na ten sam plan — bo plan docelowy definiuje zestaw od nowa (FULL zawiera
     * wszystkie; BASIC po zakupie dostaje dokładnie moduły z zamówienia, a nie sumę z tym,
     * co zostało po wygasłej subskrypcji).
     */
    fun changePlan(newPlan: PlanEntity, now: Instant) {
        if (plan.id != newPlan.id) {
            plan = newPlan
            activatedAt = now
        }
        activeAddOns.clear()
    }

    fun findAddOn(addOnId: UUID): StudioAddOnEntity? = activeAddOns.firstOrNull { it.addOn.id == addOnId }
}
