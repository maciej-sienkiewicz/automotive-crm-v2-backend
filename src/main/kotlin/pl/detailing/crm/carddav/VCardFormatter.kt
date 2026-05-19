package pl.detailing.crm.carddav

import org.springframework.stereotype.Component
import pl.detailing.crm.customer.infrastructure.CustomerEntity
import java.security.MessageDigest

@Component
class VCardFormatter {

    fun format(customer: CustomerEntity): String {
        val fn = buildFn(customer)
        val phone = normalizePhone(customer.phone ?: return "")
        val uid = customer.id.toString()

        return buildString {
            appendLine("BEGIN:VCARD")
            appendLine("VERSION:3.0")
            appendLine("FN:$fn")
            appendLine("N:${customer.lastName ?: ""};${customer.firstName ?: ""};;;")
            appendLine("TEL;TYPE=CELL,VOICE:$phone")
            appendLine("UID:$uid")
            appendLine("END:VCARD")
        }.trimEnd() + "\r\n"
    }

    fun etag(customer: CustomerEntity): String {
        val raw = "${customer.id}:${customer.updatedAt.toEpochMilli()}"
        val digest = MessageDigest.getInstance("MD5").digest(raw.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun buildFn(customer: CustomerEntity): String {
        val first = customer.firstName?.trim().orEmpty()
        val last = customer.lastName?.trim().orEmpty()
        val name = listOf(first, last).filter { it.isNotEmpty() }.joinToString(" ")
        return if (name.isNotBlank()) "$name (CRM)" else "(CRM)"
    }

    private fun normalizePhone(raw: String): String {
        val digits = raw.replace(Regex("[^+\\d]"), "")
        return if (digits.startsWith("+")) digits else "+$digits"
    }
}
