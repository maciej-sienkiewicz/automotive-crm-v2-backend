package pl.detailing.crm.comms.signature

import pl.detailing.crm.shared.ValidationException

/**
 * Motywy konfiguratora stopki. Zestaw jest celowo krótki: każdy motyw to osobny układ
 * tabel, który musi przejść przez Gmaila, Outlooka i Apple Mail, więc każdy dodatkowy
 * to koszt utrzymania, a nie „jeszcze jedna opcja za darmo".
 *
 * [id] jest kontraktem z frontendem (`signatureTemplates.ts`) i trafia do bazy w JSON-ie
 * projektu — zmiana nazwy osieroci zapisane stopki.
 */
enum class MailSignatureTemplate(val id: String) {
    CLASSIC("klasyczna"),
    PHOTO("ze-zdjeciem"),
    COMPANY_LOGO("firmowa"),
    CIRCLE_BANNER("baner-okrag"),
    TWO_BANDS("dwa-pasma");

    companion object {
        fun fromId(id: String?): MailSignatureTemplate? = entries.firstOrNull { it.id == id }
    }
}

/**
 * Projekt stopki z konfiguratora: motyw, dane i styl. Serwer przechowuje go obok
 * wyrenderowanego HTML-a wyłącznie po to, żeby kreator otworzył się z tym, co użytkownik
 * ustawił poprzednio. Wysyłany jest zawsze HTML — renderer jest jeden (frontend, ten sam,
 * który rysuje podgląd na żywo), bo dwa renderery prędzej czy później pokazałyby co innego
 * w podglądzie, a co innego u odbiorcy.
 *
 * Wszystkie pola tekstowe są opcjonalne poza imieniem i nazwiskiem: pusty wiersz
 * po prostu nie pojawia się w stopce.
 */
