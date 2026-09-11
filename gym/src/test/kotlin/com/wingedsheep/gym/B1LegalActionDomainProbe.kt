package com.wingedsheep.gym

import com.wingedsheep.engine.legalactions.LegalAction
import kotlinx.serialization.Serializable
import java.lang.management.ManagementFactory
import java.util.IdentityHashMap
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.LongAdder
import kotlin.jvm.JvmName

/**
 * Test-only deep accounting for the legal-action/domain characterization.
 *
 * The probe records scalar counts and exclusive method timing only while a measured segment and
 * external policy decision are active. It never retains a GameState, observation, or domain object
 * beyond one active decision: state/list references are held only while comparing adjacent
 * legalActions calls, then released at the next call/decision boundary.
 */
internal object B1LegalActionDomainProbe {
    private val activeSession = AtomicReference<Session?>()
    private val allocationBean = (ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean)
        ?.takeIf { it.isThreadAllocatedMemorySupported && it.isThreadAllocatedMemoryEnabled }

    @Serializable
    internal data class Distribution(
        val count: Long,
        val sum: Long,
        val min: Long,
        val p50: Long,
        val p95: Long,
        val max: Long,
    )

    @Serializable
    internal data class PhaseSnapshot(
        val invocations: Long,
        val inclusiveWallNanos: Long = 0L,
        val exclusiveWallNanos: Long,
        val inclusiveAllocatedBytes: Long? = null,
        val exclusiveAllocatedBytes: Long? = null,
        val allocationSamples: Long,
        val returnedItems: Distribution? = null,
        val zeroResultInvocations: Long = 0L,
        val nonZeroResultInvocations: Long = 0L,
    )

    @Serializable
    internal data class NestedPhaseSnapshot(
        val invocations: Long,
        val inclusiveWallNanos: Long,
        val inclusiveAllocatedBytes: Long? = null,
    )

    @Serializable
    internal data class OwnPermanentSnapshot(
        val invocations: Long,
        val returnedActions: Distribution,
        val zeroResultInvocations: Long,
        val nonZeroResultInvocations: Long,
        val battlefieldPermanentsScanned: Distribution,
        val zeroResultBattlefieldPermanents: Distribution? = null,
        val nonZeroResultBattlefieldPermanents: Distribution? = null,
        val tempGrantedScanInvocations: Long,
        val tempGrantedElementsVisited: Long,
        val tempGrantedMatches: Long,
        val staticGrantedLookupCalls: Long,
        val staticGrantedResults: Long,
        val effectiveActivatedAbilities: Long,
        val classLevelUpAbilities: Long,
        val activationPreventedSkips: Long,
        val playerActivationPreventedSkips: Long,
        val emblemScanInvocations: Long,
        val emblemEntitiesVisited: Long,
        val emblemAbilitiesFound: Long,
        val emblemMatchesFound: Long,
        val cardRegistryLookups: Long,
        val costPathInvocations: Long,
        val targetPathInvocations: Long,
        val actionConstructionInvocations: Long,
        val zeroClassifications: Map<String, Long>,
        val phases: Map<String, PhaseSnapshot>,
    )

    @Serializable
    internal data class DecisionKindSnapshot(
        val decisions: Long,
        val legalActionCalls: Long,
        val legalActionWallNanos: Long,
        val legalActionAllocatedBytes: Long? = null,
        val callsPerDecision: Distribution,
    )

    @Serializable
    internal data class SegmentSnapshot(
        val label: String,
        val decisions: Long,
        val actionChoices: Long,
        val structuredResponses: Long,
        val semanticChoiceGaps: Long,
        val legalActionCallsTotal: Long,
        val legalActionCallsPerDecision: Distribution,
        val legalActionCallsByPurpose: Map<String, Long>,
        val decisionsWith0LegalActionCalls: Long,
        val decisionsWith1LegalActionCall: Long,
        val decisionsWith2LegalActionCalls: Long,
        val decisionsWith3PlusLegalActionCalls: Long,
        val sameStateLegalActionRepeatCount: Long,
        val stateChangedLegalActionRepeatCount: Long,
        val sameStateSemanticallyEqualResults: Long,
        val sameStateSemanticallyDifferentResults: Long,
        val observationBuilds: Long,
        val publicLegalCandidates: Distribution,
        val publishedObservations: Long,
        val publishedPublicLegalCandidates: Distribution? = null,
        val structuredDecisionDomains: Distribution? = null,
        val actionFamilyCandidates: Map<String, Long>,
        val decisionKinds: Map<String, DecisionKindSnapshot>,
        val phases: Map<String, PhaseSnapshot>,
        val manaSolverByEnumerator: Map<String, NestedPhaseSnapshot> = emptyMap(),
        val ownPermanent: OwnPermanentSnapshot? = null,
        val ownPermanentDirectPhases: Map<String, PhaseSnapshot> = emptyMap(),
    )

    @Serializable
    internal data class Snapshot(
        val schemaVersion: String,
        val allocationMeasurement: String,
        val integrity: String,
        val segments: List<SegmentSnapshot>,
    )

    internal class Session internal constructor() {
        private val currentSegment = AtomicReference<Segment?>()
        private val segments = CopyOnWriteArrayList<Segment>()
        private val decisionState = ThreadLocal<DecisionState?>()
        private val integrityErrors = AtomicLong(0)
        private val zoneEnumeratorZones = IdentityHashMap<Any, String>()

        internal fun beginSegment(label: String): SegmentHandle {
            val segment = Segment(label)
            segments += segment
            if (!currentSegment.compareAndSet(null, segment)) {
                markIntegrityError()
            }
            return SegmentHandle(this, segment)
        }

        internal fun endSegment(segment: Segment) {
            if (!currentSegment.compareAndSet(segment, null)) {
                markIntegrityError()
            }
        }

        internal fun currentSegmentForMeasurement(): Segment? = currentSegment.get()

        internal fun beginDecision(kind: String): Boolean {
            val segment = currentSegment.get() ?: return false
            if (decisionState.get() != null) {
                markIntegrityError()
                return false
            }
            decisionState.set(DecisionState(segment, kind))
            segment.recordDecisionStarted(kind)
            return true
        }

