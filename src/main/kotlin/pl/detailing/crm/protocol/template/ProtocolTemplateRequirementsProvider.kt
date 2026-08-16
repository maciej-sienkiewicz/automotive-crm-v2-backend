package pl.detailing.crm.protocol.template

import org.springframework.stereotype.Component
import pl.detailing.crm.protocol.ProtocolTemplateRequirementsResponse
import pl.detailing.crm.protocol.LogoRequirements
import pl.detailing.crm.protocol.SignatureRequirements
import pl.detailing.crm.protocol.TemplateFieldRequirement
import pl.detailing.crm.protocol.TemplateFormatInfo
import pl.detailing.crm.protocol.TemplateInstructions
import pl.detailing.crm.shared.CrmDataKey

/**
 * Single source of truth for the template configuration requirements shown in the
 * "Dowiedz się więcej" modal on the Dokumenty i podpisy settings tab. The frontend
 * renders this payload — copy lives here so PDF and HTML rules never drift from
 * what the verification service actually enforces.
 */
@Component
class ProtocolTemplateRequirementsProvider {

    fun requirements(): ProtocolTemplateRequirementsResponse {
        val labels = mapOf(
            "brand" to "Marka pojazdu",
            "model" to "Model pojazdu",
            "licenseplate" to "Numer rejestracyjny",
            "mileage" to "Przebieg",
            "services" to "Zakres usługi (pole wieloliniowe)",
            "remarks" to "Uwagi (pole wieloliniowe)",
            "fullname" to "Imię i nazwisko klienta",
            "companyname" to "Nazwa firmy",
            "phonenumber" to "Numer telefonu",
            "email" to "Adres e-mail",
            "tax" to "NIP",
            "date" to "Data i godzina przyjęcia",
            "price" to "Łączny koszt usług",
            "keys" to "Przekazano kluczyk (checkbox)",
            "documents" to "Przekazano dokumenty (checkbox)"
        )
        val checkboxFields = setOf("keys", "documents")
        val multilineFields = setOf("services", "remarks")

        val mappedFields = DefaultProtocolFieldMappings.getDefaultMappings().map { (fieldName, crmKey) ->
            TemplateFieldRequirement(
                fieldName = fieldName,
                label = labels[fieldName] ?: crmKey.description,
                crmDataKey = crmKey.name,
                fieldType = when (fieldName) {
                    in checkboxFields -> "CHECKBOX"
                    in multilineFields -> "TEXT_MULTILINE"
                    else -> "TEXT"
                },
                required = true
            )
        }

        val signatureFields = listOf(
            TemplateFieldRequirement(
                fieldName = ProtocolTemplateVerificationService.CUSTOMER_SIGNATURE_FIELD,
                label = "Podpis zleceniodawcy (obszar podpisu klienta)",
                crmDataKey = null,
                fieldType = "SIGNATURE_AREA",
                required = true
            ),
            TemplateFieldRequirement(
                fieldName = ProtocolTemplateVerificationService.COMPANY_SIGNATURE_FIELD,
                label = "Podpis zleceniobiorcy (obszar podpisu pracownika)",
                crmDataKey = null,
                fieldType = "SIGNATURE_AREA",
                required = false
            )
        )

        return ProtocolTemplateRequirementsResponse(
            supportedFormats = listOf(
                TemplateFormatInfo(
                    format = "PDF",
                    contentType = "application/pdf",
                    description = "Wypełnialny formularz PDF (AcroForm). Pełne wsparcie: " +
                        "automatyczne uzupełnianie danymi z CRM, podpis na tablecie, " +
                        "pieczęć kwalifikowana i wysyłka e-mail.",
                    limitations = emptyList()
                ),
                TemplateFormatInfo(
                    format = "HTML",
                    contentType = "text/html",
                    description = "Samodzielny dokument HTML z placeholderami data-field. " +
                        "Uzupełniany danymi z CRM po stronie serwera; do podglądu i wydruku.",
                    limitations = listOf(
                        "Podpisywanie na tablecie i pieczęć kwalifikowana wymagają formatu PDF — " +
                            "dla pełnego obiegu podpisu użyj szablonu PDF."
                    )
                )
            ),
            fields = mappedFields + signatureFields,
            signature = SignatureRequirements(
                customerFieldName = ProtocolTemplateVerificationService.CUSTOMER_SIGNATURE_FIELD,
                companyFieldName = ProtocolTemplateVerificationService.COMPANY_SIGNATURE_FIELD,
                notes = listOf(
                    "W PDF pole 'signature' musi być polem formularza z widocznym obszarem na stronie — " +
                        "podpis z tabletu jest wpasowywany w ten obszar z zachowaniem proporcji.",
                    "Minimalny rozmiar obszaru podpisu: " +
                        "${ProtocolTemplateVerificationService.MIN_SIGNATURE_BOX_WIDTH_PT.toInt()}x" +
                        "${ProtocolTemplateVerificationService.MIN_SIGNATURE_BOX_HEIGHT_PT.toInt()}pt " +
                        "(zalecany: ok. 260x40pt — ~92x14mm).",
                    "Pole 'company_signature' jest opcjonalne — jeśli istnieje, nanoszony jest w nim " +
                        "skonfigurowany podpis pracownika."
                )
            ),
            logo = LogoRequirements(
                formats = listOf("SVG", "PNG (przezroczyste tło)"),
                minWidthPx = 800,
                recommendedWidthPx = 1600,
                minAspectRatio = "2:1",
                maxAspectRatio = "12:1",
                maxBoxMm = "66 x 14 mm"
            ),
            instructions = TemplateInstructions(
                pdf = listOf(
                    "Przygotuj jednostronicowy dokument A4 z układem protokołu (możesz pobrać szablon " +
                        "domyślny i użyć go jako bazy).",
                    "W programie do edycji PDF (Adobe Acrobat, LibreOffice Writer → eksport PDF z formularzem) " +
                        "dodaj pola formularza (AcroForm) o dokładnych nazwach z listy pól poniżej.",
                    "Pola 'keys' i 'documents' utwórz jako pola wyboru (checkbox), 'services' i 'remarks' " +
                        "jako pola tekstowe wieloliniowe, pozostałe jako zwykłe pola tekstowe.",
                    "Dodaj pole tekstowe o nazwie 'signature' w miejscu podpisu klienta " +
                        "(minimum 60x20pt, zalecane ok. 260x40pt) oraz opcjonalnie 'company_signature' " +
                        "w miejscu podpisu pracownika.",
                    "Nie ustawiaj własnych czcionek w polach — system nadpisuje je czcionką z polskimi " +
                        "znakami (Liberation Sans 7pt).",
                    "Wgraj plik i poczekaj na wynik weryfikacji — system sprawdzi obecność wszystkich " +
                        "wymaganych pól i rozmiar obszaru podpisu."
                ),
                html = listOf(
                    "Przygotuj samodzielny plik HTML (style CSS i czcionki osadzone w pliku — " +
                        "bez odwołań do zasobów zewnętrznych).",
                    "W miejscach, w które system ma wstawić dane z CRM, dodaj elementom atrybut " +
                        "data-field z nazwą pola z listy poniżej, np. <div data-field=\"brand\"></div>.",
                    "Dodaj elementy data-field=\"signature\" oraz opcjonalnie " +
                        "data-field=\"company_signature\" w miejscach podpisów.",
                    "Zadbaj o format wydruku A4 (reguła @page { size: A4 }) — dokument jest drukowany 1:1.",
                    "Wgraj plik i poczekaj na wynik weryfikacji obecności wymaganych placeholderów.",
                    "Uwaga: pełny obieg podpisu na tablecie wymaga szablonu PDF — HTML służy do " +
                        "podglądu i wydruku."
                )
            )
        )
    }
}
