package pl.detailing.crm.role.domain

import pl.detailing.crm.role.domain.Permission.*

/**
 * Declares the **hard (data-integrity) dependencies** between permissions: relations of the
 * form "permission X requires permission Y". A permission requires another when granting it
 * would be meaningless or impossible without the other — e.g. *editing* a customer is
 * impossible without *viewing* their personal data, and *creating a visit* inherently means
 * entering a customer's and a vehicle's data.
 *
 * Only **direct** edges are declared here; transitive prerequisites are computed by [close].
 * The graph is a DAG (enforced by tests).
 *
 * ## What is NOT modelled here
 * - **Personal-data visibility** is handled by *masking* at the serialization layer (driven by
 *   [Permission.CUSTOMERS_VIEW_PERSONAL_DATA]), not by dependencies. A user with `*_VIEW` may
 *   see a record but with personal fields blurred unless they also hold the personal-data
 *   permission. Therefore plain `*_VIEW` permissions never require `CUSTOMERS_VIEW_PERSONAL_DATA`.
 * - **Soft (policy) checks** that the business may toggle independently are enforced at the
 *   action site, not auto-granted here.
 *
 * ## Cross-module dependencies
 * Edges may cross [PermissionModule] boundaries (e.g. `VISITS_CREATE` requires `CUSTOMERS_CREATE`).
 * This is safe: [close] only completes a role's permission *set*; each permission is still
 * independently feature-gated at runtime by its module's [PermissionModule.featureKey].
 */
object PermissionDependencies {

    private val directEdges: Map<Permission, Set<Permission>> = mapOf(
        // ── Calendar ───────────────────────────────────────────────────────────
        // Managing the calendar creates/updates appointments, which carry customer and
        // vehicle data inline (CustomerIdentity.New/Update, VehicleIdentity.New/Update).
        CALENDAR_MANAGE to setOf(CALENDAR_VIEW, CUSTOMERS_CREATE, CUSTOMERS_EDIT, VEHICLES_CREATE, VEHICLES_EDIT),

        // ── Visits ─────────────────────────────────────────────────────────────
        // A visit references a customer and a vehicle (snapshots) — viewing it resolves both.
        VISITS_VIEW to setOf(CUSTOMERS_VIEW, VEHICLES_VIEW),
        // Creating a visit means entering a (possibly new) customer and vehicle.
        VISITS_CREATE to setOf(VISITS_VIEW, CUSTOMERS_CREATE, VEHICLES_CREATE),
        VISITS_CHANGE_STATUS to setOf(VISITS_VIEW),
        VISITS_VIEW_PRICES to setOf(VISITS_VIEW),
        VISITS_VIEW_COMMENTS to setOf(VISITS_VIEW),
        VISITS_ADD_COMMENT to setOf(VISITS_VIEW_COMMENTS),

        // ── Customers ──────────────────────────────────────────────────────────
        CUSTOMERS_VIEW_PERSONAL_DATA to setOf(CUSTOMERS_VIEW),
        CUSTOMERS_CREATE to setOf(CUSTOMERS_VIEW_PERSONAL_DATA),
        CUSTOMERS_EDIT to setOf(CUSTOMERS_VIEW_PERSONAL_DATA),
        CUSTOMERS_DELETE to setOf(CUSTOMERS_VIEW),

        // ── Vehicles ───────────────────────────────────────────────────────────
        VEHICLES_CREATE to setOf(VEHICLES_VIEW),
        VEHICLES_EDIT to setOf(VEHICLES_VIEW),
        VEHICLES_DELETE to setOf(VEHICLES_VIEW),

        // ── Documents (live inside a visit) ────────────────────────────────────
        DOCUMENTS_VIEW to setOf(VISITS_VIEW),
        DOCUMENTS_CREATE to setOf(DOCUMENTS_VIEW),
        DOCUMENTS_SIGN to setOf(DOCUMENTS_VIEW),

        // ── Gallery (photos live inside a visit) ───────────────────────────────
        GALLERY_VIEW to setOf(VISITS_VIEW),
        GALLERY_UPLOAD to setOf(GALLERY_VIEW),
        GALLERY_DELETE to setOf(GALLERY_VIEW),

        // ── Finance ────────────────────────────────────────────────────────────
        // An income document is created from a visit and carries the customer's NIP/name.
        FINANCE_CREATE_INVOICE to setOf(FINANCE_VIEW_INVOICES, VISITS_VIEW, CUSTOMERS_VIEW_PERSONAL_DATA),
        FINANCE_MANAGE_CASH_REGISTER to setOf(FINANCE_VIEW_INVOICES),

        // ── Employees ──────────────────────────────────────────────────────────
        EMPLOYEES_MANAGE to setOf(EMPLOYEES_VIEW),
        EMPLOYEES_VIEW_PAYROLL to setOf(EMPLOYEES_VIEW),
        EMPLOYEES_MANAGE_PAYROLL to setOf(EMPLOYEES_VIEW_PAYROLL),
        EMPLOYEES_MANAGE_ACCOUNTS to setOf(EMPLOYEES_MANAGE),

        // ── Communication ──────────────────────────────────────────────────────
        COMMUNICATION_VIEW_LOGS to setOf(CUSTOMERS_VIEW),
        // Sending requires the customer's real phone/email — actual personal data.
        COMMUNICATION_SEND_SMS to setOf(COMMUNICATION_VIEW_LOGS, CUSTOMERS_VIEW_PERSONAL_DATA),
        COMMUNICATION_SEND_EMAIL to setOf(COMMUNICATION_VIEW_LOGS, CUSTOMERS_VIEW_PERSONAL_DATA),

        // ── Leads ──────────────────────────────────────────────────────────────
        LEADS_MANAGE to setOf(LEADS_VIEW),

        // ── Tasks ──────────────────────────────────────────────────────────────
        TASKS_MANAGE to setOf(TASKS_VIEW),
        TASKS_ASSIGN to setOf(TASKS_VIEW, EMPLOYEES_VIEW)
    )

    /** Direct prerequisites of [permission] (one hop). Used by the frontend to lock/cascade checkboxes. */
    fun directDependenciesOf(permission: Permission): Set<Permission> = directEdges[permission] ?: emptySet()

    /**
     * Returns [permissions] expanded with every transitive prerequisite. The result is the
     * smallest internally-consistent superset — i.e. no granted permission is missing one of
     * its dependencies. Idempotent: `close(close(x)) == close(x)`.
     */
    fun close(permissions: Set<Permission>): Set<Permission> {
        val result = permissions.toMutableSet()
        val queue = ArrayDeque(permissions)
        while (queue.isNotEmpty()) {
            directDependenciesOf(queue.removeFirst()).forEach { dependency ->
                if (result.add(dependency)) queue.add(dependency)
            }
        }
        return result
    }
}