        internal fun endDecision() {
            val decision = decisionState.get() ?: return
            if (decision.frames.isNotEmpty()) markIntegrityError()
            decisionState.remove()
            decision.segment.recordDecisionFinished(decision)
        }

        internal fun pushPurpose(purpose: String) {
            decisionState.get()?.purposes?.addLast(purpose)
        }

        internal fun popPurpose(purpose: String) {
            val decision = decisionState.get() ?: return
            val actual = decision.purposes.removeLastOrNull()
            if (actual != purpose) markIntegrityError()
        }

        internal fun beginLegalActions(environment: Any?) {
            val decision = decisionState.get() ?: return
            val state = (environment as? GameEnvironment)?.state
            val purpose = decision.purposes.lastOrNull() ?: CALL_PURPOSE_OTHER
            val hadPreviousState = decision.hasPreviousLegalState
            val previousState = decision.lastLegalState
            val sameStateAsPrevious = hadPreviousState && previousState === state
            if (hadPreviousState) {
                if (sameStateAsPrevious) {
                    decision.sameStateRepeats++
                } else {
                    decision.stateChangedRepeats++
                }
            }
            decision.hasPreviousLegalState = true
            decision.lastLegalState = state
            decision.legalCalls++
            decision.pendingLegalCalls.addLast(
                LegalCall(
                    purpose = purpose,
                    sameStateAsPrevious = sameStateAsPrevious,
                    previousResult = decision.lastLegalResult,
                ),
            )
            decision.frames.addLast(Frame(LEGAL_ACTION_CALL, System.nanoTime(), currentThreadAllocatedBytes()))
        }

        internal fun endLegalActions(returnedActions: Any?) {
            val decision = decisionState.get() ?: return
            val call = decision.pendingLegalCalls.removeLastOrNull()
            if (call == null) {
                markIntegrityError()
                return
            }
            val measurement = endFrame(decision, LEGAL_ACTION_CALL).measurement
            decision.legalCallWallNanos += measurement.inclusiveWallNanos
            decision.legalCallAllocatedBytes = addNullable(
                decision.legalCallAllocatedBytes,
                measurement.inclusiveAllocatedBytes,
            )
            val actions = returnedActions as? List<*>
                ?: error("Deep legal-actions probe expected a List result")
            if (call.sameStateAsPrevious && call.previousResult != null) {
                if (call.previousResult == actions) {
                    decision.sameStateSemanticallyEqualResults++
                } else {
                    decision.sameStateSemanticallyDifferentResults++
                }
            }
            decision.lastLegalResult = actions
            decision.segment.recordLegalActionCall(call.purpose, actions.size)
        }

        internal fun recordOwnTempGrantedElements(count: Int) {
            decisionState.get()?.segment?.recordOwnTempGrantedElements(count)
        }

        internal fun recordOwnTempGrantedMatches(count: Int) {
            decisionState.get()?.segment?.recordOwnTempGrantedMatches(count)
        }

        internal fun recordOwnEffectiveAbilities(count: Int) {
            decisionState.get()?.segment?.recordOwnEffectiveAbilities(count)
        }

        internal fun recordOwnEmblemScan(entitiesVisited: Int) {
            decisionState.get()?.segment?.recordOwnEmblemScan(entitiesVisited)
        }

        internal fun recordOwnEmblemAbilitiesFound(count: Int) {
            decisionState.get()?.segment?.recordOwnEmblemAbilitiesFound(count)
        }

        internal fun recordOwnEmblemMatch(matches: Boolean) {
            decisionState.get()?.segment?.recordOwnEmblemMatch(matches)
        }

        internal fun recordOwnActivationPrevented(prevented: Boolean) {
            decisionState.get()?.segment?.recordOwnActivationPrevented(prevented)
        }

        internal fun recordOwnPlayerActivationPrevented(prevented: Boolean) {
            decisionState.get()?.segment?.recordOwnPlayerActivationPrevented(prevented)
        }

        internal fun recordOwnActionConstruction() {
            decisionState.get()?.segment?.recordOwnActionConstruction()
        }

        internal fun registerZoneEnumerator(enumerator: Any?, zone: Any?) {
            if (enumerator == null || zone == null) {
                markIntegrityError()
                return
            }
            synchronized(zoneEnumeratorZones) {
                zoneEnumeratorZones[enumerator] = zone.toString().uppercase(Locale.ROOT)
            }
        }

        internal fun startPhase(family: String) {
            val decision = decisionState.get() ?: return
            decision.frames.addLast(Frame(family, System.nanoTime(), currentThreadAllocatedBytes()))
            if (family == OWN_PERMANENT_SCAN) {
                decision.segment.beginOwnPermanentInvocation()
            }
        }

        internal fun startEnumerator(enumerator: Any?, family: String) {
            val decision = decisionState.get() ?: return
            val resolvedFamily = resolveEnumeratorFamily(enumerator, family)
            decision.frames.addLast(
                Frame(
                    family = resolvedFamily,
                    startNanos = System.nanoTime(),
                    startAllocatedBytes = currentThreadAllocatedBytes(),
                    baseFamily = family,
                ),
            )
        }

        internal fun endPhase(family: String) {
            val decision = decisionState.get() ?: return
            endFrame(decision, family).also { frame ->
                decision.segment.recordPhase(family, frame.measurement, null)
                if (frame.ownerFamily == OWN_PERMANENT_SCAN) {
                    decision.segment.recordOwnPermanentPhase(family, frame.measurement, null, frame.parentFamily)
                }
                if (family == OWN_PERMANENT_SCAN) {
                    decision.segment.endOwnPermanentInvocation()
                }
            }
        }

        internal fun endListPhase(family: String, returnedItems: Int) {
            val decision = decisionState.get() ?: return
            endFrame(decision, family).also { frame ->
                decision.segment.recordPhase(family, frame.measurement, returnedItems.toLong())
                if (frame.ownerFamily == OWN_PERMANENT_SCAN) {
                    decision.segment.recordOwnPermanentPhase(
                        family,
                        frame.measurement,
                        returnedItems.toLong(),
                        frame.parentFamily,
                    )
                }
            }
        }

