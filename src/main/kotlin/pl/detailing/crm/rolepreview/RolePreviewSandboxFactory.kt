package pl.detailing.crm.rolepreview

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.demo.DemoDataInitializer
import pl.detailing.crm.demo.SeedContacts
import pl.detailing.crm.role.domain.Permission
import pl.detailing.crm.role.domain.PermissionHierarchy
import pl.detailing.crm.role.domain.Role
import pl.detailing.crm.role.infrastructure.RoleEntity
import pl.detailing.crm.role.infrastructure.RoleRepository
import pl.detailing.crm.rolepreview.infrastructure.RolePreviewSandboxEntity
import pl.detailing.crm.rolepreview.infrastructure.RolePreviewSandboxRepository
import pl.detailing.crm.shared.RoleId
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.SubscriptionStatus
import pl.detailing.crm.shared.UserId
import pl.detailing.crm.studio.domain.StudioKind
import pl.detailing.crm.studio.infrastructure.StudioEntity
import pl.detailing.crm.studio.infrastructure.StudioRepository
import pl.detailing.crm.subscription.entitlement.EntitlementService
import pl.detailing.crm.subscription.entitlement.domain.StudioEntitlements
import pl.detailing.crm.user.infrastructure.UserEntity
import pl.detailing.crm.user.infrastructure.UserRepository
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Zakłada piaskownicę podglądu roli - w jednej transakcji: studio, jego moduły, konta,
 * rolę, dane przykładowe i wpis w rejestrze. Błąd w którymkolwiek kroku cofa całość, więc
 * nie zostaje po nim ani pół piaskownicy.
 */
