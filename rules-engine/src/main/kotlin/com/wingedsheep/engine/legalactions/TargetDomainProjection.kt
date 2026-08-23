package com.wingedsheep.engine.legalactions

/**
 * Stable engine-side reasons why an action target domain cannot be projected into the fixed
 * public contract.  These values deliberately contain no card names, entity ids, Oracle text,
 * exception text, or hidden-state details.
 */
enum class TargetDomainUnsupportedReason {
    INCOMPLETE_SEMANTICS,
    UNRESOLVED_CARDINALITY,
    UNRESOLVED_X,
    AMBIGUOUS_FLAT_PARTITION,
    NON_CONTROLLER_CHOOSER,
}

/**
 * Whether an action's target requirements are complete enough for the fixed target-domain
 * projection.  Unsupported actions remain engine-visible during this Rules-only migration; the
 * later Gym mapper is responsible for withholding them from the external action contract.
 */
sealed interface TargetDomainSupport {
    data object SUPPORTED : TargetDomainSupport

    data class UNSUPPORTED(val reason: TargetDomainUnsupportedReason) : TargetDomainSupport
}

/**
 * The ordered target-information result produced by the Rules target enumerator.
 *
 * The type delegates [List] so existing Rules callers can continue to perform candidate and
 * satisfiability checks without rebuilding a second list.  [support] is the authoritative seam
 * metadata; a caller must not treat [infos] as externally publishable when it is UNSUPPORTED.
 */
data class TargetInfoProjection(
    val infos: List<TargetInfo>,
    val support: TargetDomainSupport,
) : List<TargetInfo> by infos {
    override fun equals(other: Any?): Boolean = when (other) {
        is TargetInfoProjection -> infos == other.infos && support == other.support
        is List<*> -> infos == other
        else -> false
    }

    override fun hashCode(): Int = infos.hashCode()

    override fun toString(): String = "TargetInfoProjection(infos=$infos, support=$support)"
}
