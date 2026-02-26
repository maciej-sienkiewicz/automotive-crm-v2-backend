package pl.detailing.crm.ksef.sync

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface KsefSyncCursorRepository : JpaRepository<KsefSyncCursorEntity, UUID>
