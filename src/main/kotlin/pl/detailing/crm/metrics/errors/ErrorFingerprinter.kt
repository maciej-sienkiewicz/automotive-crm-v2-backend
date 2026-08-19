package pl.detailing.crm.metrics.errors

import org.springframework.stereotype.Component
import pl.detailing.crm.metrics.config.MetricsProperties
import pl.detailing.crm.metrics.domain.ErrorFingerprint
import pl.detailing.crm.metrics.domain.ErrorOrigin
import java.security.MessageDigest

/**
 * Collapses many occurrences of one defect into one identity.
 *
 * ## Why this is not optional
 *
 * "4 812 errors yesterday" is not information anyone can act on. "Nine distinct defects,
 * and this one hit 23 studios" is. The whole difference is a fingerprint, and the whole
 * difficulty is making it stable:
 *
 * - **Too specific** (hashing the raw message) and every occurrence becomes its own
 *   group, because the message contains an id: `Nie znaleziono wizyty 8f3c…` produces a
 *   new group per visit, and the console is unusable within a day.
 * - **Too general** (hashing only the exception class) and every `IllegalStateException`
 *   in the codebase merges into one group, so fixing one "resolves" thirty others.
 *
 * The compromise: exception class + message with all variable parts masked + the top few
 * stack frames **belonging to our own package**. Framework frames are dropped because
 * they are identical for unrelated defects; ours are what distinguish them.
 */
@Component
class ErrorFingerprinter(private val properties: MetricsProperties) {

    /** Digits, UUIDs, e-mails, plates and quoted literals — the parts that vary per occurrence. */
    private val variablePatterns = listOf(
        Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}") to "{uuid}",
        Regex("[\\w.+-]+@[\\w-]+\\.[\\w.]+") to "{email}",
        Regex("\\b\\d{4}-\\d{2}-\\d{2}([T ]\\d{2}:\\d{2}(:\\d{2})?)?\\b") to "{date}",
        Regex("'[^']*'") to "'{val}'",
        Regex("\"[^\"]*\"") to "\"{val}\"",
        Regex("\\b\\d+\\b") to "{n}"
    )

    fun fingerprint(
        origin: ErrorOrigin,
        exceptionClass: String,
        message: String?,
        stackTrace: String?
    ): ErrorFingerprint {
        val material = buildString {
            append(origin.name).append('|')
            append(exceptionClass).append('|')
            append(normalizeMessage(message)).append('|')
            append(significantFrames(stackTrace).joinToString(">"))
        }

        val digest = MessageDigest.getInstance("SHA-256")
            .digest(material.toByteArray(Charsets.UTF_8))

        // 16 bytes / 32 hex chars: collision probability is negligible at any realistic
        // number of distinct defects, and the value stays short enough to read in a URL.
        return ErrorFingerprint(digest.take(16).joinToString("") { "%02x".format(it) })
    }

    internal fun normalizeMessage(message: String?): String {
        if (message.isNullOrBlank()) return ""
        return variablePatterns
            .fold(message) { acc, (pattern, replacement) -> pattern.replace(acc, replacement) }
            .trim()
            .take(300)
    }

    /**
     * The first N frames inside our own package, reduced to `Class.method:line`.
     *
     * Line numbers are kept deliberately: two different bugs in the same method are two
     * different defects, and merging them would hide the second one behind the first's
     * "resolved" flag. The cost is that a group re-forms when the file shifts by a
     * refactor — visible in the console as a new group, which is honest, and far cheaper
     * than a silently merged pair.
     */
    internal fun significantFrames(stackTrace: String?): List<String> {
        if (stackTrace.isNullOrBlank()) return emptyList()

        return stackTrace.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("at ") }
            .map { it.removePrefix("at ").trim() }
            .filter { it.startsWith(properties.errors.applicationPackage) }
            .map { frame ->
                // "pl.detailing.crm.visit.create.CreateVisitHandler.handle(CreateVisitHandler.kt:88)"
                //   → "pl.detailing.crm.visit.create.CreateVisitHandler.handle:88"
                val method = frame.substringBefore('(')
                val line = frame.substringAfterLast(':', "").removeSuffix(")")
                if (line.isNotBlank()) "$method:$line" else method
            }
            .take(properties.errors.fingerprintFrames)
            .toList()
    }

    /** Human-readable group title: `CreateVisitHandler.handle — ValidationException: …` */
    fun titleFor(exceptionClass: String, message: String?, stackTrace: String?): String {
        val shortClass = exceptionClass.substringAfterLast('.')
        // Class *and* method, not just the method: "handle" appears in forty handlers and
        // names none of them, which is the one thing a triage title has to do.
        val origin = significantFrames(stackTrace).firstOrNull()
            ?.substringBeforeLast(':')
            ?.split('.')
            ?.takeLast(2)
            ?.joinToString(".")

        val normalized = normalizeMessage(message).take(160)
        return when {
            origin.isNullOrBlank() && normalized.isBlank() -> shortClass
            origin.isNullOrBlank() -> "$shortClass: $normalized"
            normalized.isBlank() -> "$origin — $shortClass"
            else -> "$origin — $shortClass: $normalized"
        }.take(300)
    }
}
