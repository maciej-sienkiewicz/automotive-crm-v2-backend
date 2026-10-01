package pl.detailing.crm.studio.infrastructure

import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import pl.detailing.crm.shared.persistence.FreshRowLock
import java.util.UUID

interface StudioRowLocks {
    /**
     * Wiersz studia z blokadą (`SELECT … FOR UPDATE`) i ŚWIEŻYM stanem ([FreshRowLock]). Każda
     * mutacja subskrypcji zaczyna od niej — zakupy, zmiany planu, przejścia cyklu życia
     * i realizacje zamówień jednego studia idą po kolei, a decyzja zapada na stanie odczytanym
     * POD blokadą (audyt, inwariant 2). Kolejność blokad w całym module: studio → plan →
     * moduły → zamówienie.
     */
    fun lockById(id: UUID): StudioEntity?
}

class StudioRowLocksImpl : StudioRowLocks {
    @PersistenceContext
    private lateinit var entityManager: EntityManager

    override fun lockById(id: UUID): StudioEntity? = FreshRowLock.lock(entityManager, StudioEntity::class.java, id)
}
