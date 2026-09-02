package com.wingedsheep.gym.contract

/**
 * Opt-in scalar diagnostics for B1 canonicalization characterization.
 *
 * The probe is inert unless a test explicitly starts a session. It retains no game state,
 * observation, semantic string, or UTF-8 byte array. Production behavior must never read a probe
 * value to make a semantic decision.
 */
internal object B1CanonicalizationProbe {
    internal enum class SortSite {
        LEGAL_ACTION,
        STRUCTURED_DOMAIN,
    }

    internal class SemanticCall internal constructor(
        internal val previous: SemanticCall?,
        internal val legalActionCount: Int,
    ) {
        internal var semanticChars: Int = 0
        internal var semanticActionFingerprintCalls: Long = 0
        internal var legalActionSortKeyEvaluations: Long = 0
        internal var structuredDomainSortKeyEvaluations: Long = 0
    }

    internal data class SemanticCallSnapshot(
        val legalActionCount: Int,
        val semanticChars: Int,
        val semanticActionFingerprintCalls: Long,
        val legalActionSortKeyEvaluations: Long,
        val structuredDomainSortKeyEvaluations: Long,
    )

    internal data class Snapshot(
        val observationBuilderCalls: Long,
        val legalActionEnumerationCalls: Long,
        val semanticJsonCalls: Long,
        val wireJsonCalls: Long,
        val semanticActionFingerprintCalls: Long,
        val legalActionSortKeyEvaluations: Long,
        val structuredDomainSortKeyEvaluations: Long,
        val finalCanonicalizationCalls: Long,
        val semanticJsonChars: Long,
        val wireJsonChars: Long,
        val stateDigestCalls: Long,
        val stateDigestInputBytes: Long,
        val sha256Calls: Long,
        val digestHexFormattingCalls: Long,
        val largestSemanticCall: SemanticCallSnapshot?,
    )

    internal class Session {
        internal var observationBuilderCalls: Long = 0
        internal var legalActionEnumerationCalls: Long = 0
        internal var semanticJsonCalls: Long = 0
        internal var wireJsonCalls: Long = 0
        internal var semanticActionFingerprintCalls: Long = 0
        internal var legalActionSortKeyEvaluations: Long = 0
        internal var structuredDomainSortKeyEvaluations: Long = 0
        internal var finalCanonicalizationCalls: Long = 0
        internal var semanticJsonChars: Long = 0
        internal var wireJsonChars: Long = 0
        internal var stateDigestCalls: Long = 0
        internal var stateDigestInputBytes: Long = 0
        internal var sha256Calls: Long = 0
        internal var digestHexFormattingCalls: Long = 0
        internal var largestSemanticCall: SemanticCallSnapshot? = null

        internal fun snapshot(): Snapshot = Snapshot(
            observationBuilderCalls = observationBuilderCalls,
            legalActionEnumerationCalls = legalActionEnumerationCalls,
            semanticJsonCalls = semanticJsonCalls,
            wireJsonCalls = wireJsonCalls,
            semanticActionFingerprintCalls = semanticActionFingerprintCalls,
            legalActionSortKeyEvaluations = legalActionSortKeyEvaluations,
            structuredDomainSortKeyEvaluations = structuredDomainSortKeyEvaluations,
            finalCanonicalizationCalls = finalCanonicalizationCalls,
            semanticJsonChars = semanticJsonChars,
            wireJsonChars = wireJsonChars,
            stateDigestCalls = stateDigestCalls,
            stateDigestInputBytes = stateDigestInputBytes,
            sha256Calls = sha256Calls,
            digestHexFormattingCalls = digestHexFormattingCalls,
            largestSemanticCall = largestSemanticCall,
        )
    }

    private val activeSession = ThreadLocal<Session?>()
    private val activeSemanticCall = ThreadLocal<SemanticCall?>()

    internal fun start(): Session {
        check(activeSession.get() == null) { "B1 canonicalization probe session is already active" }
        val session = Session()
        activeSession.set(session)
        return session
    }

