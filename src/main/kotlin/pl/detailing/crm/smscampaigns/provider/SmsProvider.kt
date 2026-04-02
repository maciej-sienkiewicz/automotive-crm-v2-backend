package pl.detailing.crm.smscampaigns.provider

/**
 * Open-Closed abstraction for SMS delivery.
 *
 * New providers (Twilio, AWS SNS, Vonage, …) are added by implementing this
 * interface and registering the bean — the scheduler never needs to change.
 *
 * [phoneNumber] must be in E.164 format, e.g. "+48100200300".
 */
interface SmsProvider {
    fun send(phoneNumber: String, message: String): SmsDeliveryResult
}
