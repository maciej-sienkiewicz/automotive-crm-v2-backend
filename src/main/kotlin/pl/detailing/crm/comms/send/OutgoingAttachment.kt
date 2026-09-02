package pl.detailing.crm.comms.send

import pl.detailing.crm.shared.ValidationException

/** Plik dołączony do wiadomości pisanej w CRM — jeszcze przed wysyłką, bez identyfikatora. */
data class OutgoingAttachment(
    val fileName: String,
    val contentType: String,
    val content: ByteArray
) {
    val sizeBytes: Long get() = content.size.toLong()
}

/**
 * Reguły dla załączników wychodzących. Limity są tu, a nie tylko w konfiguracji
 * multipart Springa, bo błąd ma wrócić do użytkownika po polsku i z podaną liczbą —
 * a nie jako 413 bez treści. Wartości pokrywają się z tym, co przyjmuje import
 * (MimeEmailParser: 15 MB na plik) i z typowym limitem serwerów SMTP (~25 MB).
 */
object OutgoingAttachmentPolicy {
    const val MAX_FILES = 10
    const val MAX_FILE_BYTES = 15L * 1024 * 1024
    /**
     * Suma dla całej wiadomości. Poniżej limitu żądania (15 MB w application.properties),
     * bo po zakodowaniu base64 treść rośnie o jedną trzecią, a serwery SMTP liczą
     * wielkość już zakodowanej wiadomości.
     */
    const val MAX_TOTAL_BYTES = 15L * 1024 * 1024

    /**
     * Rozszerzenia, których nie przyjmie praktycznie żaden serwer odbiorcy (Gmail,
     * Outlook odrzucają je z automatu). Lepiej powiedzieć to od razu niż po
     * odbiciu wiadomości — a „załączyłem plik .exe" w studiu detailingu nigdy nie
     * jest scenariuszem, który chcemy wspierać.
     */
    private val BLOCKED_EXTENSIONS = setOf(
        "exe", "msi", "bat", "cmd", "com", "scr", "pif", "cpl", "jar", "js", "jse",
        "vbs", "vbe", "wsf", "wsh", "ps1", "psm1", "reg", "lnk", "hta", "dll", "sys"
    )

    fun validate(attachments: List<OutgoingAttachment>) {
        if (attachments.size > MAX_FILES) {
            throw ValidationException("Do jednej wiadomości można dołączyć najwyżej $MAX_FILES plików")
        }
        attachments.forEach { attachment ->
            if (attachment.content.isEmpty()) {
                throw ValidationException("Plik „${attachment.fileName}” jest pusty")
            }
            if (attachment.sizeBytes > MAX_FILE_BYTES) {
                throw ValidationException(
                    "Plik „${attachment.fileName}” jest za duży — limit to ${MAX_FILE_BYTES / (1024 * 1024)} MB na plik"
                )
            }
            val extension = attachment.fileName.substringAfterLast('.', "").lowercase()
            if (extension in BLOCKED_EXTENSIONS) {
                throw ValidationException(
                    "Pliki .$extension są odrzucane przez serwery pocztowe — spakuj plik do archiwum ZIP"
                )
            }
        }
        val total = attachments.sumOf { it.sizeBytes }
        if (total > MAX_TOTAL_BYTES) {
            throw ValidationException(
                "Załączniki ważą łącznie za dużo — limit to ${MAX_TOTAL_BYTES / (1024 * 1024)} MB na wiadomość"
            )
        }
    }

    /**
     * Nazwa pliku z przeglądarki potrafi nieść ścieżkę (stare IE) albo znaki sterujące;
     * do nagłówka MIME i do bazy trafia sama nazwa bez katalogów i bez znaków,
     * które łamią nagłówki.
     */
    fun safeFileName(original: String?): String {
        val bare = original.orEmpty()
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .replace(Regex("[\\p{Cntrl}\"\\\\]"), "_")
            .trim()
        return bare.ifBlank { "zalacznik" }.take(255)
    }
}
