package pl.detailing.crm.shared.pii

/** Outcome of the per-request personal-data access decision. */
enum class PiiAccess { GRANTED, MASKED }

/**
 * Thread-bound decision "may personal data leave the JVM on this execution path?".
 *
 * Populated once per HTTP request by [PiiAccessFilter]; Jackson's [PiiMaskingModule]
 * consults it at the moment a `@Pii` field is written. The invariant that makes the
 * design non-bypassable:
 *
 * **No context = masked.** Any serialization happening outside an explicitly resolved
 * scope — scheduled jobs, STOMP broadcasts, listeners on broker threads — produces
 * masked output. Revealing on such paths requires a deliberate, reviewable
 * [withGranted] block at the call site.
 */
object PiiAccessContext {

    private val holder = ThreadLocal<PiiAccess?>()

    /** Deny-by-default: absent decision serializes masked. */
    fun current(): PiiAccess = holder.get() ?: PiiAccess.MASKED

    fun isGranted(): Boolean = current() == PiiAccess.GRANTED

    fun open(access: PiiAccess) = holder.set(access)

    fun clear() = holder.remove()

    /**
     * Runs [block] with personal data revealed, restoring the previous decision afterwards.
     * For egress that is *by design* addressed to someone entitled to the data regardless
     * of any employee permission — e.g. a document pushed to the signing tablet where the
     * customer confirms their own data. Every call site is an auditable security decision.
     */
    fun <T> withGranted(block: () -> T): T = with(PiiAccess.GRANTED, block)

    /**
     * Runs [block] with personal data forcibly masked, restoring the previous decision
     * afterwards. For broadcast channels whose audience is unknown or mixed (e.g. the
     * studio-wide dashboard topic) — a broadcast must never depend on the permissions
     * of whoever happened to trigger it.
     */
    fun <T> withMasked(block: () -> T): T = with(PiiAccess.MASKED, block)

    private fun <T> with(access: PiiAccess, block: () -> T): T {
        val previous = holder.get()
        holder.set(access)
        try {
            return block()
        } finally {
            if (previous == null) holder.remove() else holder.set(previous)
        }
    }
}
