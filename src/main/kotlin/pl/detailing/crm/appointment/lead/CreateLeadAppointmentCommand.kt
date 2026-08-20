package pl.detailing.crm.appointment.lead

import pl.detailing.crm.appointment.create.CreateAppointmentCommand
import pl.detailing.crm.shared.LeadId

/**
 * Rezerwacja zakładana z leada.
 *
 * Nie powtarzamy tu pól rezerwacji, tylko nosimy gotową [base]: rezerwacja z leada
 * to ta sama rezerwacja co każda inna — z klientem, pojazdem, wyceną, dojazdem
 * i notatkami — a jedyne, co ją wyróżnia, to lead, z którym ma zostać powiązana.
 * Kopia pól rozjeżdżała się z bazową komendą przy każdym nowym polu rezerwacji
 * i po cichu gubiła to, czego nie przepisano (jak dane door-to-door).
 */
data class CreateLeadAppointmentCommand(
    val leadId: LeadId,
    val base: CreateAppointmentCommand
)