        internal fun endEnumerator(family: String, returnedItems: Int) {
            val decision = decisionState.get() ?: return
            val frame = endFrame(decision, family, dynamicBaseFamily = family)
            val resolvedFamily = frame.family
            decision.segment.recordPhase(resolvedFamily, frame.measurement, returnedItems.toLong())
        }

        internal fun recordObservationCandidateCount(candidateCount: Int) {
            val decision = decisionState.get() ?: return
            decision.observationBuilds++
            decision.segment.recordObservationCandidate(candidateCount)
        }

        internal fun recordPublishedObservationCandidateCount(candidateCount: Int) {
            activeSession.get()?.currentSegmentForMeasurement()?.recordPublishedObservationCandidate(candidateCount)
        }

        internal fun recordActionView(action: Any?) {
            val decision = decisionState.get() ?: return
            val legalAction = action as? LegalAction
            val family = legalAction?.let { actionFamily(it.actionType) } ?: ACTION_FAMILY_OTHER
            decision.segment.recordActionFamily(family)
        }

        internal fun snapshot(): Snapshot {
            if (currentSegment.get() != null || decisionState.get() != null) markIntegrityError()
            return Snapshot(
                schemaVersion = "argentum-b1-legal-action-domain-deep-v3",
                allocationMeasurement = if (allocationBean == null) {
                    "NOT_AVAILABLE"
                } else {
                    "ThreadMXBean.getThreadAllocatedBytes"
                },
                integrity = if (integrityErrors.get() == 0L) {
                    "PASS"
                } else {
                    "FAIL errors=${integrityErrors.get()}"
                },
                segments = segments.map(Segment::snapshot),
            )
        }

        private fun endFrame(decision: DecisionState, family: String): FrameMeasurementWithFamily {
            return endFrame(decision, family, dynamicBaseFamily = null)
        }

        private fun endFrame(
            decision: DecisionState,
            family: String,
            dynamicBaseFamily: String?,
        ): FrameMeasurementWithFamily {
            val frame = decision.frames.removeLastOrNull()
            val matches = if (dynamicBaseFamily == null) {
                frame?.family == family
            } else {
                frame?.baseFamily == dynamicBaseFamily
            }
            if (frame == null || !matches) {
                markIntegrityError()
                return FrameMeasurementWithFamily(
                    family = family,
                    measurement = FrameMeasurement(0L, 0L, null, null),
                )
            }
            val inclusiveWallNanos = (System.nanoTime() - frame.startNanos).coerceAtLeast(0L)
            val inclusiveAllocatedBytes = currentThreadAllocatedBytes()?.let { end ->
                frame.startAllocatedBytes?.let { start -> (end - start).coerceAtLeast(0L) }
            }
            val exclusiveWallNanos = (inclusiveWallNanos - frame.childWallNanos).coerceAtLeast(0L)
            val exclusiveAllocatedBytes = inclusiveAllocatedBytes?.let {
                (it - frame.childAllocatedBytes).coerceAtLeast(0L)
            }
            val parentFamily = decision.frames.lastOrNull()?.family
            val ownerFamily = decision.frames.lastOrNull { it.family == OWN_PERMANENT_SCAN }?.family
            val measurement = FrameMeasurement(
                inclusiveWallNanos = inclusiveWallNanos,
                exclusiveWallNanos = exclusiveWallNanos,
                inclusiveAllocatedBytes = inclusiveAllocatedBytes,
                exclusiveAllocatedBytes = exclusiveAllocatedBytes,
            )
            val parent = decision.frames.lastOrNull()
            if (parent != null) {
                parent.childWallNanos += inclusiveWallNanos
                if (inclusiveAllocatedBytes != null) parent.childAllocatedBytes += inclusiveAllocatedBytes
            }
            if (frame.family == "MANA_CAN_PAY") {
                decision.frames.lastOrNull { isActivatedEnumeratorFamily(it.family) }?.let { owner ->
                    decision.segment.recordManaSolver(owner.family, measurement)
                }
            }
            return FrameMeasurementWithFamily(frame.family, measurement, ownerFamily, parentFamily)
        }

        private fun resolveEnumeratorFamily(enumerator: Any?, family: String): String {
            if (family != ZONE_ACTIVATED_ABILITY_FAMILY) return family
            val zone = synchronized(zoneEnumeratorZones) { enumerator?.let(zoneEnumeratorZones::get) }
            return when (zone) {
                "GRAVEYARD" -> ZONE_ACTIVATED_ABILITY_GRAVEYARD
                "HAND" -> ZONE_ACTIVATED_ABILITY_HAND
                else -> {
                    markIntegrityError()
                    ZONE_ACTIVATED_ABILITY_UNRESOLVED
                }
            }
        }

        private fun markIntegrityError() {
            integrityErrors.incrementAndGet()
        }
    }

    internal class SegmentHandle internal constructor(
        private val session: Session,
        private val segment: Segment,
    ) : AutoCloseable {
        private val closed = AtomicBoolean(false)

        override fun close() {
            if (closed.compareAndSet(false, true)) session.endSegment(segment)
        }
    }

