package pl.detailing.crm.instagram.ads

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import pl.detailing.crm.auth.UserPrincipal
import pl.detailing.crm.instagram.infrastructure.InstagramProfileEntity
import pl.detailing.crm.instagram.infrastructure.InstagramProfileRepository
import pl.detailing.crm.instagram.infrastructure.StudioInstagramProfileEntity
import pl.detailing.crm.instagram.infrastructure.StudioInstagramProfileRepository
import pl.detailing.crm.shared.InstagramProfileStatus
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import java.util.Optional
import java.util.UUID

/**
 * Powiązanie profilu ze stroną na Facebooku siedzi na GLOBALNYM wierszu profilu, wspólnym
 * dla wszystkich studiów, które go obserwują — razem z migawkami reklam. Zapis wprost
 * z aplikacji znaczył, że jedno studio przestawia dane pozostałym i kasuje im historię.
 *
 * Stąd podział: pierwsze wskazanie zapisujemy, każda zmiana i odpięcie idzie mailem do
 * administratora, a baza zostaje nietknięta do jego decyzji.
 */
class PageLinkRequestTest {

    private val studioId = StudioId(UUID.randomUUID())
    private val profileId = UUID.randomUUID()

    private val studioProfileRepository = mockk<StudioInstagramProfileRepository>()
    private val profileRepository = mockk<InstagramProfileRepository>(relaxed = true)
    private val snapshotRepository = mockk<MetaAdSnapshotRepository>(relaxed = true)
    private val mailer = mockk<MetaPageChangeRequestMailer>(relaxed = true)

    private val service = MetaAdsReadService(
        studioProfileRepository = studioProfileRepository,
        profileRepository = profileRepository,
        snapshotRepository = snapshotRepository,
        client = mockk(relaxed = true),
        instagramResolver = mockk(relaxed = true),
        changeRequestMailer = mailer
    )

    private val principal = UserPrincipal(
        userId = UserId(UUID.randomUUID()),
        studioId = studioId,
        isOwner = true,
        email = "wlasciciel@studio.pl",
        fullName = "Jan Kowalski",
        phoneNumber = "+48600100200"
    )

    private fun profile(pageId: String?) = InstagramProfileEntity(
        id = profileId,
        username = "konkurencja"
    ).also { it.facebookPageId = pageId }

    private fun watching(): List<StudioInstagramProfileEntity> = listOf(
        StudioInstagramProfileEntity(
            id = UUID.randomUUID(),
            studioId = studioId.value,
            profileId = profileId,
            status = InstagramProfileStatus.ACTIVE,
            addedByUserId = UUID.randomUUID()
        )
    )

    @Test
    fun `pierwsze wskazanie strony zapisuje sie od reki`() {
        val entity = profile(null)
        every { studioProfileRepository.findByStudioId(studioId.value) } returns watching()
        every { profileRepository.findById(profileId) } returns Optional.of(entity)
        every { profileRepository.save(any()) } answers { firstArg() }

        val outcome = service.linkFacebookPage(principal, profileId, LinkFacebookPageRequest("100064123456789", "Car Art"))

        assertInstanceOf(PageLinkOutcome.Linked::class.java, outcome)
        assertEquals("100064123456789", entity.facebookPageId)
        verify(exactly = 0) { mailer.request(any(), any(), any()) }
    }

    @Test
    fun `zmiana strony nie rusza bazy i idzie mailem do administratora`() {
        val entity = profile("100064111111111")
        every { studioProfileRepository.findByStudioId(studioId.value) } returns watching()
        every { profileRepository.findById(profileId) } returns Optional.of(entity)

        val outcome = service.linkFacebookPage(principal, profileId, LinkFacebookPageRequest("100064222222222", null))

        assertEquals(PageLinkOutcome.RequestSent, outcome)
        assertEquals("100064111111111", entity.facebookPageId, "stara strona zostaje do decyzji administratora")
        verify(exactly = 1) { mailer.request(principal, entity, "100064222222222") }
        verify(exactly = 0) { snapshotRepository.deleteByProfileId(any()) }
    }

    @Test
    fun `odpiecie strony tez jest prosba, a migawki zostaja`() {
        val entity = profile("100064111111111")
        every { studioProfileRepository.findByStudioId(studioId.value) } returns watching()
        every { profileRepository.findById(profileId) } returns Optional.of(entity)

        val outcome = service.unlinkFacebookPage(principal, profileId)

        assertEquals(PageLinkOutcome.RequestSent, outcome)
        assertEquals("100064111111111", entity.facebookPageId)
        verify(exactly = 1) { mailer.request(principal, entity, null) }
        verify(exactly = 0) { snapshotRepository.deleteByProfileId(any()) }
    }

    /** Studio, które profilu nie obserwuje, nie ma czego zgłaszać — i nie może zaśmiecić skrzynki. */
    @Test
    fun `studio bez obserwacji profilu dostaje odmowe`() {
        every { studioProfileRepository.findByStudioId(studioId.value) } returns emptyList()

        assertEquals(
            PageLinkOutcome.Rejected,
            service.linkFacebookPage(principal, profileId, LinkFacebookPageRequest("100064222222222", null))
        )
        assertEquals(PageLinkOutcome.Rejected, service.unlinkFacebookPage(principal, profileId))
        verify(exactly = 0) { mailer.request(any(), any(), any()) }
    }

    @Test
    fun `ten sam numer co obecny nie generuje zgloszenia`() {
        val entity = profile("100064111111111")
        every { studioProfileRepository.findByStudioId(studioId.value) } returns watching()
        every { profileRepository.findById(profileId) } returns Optional.of(entity)

        assertEquals(
            PageLinkOutcome.Rejected,
            service.linkFacebookPage(principal, profileId, LinkFacebookPageRequest("100064111111111", null))
        )
        verify(exactly = 0) { mailer.request(any(), any(), any()) }
    }

    @Test
    fun `numer z liter jest odrzucany zanim powstanie mail`() {
        val entity = profile("100064111111111")
        every { studioProfileRepository.findByStudioId(studioId.value) } returns watching()
        every { profileRepository.findById(profileId) } returns Optional.of(entity)

        assertEquals(
            PageLinkOutcome.Rejected,
            service.linkFacebookPage(principal, profileId, LinkFacebookPageRequest("CarArtDetailing", null))
        )
        verify(exactly = 0) { mailer.request(any(), any(), any()) }
    }
}
