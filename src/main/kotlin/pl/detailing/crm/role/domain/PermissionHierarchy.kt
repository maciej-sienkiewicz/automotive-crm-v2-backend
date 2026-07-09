package pl.detailing.crm.role.domain

/**
 * Query helpers over the permission tree declared by [Permission.parent].
 *
 * The single dependency rule of the catalog: **a permission requires its whole ancestor
 * chain**. There are no cross-branch or cross-module dependencies — granting a node grants
 * (via [close]) exactly the path from the tree root down to that node.
 */
object PermissionHierarchy {

    private val childrenByParent: Map<Permission?, List<Permission>> =
        Permission.entries.groupBy { it.parent }

    /** Direct children of [permission] in declaration order. */
    fun childrenOf(permission: Permission): List<Permission> =
        childrenByParent[permission].orEmpty()

    /** Root permissions (no parent) of [module] in declaration order. */
    fun rootsOf(module: PermissionModule): List<Permission> =
        childrenByParent[null].orEmpty().filter { it.module == module }

    /** Every ancestor of [permission], nearest first. Empty for roots. */
    fun ancestorsOf(permission: Permission): List<Permission> =
        generateSequence(permission.parent) { it.parent }.toList()

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
     * Returns [permissions] expanded with every ancestor, so the stored set always forms
     * complete root-to-node paths. Idempotent: `close(close(x)) == close(x)`.
     *
     * In-module implication: [Permission.VISITS_CREATE] implies
     * [Permission.VISITS_SERVICE_PRICES_VIEW] — composing a visit means selecting services
     * with their prices. It deliberately does NOT grant the whole VISITS module: deleting
     * visits, deleting photos and price *editing* (discounts) are separate policies.
     * Cross-module implications (customer data entry, the service catalog) live in
     * [pl.detailing.crm.role.permission.PermissionCheckService].
     */
    fun close(permissions: Set<Permission>): Set<Permission> {
        val result = permissions.flatMapTo(mutableSetOf()) { ancestorsOf(it) + it }
        if (Permission.VISITS_CREATE in result) {
            result.add(Permission.VISITS_SERVICE_PRICES_VIEW)
        }
        return result
    }
}
