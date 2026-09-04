package pl.detailing.crm.studio.reset

import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import org.springframework.stereotype.Component
import pl.detailing.crm.instagram.infrastructure.StudioInstagramProfileRepository
import pl.detailing.crm.shared.InstagramProfileStatus
import java.util.UUID

/**
 * Kontekst pojedynczego przebiegu czyszczenia konta.
 *
 * [keepUserId] to owner, który zlecił reset — jego konto użytkownika jako jedyne
 * przetrwa krok "Użytkownicy i role".
 */
data class StudioResetContext(
    val studioId: UUID,
    val keepUserId: UUID,
    val wipeCompanyData: Boolean
)

/**
 * Jeden krok resetu. Runner wykonuje każdy krok w osobnej transakcji i zapisuje postęp
 * po jego zatwierdzeniu, więc każdy krok MUSI być idempotentny — po awarii w połowie
 * kroku job wznawia się od jego początku.
 */
data class StudioResetStep(
    val name: String,
    val execute: (StudioResetContext) -> Unit
)

/**
 * Uporządkowane pod klucze obce czyszczenie wszystkich danych studia z bazy.
 *
 * Kolejność i pułapki kaskad pochodzą z [pl.detailing.crm.demo.DemoCleanupJob] (przetestowany
 * w boju 25-krokowy purge kont demo), rozszerzone o moduły, których tamten job nie dotyka:
 * finanse, KSeF, komunikację, kampanie, SMS, zlecenia hurtowe, zadania, HR i pozostałe.
 *
 * Co świadomie ZOSTAJE (decyzja produktowa, nie przeoczenie):
 *  - rozliczenia wobec platformy: `studios`, `studio_subscription_plans`,
 *    `studio_subscription_add_ons`, `pending_plan_changes`, `subscription_payment_log`,
 *    `payment_orders` — plan i historia płatności to relacja studio ↔ platforma;
 *  - saldo i historia kredytów SMS (`sms_credit_balances`, `sms_credit_transactions`) —
 *    to zapłacone środki;
 *  - `audit_logs` — dziennik jest rejestrem; reset sam zostawia w nim wpis CRITICAL;
 *  - konto ownera zlecającego reset (`users` poza nim są usuwane);
 *  - liczniki live-metrics w Redisie — diagnostyka operatora, niewidoczna dla studia;
 *  - dane globalne współdzielone między studiami (katalogi planów, segmenty pojazdów,
 *    profile Instagrama — te ostatnie tylko odpinane, z GC osieroconych).
 *
 * Pełną klasyfikację encji utrzymuje test pokrycia w
 * `pl.detailing.crm.studio.reset.StudioResetCoverageTest` — nowa encja bez klasyfikacji
 * to czerwony build.
 */
