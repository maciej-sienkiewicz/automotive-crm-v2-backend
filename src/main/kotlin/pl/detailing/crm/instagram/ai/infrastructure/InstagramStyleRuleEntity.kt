package pl.detailing.crm.instagram.ai.infrastructure

import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

/**
 * Reguła stylistyczna studia, nadrzędna wobec przykładów few-shot przy generowaniu posta.
 *
 * Reguły żyją w bazie, a nie w żądaniu z frontendu, bo to samo studio pisze posty w tym
 * samym stylu przez miesiące — przepisywanie „Nie używaj emoji" przy każdym generowaniu
 * było czystą stratą, a bez zapisu nie dało się też ocenić posta względem reguł,
 * które wtedy obowiązywały.
 */
@Entity
@Table(
    name = "instagram_style_rules",
    indexes = [
        Index(name = "idx_instagram_style_rules_studio", columnList = "studio_id")
    ]
)
class InstagramStyleRuleEntity(
    @Id
    @Column(name = "id", columnDefinition = "uuid")
    val id: UUID,

    @Column(name = "studio_id", nullable = false, columnDefinition = "uuid")
    val studioId: UUID,

    @Column(name = "rule_text", nullable = false, columnDefinition = "TEXT")
    var ruleText: String,

    @Column(name = "active", nullable = false)
    var active: Boolean,

    @Column(name = "created_at", nullable = false, columnDefinition = "timestamp with time zone")
    val createdAt: Instant,

    @Column(name = "updated_at", nullable = false, columnDefinition = "timestamp with time zone")
    var updatedAt: Instant
)
