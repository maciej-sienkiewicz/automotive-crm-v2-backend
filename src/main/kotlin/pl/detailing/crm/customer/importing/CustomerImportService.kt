package pl.detailing.crm.customer.importing

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.audit.domain.AuditAction
import pl.detailing.crm.audit.domain.AuditActor
import pl.detailing.crm.audit.domain.AuditEvent
import pl.detailing.crm.audit.domain.AuditModule
import pl.detailing.crm.audit.domain.AuditService
import pl.detailing.crm.customer.infrastructure.CustomerEntity
import pl.detailing.crm.customer.infrastructure.CustomerRepository
import pl.detailing.crm.shared.EntityNotFoundException
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import pl.detailing.crm.shared.ValidationException
import pl.detailing.crm.shared.normalizeToE164
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID

/**
 * Co import zamierza zrobić z jednym kontaktem — i dlaczego.
 *
 * Status jest tu ważniejszy niż same dane: to on decyduje, czy wiersz jest domyślnie
 * zaznaczony, i to on chroni studio przed drugą kartoteką tego samego klienta.
 */
enum class ImportRowStatus {
    /** Nie ma takiego klienta — do zaimportowania. Jedyny status zaznaczony domyślnie. */
    NEW,

    /** Klient już jest w bazie (ten sam numer albo e-mail). Pokazujemy, którym jest. */
    EXISTING,

    /** Ten sam kontakt występuje wyżej na tej samej liście — książki adresowe ich pełne. */
    DUPLICATE_IN_FILE,

    /**
     * Nie ma ani numeru, ani e-maila w postaci, którą da się rozpoznać. Takiego wpisu nie
     * da się z niczym powiązać ani do niczego użyć — importowanie go tworzy pustą
     * kartotekę, której nikt później nie posprząta.
     */
    NOT_IMPORTABLE
}

data class ImportPreviewRow(
    /** Pozycja w liście sesji — stabilny identyfikator wiersza dla zatwierdzenia. */
    val index: Int,
    val firstName: String?,
    val lastName: String?,
    val displayName: String?,
    val phone: String?,
    val email: String?,
    val companyName: String?,
    val status: ImportRowStatus,
    /** Klient, z którym wiersz się pokrywa — tylko dla [ImportRowStatus.EXISTING]. */
    val matchedCustomerId: UUID?,
    val matchedCustomerName: String?,
    /** Po czym rozpoznano zbieżność: `phone` albo `email`. Żeby dało się to zweryfikować. */
    val matchedBy: String?,
    /** Czy wiersz ma być zaznaczony po otwarciu podglądu. */
    val selectedByDefault: Boolean
)

data class ImportPreview(
    val sessionId: UUID,
    val status: CustomerImportStatus,
    val source: CustomerImportSource,
    val deviceLabel: String?,
    val rows: List<ImportPreviewRow>,
    val newCount: Int,
    val existingCount: Int,
    val duplicateCount: Int,
    val notImportableCount: Int
)

data class ImportCommitResult(
    val imported: Int,
    val skipped: Int
)

/**
 * Import kontaktów do kartoteki klientów.
 *
 * ## Dwie drogi, jeden przebieg
 *
 * Android przysyła kontakty z telefonu (systemowy wybór po zeskanowaniu kodu QR), iPhone
 * idzie przez plik `.vcf` wgrany na komputerze — ale od momentu, w którym kontakty
 * wylądują w sesji, dzieje się dokładnie to samo: podgląd z wykrytymi duplikatami,
 * odznaczanie, zatwierdzenie. Różnica między platformami kończy się na wejściu.
 *
 * ## Dlaczego podgląd liczy się przy każdym odczycie
 *
 * Statusy nie są zapisywane razem z kontaktami. Między przesłaniem listy a kliknięciem
 * „Zapisz" mija czasem kwadrans, w którym ktoś inny mógł założyć tego samego klienta —
 * a import, który pokazuje stan sprzed kwadransa, zrobi duplikat mimo działającego
 * wykrywania duplikatów. Zatwierdzenie dodatkowo sprawdza wszystko jeszcze raz.
 */
