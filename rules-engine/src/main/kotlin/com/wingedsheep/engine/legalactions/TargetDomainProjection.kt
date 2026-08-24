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
 * This is an engine-side result wrapper rather than a [List].  [infos] is the ordered target
 * metadata and [support] is the authoritative seam metadata; a caller must not treat [infos] as
 * externally publishable when it is UNSUPPORTED.  Keeping the wrapper distinct from [List] is
 * intentional: a support-bearing projection must not claim list equality with its payload and
 * thereby violate the symmetric [Any.equals] contract.
 */
data class TargetInfoProjection(
    val infos: List<TargetInfo>,
    val support: TargetDomainSupport,
) : Iterable<TargetInfo> {
    val size: Int get() = infos.size

    operator fun get(index: Int): TargetInfo = infos[index]

    fun isEmpty(): Boolean = infos.isEmpty()

    fun isNotEmpty(): Boolean = infos.isNotEmpty()

    override fun iterator(): Iterator<TargetInfo> = infos.iterator()

    override fun equals(other: Any?): Boolean =
        other is TargetInfoProjection && infos == other.infos && support == other.support

    override fun hashCode(): Int = 31 * infos.hashCode() + support.hashCode()

    override fun toString(): String = "TargetInfoProjection(infos=$infos, support=$support)"
}
