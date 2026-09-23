package pl.detailing.crm.signing.infrastructure

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import pl.detailing.crm.shared.VisitId
import pl.detailing.crm.shared.VisitProtocolId
import pl.detailing.crm.signing.domain.SignatureSubject
import pl.detailing.crm.signing.signatureRequest
import java.util.UUID

/** Podmiot podpisu przechodzi przez bazę bez zmian: protokół wizyty ALBO lista obecności. */
class SignatureRequestEntityTest {

    @Test
    fun `protokol wizyty zapisuje wizyte i protokol, bez listy obecnosci`() {
        val subject = SignatureSubject.VisitProtocol(VisitId.random(), VisitProtocolId.random())

        val entity = SignatureRequestEntity.fromDomain(signatureRequest(subject))

        assertEquals(subject.visitId.value, entity.visitId)
        assertEquals(subject.protocolId.value, entity.protocolId)
        assertNull(entity.attendanceSheetId)
        assertEquals(subject, entity.toDomain().subject)
    }

    @Test
    fun `lista obecnosci zapisuje tylko arkusz`() {
        val subject = SignatureSubject.AttendanceSheet(UUID.randomUUID())

        val entity = SignatureRequestEntity.fromDomain(signatureRequest(subject))

        assertNull(entity.visitId)
        assertNull(entity.protocolId)
        assertEquals(subject.sheetId, entity.attendanceSheetId)
        assertEquals(subject, entity.toDomain().subject)
    }

    @Test
    fun `wiersz bez zadnego podmiotu nie udaje protokolu`() {
        val broken = SignatureRequestEntity.fromDomain(signatureRequest(SignatureSubject.AttendanceSheet(UUID.randomUUID())))
            .let { entity ->
                SignatureRequestEntity(
                    id = entity.id, studioId = entity.studioId, visitId = null, protocolId = null,
                    attendanceSheetId = null, tabletId = null, status = entity.status,
                    documentS3Key = entity.documentS3Key, documentSha256 = entity.documentSha256,
                    documentName = entity.documentName, signerName = entity.signerName,
                    declarationText = entity.declarationText, requestedBy = entity.requestedBy,
                    requestedByName = entity.requestedByName, createdAt = entity.createdAt,
                    expiresAt = entity.expiresAt, updatedAt = entity.updatedAt
                )
            }

        assertThrows<IllegalStateException> { broken.toDomain() }
    }
}