@Component
class StudioDataPurger(
    private val studioInstagramProfileRepository: StudioInstagramProfileRepository,
    @PersistenceContext private val entityManager: EntityManager
) {

    fun steps(): List<StudioResetStep> = listOf(
        StudioResetStep("Protokoły i podpisy") { ctx ->
            // Rewizje komentarzy → komentarze → dokumenty i dziennik wizyt (NIE kaskadowane
            // z VisitEntity — patrz DemoCleanupJob kroki 2a/2a') → podpisy → protokoły.
            deleteWhere(
                "VisitCommentRevisionEntity r",
                """r.commentId IN (SELECT c.id FROM VisitCommentEntity c
                   WHERE c.visitId IN (SELECT v.id FROM VisitEntity v WHERE v.studioId = :studioId))""",
                ctx
            )
            deleteWhere(
                "VisitCommentEntity c",
                "c.visitId IN (SELECT v.id FROM VisitEntity v WHERE v.studioId = :studioId)",
                ctx
            )
            deleteWhere(
                "VisitDocumentEntity d",
                "d.visit.id IN (SELECT v.id FROM VisitEntity v WHERE v.studioId = :studioId)",
                ctx
            )
            deleteWhere(
                "VisitJournalEntryEntity j",
                "j.visit.id IN (SELECT v.id FROM VisitEntity v WHERE v.studioId = :studioId)",
                ctx
            )
            deleteByStudio("SignatureAuditEventEntity", ctx)
            deleteByStudio("SignatureRequestEntity", ctx)
            deleteByStudio("VisitProtocolEntity", ctx)
            deleteByStudio("VisitTechnicalNoteHistoryEntity", ctx)
        },

        StudioResetStep("Zdjęcia i pozycje wizyt") { ctx ->
            deleteWhere(
                "VisitServiceItemEntity i",
                "i.visit.id IN (SELECT v.id FROM VisitEntity v WHERE v.studioId = :studioId)",
                ctx
            )
            deleteWhere(
                "VisitPhotoEntity p",
                "p.visit.id IN (SELECT v.id FROM VisitEntity v WHERE v.studioId = :studioId)",
                ctx
            )
            deleteWhere(
                "TemporaryPhotoEntity t",
                """t.sessionId IN (SELECT s.id FROM PhotoUploadSessionEntity s
                   WHERE s.studioId = :studioId)""",
                ctx
            )
            deleteByStudio("PhotoUploadSessionEntity", ctx)
            deleteByStudio("PhotoTagEntity", ctx)
        },

        StudioResetStep("Karty Wizyty") { ctx ->
            deleteByStudio("VisitCardTokenEntity", ctx)
            deleteByStudio("UpsellReservationConsentEntity", ctx)
            deleteByStudio("VisitUpsellSuggestionEntity", ctx)
        },

        StudioResetStep("Wizyty") { ctx ->
            deleteByStudio("VisitEntity", ctx)
        },

        StudioResetStep("Rezerwacje i kalendarz") { ctx ->
            deleteWhere(
                "AppointmentLineItemEntity i",
                "i.appointment.id IN (SELECT a.id FROM AppointmentEntity a WHERE a.studioId = :studioId)",
                ctx
            )
            deleteByStudio("AppointmentEntity", ctx)
            deleteByStudio("RecurrenceSeriesEntity", ctx)
            deleteByStudio("AppointmentColorEntity", ctx)
            deleteByStudio("CalendarEventEntity", ctx)
        },

        StudioResetStep("Pojazdy") { ctx ->
            deleteWhere(
                "VehiclePhotoEntity p",
                "p.vehicle.id IN (SELECT v.id FROM VehicleEntity v WHERE v.studioId = :studioId)",
                ctx
            )
            deleteByStudio("VehicleDocumentEntity", ctx)
            deleteByStudio("VehicleNoteEntity", ctx)
            // Junction bez kaskady i bez studio_id — czyszczona po obu krawędziach,
            // bo współwłaścicielem pojazdu bywa klient innego studia tylko teoretycznie,
            // ale wiersz z jedną krawędzią w tym studiu ma zniknąć na pewno.
            deleteWhere(
                "VehicleOwnerEntity vo",
                "vo.id.vehicleId IN (SELECT v.id FROM VehicleEntity v WHERE v.studioId = :studioId)",
                ctx
            )
            deleteWhere(
                "VehicleOwnerEntity vo",
                "vo.id.customerId IN (SELECT c.id FROM CustomerEntity c WHERE c.studioId = :studioId)",
                ctx
            )
            deleteByStudio("VehicleEntity", ctx)
        },

        StudioResetStep("Zgody klientów") { ctx ->
            deleteByStudio("CustomerConsentEntity", ctx)
            deleteByStudio("ConsentTemplateEntity", ctx)
            deleteByStudio("ConsentDefinitionEntity", ctx)
        },

        StudioResetStep("Klienci") { ctx ->
            deleteByStudio("CustomerNoteEntity", ctx)
            deleteByStudio("CustomerDocumentEntity", ctx)
            deleteByStudio("CustomerImportSessionEntity", ctx)
            deleteByStudio("CustomerEntity", ctx)
        },

        StudioResetStep("Leady") { ctx ->
            deleteWhere(
                "LeadTagEntity t",
                "t.leadId IN (SELECT l.id FROM LeadEntity l WHERE l.studioId = :studioId)",
                ctx
            )
            deleteByStudio("LeadServiceItemEntity", ctx)
            deleteByStudio("LeadStatusHistoryEntity", ctx)
            deleteByStudio("LeadNoteEntity", ctx)
            deleteByStudio("LeadCallbackEntity", ctx)
            deleteByStudio("LeadTagDefinitionEntity", ctx)
            deleteByStudio("LeadIntakeDeliveryEntity", ctx)
            deleteByStudio("LeadIntakeWebhookEntity", ctx)
            deleteByStudio("LeadMessageClassificationEntity", ctx)
            deleteByStudio("FormMailExtractionEntity", ctx)
            deleteByStudio("FormMailSourceEntity", ctx)
            deleteByStudio("LeadEntity", ctx)
        },

        StudioResetStep("Usługi i cennik") { ctx ->
            deleteByStudio("CategoryServiceAssignmentEntity", ctx)
            deleteByStudio("ServiceCategoryEntity", ctx)
            deleteByStudio("ManualServiceCategoryAssignmentEntity", ctx)
            deleteByStudio("ManualServiceEntity", ctx)
            deleteByStudio("ServicePackageItemEntity", ctx)
            deleteByStudio("ServiceEntity", ctx)
        },

        StudioResetStep("Szablony protokołów") { ctx ->
            deleteWhere(
                "ProtocolTemplateDefaultRevisionEntity r",
                "r.templateId IN (SELECT t.id FROM ProtocolTemplateEntity t WHERE t.studioId = :studioId)",
                ctx
            )
            deleteByStudio("ProtocolFieldMappingEntity", ctx)
            deleteByStudio("ProtocolRuleEntity", ctx)
            deleteByStudio("ProtocolTemplateEntity", ctx)
        },

        StudioResetStep("Finanse i kasa") { ctx ->
            deleteByStudio("CashOperationEntity", ctx)
            deleteByStudio("CashRegisterEntity", ctx)
            deleteByStudio("CostItemAssignmentEntity", ctx)
            deleteByStudio("CostCategoryEntity", ctx)
            deleteByStudio("SupplierAutoRuleEntity", ctx)
            deleteByStudio("DocumentDuplicateLinkEntity", ctx)
            deleteByStudio("FinancialDocumentEntity", ctx)
        },

        StudioResetStep("KSeF") { ctx ->
            deleteWhere(
                "KsefInvoiceItemEntity i",
                "i.invoiceId IN (SELECT f.id FROM KsefInvoiceEntity f WHERE f.studioId = :studioId)",
                ctx
            )
            deleteByStudio("KsefInvoiceEntity", ctx)
            deleteWhere(
                "KsefRevenueInvoiceItemEntity i",
                "i.invoiceId IN (SELECT f.id FROM KsefRevenueInvoiceEntity f WHERE f.studioId = :studioId)",
                ctx
            )
            deleteByStudio("KsefRevenueInvoiceEntity", ctx)
            deleteByStudio("KsefCredentialsEntity", ctx)
            deleteByStudio("KsefSyncCursorEntity", ctx)
        },

        StudioResetStep("Skrzynka i komunikacja") { ctx ->
            deleteByStudio("CommAttachmentEntity", ctx)
            deleteByStudio("CommMessageEntity", ctx)
            deleteByStudio("CommOutboxEntity", ctx)
            deleteByStudio("CommLabelEntity", ctx)
            deleteByStudio("CommThreadEntity", ctx)
            deleteByStudio("ContactNoteEventEntity", ctx)
            deleteByStudio("ContactNoteEntity", ctx)
            deleteByStudio("CommUserSignatureEntity", ctx)
            deleteByStudio("MailAccountEntity", ctx)
            deleteByStudio("CommunicationLogEntity", ctx)
        },

        StudioResetStep("SMS i kampanie") { ctx ->
            deleteByStudio("CampaignRecipientEntity", ctx)
            deleteByStudio("CampaignOptOutEntity", ctx)
            deleteByStudio("CampaignEntity", ctx)
            deleteByStudio("CampaignSettingsEntity", ctx)
            deleteByStudio("SmsLogEntity", ctx)
            deleteByStudio("SmsAutomationConfigEntity", ctx)
            deleteByStudio("SmsConsentRequestEntity", ctx)
            deleteByStudio("ScheduledSmsReminderEntity", ctx)
            deleteByStudio("EmailAutomationConfigEntity", ctx)
            deleteByStudio("CommunicationRedirectEntity", ctx)
        },

        StudioResetStep("Zlecenia hurtowe") { ctx ->
            deleteByStudio("BatchOrderPhotoEntity", ctx)
            deleteByStudio("BatchOrderServiceEntity", ctx)
            deleteByStudio("BatchOrderEntryEntity", ctx)
            deleteByStudio("BatchOrderCloseHistoryEntity", ctx)
            deleteByStudio("BatchContractorEntity", ctx)
        },

        StudioResetStep("Zadania") { ctx ->
            deleteWhere(
                "TaskReadEntity r",
                "r.taskId IN (SELECT t.id FROM TaskEntity t WHERE t.studioId = :studioId)",
                ctx
            )
            deleteByStudio("TaskEntity", ctx)
        },

        StudioResetStep("Pracownicy i czas pracy") { ctx ->
            deleteByStudio("EmployeeLeaveEntity", ctx)
            deleteByStudio("WorkTimeEntryEntity", ctx)
            deleteByStudio("WorkTimePeriodEntity", ctx)
            // Wygenerowane listy obecności — dokumenty kadrowe tego studia.
            deleteByStudio("AttendanceSheetEntity", ctx)
            deleteByStudio("EmployeeEntity", ctx)
        },

        StudioResetStep("Pozostałe dane") { ctx ->
            deleteByStudio("DoorToDoorEntity", ctx)
            deleteByStudio("CallLogEntity", ctx)
            deleteByStudio("PushDeviceEntity", ctx)
            deleteByStudio("SigningTabletEntity", ctx)
        },

        StudioResetStep("Instagram") { ctx ->
            // Profile Instagrama są globalne (współdzielone między studiami): odpinamy
            // linki tego studia, a osierocone profile i ich snapshoty sprzątamy tak samo
            // jak DemoCleanupJob (krok 22).
            val links = studioInstagramProfileRepository.findByStudioId(ctx.studioId)
            deleteByStudio("StudioInstagramPostReactionEntity", ctx)
            // Generator AI: reguły stylistyczne i historia wygenerowanych postów to dane
            // operacyjne studia — po resecie model uczy się jego stylu od zera.
            deleteByStudio("InstagramStyleRuleEntity", ctx)
            deleteByStudio("InstagramGeneratedPostEntity", ctx)
            deleteByStudio("InstagramInsightEntity", ctx)
            deleteByStudio("InstagramReportEntity", ctx)
            deleteByStudio("StudioInstagramProfileEntity", ctx)
            entityManager.flush()
            links.forEach { link ->
                val remainingRefs = studioInstagramProfileRepository.countByProfileIdAndStatus(
                    link.profileId, InstagramProfileStatus.ACTIVE
                )
                if (remainingRefs == 0L) {
                    entityManager.createQuery(
                        """DELETE FROM InstagramPostTopicEntity t WHERE t.postId IN
                           (SELECT s.id FROM InstagramPostSnapshotEntity s WHERE s.profileId = :profileId)"""
                    ).setParameter("profileId", link.profileId).executeUpdate()
                    deleteByProfile("InstagramPostSnapshotEntity", link.profileId)
                    deleteByProfile("InstagramProfileMetricsSnapshotEntity", link.profileId)
                    deleteByProfile("InstagramProfileStatsWeeklyEntity", link.profileId)
                    deleteByProfile("InstagramProfileSuggestionEntity", link.profileId)
                    entityManager.createQuery(
                        "DELETE FROM InstagramProfileEntity p WHERE p.id = :profileId"
                    ).setParameter("profileId", link.profileId).executeUpdate()
                }
            }
        },

        StudioResetStep("Użytkownicy i role") { ctx ->
            // Dane per-użytkownik przed usunięciem użytkowników.
            deleteWhere(
                "DashboardHintDismissalEntity d",
                "d.userId IN (SELECT u.id FROM UserEntity u WHERE u.studioId = :studioId)",
                ctx
            )
            deleteWhere(
                "CardDavProvisioningEntity p",
                """p.appPasswordId IN (SELECT ap.id FROM CardDavAppPasswordEntity ap
                   WHERE ap.studioId = :studioId)""",
                ctx
            )
            deleteByStudio("CardDavAppPasswordEntity", ctx)
            entityManager.createQuery(
                "DELETE FROM UserEntity u WHERE u.studioId = :studioId AND u.id <> :keepUserId"
            )
                .setParameter("studioId", ctx.studioId)
                .setParameter("keepUserId", ctx.keepUserId)
                .executeUpdate()
            // Role po użytkownikach; ownerowi (jedynemu ocalałemu) zerujemy przypisanie,
            // żeby nie wskazywał usuniętej roli.
            entityManager.createQuery(
                "UPDATE UserEntity u SET u.customRoleId = NULL WHERE u.studioId = :studioId"
            ).setParameter("studioId", ctx.studioId).executeUpdate()
            deleteByStudio("RoleEntity", ctx)
        }
    )

    private fun deleteByStudio(entityName: String, ctx: StudioResetContext) {
        entityManager.createQuery("DELETE FROM $entityName e WHERE e.studioId = :studioId")
            .setParameter("studioId", ctx.studioId)
            .executeUpdate()
    }

    private fun deleteWhere(entityWithAlias: String, condition: String, ctx: StudioResetContext) {
        entityManager.createQuery("DELETE FROM $entityWithAlias WHERE $condition")
            .setParameter("studioId", ctx.studioId)
            .executeUpdate()
    }

    private fun deleteByProfile(entityName: String, profileId: UUID) {
        entityManager.createQuery("DELETE FROM $entityName e WHERE e.profileId = :profileId")
            .setParameter("profileId", profileId)
            .executeUpdate()
    }
}
