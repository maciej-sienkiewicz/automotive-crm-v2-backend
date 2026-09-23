package pl.detailing.crm.rolepreview

import pl.detailing.crm.role.PermissionModuleTreeResponse
import java.time.Instant

data class RolePreviewConfigResponse(
    /** False, dopóki adres podglądu nie jest skonfigurowany - wtedy przycisk się nie pokazuje. */
    val enabled: Boolean,
    val previewBaseUrl: String?
)

data class StartRolePreviewRequest(
    val roleName: String,
    val permissions: List<String>,
    val trackWorkTime: Boolean = false,
    /**
     * Jednorazowy kod wejścia wygenerowany w przeglądarce administratora (32 losowe bajty,
     * base64url). Przeglądarka otwiera okno podglądu z tym kodem od razu po kliknięciu -
     * zanim piaskownica powstanie - więc blokada wyskakujących okien nie ma do czego się
     * przyczepić. Do bazy trafia tylko skrót kodu.
     */
    val entryCode: String
)

data class StartRolePreviewResponse(
    val previewBaseUrl: String,
    val expiresAt: Instant
)

data class EnterRolePreviewRequest(
    val entryCode: String
)

data class EnterRolePreviewResponse(
    val roleName: String,
    val expiresAt: Instant
)

data class UpdateRolePreviewRequest(
    val permissions: List<String>,
    val trackWorkTime: Boolean = false
)

data class RolePreviewStateResponse(
    val roleName: String,
    /** Uprawnienia roli teraz - już domknięte po drzewie zależności. */
    val permissions: List<String>,
    val trackWorkTime: Boolean,
    /** Uprawnienia w chwili otwarcia podglądu - punkt odniesienia dla „Zmian". */
    val initialPermissions: List<String>,
    val initialTrackWorkTime: Boolean,
    /** Moduły studia (skopiowane z prawdziwego studia przy otwarciu podglądu). */
    val enabledFeatures: List<String>,
    val catalog: List<PermissionModuleTreeResponse>,
    val openedByName: String,
    val expiresAt: Instant,
    /** Kiedy podgląd wygaśnie, jeśli nikt nic nie zrobi. */
    val idleExpiresAt: Instant,
    val simulatedEffects: List<SimulatedEffectResponse>
)

data class SimulatedEffectResponse(
    val channel: String,
    val channelLabel: String,
    val recipient: String?,
    val summary: String,
    val at: Instant
)
