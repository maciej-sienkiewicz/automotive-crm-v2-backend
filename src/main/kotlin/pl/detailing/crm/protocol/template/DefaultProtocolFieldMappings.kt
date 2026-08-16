package pl.detailing.crm.protocol.template

import pl.detailing.crm.shared.CrmDataKey

/**
 * Default field mappings configuration for protocol templates.
 * These mappings are automatically created when a new template is created.
 */
object DefaultProtocolFieldMappings {

    /**
     * Returns the default field mappings as a map of PDF field name to CRM data key.
     * The order of fields matches the expected order in the PDF form.
     */
    fun getDefaultMappings(): Map<String, CrmDataKey> = linkedMapOf(
        "brand" to CrmDataKey.VEHICLE_BRAND,
        "model" to CrmDataKey.VEHICLE_MODEL,
        "licenseplate" to CrmDataKey.VEHICLE_PLATE,
        "mileage" to CrmDataKey.VISIT_MILEAGE,
        "services" to CrmDataKey.SERVICES_LIST,
        "remarks" to CrmDataKey.NOTES,
        "fullname" to CrmDataKey.CUSTOMER_FULL_NAME,
        "companyname" to CrmDataKey.STUDIO_NAME,
        "phonenumber" to CrmDataKey.CUSTOMER_PHONE,
        "email" to CrmDataKey.CUSTOMER_EMAIL,
        "tax" to CrmDataKey.CUSTOMER_COMPANY_NIP,
        "date" to CrmDataKey.CURRENT_DATETIME,
        "price" to CrmDataKey.TOTAL_GROSS_AMOUNT,
        "keys" to CrmDataKey.VEHICLE_KEYS_RECEIVED,
        "documents" to CrmDataKey.VEHICLE_DOCUMENTS_RECEIVED
    ) + getSupplementaryMappings()

    /**
     * Header-section mappings added after the original 15: provider name
     * (Usługodawca), protocol number and the employee who received the vehicle.
     * Filled when the template contains the fields, but NOT required by
     * verification — templates uploaded before these existed stay valid.
     */
    fun getSupplementaryMappings(): Map<String, CrmDataKey> = linkedMapOf(
        "protocolnumber" to CrmDataKey.VISIT_NUMBER,
        "receivedby" to CrmDataKey.RECEIVED_BY_NAME,
        "provider" to CrmDataKey.PROVIDER_NAME
    )
}