    internal class Segment internal constructor(
        private val label: String,
    ) {
        private var decisions = 0L
        private var actionChoices = 0L
        private var structuredResponses = 0L
        private var semanticChoiceGaps = 0L
        private var legalActionCallsTotal = 0L
        private val legalActionCallsPerDecision = Samples()
        private val legalActionCallsByPurpose = linkedMapOf<String, Long>()
        private var decisionsWith0LegalActionCalls = 0L
        private var decisionsWith1LegalActionCall = 0L
        private var decisionsWith2LegalActionCalls = 0L
        private var decisionsWith3PlusLegalActionCalls = 0L
        private var sameStateLegalActionRepeatCount = 0L
        private var stateChangedLegalActionRepeatCount = 0L
        private var sameStateSemanticallyEqualResults = 0L
        private var sameStateSemanticallyDifferentResults = 0L
        private var observationBuilds = 0L
        private val publicLegalCandidates = Samples()
        private var publishedObservations = 0L
        private val publishedPublicLegalCandidates = Samples()
        private val structuredDecisionDomains = Samples()
        private val actionFamilyCandidates = linkedMapOf<String, Long>()
        private val phases = linkedMapOf<String, PhaseTotals>()
        private val decisionKinds = linkedMapOf<String, DecisionTotals>()
        private val manaSolverByEnumerator = linkedMapOf<String, NestedPhaseTotals>()
        private val ownPermanentPhases = linkedMapOf<String, PhaseTotals>()
        private val ownPermanentDirectPhases = linkedMapOf<String, PhaseTotals>()
        private var ownPermanentInvocations = 0L
        private var ownPermanentZeroResults = 0L
        private var ownPermanentNonZeroResults = 0L
        private val ownPermanentReturnedActions = Samples()
        private val ownPermanentBattlefieldPermanents = Samples()
        private val ownZeroResultBattlefieldPermanents = Samples()
        private val ownNonZeroResultBattlefieldPermanents = Samples()
        private var ownTempGrantedScanInvocations = 0L
        private var ownTempGrantedElementsVisited = 0L
        private var ownTempGrantedMatches = 0L
        private var ownStaticGrantedLookupCalls = 0L
        private var ownStaticGrantedResults = 0L
        private var ownEffectiveActivatedAbilities = 0L
        private var ownClassLevelUpAbilities = 0L
        private var ownActivationPreventedSkips = 0L
        private var ownPlayerActivationPreventedSkips = 0L
        private var ownEmblemScanInvocations = 0L
        private var ownEmblemEntitiesVisited = 0L
        private var ownEmblemAbilitiesFound = 0L
        private var ownEmblemMatchesFound = 0L
        private var ownCardRegistryLookups = 0L
        private var ownCostPathInvocations = 0L
        private var ownTargetPathInvocations = 0L
        private var ownActionConstructionInvocations = 0L
        private val ownZeroClassifications = linkedMapOf<String, Long>()

        internal fun recordDecisionStarted(kind: String) {
            when (kind) {
                DECISION_ACTION -> actionChoices++
                DECISION_STRUCTURED -> structuredResponses++
                DECISION_GAP -> semanticChoiceGaps++
            }
            decisionKinds.getOrPut(kind) { DecisionTotals() }
        }

        internal fun recordDecisionFinished(decision: DecisionState) {
            decisions++
            legalActionCallsTotal += decision.legalCalls.toLong()
            legalActionCallsPerDecision.record(decision.legalCalls.toLong())
            when (decision.legalCalls) {
                0 -> decisionsWith0LegalActionCalls++
                1 -> decisionsWith1LegalActionCall++
                2 -> decisionsWith2LegalActionCalls++
                else -> decisionsWith3PlusLegalActionCalls++
            }
            sameStateLegalActionRepeatCount += decision.sameStateRepeats
            stateChangedLegalActionRepeatCount += decision.stateChangedRepeats
            sameStateSemanticallyEqualResults += decision.sameStateSemanticallyEqualResults
            sameStateSemanticallyDifferentResults += decision.sameStateSemanticallyDifferentResults
            observationBuilds += decision.observationBuilds
            val totals = decisionKinds.getOrPut(decision.kind) { DecisionTotals() }
            totals.record(decision)
        }

        internal fun recordLegalActionCall(purpose: String, candidateCount: Int) {
            legalActionCallsByPurpose[purpose] = (legalActionCallsByPurpose[purpose] ?: 0L) + 1L
        }

        internal fun recordObservationCandidate(candidateCount: Int) {
            publicLegalCandidates.record(candidateCount.toLong())
        }

        internal fun recordPublishedObservationCandidate(candidateCount: Int) {
            publishedObservations++
            publishedPublicLegalCandidates.record(candidateCount.toLong())
        }

        internal fun recordActionFamily(family: String) {
            actionFamilyCandidates[family] = (actionFamilyCandidates[family] ?: 0L) + 1L
        }

        internal fun recordPhase(family: String, measurement: FrameMeasurement, returnedItems: Long?) {
            phases.getOrPut(family) { PhaseTotals() }.record(measurement, returnedItems)
            if (family == "STRUCTURED_DECISION_DOMAIN" && returnedItems != null) {
                structuredDecisionDomains.record(returnedItems)
            }
        }

        internal fun snapshot(): SegmentSnapshot = SegmentSnapshot(
            label = label,
            decisions = decisions,
            actionChoices = actionChoices,
            structuredResponses = structuredResponses,
            semanticChoiceGaps = semanticChoiceGaps,
            legalActionCallsTotal = legalActionCallsTotal,
            legalActionCallsPerDecision = legalActionCallsPerDecision.snapshot(),
            legalActionCallsByPurpose = legalActionCallsByPurpose.toSortedMap(),
            decisionsWith0LegalActionCalls = decisionsWith0LegalActionCalls,
            decisionsWith1LegalActionCall = decisionsWith1LegalActionCall,
            decisionsWith2LegalActionCalls = decisionsWith2LegalActionCalls,
            decisionsWith3PlusLegalActionCalls = decisionsWith3PlusLegalActionCalls,
            sameStateLegalActionRepeatCount = sameStateLegalActionRepeatCount,
            stateChangedLegalActionRepeatCount = stateChangedLegalActionRepeatCount,
            sameStateSemanticallyEqualResults = sameStateSemanticallyEqualResults,
            sameStateSemanticallyDifferentResults = sameStateSemanticallyDifferentResults,
            observationBuilds = observationBuilds,
            publicLegalCandidates = publicLegalCandidates.snapshot(),
            publishedObservations = publishedObservations,
            publishedPublicLegalCandidates = publishedPublicLegalCandidates.snapshotOrNull(),
            structuredDecisionDomains = structuredDecisionDomains.snapshotOrNull(),
            actionFamilyCandidates = actionFamilyCandidates.toSortedMap(),
            decisionKinds = decisionKinds.toSortedMap().mapValues { (_, value) -> value.snapshot() },
            phases = phases.toSortedMap().mapValues { (_, value) -> value.snapshot() },
            manaSolverByEnumerator = synchronized(manaSolverByEnumerator) {
                manaSolverByEnumerator.toSortedMap().mapValues { (_, value) -> value.snapshot() }
            },
            ownPermanent = ownPermanentInvocations.takeIf { it > 0L }?.let {
                OwnPermanentSnapshot(
                    invocations = ownPermanentInvocations,
                    returnedActions = ownPermanentReturnedActions.snapshot(),
                    zeroResultInvocations = ownPermanentZeroResults,
                    nonZeroResultInvocations = ownPermanentNonZeroResults,
                    battlefieldPermanentsScanned = ownPermanentBattlefieldPermanents.snapshot(),
                    zeroResultBattlefieldPermanents = ownZeroResultBattlefieldPermanents.snapshotOrNull(),
                    nonZeroResultBattlefieldPermanents = ownNonZeroResultBattlefieldPermanents.snapshotOrNull(),
                    tempGrantedScanInvocations = ownTempGrantedScanInvocations,
                    tempGrantedElementsVisited = ownTempGrantedElementsVisited,
                    tempGrantedMatches = ownTempGrantedMatches,
                    staticGrantedLookupCalls = ownStaticGrantedLookupCalls,
                    staticGrantedResults = ownStaticGrantedResults,
                    effectiveActivatedAbilities = ownEffectiveActivatedAbilities,
                    classLevelUpAbilities = ownClassLevelUpAbilities,
                    activationPreventedSkips = ownActivationPreventedSkips,
                    playerActivationPreventedSkips = ownPlayerActivationPreventedSkips,
                    emblemScanInvocations = ownEmblemScanInvocations,
                    emblemEntitiesVisited = ownEmblemEntitiesVisited,
                    emblemAbilitiesFound = ownEmblemAbilitiesFound,
                    emblemMatchesFound = ownEmblemMatchesFound,
                    cardRegistryLookups = ownCardRegistryLookups,
                    costPathInvocations = ownCostPathInvocations,
                    targetPathInvocations = ownTargetPathInvocations,
                    actionConstructionInvocations = ownActionConstructionInvocations,
                    zeroClassifications = ownZeroClassifications.toSortedMap(),
                    phases = synchronized(ownPermanentPhases) {
                        ownPermanentPhases.toSortedMap().mapValues { (_, value) -> value.snapshot() }
                    },
                )
            },
            ownPermanentDirectPhases = synchronized(ownPermanentDirectPhases) {
                ownPermanentDirectPhases.toSortedMap().mapValues { (_, value) -> value.snapshot() }
            },
        )

        internal fun recordManaSolver(family: String, measurement: FrameMeasurement) {
            synchronized(manaSolverByEnumerator) {
                manaSolverByEnumerator.getOrPut(family) { NestedPhaseTotals() }.record(measurement)
            }
        }

        private fun recordOwnPermanentInvocation(invocation: OwnPermanentInvocation) {
            ownPermanentInvocations++
            ownPermanentReturnedActions.record(invocation.actionConstructionInvocations)
            if (invocation.actionConstructionInvocations == 0L) {
                ownPermanentZeroResults++
                ownZeroResultBattlefieldPermanents.record(invocation.battlefieldPermanents)
                val classification = invocation.zeroClassification()
                ownZeroClassifications[classification] = (ownZeroClassifications[classification] ?: 0L) + 1L
            } else {
                ownPermanentNonZeroResults++
                ownNonZeroResultBattlefieldPermanents.record(invocation.battlefieldPermanents)
            }
            ownPermanentBattlefieldPermanents.record(invocation.battlefieldPermanents)
            ownTempGrantedScanInvocations += invocation.tempGrantedScanInvocations
            ownTempGrantedElementsVisited += invocation.tempGrantedElementsVisited
            ownTempGrantedMatches += invocation.tempGrantedMatches
            ownStaticGrantedLookupCalls += invocation.staticGrantedLookupCalls
            ownStaticGrantedResults += invocation.staticGrantedResults
            ownEffectiveActivatedAbilities += invocation.effectiveAbilities
            ownClassLevelUpAbilities += invocation.classLevelUpAbilities
            ownActivationPreventedSkips += invocation.activationPreventedSkips
            ownPlayerActivationPreventedSkips += invocation.playerActivationPreventedSkips
            ownEmblemScanInvocations += invocation.emblemScanInvocations
            ownEmblemEntitiesVisited += invocation.emblemEntitiesVisited
            ownEmblemAbilitiesFound += invocation.emblemAbilitiesFound
            ownEmblemMatchesFound += invocation.emblemMatchesFound
            ownCardRegistryLookups += invocation.cardRegistryLookups
            ownCostPathInvocations += invocation.costPathInvocations
            ownTargetPathInvocations += invocation.targetPathInvocations
            ownActionConstructionInvocations += invocation.actionConstructionInvocations
        }

        internal fun recordOwnPermanentPhase(
            family: String,
            measurement: FrameMeasurement,
            returnedItems: Long?,
            parentFamily: String?,
        ) {
            synchronized(ownPermanentPhases) {
                ownPermanentPhases.getOrPut(family) { PhaseTotals() }.record(measurement, returnedItems)
            }
            if (parentFamily == OWN_PERMANENT_SCAN) {
                synchronized(ownPermanentDirectPhases) {
                    ownPermanentDirectPhases.getOrPut(family) { PhaseTotals() }.record(measurement, returnedItems)
                }
            }
            val invocation = currentOwnPermanentInvocation.get() ?: return
            invocation.recordPhase(family, returnedItems)
        }

        private val currentOwnPermanentInvocation = ThreadLocal<OwnPermanentInvocation?>()

        internal fun beginOwnPermanentInvocation() {
            val invocation = OwnPermanentInvocation()
            currentOwnPermanentInvocation.set(invocation)
        }

        internal fun endOwnPermanentInvocation() {
            val invocation = currentOwnPermanentInvocation.get() ?: return
            currentOwnPermanentInvocation.remove()
            recordOwnPermanentInvocation(invocation)
        }

        internal fun recordOwnTempGrantedElements(count: Int) {
            currentOwnPermanentInvocation.get()?.let {
                it.tempGrantedScanInvocations++
                it.tempGrantedElementsVisited += count.toLong()
            }
        }

        internal fun recordOwnTempGrantedMatches(count: Int) {
            currentOwnPermanentInvocation.get()?.let { it.tempGrantedMatches += count.toLong() }
        }

        internal fun recordOwnEffectiveAbilities(count: Int) {
            currentOwnPermanentInvocation.get()?.let { it.effectiveAbilities += count.toLong() }
        }

        internal fun recordOwnEmblemScan(entitiesVisited: Int) {
            currentOwnPermanentInvocation.get()?.let {
                it.emblemScanInvocations++
                it.emblemEntitiesVisited += entitiesVisited.toLong()
            }
        }

        internal fun recordOwnEmblemAbilitiesFound(count: Int) {
            currentOwnPermanentInvocation.get()?.let { it.emblemAbilitiesFound += count.toLong() }
        }

        internal fun recordOwnEmblemMatch(matches: Boolean) {
            if (matches) currentOwnPermanentInvocation.get()?.let { it.emblemMatchesFound++ }
        }

        internal fun recordOwnActivationPrevented(prevented: Boolean) {
            if (prevented) currentOwnPermanentInvocation.get()?.let { it.activationPreventedSkips++ }
        }

        internal fun recordOwnPlayerActivationPrevented(prevented: Boolean) {
            if (prevented) currentOwnPermanentInvocation.get()?.let { it.playerActivationPreventedSkips++ }
        }

        internal fun recordOwnActionConstruction() {
            currentOwnPermanentInvocation.get()?.let { it.actionConstructionInvocations++ }
        }
    }

