package pl.detailing.crm.studio.infrastructure

import jakarta.persistence.*
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.SubscriptionStatus
import pl.detailing.crm.studio.domain.Studio
import pl.detailing.crm.studio.domain.StudioKind
import java.time.Instant
import java.util.UUID

@Entity
@Table(
    name = "studios",
    indexes = [
        Index(name = "idx_studios_created_at", columnList = "created_at"),
        Index(name = "idx_studios_subscription_status", columnList = "subscription_status"),
        Index(name = "idx_studios_trial_ends_at", columnList = "trial_ends_at"),
        Index(name = "idx_studios_email_alias", columnList = "email_alias", unique = true)
    ]
)
class StudioEntity(
    @Id
    @Column(name = "id", columnDefinition = "uuid")
    val id: UUID,

    @Column(name = "name", nullable = false, length = 200)
    var name: String,

    @Enumerated(EnumType.STRING)
    @Column(name = "subscription_status", nullable = false, length = 20)
    var subscriptionStatus: SubscriptionStatus,

    @Column(name = "trial_ends_at", columnDefinition = "timestamp with time zone")
    var trialEndsAt: Instant?,

    @Column(name = "subscription_ends_at", columnDefinition = "timestamp with time zone")
    var subscriptionEndsAt: Instant?,

    @Column(name = "trial_used", nullable = false)
    var trialUsed: Boolean,

    @Column(name = "created_at", nullable = false, columnDefinition = "timestamp with time zone")
    val createdAt: Instant = Instant.now(),

    // UUID without dashes – serves as a unique email alias for inbound CloudFlare email routing.
    // Nullable to support existing studios created before this feature was introduced.
    @Column(name = "email_alias", unique = true, nullable = true, length = 32)
    var emailAlias: String? = null,

    /**
     * Rodzaj studia (zwykłe, DEMO, piaskownica podglądu roli). Od niego zależą blokady
     * logowania, wysyłek na zewnątrz i zadań platformy, więc nadaje się go raz, przy
     * INSERT-cie, i żaden zapis go potem nie zmienia: `updatable = false` wyjmuje kolumnę
     * z UPDATE-ów Hibernate'a. Bez tego zapis encji zbudowanej z domeny bez tego pola
     * (np. przy zmianie subskrypcji) po cichu zrobiłby z piaskownicy zwykłe studio.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, updatable = false, length = 20, columnDefinition = "VARCHAR(20) DEFAULT 'REGULAR'")
    val kind: StudioKind = StudioKind.REGULAR
) {
    fun toDomain(): Studio = Studio(
        id = StudioId(id),
        name = name,
        subscriptionStatus = subscriptionStatus,
        trialEndsAt = trialEndsAt,
        subscriptionEndsAt = subscriptionEndsAt,
        trialUsed = trialUsed,
        createdAt = createdAt,
        emailAlias = emailAlias,
        kind = kind
    )

    companion object {
        fun fromDomain(studio: Studio): StudioEntity = StudioEntity(
            id = studio.id.value,
            name = studio.name,
            subscriptionStatus = studio.subscriptionStatus,
            trialEndsAt = studio.trialEndsAt,
            subscriptionEndsAt = studio.subscriptionEndsAt,
            trialUsed = studio.trialUsed,
            createdAt = studio.createdAt,
            emailAlias = studio.emailAlias,
            kind = studio.kind
        )
    }
}
