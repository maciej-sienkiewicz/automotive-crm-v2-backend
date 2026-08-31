package pl.detailing.crm.customer.importing

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration

/** Ograniczenia importu kontaktów — wielkości, po których przekroczeniu coś jest nie tak. */
@ConfigurationProperties(prefix = "crm.customers.import")
data class CustomerImportProperties(

    /**
     * Ile minut żyje kod QR. Krótko, bo to sekret wyświetlony na ekranie w warsztacie —
     * a przez ten czas ma tylko dojść do telefonu, który stoi obok.
     */
    val handoffTtlMinutes: Long = 15,

    /**
     * Ile godzin żyje sesja z wgranymi już kontaktami. Tyle, żeby dało się przejrzeć
     * listę po przerwie, i nie więcej: sesja trzyma czyjąś książkę adresową.
     */
    val sessionTtlHours: Long = 4,

    /** Górna granica jednego importu. Powyżej to już migracja bazy, nie import kontaktów. */
    val maxContacts: Int = 5000,

    /** Maksymalny rozmiar pliku `.vcf`. Zdjęcia w wizytówkach potrafią go rozdąć. */
    val maxFileSizeBytes: Long = 10 * 1024 * 1024,

    /** Cron sprzątania wygasłych sesji. */
    val purgeCron: String = "0 20 3 * * *"
)

@Configuration
@EnableConfigurationProperties(CustomerImportProperties::class)
class CustomerImportConfig