@Service
class RolePreviewSandboxFactory(
    private val studios: RolePreviewStudios,
    private val studioRepository: StudioRepository,
    private val userRepository: UserRepository,
    private val roleRepository: RoleRepository,
    private val sandboxRepository: RolePreviewSandboxRepository,
    private val entitlementService: EntitlementService,
    private val demoDataInitializer: DemoDataInitializer,
    private val sampleData: RolePreviewSampleData
) {

    @Transactional
    fun create(spec: SandboxSpec): RolePreviewSandboxEntity {
        val sandboxStudioId = UUID.randomUUID()
        // Piaskownica jest piaskownicą od pierwszej chwili: cokolwiek wydarzy się w trakcie
        // zakładania, już podlega jej blokadom (logowanie, wysyłki na zewnątrz).
        studios.rememberRolePreview(sandboxStudioId)
        try {
            return createStudio(sandboxStudioId, spec)
        } catch (e: Exception) {
            studios.forget(sandboxStudioId)
            throw e
        }
    }

    private fun createStudio(sandboxStudioId: UUID, spec: SandboxSpec): RolePreviewSandboxEntity {
        val now = spec.now
        studioRepository.save(
            StudioEntity(
                id = sandboxStudioId,
                name = spec.studioName.take(200),
                // Aktywna subskrypcja do końca życia piaskownicy (z zapasem): żadne zadanie
                // rozliczeniowe, próbne ani wygaszające nie ma w niej czego robić.
                subscriptionStatus = SubscriptionStatus.ACTIVE,
                trialEndsAt = null,
                subscriptionEndsAt = spec.expiresAt.plus(1, ChronoUnit.DAYS),
                trialUsed = true,
                createdAt = now,
                // Bez aliasu: piaskownica nie ma adresu, na który ktoś z zewnątrz mógłby
                // przysłać wiadomość (przychodzące e-maile trafiają do studia po aliasie).
                emailAlias = null,
                kind = StudioKind.ROLE_PREVIEW
            )
        )

        // Moduły dokładnie jak w prawdziwym studiu - od nich zależy, co pracownik zobaczy.
        val studioId = StudioId(sandboxStudioId)
        entitlementService.ensurePlanAssigned(studioId, spec.entitlements.planKey)
        spec.entitlements.activeAddOnKeys.forEach { entitlementService.activateAddOn(studioId, it) }

        val suffix = sandboxStudioId.toString().replace("-", "")
        val ownerUserId = UUID.randomUUID()
        val employeeUserId = UUID.randomUUID()
        val roleId = UUID.randomUUID()

        userRepository.save(
            sandboxUser(ownerUserId, sandboxStudioId, "wlasciciel-$suffix", "Właściciel", "Podglądu", owner = true, now = now)
        )
        roleRepository.save(
            RoleEntity.fromDomain(
                Role(
                    id = RoleId(roleId),
                    studioId = studioId,
                    name = spec.roleName,
                    description = null,
                    permissions = PermissionHierarchy.close(spec.permissions),
                    trackWorkTime = spec.trackWorkTime,
                    createdBy = UserId(ownerUserId),
                    createdAt = now,
                    updatedAt = now
                )
            )
        )
        userRepository.save(
            sandboxUser(employeeUserId, sandboxStudioId, "pracownik-$suffix", EMPLOYEE_FIRST_NAME, EMPLOYEE_LAST_NAME, owner = false, now = now)
                .apply { customRoleId = roleId }
        )

        demoDataInitializer.seed(
            sandboxStudioId, ownerUserId,
            contacts = SeedContacts.UNDELIVERABLE,
            followInstagramProfiles = false
        )
        sampleData.seed(
            SandboxAccounts(
                studioId = sandboxStudioId,
                ownerUserId = ownerUserId,
                employeeUserId = employeeUserId,
                employeeFirstName = EMPLOYEE_FIRST_NAME,
                employeeLastName = EMPLOYEE_LAST_NAME,
                roleId = roleId
            )
        )

        return sandboxRepository.save(
            RolePreviewSandboxEntity(
                id = UUID.randomUUID(),
                sandboxStudioId = sandboxStudioId,
                sourceStudioId = spec.sourceStudioId,
                createdByUserId = spec.createdByUserId,
                createdByName = spec.createdByName.take(200),
                ownerUserId = ownerUserId,
                employeeUserId = employeeUserId,
                roleId = roleId,
                roleName = spec.roleName,
                initialPermissions = PermissionHierarchy.close(spec.permissions).map { it.name }.sorted().joinToString(","),
                initialTrackWorkTime = spec.trackWorkTime,
                entryCodeHash = spec.entryCodeHash,
                entryCodeExpiresAt = spec.entryCodeExpiresAt,
                createdAt = now,
                lastActivityAt = now,
                expiresAt = spec.expiresAt
            )
        )
    }

    /**
     * Konto piaskownicy: adres w domenie `.invalid` (zarezerwowanej, RFC 2606 - nic nie da
     * się na nią doręczyć) i hasło, które nie jest żadnym skrótem BCrypt, więc nie pasuje
     * do niczego. Logowanie i tak odrzuca konta piaskownic, zanim porówna hasło.
     */
    private fun sandboxUser(
        id: UUID,
        studioId: UUID,
        localPart: String,
        firstName: String,
        lastName: String,
        owner: Boolean,
        now: Instant
    ) = UserEntity(
        id = id,
        studioId = studioId,
        email = "$localPart@$EMAIL_DOMAIN",
        phoneNumber = NO_PHONE,
        passwordHash = NO_PASSWORD,
        firstName = firstName,
        lastName = lastName,
        isOwner = owner,
        isActive = true,
        createdAt = now
    )

    companion object {
        const val EMAIL_DOMAIN = "podglad.invalid"
        const val NO_PASSWORD = "!role-preview:no-password"
        const val NO_PHONE = "+48000000000"
        const val EMPLOYEE_FIRST_NAME = "Pracownik"
        const val EMPLOYEE_LAST_NAME = "Podglądowy"
    }
}

/** Wszystko, czego potrzeba do założenia piaskownicy - zebrane i sprawdzone wcześniej. */
data class SandboxSpec(
    val sourceStudioId: UUID,
    val studioName: String,
    val createdByUserId: UUID,
    val createdByName: String,
    val roleName: String,
    val permissions: Set<Permission>,
    val trackWorkTime: Boolean,
    val entitlements: StudioEntitlements,
    val entryCodeHash: String,
    val now: Instant,
    val entryCodeExpiresAt: Instant,
    val expiresAt: Instant
)
