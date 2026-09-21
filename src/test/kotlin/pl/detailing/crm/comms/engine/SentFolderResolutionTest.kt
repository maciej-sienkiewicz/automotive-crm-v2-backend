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
        otherRole: Boolean = false,
        /** null = nie pytaliśmy serwera; liczba = tyle wiadomości zgłosił. */
        messages: Int? = null
    ) = SentCandidate(fullName, leaf, depth, sent, otherRole, messages)

    // ── Zawartość folderu bije nazwę ──────────────────────────────────────────
    //
    // Skrzynka po latach pracy ma po kilka folderów wysłanych naraz — polski obok
    // angielskiego, a prawdziwy ruch pod „INBOX.". Nazwa bywa myląca u każdego
    // dostawcy inaczej; pusty folder jest pusty wszędzie tak samo.

    /** Przypadek z produkcji: trzy foldery wysłanych, poczta w najgłębszym. */
    @Test
    fun `folder z wiadomosciami wygrywa z pustym o lepszej nazwie`() {
        val chosen = ImapSessions.chooseSentFolder(listOf(
            folder("Elementy wysłane", messages = 0),
            folder("Sent", messages = 0),
            folder("INBOX.Sent", leaf = "Sent", depth = 1, messages = 1088),
            folder("INBOX", leaf = "INBOX")
        ))
        assertEquals("INBOX.Sent", chosen)
    }

    @Test
    fun `pusty SPECIAL-USE przegrywa z niepustym dopasowaniem po nazwie`() {
        val chosen = ImapSessions.chooseSentFolder(listOf(
            folder("Elementy wysłane", sent = true, messages = 0),
            folder("INBOX.Sent", leaf = "Sent", depth = 1, messages = 412)
        ))
        assertEquals("INBOX.Sent", chosen)
    }

    @Test
    fun `przy dwoch niepustych decyduje nazwa, potem glebokosc`() {
        val chosen = ImapSessions.chooseSentFolder(listOf(
            folder("Archiwum/Sent", leaf = "Sent", depth = 1, messages = 900),
            folder("Elementy wysłane", sent = true, messages = 12)
        ))
        assertEquals("Elementy wysłane", chosen, "SPECIAL-USE wygrywa, gdy obydwa mają pocztę")
    }

    /**
     * „Nie wiem" to nie to samo co „pusty": sonda bywa pominięta (jeden kandydat)
     * albo serwer nie odpowie na STATUS. Wtedy decyduje dokładnie ta sama kolejność
     * co przed wprowadzeniem sondy.
     */
    @Test
    fun `brak danych o liczbie wiadomosci nie dyskwalifikuje kandydata`() {
        val chosen = ImapSessions.chooseSentFolder(listOf(
            folder("Elementy wysłane", messages = 0),
            folder("INBOX.Sent", leaf = "Sent", depth = 1, messages = null)
        ))
        assertEquals("INBOX.Sent", chosen, "kandydat bez pomiaru idzie przed tym, o którym wiemy, że pusty")
    }

    @Test
    fun `swieza skrzynka z samymi pustymi folderami dalej wskazuje ten wlasciwy`() {
        val chosen = ImapSessions.chooseSentFolder(listOf(
            folder("Drafts", otherRole = true, messages = 3),
            folder("Sent", messages = 0),
            folder("INBOX", leaf = "INBOX")
        ))
        assertEquals("Sent", chosen, "pusty Sent to nadal Sent — nie ma czego wybrać lepiej")
    }

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

    // ── Skanujemy WSZYSTKIE foldery, a nie zwycięzcę ──────────────────────────
    //
    // Produkcja pokazała, że wybór jednego folderu jest zakładem o cudzy nawyk i że
    // przegrany zakład nie daje żadnego objawu: skan melduje „0 nowych", bo w wybranym
    // folderze faktycznie nic nowego nie ma. Te testy pilnują, żeby żaden wiarygodny
    // folder nie wypadł z listy - kolejność jest podpowiedzią, nie filtrem.

    /**
     * Dokładny układ skrzynki biuro@carslab.pl z 21 września: najpierw czytaliśmy pusty
     * „Elementy wysłane", potem „Sent" stojący na UID 1093, a poczta szła do „INBOX.Sent".
     * Obie pomyłki znikają dopiero wtedy, gdy na liście są wszystkie trzy.
     */
    @Test
    fun `wszystkie trzy foldery wyslanych trafiaja na liste`() {
        val order = ImapSessions.orderSentFolders(listOf(
            folder("Elementy wysłane", messages = 0),
            folder("Sent", messages = 106),
            folder("INBOX.Sent", leaf = "Sent", depth = 1, messages = 67),
            folder("INBOX", leaf = "INBOX"),
            folder("Kosz", messages = 400),
            folder("Kopie robocze", messages = 3)
        ))

        assertEquals(listOf("Sent", "INBOX.Sent", "Elementy wysłane"), order)
    }

    @Test
    fun `pusty folder zostaje na liscie, tylko na jej koncu`() {
        // Pusty dziś nie znaczy pusty jutro: to do niego może zacząć pisać kolejny
        // program pocztowy. Wypadnięcie z listy byłoby tą samą pomyłką co wcześniej.
        val order = ImapSessions.orderSentFolders(listOf(
            folder("Sent", messages = 0),
            folder("Wysłane", messages = 12)
        ))

        assertEquals(listOf("Wysłane", "Sent"), order)
    }

    @Test
    fun `foldery o innej roli nie wchodza na liste, choc maja poczte`() {
        val order = ImapSessions.orderSentFolders(listOf(
            folder("Kosz", messages = 900),
            folder("Spam", messages = 500),
            folder("Archiwum", otherRole = true, messages = 4000),
            folder("Sent", messages = 1)
        ))

        assertEquals(listOf("Sent"), order)
    }

    @Test
    fun `skrzynka z jednym folderem wyslanych oddaje dokladnie jeden`() {
        // Większość skrzynek. Nie ma czego rozstrzygać i nie ma za co płacić sondą.
        val order = ImapSessions.orderSentFolders(listOf(
            folder("INBOX", leaf = "INBOX"),
            folder("Wysłane")
        ))

        assertEquals(listOf("Wysłane"), order)
    }

    @Test
    fun `czolo listy jest tym samym wyborem co dotad`() {
        // APPEND po własnej wysyłce idzie do jednego folderu, więc wybór nie znika -
        // schodzi tylko do roli „pierwszy na liście".
        val candidates = listOf(
            folder("Elementy wysłane", messages = 0),
            folder("Sent", messages = 106),
            folder("INBOX.Sent", leaf = "Sent", depth = 1, messages = 67)
        )

        assertEquals(ImapSessions.orderSentFolders(candidates).first(), ImapSessions.chooseSentFolder(candidates))
    }

    @Test
    fun `brak kandydatow to pusta lista, a nie wyjatek`() {
        val order = ImapSessions.orderSentFolders(listOf(
            folder("INBOX", leaf = "INBOX"),
            folder("Kosz")
        ))

        assertEquals(emptyList<String>(), order)
    }
}