data class MailSignatureDesign(
    val template: String,
    val fullName: String? = null,
    val position: String? = null,
    val company: String? = null,
    val phone: String? = null,
    val phone2: String? = null,
    val phoneLand: String? = null,
    val fax: String? = null,
    val email: String? = null,
    val website: String? = null,
    val address: String? = null,
    val disclaimer: String? = null,
    /** Absolutny adres https — obrazek pobiera klient poczty odbiorcy, nie nasza aplikacja. */
    val photoUrl: String? = null,
    val logoUrl: String? = null,
    val linkedin: String? = null,
    val facebook: String? = null,
    val instagram: String? = null,
    val youtube: String? = null,
    val tiktok: String? = null,
    val color: String = DEFAULT_COLOR,
    val font: String = DEFAULT_FONT,
    val size: String = DEFAULT_SIZE,
    val iconStyle: String = DEFAULT_ICON_STYLE
) {

    /**
     * Postać zapisywana do bazy: przycięte pola, puste jako `null`, kolor małymi literami.
     * Rzuca [ValidationException] z komunikatem dla użytkownika, gdy czegoś nie da się
     * zapisać — lepiej odmówić zapisu niż zapisać projekt, którego kreator nie odtworzy.
     */
    fun normalized(): MailSignatureDesign {
        if (MailSignatureTemplate.fromId(template) == null) {
            throw ValidationException("Nieznany motyw stopki")
        }
        val normalizedColor = color.trim().lowercase()
        if (!COLOR.matches(normalizedColor)) throw ValidationException("Kolor przewodni musi mieć postać #RRGGBB")
        if (font !in FONTS) throw ValidationException("Nieznana czcionka stopki")
        if (size !in SIZES) throw ValidationException("Nieznana wielkość tekstu stopki")
        if (iconStyle !in ICON_STYLES) throw ValidationException("Nieznany styl ikon stopki")

        val design = copy(
            fullName = line(fullName, "Imię i nazwisko"),
            position = line(position, "Stanowisko"),
            company = line(company, "Firma"),
            phone = line(phone, "Telefon"),
            phone2 = line(phone2, "Drugi telefon"),
            phoneLand = line(phoneLand, "Telefon stacjonarny"),
            fax = line(fax, "Fax"),
            email = line(email, "Adres e-mail"),
            website = link(website, "Strona WWW"),
            address = line(address, "Adres"),
            disclaimer = text(disclaimer, "Stopka prawna", MAX_DISCLAIMER),
            photoUrl = image(photoUrl, "Zdjęcie"),
            logoUrl = image(logoUrl, "Logo"),
            linkedin = link(linkedin, "LinkedIn"),
            facebook = link(facebook, "Facebook"),
            instagram = link(instagram, "Instagram"),
            youtube = link(youtube, "YouTube"),
            tiktok = link(tiktok, "TikTok"),
            color = normalizedColor
        )
        if (design.fullName == null) throw ValidationException("Podaj imię i nazwisko do stopki")
        return design
    }

    companion object {
        const val DEFAULT_COLOR = "#c0272d"
        const val DEFAULT_FONT = "arial"
        const val DEFAULT_SIZE = "m"
        const val DEFAULT_ICON_STYLE = "mono"

        /** Czcionki bezpieczne w poczcie — każda jest na Windowsie, macOS i w webmailach. */
        val FONTS = setOf("arial", "helvetica", "verdana", "trebuchet", "tahoma", "georgia", "times")
        val SIZES = setOf("s", "m", "l")
        val ICON_STYLES = setOf("mono", "color", "color-sq")

        const val MAX_LINE = 160
        const val MAX_LINK = 300
        const val MAX_IMAGE_URL = 500
        const val MAX_DISCLAIMER = 1000

        private val COLOR = Regex("^#[0-9a-f]{6}$")
        private val SCHEME = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:")
        private val CONTROL = Regex("[\\p{Cntrl}&&[^\\n\\t]]")

        private fun line(value: String?, label: String): String? {
            val trimmed = value?.replace(Regex("\\s+"), " ")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            if (trimmed.length > MAX_LINE) throw ValidationException("$label: maksymalnie $MAX_LINE znaków")
            return trimmed
        }

        private fun text(value: String?, label: String, max: Int): String? {
            val trimmed = value?.replace(CONTROL, "")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            if (trimmed.length > max) throw ValidationException("$label: maksymalnie $max znaków")
            return trimmed
        }

        /**
         * Link profilu albo strony. Bez schematu („www.firma.pl") jest w porządku —
         * renderer dopisze https://. Schemat inny niż http(s) to nie link do profilu,
         * tylko próba przemycenia `javascript:` do cudzej skrzynki.
         */
        private fun link(value: String?, label: String): String? {
            val trimmed = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            if (trimmed.length > MAX_LINK) throw ValidationException("$label: adres jest za długi")
            if (trimmed.any { it.isWhitespace() }) throw ValidationException("$label: adres nie może zawierać spacji")
            val scheme = SCHEME.find(trimmed)?.value?.lowercase()
            // „firma.pl:8080" też pasuje do wzorca schematu — port (cyfra po dwukropku) nim nie jest.
            val isPort = scheme != null && trimmed.getOrNull(scheme.length)?.isDigit() == true
            if (scheme != null && !isPort && scheme != "http:" && scheme != "https:") {
                throw ValidationException("$label: dozwolone są tylko adresy http(s)")
            }
            return trimmed
        }

        /**
         * Obrazek musi mieć absolutny adres https: pobiera go klient poczty odbiorcy,
         * więc adres względny nie zadziała nigdzie poza naszą aplikacją, a http blokuje
         * część skrzynek. `data:` odpada, bo Gmail i Outlook go nie wyświetlają.
         */
        private fun image(value: String?, label: String): String? {
            val trimmed = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            if (trimmed.length > MAX_IMAGE_URL) throw ValidationException("$label: adres obrazka jest za długi")
            if (!trimmed.startsWith("https://", ignoreCase = true) || trimmed.any { it.isWhitespace() }) {
                throw ValidationException("$label: podaj pełny adres obrazka zaczynający się od https://")
            }
            return trimmed
        }
    }
}
