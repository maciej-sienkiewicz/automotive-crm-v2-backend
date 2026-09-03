package pl.detailing.crm.communication.rehearsal

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import pl.detailing.crm.communication.OutboundCommunicationGateway
import pl.detailing.crm.communication.redirect.ActiveRedirect
import pl.detailing.crm.communication.redirect.CommunicationRedirectService
import pl.detailing.crm.communication.template.MessageTemplateKind
import pl.detailing.crm.communication.template.MessageTemplateRenderer
import pl.detailing.crm.email.automation.GetEmailTemplateConfigHandler
import pl.detailing.crm.email.domain.EmailAutomationConfig
import pl.detailing.crm.email.domain.EmailNotificationRule
import pl.detailing.crm.email.provider.EmailDeliveryResult
import pl.detailing.crm.shared.InsufficientSmsCreditsException
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.ValidationException
import pl.detailing.crm.smscampaigns.automation.GetAutomationConfigHandler
import pl.detailing.crm.smscampaigns.domain.SmsAutomationConfig
import pl.detailing.crm.smscampaigns.domain.SmsAutomationRule
import pl.detailing.crm.smscampaigns.domain.SmsNotificationRule
import pl.detailing.crm.smscampaigns.provider.SmsDeliveryResult
import java.util.UUID

class CommsRehearsalRunnerTest {

    private val studioId = StudioId(UUID.randomUUID())
    private val smsConfig: GetAutomationConfigHandler = mockk()
    private val emailConfig: GetEmailTemplateConfigHandler = mockk()
    private val redirectService: CommunicationRedirectService = mockk()
    private val gateway: OutboundCommunicationGateway = mockk()
    private val runner = CommsRehearsalRunner(smsConfig, emailConfig, MessageTemplateRenderer(), redirectService, gateway)

    private val redirect = ActiveRedirect("+48500100200", "owner@studio.pl")

    /** Every rule enabled with a template that uses every placeholder its kind allows. */
    private fun fullSms(): SmsAutomationConfig {
        fun t(kind: MessageTemplateKind) = kind.allowedPlaceholders.sorted().joinToString(" ") { "{{$it}}" } + " 04.09.2026 10:00 Jan"
        fun auto(kind: MessageTemplateKind) = SmsAutomationRule(true, 60, t(kind))
        fun notif(kind: MessageTemplateKind) = SmsNotificationRule(true, t(kind))
        return SmsAutomationConfig(
            studioId,
            preVisit = auto(MessageTemplateKind.SMS_PRE_VISIT),
            postVisit = auto(MessageTemplateKind.SMS_POST_VISIT),
            delayedReminder = auto(MessageTemplateKind.SMS_DELAYED_REMINDER),
            bookingConfirmation = notif(MessageTemplateKind.SMS_BOOKING_CONFIRMATION),
            rescheduleConfirmation = notif(MessageTemplateKind.SMS_RESCHEDULE_CONFIRMATION),
            visitReadyForPickup = notif(MessageTemplateKind.SMS_VISIT_READY_FOR_PICKUP),
            visitCardLink = notif(MessageTemplateKind.SMS_VISIT_CARD_LINK),
            reservationCardLink = notif(MessageTemplateKind.SMS_RESERVATION_CARD_LINK),
            upsellConsent = notif(MessageTemplateKind.SMS_UPSELL_CONSENT),
            signatureRequest = notif(MessageTemplateKind.SMS_SIGNATURE_REQUEST)
        )
    }

    private fun fullEmail(): EmailAutomationConfig {
        fun rule(kind: MessageTemplateKind) = EmailNotificationRule(
            true,
            "Temat {{${kind.allowedPlaceholders.sorted().first()}}}",
            kind.allowedPlaceholders.sorted().joinToString("\n") { "$it: {{$it}}" } + "\nDzień dobry, to jest pełna treść wiadomości testowej."
        )
        return EmailAutomationConfig(
            studioId,
            visitWelcome = rule(MessageTemplateKind.EMAIL_VISIT_WELCOME),
            visitReadyForPickup = rule(MessageTemplateKind.EMAIL_VISIT_READY_FOR_PICKUP),
            batchOrderClose = rule(MessageTemplateKind.EMAIL_BATCH_ORDER_CLOSE),
            visitCardLink = rule(MessageTemplateKind.EMAIL_VISIT_CARD_LINK),
            reservationCardLink = rule(MessageTemplateKind.EMAIL_RESERVATION_CARD_LINK)
        )
    }

