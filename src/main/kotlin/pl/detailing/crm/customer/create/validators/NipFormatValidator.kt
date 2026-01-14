package pl.detailing.crm.customer.create.validators

import org.springframework.stereotype.Component
import pl.detailing.crm.customer.create.CreateCustomerValidationContext
import pl.detailing.crm.shared.ValidationException

@Component
class NipFormatValidator {
    fun validate(context: CreateCustomerValidationContext) {
        val nip = context.companyData?.nip
        
        if (nip != null && nip.isNotBlank()) {
            val cleanedNip = nip.replace("-", "").replace(" ", "")
            
            if (!cleanedNip.matches(Regex("^\\d{10}$"))) {
                throw ValidationException("Invalid NIP format. NIP must contain exactly 10 digits")
            }
            
            // Validate NIP checksum
            if (!isValidNipChecksum(cleanedNip)) {
                throw ValidationException("Invalid NIP checksum")
            }
        }
    }
    
    private fun isValidNipChecksum(nip: String): Boolean {
        if (nip.length != 10) return false
        
        val weights = intArrayOf(6, 5, 7, 2, 3, 4, 5, 6, 7)
        var sum = 0
        
        for (i in 0..8) {
            sum += nip[i].digitToInt() * weights[i]
        }
        
        val checksum = sum % 11
        val lastDigit = nip[9].digitToInt()
        
        return checksum == lastDigit
    }
}
