package pl.detailing.crm.audit.feed

import org.springframework.stereotype.Component
import pl.detailing.crm.audit.domain.AuditAction
import pl.detailing.crm.audit.domain.AuditAmount
import pl.detailing.crm.audit.domain.AuditContext
import pl.detailing.crm.audit.domain.AuditFieldCatalog
import pl.detailing.crm.audit.domain.AuditLog
import pl.detailing.crm.audit.domain.AuditResource
import pl.detailing.crm.audit.domain.AuditValueType
import java.util.Locale

/**
 * Turns a stored [AuditLog] into a row a client can render without knowing anything about
 * the domain that produced it.
 *
 * This is the piece that makes the feed consistent. Without it every client has to
 * reconstruct meaning from `module + action + entityDisplayName + a free-form metadata
 * map`, which means each client invents its own phrasing, its own icons and its own idea
 * of which events matter — and all of them drift the moment a handler changes.
 */
@Component
class AuditFeedRenderer {

    /** Verbs that do not say what they acted on; [sentenceOf] appends the module noun. */
    private val GENERIC_VERBS = setOf(AuditAction.CREATE, AuditAction.UPDATE, AuditAction.DELETE)

    fun render(log: AuditLog): AuditFeedItem {
        val subject = subjectOf(log)
        val related = relatedOf(log.context, subject)
        val changes = log.changes.map { change ->
            val rendered = AuditFieldCatalog.render(change)
            AuditChangeView(
                field = rendered.field,
                label = rendered.label,
                type = rendered.type,
                oldValue = rendered.oldValue,
                newValue = rendered.newValue,
                oldValueDisplay = rendered.oldValueDisplay,
                newValueDisplay = rendered.newValueDisplay
            )
        }

        val title = titleOf(log, subject)
        val description = descriptionOf(log, subject, related)

        return AuditFeedItem(
            id = log.id.toString(),
            occurredAt = log.createdAt,
            actor = AuditActorView(
                type = log.actor.type.name,
                typeLabel = log.actor.type.label,
                id = log.actor.id?.toString(),
                displayName = log.actor.displayName,
                initials = initialsOf(log.actor.displayName)
            ),
            module = AuditCodeLabel(log.module.name, log.module.label),
            action = AuditCodeLabel(log.action.name, log.action.label),
            severity = AuditCodeLabel(log.severity.name, log.severity.label),
            channel = AuditCodeLabel(log.channel.name, log.channel.label),
            icon = log.action.icon.name,
            title = title,
            description = description,
            summary = listOfNotNull(log.actor.displayName, title).joinToString(" · "),
            subject = subject,
            related = related,
            amount = log.amount?.let(::renderAmount),
            changes = changes,
            changeSummary = changes
                .filter { it.type != AuditValueType.SECRET }
                .joinToString(", ") { it.label }
                .takeIf { it.isNotEmpty() },
            metadata = log.metadata,
            correlationId = log.correlationId?.toString(),
            ipAddress = log.ipAddress
        )
    }

    /**
     * "Przyjęto pojazd — Audi A4 (WX 1234)".
     *
     * The verb is impersonal on purpose: Polish past tense inflects for gender and the
     * actor's gender is not something the system knows, so the alternative would be
     * "dodał(a)" on every single row.
     */
    private fun titleOf(log: AuditLog, subject: AuditReferenceView?): String {
        val sentence = sentenceOf(log)
        val label = subject?.label ?: log.entityDisplayName
        return if (label.isNullOrBlank()) sentence else "$sentence - $label"
    }

    /**
     * The verb, with the module's object noun appended when the action alone does not
     * name what it acted on: "Utworzono" by itself reads ambiguously in a mixed feed
     * ("Utworzono - Test"), so generic CRUD verbs become "Utworzono usługę - Test".
     * Specific actions ("Rozpoczęto wizytę") already carry their own noun.
     */
    private fun sentenceOf(log: AuditLog): String {
        val noun = log.module.objectNoun ?: return log.action.sentence
        return if (log.action in GENERIC_VERBS) "${log.action.sentence} $noun" else log.action.sentence
    }

    /** Context line, skipping whatever is already named in the title. */
    private fun descriptionOf(
        log: AuditLog,
        subject: AuditReferenceView?,
        related: List<AuditReferenceView>
    ): String? {
        val parts = mutableListOf<String>()

        related.forEach { reference ->
            val label = reference.label ?: return@forEach
            if (label == subject?.label) return@forEach
            parts += "${resourceLabel(reference.resource)}: $label"
        }

        log.amount?.let { parts += "Kwota: ${renderAmount(it).display}" }

        return parts.joinToString(" · ").takeIf { it.isNotEmpty() }
    }

    /**
     * What the action was performed on. Falls back to the module's resource type, which is
     * correct for every module whose `entityId` addresses a real object; modules that act
     * on nothing addressable ([AuditResource.NONE]) get no subject rather than a dead link.
     */
    private fun subjectOf(log: AuditLog): AuditReferenceView? {
        if (log.module.resource == AuditResource.NONE) return null
        return AuditReferenceView(
            resource = log.module.resource.name,
            id = log.entityId,
            label = log.entityDisplayName
        )
    }

    private fun relatedOf(context: AuditContext, subject: AuditReferenceView?): List<AuditReferenceView> {
        if (context.isEmpty) return emptyList()

        val references = mutableListOf<AuditReferenceView>()
        context.customerId?.let {
            references += AuditReferenceView(AuditResource.CUSTOMER.name, it.toString(), context.customerName)
        }
        context.vehicleId?.let {
            references += AuditReferenceView(AuditResource.VEHICLE.name, it.toString(), context.vehicleName)
        }
        context.visitId?.let {
            references += AuditReferenceView(AuditResource.VISIT.name, it.toString(), context.visitName)
        }
        context.appointmentId?.let {
            references += AuditReferenceView(AuditResource.APPOINTMENT.name, it.toString(), context.appointmentName)
        }

        // The subject is already rendered on its own; repeating it as a related link would
        // give the reader two identical chips.
        val subjectKey = subject?.let { it.resource to it.id }
        return references.filterNot { (it.resource to it.id) == subjectKey }
    }

    private fun renderAmount(amount: AuditAmount): AuditAmountView {
        val plain = amount.value.toPlainString()
        return AuditAmountView(
            value = plain,
            currency = amount.currency,
            display = AuditFieldCatalog.formatValue(plain, AuditValueType.MONEY) ?: plain
        )
    }

    private fun resourceLabel(resource: String): String = when (resource) {
        AuditResource.CUSTOMER.name -> "Klient"
        AuditResource.VEHICLE.name -> "Pojazd"
        AuditResource.VISIT.name -> "Wizyta"
        AuditResource.APPOINTMENT.name -> "Rezerwacja"
        else -> "Powiązanie"
    }

    /** "Jan Kowalski" -> "JK", "System" -> "S". */
    private fun initialsOf(displayName: String): String {
        val words = displayName.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return "?"
        return words.take(2)
            .map { it.first().uppercaseChar() }
            .joinToString("")
            .ifEmpty { "?" }
            .uppercase(Locale.ROOT)
    }
}