    private fun stubConfigs(sms: SmsAutomationConfig = fullSms(), email: EmailAutomationConfig = fullEmail()) {
        every { smsConfig.handle(studioId) } returns sms
        every { emailConfig.handle(studioId) } returns email
    }

    private fun gatewaySucceeds() {
        every { gateway.sendTransactionalSms(any(), any(), any(), any()) } returns SmsDeliveryResult.success("s")
        every { gateway.sendTransactionalEmail(any(), any(), any(), any(), any(), any()) } returns EmailDeliveryResult.success("m")
    }

    @Nested
    inner class Plan {

        @Test
        fun `covers every sms and email kind exactly once, numbered per channel`() {
            stubConfigs(); every { redirectService.activeFor(studioId.value) } returns null
            val report = runner.plan(studioId)

            val sms = report.items.filter { it.channel == RehearsalChannel.SMS }
            val email = report.items.filter { it.channel == RehearsalChannel.EMAIL }
            assertEquals(MessageTemplateKind.entries.filter { it.name.startsWith("SMS_") }.toSet(), sms.map { it.kind }.toSet())
            assertEquals(MessageTemplateKind.entries.filter { it.name.startsWith("EMAIL_") }.toSet(), email.map { it.kind }.toSet())
            assertEquals((1..sms.size).toList(), sms.map { it.seq })
            assertEquals((1..email.size).toList(), email.map { it.seq })
            assertTrue(sms.all { it.total == sms.size })
            assertEquals("[R01/${sms.size}] ", sms.first().stamp)
            assertFalse(report.items.any { it.kind == MessageTemplateKind.CAMPAIGN })
        }

        @Test
        fun `clean templates render with the fixture and produce no errors`() {
            stubConfigs(); every { redirectService.activeFor(studioId.value) } returns null
            val report = runner.plan(studioId)

            assertEquals(0, report.errorCount, report.items.flatMap { it.findings }.toString())
            val ready = report.items.first { it.kind == MessageTemplateKind.SMS_VISIT_READY_FOR_PICKUP }
            assertTrue("Audi RS6 Avant" in ready.body)
            assertTrue("WE 4RS6X" in ready.body)
            assertNotNull(ready.segments)
            val welcome = report.items.first { it.kind == MessageTemplateKind.EMAIL_VISIT_WELCOME }
            assertEquals("Temat 04.09.2026".take(6), welcome.subject!!.take(6))
            assertNull(welcome.segments)
        }

        @Test
        fun `a template with an unknown placeholder is an error, not a crash`() {
            val broken = fullSms().copy(preVisit = SmsAutomationRule(true, 60, "{{clinet_name}} 04.09.2026 10:00"))
            stubConfigs(sms = broken); every { redirectService.activeFor(studioId.value) } returns null

            val item = runner.plan(studioId).items.first { it.kind == MessageTemplateKind.SMS_PRE_VISIT }
            assertTrue(item.hasErrors)
            assertEquals("unknown-placeholder", item.findings.single().rule)
            assertTrue("{{clinet_name}}" in item.findings.single().detail)
        }

        @Test
        fun `a placeholder with a polish letter passes the renderer but is caught by the validator`() {
            val broken = fullSms().copy(bookingConfirmation = SmsNotificationRule(true, "{{imię}} {{imie}} {{nazwisko}} {{data}} {{godzina}}"))
            stubConfigs(sms = broken); every { redirectService.activeFor(studioId.value) } returns null

            val item = runner.plan(studioId).items.first { it.kind == MessageTemplateKind.SMS_BOOKING_CONFIRMATION }
            assertTrue(item.findings.any { it.rule == "placeholder-with-diacritics" }, item.findings.toString())
        }

        @Test
        fun `an enabled rule without content is reported as a warning, never as a send`() {
            val empty = fullSms().copy(postVisit = SmsAutomationRule(true, 30, "   "))
            stubConfigs(sms = empty); every { redirectService.activeFor(studioId.value) } returns null

            val item = runner.plan(studioId).items.first { it.kind == MessageTemplateKind.SMS_POST_VISIT }
            assertFalse(item.hasErrors)
            assertEquals("template-empty", item.findings.single().rule)
            assertEquals(Severity.WARNING, item.findings.single().severity)
            assertTrue("włączona" in item.findings.single().detail)
        }

        @Test
        fun `an email with a subject but no body is treated as empty`() {
            val cfg = fullEmail().copy(visitWelcome = EmailNotificationRule(true, "Temat", ""))
            stubConfigs(email = cfg); every { redirectService.activeFor(studioId.value) } returns null
            val item = runner.plan(studioId).items.first { it.kind == MessageTemplateKind.EMAIL_VISIT_WELCOME }
            assertEquals("template-empty", item.findings.single().rule)
        }

        @Test
        fun `plan reports the redirect targets when the redirect is on and nulls when off`() {
            stubConfigs()
            every { redirectService.activeFor(studioId.value) } returns redirect
            val on = runner.plan(studioId)
            assertEquals("+48500100200", on.redirectPhone)
            assertEquals("owner@studio.pl", on.redirectEmail)
            assertFalse(on.sent)

            every { redirectService.activeFor(studioId.value) } returns null
            assertNull(runner.plan(studioId).redirectPhone)
        }

        @Test
        fun `plan never sends anything`() {
            stubConfigs(); every { redirectService.activeFor(studioId.value) } returns redirect
            runner.plan(studioId)
            verify(exactly = 0) { gateway.sendTransactionalSms(any(), any(), any(), any()) }
            verify(exactly = 0) { gateway.sendTransactionalEmail(any(), any(), any(), any(), any(), any()) }
        }
    }

