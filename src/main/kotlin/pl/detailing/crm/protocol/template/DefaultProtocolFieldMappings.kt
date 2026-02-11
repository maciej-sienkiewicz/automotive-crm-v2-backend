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
        "brand" to CrmDataKey.VEHICLE_BRAND_MODEL,
        "model" to CrmDataKey.VEHICLE_BRAND_MODEL,
        "licenseplate" to CrmDataKey.VEHICLE_PLATE,
        "mileage" to CrmDataKey.VISIT_MILEAGE,
        "services" to CrmDataKey.SERVICES_LIST,
        "fullname" to CrmDataKey.CUSTOMER_FULL_NAME,
        "companyname" to CrmDataKey.STUDIO_NAME,
        "phonenumber" to CrmDataKey.CUSTOMER_PHONE,
        "email" to CrmDataKey.CUSTOMER_EMAIL,
        "tax" to CrmDataKey.TOTAL_VAT_AMOUNT,
        "date" to CrmDataKey.VISIT_DATE,
        "price" to CrmDataKey.TOTAL_GROSS_AMOUNT
    )
}
