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
     */
    private val implications: Map<Permission, Set<Permission>> = mapOf(
        // The booking desk-flow is one capability: composing a visit means entering the
        // (possibly new) customer's and vehicle's data, pricing the services (discounts),
        // handling documents/protocols and picking services from the catalog. A role that
        // can book but cannot see the customer is absurd.
        Permission.VISITS_CREATE to setOf(
            Permission.CUSTOMERS_MANAGE,
            Permission.VISITS_SERVICE_PRICES_EDIT,
            Permission.VISITS_DOCUMENTS_MANAGE,
            Permission.SERVICES_VIEW
        ),
        // Person-centric capabilities: their content IS customer personal data, so they
        // imply the personal-data permission — 403/masking would make them useless.
        Permission.VISITS_DOCUMENTS_MANAGE to setOf(Permission.CUSTOMERS_VIEW),
        Permission.COMMUNICATION_SEND to setOf(Permission.CUSTOMERS_VIEW),
        // An invoice carries the customer's data, is created from a visit and references
        // the price list.
        Permission.FINANCE_INVOICES to setOf(
            Permission.CUSTOMERS_VIEW,
            Permission.VISITS_VIEW,
            Permission.SERVICES_VIEW
        ),
        // Financial and statistical reporting reference the service catalog.
        Permission.FINANCE_VIEW_REPORTS to setOf(Permission.SERVICES_VIEW),
        Permission.STATISTICS_VIEW to setOf(Permission.SERVICES_VIEW)
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
