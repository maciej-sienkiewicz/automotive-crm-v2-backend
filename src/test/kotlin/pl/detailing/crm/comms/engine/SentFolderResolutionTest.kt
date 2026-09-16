package pl.detailing.crm.comms.engine

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Dobór folderu Wysłanych musi być GENERYCZNY: u każdego dostawcy ten folder nazywa
 * się inaczej, a mimo to odpowiedź wysłana spoza CRM-a ma się dopiąć do rozmowy.
 * Dlatego logika jest czysta (bez IMAP-a) i sprawdzana tu dla wielu układów skrzynek.
 */
class SentFolderResolutionTest {

    private fun folder(
        fullName: String,
        leaf: String = fullName.substringAfterLast('/').substringAfterLast('.'),
        depth: Int = 0,
        sent: Boolean = false,
        otherRole: Boolean = false
    ) = SentCandidate(fullName, leaf, depth, sent, otherRole)

    // ── SPECIAL-USE \Sent (RFC 6154) — najmocniejszy, niezależny od nazwy ──────

    @Test
    fun `atrybut SPECIAL-USE wygrywa niezaleznie od nazwy i jezyka`() {
        val chosen = ImapSessions.chooseSentFolder(listOf(
            folder("INBOX", leaf = "INBOX"),
            folder("Jakas Dziwna Nazwa", leaf = "Jakas Dziwna Nazwa", sent = true),
            folder("Sent", leaf = "Sent") // bez atrybutu — nie powinien wygrać z SPECIAL-USE
        ))
        assertEquals("Jakas Dziwna Nazwa", chosen)
    }

    @Test
    fun `SPECIAL-USE Sent wygrywa nad folderem Drafts`() {
        val chosen = ImapSessions.chooseSentFolder(listOf(
            folder("Drafts", leaf = "Drafts", otherRole = true),
            folder("[Gmail]/Sent Mail", leaf = "Sent Mail", depth = 1, sent = true)
        ))
        assertEquals("[Gmail]/Sent Mail", chosen)
    }

    // ── Heurystyka nazw, wiele języków ────────────────────────────────────────

    @Test
    fun `angielskie warianty`() {
        assertEquals("Sent", ImapSessions.chooseSentFolder(listOf(folder("Sent"))))
        assertEquals("Sent Items", ImapSessions.chooseSentFolder(listOf(folder("Sent Items"))))
        assertEquals("Sent Messages", ImapSessions.chooseSentFolder(listOf(folder("Sent Messages"))))
    }

    @Test
    fun `polskie warianty z diakrytyka`() {
        assertEquals("Wysłane", ImapSessions.chooseSentFolder(listOf(folder("Wysłane"))))
        assertEquals("Elementy wysłane", ImapSessions.chooseSentFolder(listOf(folder("Elementy wysłane"))))
        assertEquals("Wysłana poczta", ImapSessions.chooseSentFolder(listOf(folder("Wysłana poczta"))))
    }

    @Test
    fun `niemiecki hiszpanski francuski wloski niderlandzki`() {
        assertEquals("Gesendet", ImapSessions.chooseSentFolder(listOf(folder("Gesendet"))))
        assertEquals("Enviados", ImapSessions.chooseSentFolder(listOf(folder("Enviados"))))
        assertEquals("Éléments envoyés", ImapSessions.chooseSentFolder(listOf(folder("Éléments envoyés", leaf = "Éléments envoyés"))))
        assertEquals("Posta inviata", ImapSessions.chooseSentFolder(listOf(folder("Posta inviata", leaf = "Posta inviata"))))
        assertEquals("Verzonden", ImapSessions.chooseSentFolder(listOf(folder("Verzonden"))))
    }

    // ── Zagnieżdżenie i remisy ────────────────────────────────────────────────

    @Test
    fun `folder zagniezdzony INBOX kropka Sent`() {
        assertEquals("INBOX.Sent", ImapSessions.chooseSentFolder(listOf(
            folder("INBOX", leaf = "INBOX"),
            folder("INBOX.Sent", leaf = "Sent", depth = 1)
        )))
    }

    @Test
    fun `przy remisie wygrywa folder plycej w hierarchii`() {
        val chosen = ImapSessions.chooseSentFolder(listOf(
            folder("Archiwum/Sent", leaf = "Sent", depth = 1),
            folder("Sent", leaf = "Sent", depth = 0)
        ))
        assertEquals("Sent", chosen)
    }

    // ── Wykluczenia: nie mylimy Wysłanych z inną rolą ─────────────────────────

    @Test
    fun `Sent wygrywa nad Drafts i Trash gdy brak atrybutow`() {
        val chosen = ImapSessions.chooseSentFolder(listOf(
            folder("Kopie robocze", leaf = "Kopie robocze"),
            folder("Kosz", leaf = "Kosz"),
            folder("Spam", leaf = "Spam"),
            folder("Wysłane", leaf = "Wysłane")
        ))
        assertEquals("Wysłane", chosen)
    }

    @Test
    fun `sama skrzynka bez Wysłanych daje null`() {
        val chosen = ImapSessions.chooseSentFolder(listOf(
            folder("INBOX", leaf = "INBOX"),
            folder("Drafts", leaf = "Drafts", otherRole = true),
            folder("Trash", leaf = "Trash", otherRole = true),
            folder("Archive", leaf = "Archive", otherRole = true)
        ))
        assertNull(chosen)
    }

    @Test
    fun `folder archiwum wszystkich wiadomosci nie jest Wysłanymi`() {
        // „All Mail" / „Wszystkie" zawiera też wysłane, ale nie JEST folderem Wysłanych.
        val chosen = ImapSessions.chooseSentFolder(listOf(
            folder("[Gmail]/All Mail", leaf = "All Mail", depth = 1),
            folder("[Gmail]/Wszystkie", leaf = "Wszystkie", depth = 1)
        ))
        assertNull(chosen)
    }

    // ── Normalizacja ──────────────────────────────────────────────────────────

    @Test
    fun `normalizacja zdejmuje diakrytyke i wielkosc liter`() {
        assertEquals("wyslane", ImapSessions.normalizeFolderName("Wysłane"))
        assertEquals("elements envoyes", ImapSessions.normalizeFolderName("Éléments envoyés"))
        assertEquals("gesendet", ImapSessions.normalizeFolderName("GESENDET"))
        assertEquals("sent items", ImapSessions.normalizeFolderName("  Sent   Items "))
    }
}