    internal class DecisionState(
        val segment: Segment,
        val kind: String,
    ) {
        val frames = ArrayDeque<Frame>()
        val purposes = ArrayDeque<String>()
        val pendingLegalCalls = ArrayDeque<LegalCall>()
        var legalCalls: Int = 0
        var legalCallWallNanos: Long = 0L
        var legalCallAllocatedBytes: Long? = null
        var sameStateRepeats: Long = 0L
        var stateChangedRepeats: Long = 0L
        var sameStateSemanticallyEqualResults: Long = 0L
        var sameStateSemanticallyDifferentResults: Long = 0L
        var observationBuilds: Long = 0L
        var hasPreviousLegalState: Boolean = false
        var lastLegalState: Any? = null
        var lastLegalResult: List<*>? = null
    }

    internal data class LegalCall(
        val purpose: String,
        val sameStateAsPrevious: Boolean,
        val previousResult: List<*>?,
    )

    internal class Frame(
        val family: String,
        val startNanos: Long,
        val startAllocatedBytes: Long?,
        val baseFamily: String? = null,
    ) {
        var childWallNanos: Long = 0L
        var childAllocatedBytes: Long = 0L
    }

    internal data class FrameMeasurement(
        val inclusiveWallNanos: Long,
        val exclusiveWallNanos: Long,
        val inclusiveAllocatedBytes: Long?,
        val exclusiveAllocatedBytes: Long?,
    )