    internal fun snapshot(session: Session): Snapshot {
        check(activeSession.get() === session) { "B1 canonicalization probe session is not active" }
        return session.snapshot()
    }

    internal fun stop(session: Session): Snapshot {
        check(activeSession.get() === session) { "B1 canonicalization probe session is not active" }
        val snapshot = session.snapshot()
        activeSemanticCall.remove()
        activeSession.remove()
        return snapshot
    }

    internal fun recordObservationBuild() {
        activeSession.get()?.let { it.observationBuilderCalls++ }
    }

    internal fun recordLegalActionEnumeration() {
        activeSession.get()?.let { it.legalActionEnumerationCalls++ }
    }

    internal fun beginSemanticJson(legalActionCount: Int): SemanticCall? {
        val session = activeSession.get() ?: return null
        session.semanticJsonCalls++
        return SemanticCall(activeSemanticCall.get(), legalActionCount).also { activeSemanticCall.set(it) }
    }

    internal fun finishSemanticJson(call: SemanticCall?, semanticChars: Int) {
        if (call == null) return
        check(activeSemanticCall.get() === call) { "B1 semantic JSON calls are not properly nested" }
        val session = activeSession.get()
            ?: error("B1 semantic JSON finished without an active probe session")
        call.semanticChars = semanticChars
        if (semanticChars >= 0) session.semanticJsonChars += semanticChars.toLong()
        val completed = SemanticCallSnapshot(
            legalActionCount = call.legalActionCount,
            semanticChars = call.semanticChars,
            semanticActionFingerprintCalls = call.semanticActionFingerprintCalls,
            legalActionSortKeyEvaluations = call.legalActionSortKeyEvaluations,
            structuredDomainSortKeyEvaluations = call.structuredDomainSortKeyEvaluations,
        )
        if (session.largestSemanticCall == null || isLarger(completed, session.largestSemanticCall!!)) {
            session.largestSemanticCall = completed
        }
        activeSemanticCall.set(call.previous)
    }

    internal fun recordWireJson(wireChars: Int) {
        activeSession.get()?.let {
            it.wireJsonCalls++
            if (wireChars >= 0) it.wireJsonChars += wireChars.toLong()
        }
    }

    internal fun recordSemanticActionFingerprint() {
        activeSession.get()?.let { it.semanticActionFingerprintCalls++ }
        activeSemanticCall.get()?.let { it.semanticActionFingerprintCalls++ }
    }

    internal fun recordSortKeyEvaluation(site: SortSite) {
        val session = activeSession.get()
        val call = activeSemanticCall.get()
        when (site) {
            SortSite.LEGAL_ACTION -> {
                session?.let { it.legalActionSortKeyEvaluations++ }
                call?.let { it.legalActionSortKeyEvaluations++ }
            }
            SortSite.STRUCTURED_DOMAIN -> {
                session?.let { it.structuredDomainSortKeyEvaluations++ }
                call?.let { it.structuredDomainSortKeyEvaluations++ }
            }
        }
    }

    internal fun recordFinalCanonicalization() {
        activeSession.get()?.let { it.finalCanonicalizationCalls++ }
    }

    internal fun recordStateDigest(inputBytes: Int) {
        activeSession.get()?.let {
            it.stateDigestCalls++
            it.stateDigestInputBytes += inputBytes.toLong()
        }
    }

    internal fun recordSha256() {
        activeSession.get()?.let { it.sha256Calls++ }
    }

    internal fun recordDigestHexFormatting() {
        activeSession.get()?.let { it.digestHexFormattingCalls++ }
    }

    private fun isLarger(
        candidate: SemanticCallSnapshot,
        current: SemanticCallSnapshot,
    ): Boolean = when {
        candidate.legalActionCount != current.legalActionCount ->
            candidate.legalActionCount > current.legalActionCount
        candidate.legalActionSortKeyEvaluations != current.legalActionSortKeyEvaluations ->
            candidate.legalActionSortKeyEvaluations > current.legalActionSortKeyEvaluations
        else -> candidate.semanticChars > current.semanticChars
    }
}
