package pl.detailing.crm.studio.settings

import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "studio_settings")
class StudioSettingsEntity(
    @Id
    @Column(name = "studio_id", columnDefinition = "uuid")
    val studioId: UUID,

    @Column(name = "name", length = 200)
    var name: String? = null,

    @Column(name = "tax_id", length = 20)
    var taxId: String? = null,

    @Column(name = "regon", length = 14)
    var regon: String? = null,

    @Column(name = "street", length = 200)
    var street: String? = null,

    @Column(name = "postal_code", length = 10)
    var postalCode: String? = null,

    @Column(name = "city", length = 100)
    var city: String? = null,

    @Column(name = "phone", length = 20)
    var phone: String? = null,

    @Column(name = "email", length = 255)
    var email: String? = null,

    @Column(name = "website", length = 255)
    var website: String? = null,

    @Column(name = "bank_account", length = 40)
    var bankAccount: String? = null,

    @Column(name = "logo_s3_key", length = 500)
    var logoS3Key: String? = null,

    @Column(name = "lead_stagnant_our_threshold_hours", nullable = false)
    var leadStagnantOurThresholdHours: Int = 48,

    @Column(name = "lead_stagnant_client_threshold_hours", nullable = false)
    var leadStagnantClientThresholdHours: Int = 72,

    @Column(name = "sms_api_name_confirmed", nullable = false)
    var smsApiNameConfirmed: Boolean = false,

    /** Delivery channel for the customer Visit Card link: EMAIL, SMS, BOTH or NONE (no auto-send). */
    @Column(name = "visit_card_delivery_channel", nullable = false, length = 10)
    var visitCardDeliveryChannel: String = "EMAIL",

    /** Master switch for the customer Visit Card feature (its use requires the SMS module). */
    @Column(name = "visit_card_enabled", nullable = false, columnDefinition = "boolean not null default true")
    var visitCardEnabled: Boolean = true,

    /** Default state of the "send the Visit Card to the customer" checkbox at booking/check-in. */
    @Column(name = "visit_card_send_by_default", nullable = false, columnDefinition = "boolean not null default false")
    var visitCardSendByDefault: Boolean = false,

    @Column(name = "updated_at", nullable = false, columnDefinition = "timestamp with time zone")
    var updatedAt: Instant = Instant.now()
)