    private data class FrameMeasurementWithFamily(
        val family: String,
        val measurement: FrameMeasurement,
        val ownerFamily: String? = null,
        val parentFamily: String? = null,
    )

    private class OwnPermanentInvocation {
        var battlefieldPermanents = 0L
        var tempGrantedScanInvocations = 0L
        var tempGrantedElementsVisited = 0L
        var tempGrantedMatches = 0L
        var staticGrantedLookupCalls = 0L
        var staticGrantedResults = 0L
        var effectiveAbilities = 0L
        var classLevelUpAbilities = 0L
        var emblemScanInvocations = 0L
        var emblemEntitiesVisited = 0L
        var emblemAbilitiesFound = 0L
        var emblemMatchesFound = 0L
        var cardRegistryLookups = 0L
        var costPathInvocations = 0L
        var targetPathInvocations = 0L
        var actionConstructionInvocations = 0L
        var activationPreventedSkips = 0L
        var playerActivationPreventedSkips = 0L

        fun recordPhase(family: String, returnedItems: Long?) {
            when (family) {
                "OWN_BATTLEFIELD_PERMANENTS" -> {
                    battlefieldPermanents = returnedItems ?: battlefieldPermanents
                }
                "OWN_STATIC_GRANTED_LOOKUP" -> {
                    staticGrantedLookupCalls++
                    staticGrantedResults += returnedItems ?: 0L
                }
                "OWN_EFFECTIVE_ACTIVATED_ABILITIES" -> {
                    effectiveAbilities += returnedItems ?: 0L
                }
                "OWN_CLASS_LEVEL_UP_ABILITIES" -> {
                    classLevelUpAbilities += returnedItems ?: 0L
                }
                "OWN_CARD_REGISTRY_LOOKUP" -> cardRegistryLookups++
                "OWN_COST_PATH" -> costPathInvocations++
                "OWN_TARGET_PATH" -> targetPathInvocations++
            }
        }

        fun zeroClassification(): String = when {
            battlefieldPermanents == 0L -> "NO_BATTLEFIELD_PERMANENTS"
            tempGrantedElementsVisited > 0L && tempGrantedMatches == 0L -> "SOURCE_MATCHES_NOT_SEPARATELY_MEASURABLE"
            sourceAbilityCount() == 0L -> "NO_ACTIVATED_ABILITIES_FOUND"
            costPathInvocations == 0L -> "ALL_REJECTED_BY_TIMING_OR_RESTRICTIONS"
            targetPathInvocations == 0L -> "ALL_REJECTED_BY_COST"
            else -> "ALL_REJECTED_BY_TARGETS"
        }

        private fun sourceAbilityCount(): Long =
            effectiveAbilities + classLevelUpAbilities + staticGrantedResults + tempGrantedMatches + emblemAbilitiesFound
    }

