package pl.detailing.crm.communication.redirect

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * Per-studio "send every customer message to me instead" switch.
 *
 * One row per studio, written on the first save from the settings screen. A studio with no
 * row is a studio that has never touched the switch, which means: messages go to customers.
 */
@Entity
@Table(
    name = "communication_redirect_settings",
    indexes = [Index(name = "idx_communication_redirect_settings_studio_id", columnList = "studio_id", unique = true)]
)
class CommunicationRedirectEntity(

    @Id
    @Column(name = "id", columnDefinition = "uuid")
    val id: UUID,

    @Column(name = "studio_id", nullable = false, columnDefinition = "uuid", unique = true)
    val studioId: UUID,

    @Column(name = "enabled", nullable = false)
    var enabled: Boolean,

    /** E.164, e.g. +48500100200. Blank when never set. */
    @Column(name = "phone", nullable = false, length = 20)
    var phone: String,

    @Column(name = "email", nullable = false, length = 254)
    var email: String,

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant,

    @Column(name = "updated_by_user_id", columnDefinition = "uuid")
    var updatedByUserId: UUID?
)
