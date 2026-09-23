package pl.detailing.crm.rolepreview

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration

/**
 * Podgląd roli - piaskownica, w której administrator ogląda CRM oczami pracownika z daną rolą.
 *
 * Domyślnie wyłączony: podgląd działa wyłącznie pod osobnym adresem (subdomeną), którą
 * trzeba najpierw wystawić w DNS i w nginx (nagłówek [HOST_HEADER]). Bez tego przycisk
 * „Przejdź do podglądu roli" się nie pokazuje, a wejście do piaskownicy jest odrzucane.
 */
@ConfigurationProperties(prefix = "crm.role-preview")
data class RolePreviewProperties(

    val enabled: Boolean = false,

    /**
     * Adres podglądu, np. `https://podglad.detailboost.pl`. Osobny host, bo ciasteczka
     * sesji są przypisane do hosta: sesja piaskownicy pod adresem aplikacji nadpisałaby
     * sesję administratora we wszystkich jego kartach.
     */
    val baseUrl: String = "",

    /** Po tylu minutach bez żadnego żądania piaskownica wygasa. */
    val idleTimeoutMinutes: Long = 30,

    /** Najdłuższe życie piaskownicy, niezależnie od aktywności. */
    val maxLifetimeMinutes: Long = 120,

    /**
     * Ile sekund żyje kod wejścia. Okno podglądu otwiera się od razu po kliknięciu i czeka,
     * aż piaskownica będzie gotowa - kod musi przeżyć jej przygotowanie, ale nic ponad to.
     */
    val entryCodeTtlSeconds: Long = 120,

    /** Ile piaskownic naraz może mieć otwartych jedno prawdziwe studio. */
    val maxActivePerStudio: Int = 5,

    /** Co ile milisekund zadanie sprzątające usuwa wygasłe piaskownice. */
    val cleanupIntervalMs: Long = 60_000
) {
    companion object {
        /**
         * Nagłówek, który nginx dokleja do każdego żądania przychodzącego na adres podglądu
         * (i wycina z żądań na adres aplikacji). Tylko pod nim działają sesje piaskownic.
         */
        const val HOST_HEADER = "X-Role-Preview-Host"
    }
}

@Configuration
@EnableConfigurationProperties(RolePreviewProperties::class)
class RolePreviewConfig