    private class PhaseTotals {
        private val invocations = LongAdder()
        private val inclusiveWallNanos = LongAdder()
        private val wallNanos = LongAdder()
        private val inclusiveAllocatedBytes = LongAdder()
        private val allocatedBytes = LongAdder()
        private val allocationSamples = LongAdder()
        private val returnedItems = Samples()
        private val zeroResultInvocations = LongAdder()
        private val nonZeroResultInvocations = LongAdder()

        fun record(measurement: FrameMeasurement, resultSize: Long?) {
            invocations.increment()
            inclusiveWallNanos.add(measurement.inclusiveWallNanos)
            wallNanos.add(measurement.exclusiveWallNanos)
            measurement.inclusiveAllocatedBytes?.let {
                inclusiveAllocatedBytes.add(it)
            }
            measurement.exclusiveAllocatedBytes?.let {
                allocatedBytes.add(it)
                allocationSamples.increment()
            }
            resultSize?.let {
                returnedItems.record(it)
                if (it == 0L) zeroResultInvocations.increment() else nonZeroResultInvocations.increment()
            }
        }

        fun snapshot(): PhaseSnapshot = PhaseSnapshot(
            invocations = invocations.sum(),
            inclusiveWallNanos = inclusiveWallNanos.sum(),
            exclusiveWallNanos = wallNanos.sum(),
            inclusiveAllocatedBytes = inclusiveAllocatedBytes.sum().takeIf { allocationSamples.sum() > 0L },
            exclusiveAllocatedBytes = allocatedBytes.sum().takeIf { allocationSamples.sum() > 0L },
            allocationSamples = allocationSamples.sum(),
            returnedItems = returnedItems.snapshotOrNull(),
            zeroResultInvocations = zeroResultInvocations.sum(),
            nonZeroResultInvocations = nonZeroResultInvocations.sum(),
        )
    }

    private class NestedPhaseTotals {
        private val invocations = LongAdder()
        private val inclusiveWallNanos = LongAdder()
        private val inclusiveAllocatedBytes = LongAdder()
        private val allocationSamples = LongAdder()

        fun record(measurement: FrameMeasurement) {
            invocations.increment()
            inclusiveWallNanos.add(measurement.inclusiveWallNanos)
            measurement.inclusiveAllocatedBytes?.let {
                inclusiveAllocatedBytes.add(it)
                allocationSamples.increment()
            }
        }

        fun snapshot(): NestedPhaseSnapshot = NestedPhaseSnapshot(
            invocations = invocations.sum(),
            inclusiveWallNanos = inclusiveWallNanos.sum(),
            inclusiveAllocatedBytes = inclusiveAllocatedBytes.sum().takeIf { allocationSamples.sum() > 0L },
        )
    }

    private class DecisionTotals {
        private var decisions = 0L
        private var legalActionCalls = 0L
        private var legalActionWallNanos = 0L
        private var legalActionAllocatedBytes: Long? = null
        private val callsPerDecision = Samples()

        fun record(decision: DecisionState) {
            decisions++
            legalActionCalls += decision.legalCalls.toLong()
            legalActionWallNanos += decision.legalCallWallNanos
            legalActionAllocatedBytes = addNullable(legalActionAllocatedBytes, decision.legalCallAllocatedBytes)
            callsPerDecision.record(decision.legalCalls.toLong())
        }

        fun snapshot(): DecisionKindSnapshot = DecisionKindSnapshot(
            decisions = decisions,
            legalActionCalls = legalActionCalls,
            legalActionWallNanos = legalActionWallNanos,
            legalActionAllocatedBytes = legalActionAllocatedBytes,
            callsPerDecision = callsPerDecision.snapshot(),
        )
    }

    private class Samples {
        private val values = ArrayList<Long>()
        private var sum = 0L

        fun record(value: Long) {
            values += value
            sum += value
        }

        fun snapshotOrNull(): Distribution? = values.takeIf { it.isNotEmpty() }?.let { snapshot() }

        fun snapshot(): Distribution {
            check(values.isNotEmpty()) { "Cannot snapshot an empty distribution" }
            val sorted = values.sorted()
            fun percentile(fraction: Double): Long {
                val index = ((sorted.size - 1) * fraction).toInt().coerceIn(0, sorted.lastIndex)
                return sorted[index]
            }
            return Distribution(
                count = values.size.toLong(),
                sum = sum,
                min = sorted.first(),
                p50 = percentile(0.50),
                p95 = percentile(0.95),
                max = sorted.last(),
            )
        }
    }

    private const val LEGAL_ACTION_CALL = "LEGAL_ACTION_CALL"
    private const val CALL_PURPOSE_OTHER = "OTHER"
    private const val DECISION_ACTION = "ACTION"
    private const val DECISION_STRUCTURED = "STRUCTURED"
    private const val DECISION_GAP = "GAP"
    private const val ACTION_FAMILY_OTHER = "OTHER"
    private const val OWN_PERMANENT_SCAN = "ACTIVATED_ABILITY_OWN_PERMANENT_SCAN"

    @JvmStatic
    @JvmName("start")
    internal fun start(): Session {
        val session = Session()
        check(activeSession.compareAndSet(null, session)) {
            "B1 legal-action/domain probe session is already active"
        }
        return session
    }

    @JvmStatic
    @JvmName("stop")
    internal fun stop(session: Session): Snapshot {
        check(activeSession.compareAndSet(session, null)) {
            "B1 legal-action/domain probe session is not active"
        }
        return session.snapshot()
    }

    @JvmStatic
    @JvmName("beginDecision")
    internal fun beginDecision(kind: String): Boolean = activeSession.get()?.beginDecision(kind) == true

    @JvmStatic
    @JvmName("endDecision")
    internal fun endDecision() {
        activeSession.get()?.endDecision()
    }

    @JvmStatic
    @JvmName("pushPurpose")
    internal fun pushPurpose(purpose: String) {
        activeSession.get()?.pushPurpose(purpose)
    }

    @JvmStatic
    @JvmName("popPurpose")
    internal fun popPurpose(purpose: String) {
        activeSession.get()?.popPurpose(purpose)
    }

