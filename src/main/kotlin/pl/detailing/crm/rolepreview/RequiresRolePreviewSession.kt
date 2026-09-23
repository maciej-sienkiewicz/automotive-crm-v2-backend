package pl.detailing.crm.rolepreview

import org.aspectj.lang.ProceedingJoinPoint
import org.aspectj.lang.annotation.Around
import org.aspectj.lang.annotation.Aspect
import org.springframework.stereotype.Component
import pl.detailing.crm.auth.SecurityContextHelper
import pl.detailing.crm.shared.NotFoundException
import pl.detailing.crm.shared.UnauthorizedException

/**
 * Endpoint dostępny wyłącznie z sesji pracownika piaskownicy podglądu roli - czyli tylko
 * z okna podglądu, pod adresem podglądu (patrz [RolePreviewHostFilter]).
 *
 * Sesja prawdziwego studia dostaje 404: te endpointy nie przyjmują żadnych identyfikatorów
 * i zawsze działają na piaskownicy, do której należy sesja, więc prawdziwe studio nie ma
 * tu czego szukać.
 */
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
annotation class RequiresRolePreviewSession

@Aspect
@Component
class RolePreviewSessionAspect(
    private val studios: RolePreviewStudios
) {
    @Around(
        "@within(pl.detailing.crm.rolepreview.RequiresRolePreviewSession) || " +
        "@annotation(pl.detailing.crm.rolepreview.RequiresRolePreviewSession)"
    )
    fun enforce(joinPoint: ProceedingJoinPoint): Any? {
        val principal = try {
            SecurityContextHelper.getCurrentUser()
        } catch (e: Exception) {
            throw UnauthorizedException("Wymagane uwierzytelnienie")
        }
        if (!studios.isRolePreview(principal.studioId.value)) {
            throw NotFoundException("Podgląd roli nie istnieje")
        }
        return joinPoint.proceed()
    }
}