@Service
class CustomerImportService(
    private val sessionRepository: CustomerImportSessionRepository,
    private val customerRepository: CustomerRepository,
    private val vCardParser: VCardParser,
    private val auditService: AuditService,
    private val properties: CustomerImportProperties,
    private val objectMapper: ObjectMapper
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val secureRandom = SecureRandom()

    // ── Zakładanie sesji ─────────────────────────────────────────────────────

    /** Sesja dla telefonu: pusta, z tokenem do kodu QR, czeka na kontakty. */
    @Transactional
    fun openHandoffSession(studioId: StudioId, userId: UserId): CustomerImportSessionEntity {
        val now = Instant.now()
        val session = CustomerImportSessionEntity(
            id = UUID.randomUUID(),
            studioId = studioId.value,
            createdBy = userId.value,
            source = CustomerImportSource.ANDROID_PICKER,
            status = CustomerImportStatus.AWAITING_CONTACTS,
            handoffToken = generateHandoffToken(),
            createdAt = now,
            updatedAt = now,
            expiresAt = now.plus(Duration.ofMinutes(properties.handoffTtlMinutes))
        )
        return sessionRepository.save(session)
    }

    /** Sesja z pliku `.vcf`: od razu gotowa do podglądu. */
    @Transactional
    fun openFileSession(
        studioId: StudioId,
        userId: UserId,
        fileName: String?,
        content: String
    ): CustomerImportSessionEntity {
        val parsed = vCardParser.parse(content)
        if (parsed.isEmpty()) {
            throw ValidationException(
                "W pliku nie znaleziono żadnych kontaktów. Upewnij się, że to plik vCard (.vcf)."
            )
        }
        requireWithinLimit(parsed.size)

        val now = Instant.now()
        val session = CustomerImportSessionEntity(
            id = UUID.randomUUID(),
            studioId = studioId.value,
            createdBy = userId.value,
            source = CustomerImportSource.VCARD_FILE,
            status = CustomerImportStatus.READY,
            handoffToken = null,
            contactsJson = writeContacts(parsed.map(::toStored)),
            deviceLabel = fileName?.take(120),
            createdAt = now,
            updatedAt = now,
            expiresAt = now.plus(Duration.ofHours(properties.sessionTtlHours))
        )
        return sessionRepository.save(session)
    }

    /**
     * Telefon oddaje kontakty. Woła to publiczny endpoint uwierzytelniony wyłącznie
     * tokenem z kodu QR — stąd sprawdzenie wygaśnięcia i jednorazowość tokenu tutaj,
     * a nie w kontrolerze.
     */
    @Transactional
    fun submitFromDevice(
        handoffToken: String,
        contacts: List<StoredContact>,
        deviceLabel: String?
    ): CustomerImportSessionEntity {
        val session = sessionRepository.findByHandoffToken(handoffToken)
            ?: throw EntityNotFoundException("Sesja importu nie została znaleziona lub wygasła")

        if (session.isExpired()) {
            throw ValidationException("Kod wygasł. Wygeneruj nowy na komputerze.")
        }
        if (session.status != CustomerImportStatus.AWAITING_CONTACTS) {
            throw ValidationException("Ta sesja importu została już wykorzystana.")
        }
        if (contacts.isEmpty()) {
            throw ValidationException("Nie wybrano żadnych kontaktów.")
        }
        requireWithinLimit(contacts.size)

        session.contactsJson = writeContacts(contacts)
        session.deviceLabel = deviceLabel?.take(120)
        session.status = CustomerImportStatus.READY
        // Token zużyty: kod z ekranu działa raz, więc podejrzany zrzut ekranu nie daje
        // możliwości podmiany listy tuż przed zatwierdzeniem.
        session.handoffToken = null
        session.updatedAt = Instant.now()
        session.expiresAt = Instant.now().plus(Duration.ofHours(properties.sessionTtlHours))

        return sessionRepository.save(session)
    }

    // ── Podgląd ──────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    fun preview(sessionId: UUID, studioId: StudioId): ImportPreview {
        val session = requireSession(sessionId, studioId)
        val rows = buildRows(session)

        return ImportPreview(
            sessionId = session.id,
            status = session.status,
            source = session.source,
            deviceLabel = session.deviceLabel,
            rows = rows,
            newCount = rows.count { it.status == ImportRowStatus.NEW },
            existingCount = rows.count { it.status == ImportRowStatus.EXISTING },
            duplicateCount = rows.count { it.status == ImportRowStatus.DUPLICATE_IN_FILE },
            notImportableCount = rows.count { it.status == ImportRowStatus.NOT_IMPORTABLE }
        )
    }

    private fun buildRows(session: CustomerImportSessionEntity): List<ImportPreviewRow> {
        val contacts = readContacts(session)
        if (contacts.isEmpty()) return emptyList()

        // Normalizacja najpierw, dopasowanie potem — obie strony porównania muszą być
        // w tej samej postaci, inaczej „+48 534 920 205" i „534920205" to dwie osoby.
        val normalized = contacts.map { contact ->
            NormalizedContact(
                phone = contact.phones.firstNotNullOfOrNull { normalizeToE164(it) },
                email = contact.emails.firstOrNull { it.contains('@') }?.trim()?.lowercase(),
                raw = contact
            )
        }

        val byPhone = matchesByPhone(session.studioId, normalized)
        val byEmail = matchesByEmail(session.studioId, normalized)

        val seenPhones = mutableSetOf<String>()
        val seenEmails = mutableSetOf<String>()

        return normalized.mapIndexed { index, contact ->
            val existing = contact.phone?.let(byPhone::get) ?: contact.email?.let(byEmail::get)
            val matchedBy = when {
                existing == null -> null
                contact.phone != null && byPhone.containsKey(contact.phone) -> "phone"
                else -> "email"
            }

            val duplicateInFile =
                (contact.phone != null && !seenPhones.add(contact.phone)) ||
                    (contact.email != null && !seenEmails.add(contact.email))

            val status = when {
                contact.phone == null && contact.email == null -> ImportRowStatus.NOT_IMPORTABLE
                existing != null -> ImportRowStatus.EXISTING
                duplicateInFile -> ImportRowStatus.DUPLICATE_IN_FILE
                else -> ImportRowStatus.NEW
            }

            ImportPreviewRow(
                index = index,
                firstName = contact.raw.firstName,
                lastName = contact.raw.lastName,
                displayName = contact.raw.displayName,
                phone = contact.phone ?: contact.raw.phones.firstOrNull(),
                email = contact.email,
                companyName = contact.raw.companyName,
                status = status,
                matchedCustomerId = existing?.id,
                matchedCustomerName = existing?.let(::displayNameOf),
                matchedBy = matchedBy,
                // Domyślnie zaznaczone są WYŁĄCZNIE nowe kontakty. Odwrotna domyślność
                // byłaby zaproszeniem do wpisania całej książki adresowej — z rodziną,
                // pizzerią i infolinią operatora — do bazy, z której idą kampanie SMS.
                selectedByDefault = status == ImportRowStatus.NEW
            )
        }
    }

    private fun matchesByPhone(
        studioId: UUID,
        contacts: List<NormalizedContact>
    ): Map<String, CustomerEntity> {
        val phones = contacts.mapNotNull { it.phone }.distinct()
        if (phones.isEmpty()) return emptyMap()
        return phones.chunked(CHUNK)
            .flatMap { customerRepository.findActiveByStudioIdAndPhoneE164In(studioId, it) }
            .mapNotNull { customer -> customer.phoneE164?.let { it to customer } }
            .toMap()
    }

    private fun matchesByEmail(
        studioId: UUID,
        contacts: List<NormalizedContact>
    ): Map<String, CustomerEntity> {
        val emails = contacts.mapNotNull { it.email }.distinct()
        if (emails.isEmpty()) return emptyMap()
        return emails.chunked(CHUNK)
            .flatMap { customerRepository.findActiveByStudioIdAndEmailLowerIn(studioId, it) }
            .mapNotNull { customer -> customer.email?.lowercase()?.let { it to customer } }
            .toMap()
    }

    // ── Zatwierdzenie ────────────────────────────────────────────────────────

    /**
     * Zapisuje wybrane kontakty jako klientów.
     *
     * Wiersze przysłane przez przeglądarkę są **życzeniem, nie rozstrzygnięciem**: statusy
     * liczone są tu jeszcze raz, na aktualnym stanie bazy. Wiersz, który w międzyczasie
     * przestał być nowy, jest pomijany — nawet jeśli w przeglądarce nadal wygląda na nowy.
     */
    @Transactional
    fun commit(
        sessionId: UUID,
        studioId: StudioId,
        userId: UserId,
        userName: String?,
        selectedIndexes: Set<Int>
    ): ImportCommitResult {
        val session = requireSession(sessionId, studioId)
        if (session.status == CustomerImportStatus.COMMITTED) {
            throw ValidationException("Ten import został już zapisany.")
        }
        if (session.status != CustomerImportStatus.READY) {
            throw ValidationException("Import nie ma jeszcze żadnych kontaktów.")
        }

        val rows = buildRows(session)
        val importable = rows.filter { it.index in selectedIndexes && it.status == ImportRowStatus.NEW }
        val skipped = selectedIndexes.size - importable.size

        val now = Instant.now()
        val entities = importable.map { row ->
            CustomerEntity(
                id = UUID.randomUUID(),
                studioId = studioId.value,
                firstName = row.firstName?.trim()?.take(100),
                lastName = row.lastName?.trim()?.take(100),
                email = row.email?.take(255),
                /*
                 * Do bazy trafia wyłącznie numer, który dało się znormalizować.
                 * `row.phone` bywa surowym zapisem z telefonu (pokazujemy go w podglądzie
                 * takim, jaki jest), a w książce adresowej „numerem" bywa też „brak"
                 * albo „do żony". Kolumna ma 20 znaków i jest kluczem dopasowania —
                 * nie miejsce na notatki.
                 */
                phone = normalizeToE164(row.phone),
                homeAddressStreet = null,
                homeAddressCity = null,
                homeAddressPostalCode = null,
                homeAddressCountry = null,
                companyName = row.companyName?.trim()?.take(200),
                companyNip = null,
                companyRegon = null,
                companyAddressStreet = null,
                companyAddressCity = null,
                companyAddressPostalCode = null,
                companyAddressCountry = null,
                isActive = true,
                createdBy = userId.value,
                updatedBy = userId.value,
                createdAt = now,
                updatedAt = now
            )
        }
        customerRepository.saveAll(entities)

        session.status = CustomerImportStatus.COMMITTED
        session.committedAt = now
        session.importedCount = entities.size
        session.updatedAt = now
        // Książka adresowa znika z bazy od razu po imporcie. Zaimportowani są już
        // klientami, a reszta — ludzie, którzy klientami studia nigdy nie byli — nie ma
        // powodu leżeć dalej w naszej bazie.
        session.contactsJson = "[]"
        sessionRepository.save(session)

        recordAudit(studioId, userId, userName, session, entities.size, skipped)

        logger.info(
            "Import kontaktów {}: zapisano {} klientów, pominięto {} (źródło {})",
            session.id, entities.size, skipped, session.source
        )
        return ImportCommitResult(imported = entities.size, skipped = skipped)
    }

    /**
     * Jeden wpis na cały import, nie jeden na klienta.
     *
     * Trzysta wierszy „Utworzono klienta" zalałoby Aktywność tak, że nie dałoby się
     * w niej znaleźć niczego innego z tego dnia — a informacja i tak jest jedna:
     * ktoś wciągnął książkę adresową.
     */
    private fun recordAudit(
        studioId: StudioId,
        userId: UserId,
        userName: String?,
        session: CustomerImportSessionEntity,
        imported: Int,
        skipped: Int
    ) {
        auditService.recordSync(AuditEvent(
            studioId = studioId,
            actor = AuditActor.employee(userId, userName),
            module = AuditModule.CUSTOMER,
            action = AuditAction.CUSTOMERS_IMPORTED,
            entityId = session.id.toString(),
            entityDisplayName = when (session.source) {
                CustomerImportSource.ANDROID_PICKER -> "Kontakty z telefonu"
                CustomerImportSource.VCARD_FILE -> session.deviceLabel ?: "Plik vCard"
            },
            metadata = mapOf(
                "source" to session.source.name,
                "importedCount" to imported.toString(),
                "skippedCount" to skipped.toString(),
                "deviceLabel" to (session.deviceLabel ?: "")
            )
        ))
    }

    // ── Sprzątanie ───────────────────────────────────────────────────────────

    /**
     * Usuwa wygasłe sesje. Nie jest to optymalizacja miejsca, tylko higiena danych:
     * porzucona sesja trzyma czyjąś książkę adresową — dane osób, które nie są i nie
     * będą klientami studia.
     */
    @Transactional
    fun purgeExpired(): Int = sessionRepository.deleteExpired(Instant.now())

    // ── Pomocnicze ───────────────────────────────────────────────────────────

    private fun requireSession(sessionId: UUID, studioId: StudioId): CustomerImportSessionEntity =
        sessionRepository.findByIdAndStudioId(sessionId, studioId.value)
            ?: throw EntityNotFoundException("Sesja importu nie została znaleziona")

    private fun requireWithinLimit(count: Int) {
        if (count > properties.maxContacts) {
            throw ValidationException(
                "Jednorazowo można zaimportować najwyżej ${properties.maxContacts} kontaktów " +
                    "(otrzymano $count). Podziel plik na części."
            )
        }
    }

    private fun generateHandoffToken(): String {
        val bytes = ByteArray(24)
        secureRandom.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun displayNameOf(customer: CustomerEntity): String =
        listOfNotNull(customer.firstName, customer.lastName)
            .joinToString(" ")
            .ifBlank { customer.companyName ?: customer.phone ?: customer.email ?: "Klient" }

    private fun writeContacts(contacts: List<StoredContact>): String =
        objectMapper.writeValueAsString(contacts)

    /**
     * Odczyt listy z sesji. Uszkodzony ładunek nie może wywrócić podglądu — sesja jest
     * jednorazowa i wyrzucalna, więc pusta lista i wpis w logu są tu właściwszą reakcją
     * niż błąd 500 na ekranie kogoś, kto właśnie próbuje wciągnąć swoich klientów.
     */
    private fun readContacts(session: CustomerImportSessionEntity): List<StoredContact> =
        runCatching { objectMapper.readValue<List<StoredContact>>(session.contactsJson) }
            .onFailure { logger.error("Nieczytelny ładunek sesji importu {}: {}", session.id, it.message) }
            .getOrDefault(emptyList())

    private fun toStored(parsed: ParsedContact) = StoredContact(
        firstName = parsed.firstName,
        lastName = parsed.lastName,
        displayName = parsed.displayName,
        phones = parsed.phones,
        emails = parsed.emails,
        companyName = parsed.companyName
    )

    private data class NormalizedContact(
        val phone: String?,
        val email: String?,
        val raw: StoredContact
    )

    private companion object {
        /** Postgres nie lubi bardzo długich list w `IN` — dzielimy zapytania na porcje. */
        const val CHUNK = 500
    }
}