    @JvmStatic
    @JvmName("beginLegalActions")
    internal fun beginLegalActions(environment: Any?) {
        activeSession.get()?.beginLegalActions(environment)
    }

    @JvmStatic
    @JvmName("endLegalActions")
    internal fun endLegalActions(returnedActions: Any?) {
        activeSession.get()?.endLegalActions(returnedActions)
    }

    @JvmStatic
    @JvmName("startPhase")
    internal fun startPhase(family: String) {
        activeSession.get()?.startPhase(family)
    }

    @JvmStatic
    @JvmName("registerZoneEnumerator")
    internal fun registerZoneEnumerator(enumerator: Any?, zone: Any?) {
        activeSession.get()?.registerZoneEnumerator(enumerator, zone)
    }

    @JvmStatic
    @JvmName("startEnumerator")
    internal fun startEnumerator(enumerator: Any?, family: String) {
        activeSession.get()?.startEnumerator(enumerator, family)
    }

    @JvmStatic
    @JvmName("endPhase")
    internal fun endPhase(family: String) {
        activeSession.get()?.endPhase(family)
    }

    @JvmStatic
    @JvmName("endListPhase")
    internal fun endListPhase(family: String, returnedItems: Int) {
        activeSession.get()?.endListPhase(family, returnedItems)
    }

    @JvmStatic
    @JvmName("endEnumerator")
    internal fun endEnumerator(family: String, returnedItems: Int) {
        activeSession.get()?.endEnumerator(family, returnedItems)
    }

    @JvmStatic
    @JvmName("recordOwnTempGrantedElements")
    internal fun recordOwnTempGrantedElements(count: Int) {
        activeSession.get()?.recordOwnTempGrantedElements(count)
    }

    @JvmStatic
    @JvmName("recordOwnTempGrantedMatches")
    internal fun recordOwnTempGrantedMatches(count: Int) {
        activeSession.get()?.recordOwnTempGrantedMatches(count)
    }

    @JvmStatic
    @JvmName("recordOwnEffectiveAbilities")
    internal fun recordOwnEffectiveAbilities(count: Int) {
        activeSession.get()?.recordOwnEffectiveAbilities(count)
    }

    @JvmStatic
    @JvmName("recordOwnEmblemScan")
    internal fun recordOwnEmblemScan(entitiesVisited: Int) {
        activeSession.get()?.recordOwnEmblemScan(entitiesVisited)
    }

    @JvmStatic
    @JvmName("recordOwnEmblemAbilitiesFound")
    internal fun recordOwnEmblemAbilitiesFound(count: Int) {
        activeSession.get()?.recordOwnEmblemAbilitiesFound(count)
    }

    @JvmStatic
    @JvmName("recordOwnEmblemMatch")
    internal fun recordOwnEmblemMatch(matches: Boolean) {
        activeSession.get()?.recordOwnEmblemMatch(matches)
    }

    @JvmStatic
    @JvmName("recordOwnActivationPrevented")
    internal fun recordOwnActivationPrevented(prevented: Boolean) {
        activeSession.get()?.recordOwnActivationPrevented(prevented)
    }

    @JvmStatic
    @JvmName("recordOwnPlayerActivationPrevented")
    internal fun recordOwnPlayerActivationPrevented(prevented: Boolean) {
        activeSession.get()?.recordOwnPlayerActivationPrevented(prevented)
    }

    @JvmStatic
    @JvmName("recordOwnActionConstruction")
    internal fun recordOwnActionConstruction() {
        activeSession.get()?.recordOwnActionConstruction()
    }

    @JvmStatic
    @JvmName("recordObservationCandidateCount")
    internal fun recordObservationCandidateCount(candidateCount: Int) {
        activeSession.get()?.recordObservationCandidateCount(candidateCount)
    }

    @JvmStatic
    @JvmName("recordPublishedObservationCandidateCount")
    internal fun recordPublishedObservationCandidateCount(candidateCount: Int) {
        activeSession.get()?.recordPublishedObservationCandidateCount(candidateCount)
    }

    @JvmStatic
    @JvmName("recordActionView")
    internal fun recordActionView(action: Any?) {
        activeSession.get()?.recordActionView(action)
    }

    internal fun beginSegment(label: String): SegmentHandle? = activeSession.get()?.beginSegment(label)

    internal fun isActive(): Boolean = activeSession.get() != null

    private fun currentThreadAllocatedBytes(): Long? = allocationBean
        ?.getThreadAllocatedBytes(Thread.currentThread().threadId())
        ?.takeIf { it >= 0L }

    private fun addNullable(left: Long?, right: Long?): Long? = when {
        left == null -> right
        right == null -> left
        else -> left + right
    }

    private fun actionFamily(actionType: String): String = when {
        actionType == "PassPriority" -> "PASS_PRIORITY"
        actionType == "PlayLand" -> "PLAY_LAND"
        actionType.startsWith("Cast") || actionType.contains("Cast") -> "CAST"
        actionType == "ActivateAbility" || actionType.contains("Ability") -> "ACTIVATE"
        actionType.startsWith("Declare") || actionType.contains("Combat") -> "COMBAT"
        else -> "SPECIAL"
    }

    private fun isActivatedEnumeratorFamily(family: String): Boolean = family in setOf(
        "MANA_ABILITY_ENUMERATOR",
        "ACTIVATED_ABILITY_ENUMERATOR",
        "ZONE_ACTIVATED_ABILITY_GRAVEYARD",
        "ZONE_ACTIVATED_ABILITY_HAND",
        "COMMAND_ZONE_ABILITY_ENUMERATOR",
    )

    private const val ZONE_ACTIVATED_ABILITY_FAMILY = "ZONE_ACTIVATED_ABILITY_ENUMERATOR"
    private const val ZONE_ACTIVATED_ABILITY_GRAVEYARD = "ZONE_ACTIVATED_ABILITY_GRAVEYARD"
    private const val ZONE_ACTIVATED_ABILITY_HAND = "ZONE_ACTIVATED_ABILITY_HAND"
    private const val ZONE_ACTIVATED_ABILITY_UNRESOLVED = "ZONE_ACTIVATED_ABILITY_UNRESOLVED"
}
