package pl.detailing.crm.role.domain

/**
 * Query helpers over the permission dependency graph: the tree declared by
 * [Permission.parent] plus the catalog-level implications declared in [implications].
 *
 * Two dependency rules:
 * - **a permission requires its whole ancestor chain** (the tree),
 * - **a permission may imply permissions outside its branch** ([impliesOf]) — capabilities
 *   that are useless or absurd without one another (e.g. booking a visit without seeing
 *   the customer). Implications may cross branches and modules.
 *
 * [close] expands a set over both rules until a fixpoint, so a stored role is always a
 * consistent, self-contained set. The role editor receives the same graph
 * (`GET /api/v1/roles/permissions` serializes `implies`) and cascades selection along it,
 * so what the administrator sees checked is exactly what is persisted and enforced.
 */
object PermissionHierarchy {

    private val childrenByParent: Map<Permission?, List<Permission>> =
        Permission.entries.groupBy { it.parent }

    /**
     * Catalog-level implications that cannot be edges of the tree (a node has exactly one
     * parent). Ancestors of an implied permission are pulled in by [close], so each entry
     * lists only the deepest required node of a branch.
     *
     * Cross-module rule (v4): every non-VISITS module root implies [Permission.VISITS_CREATE].
     * A role cannot hold Finance, Employees, Communication, etc. without the booking desk
     * capability — the editor auto-selects [Permission.VISITS_CREATE] (and its full ancestor
     * chain) when any of those module roots is checked.
     *
     */
    private val implications: Map<Permission, Set<Permission>> = mapOf(
        // ── Non-VISITS roots → VISITS_CREATE ────────────────────────────────────────
        Permission.FINANCE_INVOICES to setOf(Permission.VISITS_CREATE),
        Permission.FINANCE_VIEW_REPORTS to setOf(Permission.VISITS_CREATE),
        Permission.FINANCE_EARNINGS_NOTIFICATIONS to setOf(Permission.VISITS_CREATE),
        Permission.EMPLOYEES_MANAGE to setOf(Permission.VISITS_CREATE),
        Permission.EMPLOYEES_PAYROLL to setOf(Permission.VISITS_CREATE),
        Permission.COMMUNICATION_SEND to setOf(Permission.VISITS_CREATE),
        Permission.MARKETING_MANAGE to setOf(Permission.VISITS_CREATE),
        Permission.STATISTICS_VIEW to setOf(Permission.VISITS_CREATE),
        Permission.LEADS_MANAGE to setOf(Permission.VISITS_CREATE),
        Permission.TASKS_VIEW to setOf(Permission.VISITS_CREATE),
        Permission.AUDIT_VIEW to setOf(Permission.VISITS_CREATE),
    )

    /** Direct children of [permission] in declaration order. */
    fun childrenOf(permission: Permission): List<Permission> =
        childrenByParent[permission].orEmpty()

    /** Root permissions (no parent) of [module] in declaration order. */
    fun rootsOf(module: PermissionModule): List<Permission> =
        childrenByParent[null].orEmpty().filter { it.module == module }

    /** Every ancestor of [permission], nearest first. Empty for roots. */
    fun ancestorsOf(permission: Permission): List<Permission> =
        generateSequence(permission.parent) { it.parent }.toList()

    /** Permissions [permission] directly implies beyond its parent chain. */
    fun impliesOf(permission: Permission): Set<Permission> =
        implications[permission].orEmpty()

    /** [permission] and every permission below it in the tree. */
    fun subtreeOf(permission: Permission): Set<Permission> {
        val result = mutableSetOf(permission)
        val queue = ArrayDeque(childrenOf(permission))
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (result.add(node)) queue.addAll(childrenOf(node))
        }
        return result
    }

    /**
     * Returns [permissions] expanded with every ancestor and every implication, to a
     * fixpoint: each member's parent chain and [impliesOf] set are contained in the
     * result. Idempotent: `close(close(x)) == close(x)`.
     */
    fun close(permissions: Set<Permission>): Set<Permission> {
        val result = mutableSetOf<Permission>()
        val queue = ArrayDeque(permissions)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (!result.add(node)) continue
            node.parent?.let { queue.add(it) }
            queue.addAll(impliesOf(node))
        }
        return result
    }
}
