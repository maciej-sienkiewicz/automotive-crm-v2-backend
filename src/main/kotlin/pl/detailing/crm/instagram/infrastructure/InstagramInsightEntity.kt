package pl.detailing.crm.instagram.infrastructure

import jakarta.persistence.*
import java.time.Instant
import java.time.LocalDate
import java.util.*

/**
 * Insight – gotowy wniosek dla studia, wygenerowany przez deterministyczne detektory
 * (InsightEngine). Treść (title/body/action) jest po polsku, w prostym języku:
 * "co się stało → dlaczego to ważne → co możesz zrobić".
 *
 * Per-tenant (studio_id). Dedup po (studio_id, dedup_key).
 */
@Entity
@Table(
    name = "instagram_insights",
    indexes = [
        Index(name = "idx_ig_insights_studio_created", columnList = "studio_id, created_at"),
        Index(name = "idx_ig_insights_studio_dedup", columnList = "studio_id, dedup_key", unique = true),
        Index(name = "idx_ig_insights_studio_status", columnList = "studio_id, status")
    ]
)
class InstagramInsightEntity(
    @Id
    @Column(name = "id", columnDefinition = "uuid")
    val id: UUID,

    @Column(name = "studio_id", nullable = false, columnDefinition = "uuid")
    val studioId: UUID,

    /** Typ detektora, np. PROMO_DETECTED, VIRAL_POST, FOLLOWER_SPIKE. */
    @Column(name = "type", nullable = false, length = 40)
    val type: String,

    /** HIGH | MEDIUM | LOW – steruje kolejnością i wyróżnieniem w UI. */
    @Column(name = "severity", nullable = false, length = 10)
    val severity: String,

    /** Krótki nagłówek ("Co się stało"). */
    @Column(name = "title", nullable = false, length = 200)
    val title: String,

    /** Rozwinięcie ("Dlaczego to ważne"). */
    @Column(name = "body", nullable = false, columnDefinition = "text")
    val body: String,

    /** Sugestia ("Co możesz zrobić"). */
    @Column(name = "action_text", nullable = false, columnDefinition = "text")
    val actionText: String,

    /** Profil, którego dotyczy insight (null dla insightów rynkowych). */
    @Column(name = "profile_id", nullable = true, columnDefinition = "uuid")
    val profileId: UUID?,

    /** Post, którego dotyczy insight (np. promocja / viral). */
    @Column(name = "post_id", nullable = true, columnDefinition = "uuid")
    val postId: UUID?,

    /** Prawdopodobna przyczyna (dla wystrzałów) – zawsze prezentowana jako hipoteza. */
    @Column(name = "probable_cause", nullable = true, length = 60)
    val probableCause: String?,

    /** Klucz deduplikacji, np. "PROMO:{post_pk}" – ten sam insight nie powstanie dwa razy. */
    @Column(name = "dedup_key", nullable = false, length = 120)
    val dedupKey: String,

    /** NEW | DISMISSED */
    @Column(name = "status", nullable = false, length = 20)
    var status: String,

    /** Poniedziałek tygodnia ISO, do którego insight należy (limit 5/tydzień). */
    @Column(name = "week_start", nullable = false)
    val weekStart: LocalDate,

    @Column(name = "created_at", nullable = false, columnDefinition = "timestamp with time zone")
    val createdAt: Instant,

    @Column(name = "updated_at", nullable = false, columnDefinition = "timestamp with time zone")
    var updatedAt: Instant
)