    @Nested
    inner class Run {

        @Test
        fun `refuses to send when the redirect is off`() {
            stubConfigs(); every { redirectService.activeFor(studioId.value) } returns null
            val ex = assertThrows(ValidationException::class.java) { runner.run(studioId) }
            assertTrue("przekierowanie" in ex.message!!.lowercase())
            verify(exactly = 0) { gateway.sendTransactionalSms(any(), any(), any(), any()) }
        }

        @Test
        fun `sends nothing when any template has an error`() {
            val broken = fullSms().copy(signatureRequest = SmsNotificationRule(true, "{{imie}} {{nazwisko}} {{link}} {{dokument}}}"))
            stubConfigs(sms = broken); every { redirectService.activeFor(studioId.value) } returns redirect
            gatewaySucceeds()

            val report = runner.run(studioId)

            assertFalse(report.sent)
            assertTrue(report.hasErrors)
            verify(exactly = 0) { gateway.sendTransactionalSms(any(), any(), any(), any()) }
            verify(exactly = 0) { gateway.sendTransactionalEmail(any(), any(), any(), any(), any(), any()) }
            assertTrue(report.items.all { it.delivery == null })
        }

        @Test
        fun `sends every message with content through the gateway to the fixture customer, stamped`() {
            stubConfigs(); every { redirectService.activeFor(studioId.value) } returns redirect
            gatewaySucceeds()

            val report = runner.run(studioId)

            assertTrue(report.sent)
            val smsCount = report.items.count { it.channel == RehearsalChannel.SMS }
            val emailCount = report.items.count { it.channel == RehearsalChannel.EMAIL }
            verify(exactly = smsCount) { gateway.sendTransactionalSms(studioId.value, RehearsalFixture.CUSTOMER_PHONE, any(), any()) }
            verify(exactly = emailCount) { gateway.sendTransactionalEmail(studioId.value, RehearsalFixture.CUSTOMER_EMAIL, any(), any(), any(), any()) }
            assertTrue(report.items.all { it.delivery?.success == true })

            val bodies = mutableListOf<String>()
            verify { gateway.sendTransactionalSms(studioId.value, RehearsalFixture.CUSTOMER_PHONE, capture(bodies), any()) }
            assertEquals(smsCount, bodies.size)
            bodies.forEachIndexed { i, b -> assertTrue(b.startsWith("[R%02d/%d] ".format(i + 1, smsCount)), b) }

            val subjects = mutableListOf<String>()
            verify { gateway.sendTransactionalEmail(studioId.value, RehearsalFixture.CUSTOMER_EMAIL, capture(subjects), any(), any(), any()) }
            assertEquals(emailCount, subjects.size)
            subjects.forEachIndexed { i, sub -> assertTrue(sub.startsWith("[R%02d/%d] ".format(i + 1, emailCount)), sub) }
        }

        @Test
        fun `skips empty templates and still sends the rest`() {
            val cfg = fullSms().copy(delayedReminder = SmsAutomationRule(false, 0, ""))
            stubConfigs(sms = cfg); every { redirectService.activeFor(studioId.value) } returns redirect
            gatewaySucceeds()

            val report = runner.run(studioId)

            val skipped = report.items.first { it.kind == MessageTemplateKind.SMS_DELAYED_REMINDER }
            assertNull(skipped.delivery)
            val smsWithContent = report.items.count { it.channel == RehearsalChannel.SMS } - 1
            verify(exactly = smsWithContent) { gateway.sendTransactionalSms(any(), any(), any(), any()) }
        }

        @Test
        fun `a provider failure is recorded on the item and does not stop the others`() {
            stubConfigs(); every { redirectService.activeFor(studioId.value) } returns redirect
            every { gateway.sendTransactionalSms(any(), any(), any(), any()) } returns SmsDeliveryResult.failure("SMSAPI: invalid number")
            every { gateway.sendTransactionalEmail(any(), any(), any(), any(), any(), any()) } returns EmailDeliveryResult.success("m")

            val report = runner.run(studioId)

            assertTrue(report.sent)
            assertTrue(report.items.filter { it.channel == RehearsalChannel.SMS }.all { it.delivery?.success == false })
            assertTrue(report.items.filter { it.channel == RehearsalChannel.EMAIL }.all { it.delivery?.success == true })
            assertEquals("SMSAPI: invalid number", report.items.first { it.channel == RehearsalChannel.SMS }.delivery!!.error)
        }

        @Test
        fun `running out of credits is recorded as a failed delivery, not an exception`() {
            stubConfigs(); every { redirectService.activeFor(studioId.value) } returns redirect
            every { gateway.sendTransactionalSms(any(), any(), any(), any()) } throws InsufficientSmsCreditsException("Brak kredytów SMS")
            every { gateway.sendTransactionalEmail(any(), any(), any(), any(), any(), any()) } returns EmailDeliveryResult.success("m")

            val report = runner.run(studioId)
            val sms = report.items.first { it.channel == RehearsalChannel.SMS }
            assertEquals(false, sms.delivery?.success)
            assertEquals("Brak kredytów SMS", sms.delivery?.error)
        }

        @Test
        fun `stops immediately if the redirect is switched off mid run`() {
            stubConfigs()
            // on for the guard and the plan, on for the first message, then off
            every { redirectService.activeFor(studioId.value) } returnsMany listOf(redirect, redirect, redirect, null)
            gatewaySucceeds()

            val report = runner.run(studioId)

            verify(exactly = 1) { gateway.sendTransactionalSms(any(), any(), any(), any()) }
            verify(exactly = 0) { gateway.sendTransactionalEmail(any(), any(), any(), any(), any(), any()) }
            val second = report.items[1]
            assertEquals(false, second.delivery?.success)
            assertTrue("przerwano" in second.delivery!!.error!!)
            assertTrue(report.items.drop(2).all { it.delivery == null })
        }
    }
}
